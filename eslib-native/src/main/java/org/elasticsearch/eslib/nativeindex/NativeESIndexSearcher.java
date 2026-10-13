/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.nativeindex;

import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.elasticsearch.eslib.api.ESIndexSearcher;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.FullTextParams;
import org.elasticsearch.eslib.api.model.FullTextQuerySpec;
import org.elasticsearch.eslib.api.model.IndexFilter;
import org.elasticsearch.eslib.api.model.ScalarPredicate;
import org.elasticsearch.eslib.api.model.SearchResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native Lucene reader over an owned verified archive materialization. No Java Lucene is loaded.
 */
public final class NativeESIndexSearcher implements ESIndexSearcher {
    private NativeClient client;
    private NativeMapping mapping;
    private NativeIndexManifest manifest;
    private ArchiveDataProvider provider;
    private Path directory;
    private long handle;
    private boolean loaded, closed;

    public NativeESIndexSearcher() {}

    NativeESIndexSearcher(NativeClient injected) {
        client = injected;
    }

    @Override
    public void load(
            ArchiveDataProvider provider,
            Map<String, long[]> offsets,
            Map<String, FieldIndexConfig> fields)
            throws IOException {
        if (closed || loaded || this.provider != null)
            throw new IllegalStateException("Native searcher already owns an archive");
        if (provider == null || offsets == null || offsets.size() > 100001)
            throw new IllegalArgumentException("Missing or oversized native archive");
        this.provider = provider;
        try {
            mapping = new NativeMapping(fields);
            long[] metadata = range(offsets.get(NativeIndexManifest.NAME));
            NativeClient.require(
                    metadata[1] <= NativeIndexManifest.MAX_BYTES,
                    "Native index metadata exceeds its limit");
            manifest = NativeIndexManifest.decode(exact(metadata[0], Math.toIntExact(metadata[1])));
            NativeClient.require(
                    manifest.configSha256.equals(NativeJson.hex(NativeJson.digest(mapping.config)))
                            && Arrays.equals(manifest.mapping, mapping.mapping),
                    "Native archive field mapping differs");
            Set<String> expected = new HashSet<>(manifest.files.keySet());
            expected.add(NativeIndexManifest.NAME);
            NativeClient.require(
                    expected.equals(offsets.keySet()),
                    "Native archive file names differ from its sealed manifest");
            validateRanges(offsets);
            if (client == null) client = NativeRuntime.load().client();
            NativeClient.require(
                    manifest.nativeCommit.equals(client.commit)
                            && manifest.nativeSha256.equals(client.sha256),
                    "Native writer/reader versions differ; rebuild with the selected bundle");
            directory = Files.createTempDirectory("eslib-native-reader-");
            for (Map.Entry<String, NativeIndexManifest.File> file : manifest.files.entrySet()) {
                long[] range = range(offsets.get(file.getKey()));
                NativeClient.require(
                        range[1] == file.getValue().length, "Native archive file length differs");
                materialize(file.getKey(), range[0], range[1], file.getValue().sha256);
            }
            handle = client.open(directory, manifest);
            loaded = true;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void materialize(String name, long offset, long length, String expectedSha)
            throws IOException {
        NativeIndexManifest.requireFileName(name);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (OutputStream output = Files.newOutputStream(directory.resolve(name))) {
            long position = 0;
            while (position < length) {
                int amount = (int) Math.min(length - position, 256 * 1024L);
                byte[] bytes = exact(Math.addExact(offset, position), amount);
                digest.update(bytes);
                output.write(bytes);
                position += amount;
            }
        }
        NativeClient.require(
                expectedSha.equals(NativeJson.hex(digest.digest())),
                "Native archive file checksum differs");
    }

    private byte[] exact(long offset, int length) throws IOException {
        byte[] value = provider.readRange(offset, length);
        NativeClient.require(
                value != null && value.length == length,
                "Native archive range was short or missing");
        return value;
    }

    static long[] range(long[] range) throws IOException {
        NativeClient.require(
                range != null
                        && range.length == 2
                        && range[0] >= 0
                        && range[1] >= 0
                        && range[0] <= Long.MAX_VALUE - range[1],
                "Invalid native archive range");
        return range.clone();
    }

    static void validateRanges(Map<String, long[]> offsets) throws IOException {
        List<long[]> ranges = new ArrayList<>();
        for (long[] value : offsets.values()) ranges.add(range(value));
        ranges.sort((a, b) -> Long.compare(a[0], b[0]));
        long end = 0;
        for (long[] value : ranges) {
            NativeClient.require(value[0] >= end, "Native archive ranges overlap");
            end = value[0] + value[1];
        }
    }

    private void requireLoaded() {
        if (!loaded || closed || handle == 0)
            throw new IllegalStateException("Native searcher is not loaded");
    }

    private SearchResult search(Object query, int limit, boolean docOrder) throws IOException {
        requireLoaded();
        if (limit <= 0 || limit > 1000000)
            throw new IllegalArgumentException("Native result window must be 1..1000000");
        if (manifest.rows == 0) return SearchResult.EMPTY;
        Map<String, Object> source =
                NativeJson.object(
                        "from",
                        0,
                        "size",
                        limit,
                        "track_total_hits",
                        true,
                        "_source",
                        false,
                        "query",
                        query);
        if (docOrder) source.put("sort", java.util.Collections.singletonList("_doc"));
        SearchResult result = client.query(handle, NativeJson.encode(source), limit, docOrder);
        Set<Long> unique = new HashSet<>();
        for (long row : result.ids)
            NativeClient.require(
                    row < manifest.rows && unique.add(row),
                    "Native result row is outside its sealed span or repeated");
        return result;
    }

    @Override
    public SearchResult fullTextSearch(String field, String text, int topK) throws IOException {
        return fullTextSearch(field, text, topK, FullTextParams.defaults());
    }

    @Override
    public SearchResult fullTextSearch(String field, String text, int topK, FullTextParams params)
            throws IOException {
        return fullTextSearch(new FullTextQuerySpec.Match(field, text, params), topK);
    }

    @Override
    public SearchResult fullTextSearch(FullTextQuerySpec spec, int topK) throws IOException {
        requireLoaded();
        return search(new NativeQueries(mapping).fulltext(spec), topK, false);
    }

    @Override
    public long[] scalarFilter(String field, ScalarPredicate predicate) throws IOException {
        return filter(field, IndexFilter.scalar(predicate));
    }

    @Override
    public long[] filter(String field, IndexFilter filter) throws IOException {
        requireLoaded();
        Object query = new NativeQueries(mapping).filter(field, filter);
        if (manifest.rows == 0) return new long[0];
        NativeClient.require(
                manifest.rows <= 1000000,
                "Native filter result enumeration exceeds its supported window");
        return search(query, Math.toIntExact(manifest.rows), true).ids;
    }

    @Override
    public long[] allRowIds() throws IOException {
        requireLoaded();
        if (manifest.rows == 0) return new long[0];
        NativeClient.require(
                manifest.rows <= 1000000, "Native row enumeration exceeds its supported window");
        return search(
                        NativeJson.object("match_all", NativeJson.object()),
                        Math.toIntExact(manifest.rows),
                        true)
                .ids;
    }

    @Override
    public SearchResult vectorSearch(String field, float[] query, int topK, long[] candidateIds)
            throws IOException {
        requireLoaded();
        NativeMapping.requireVector(mapping.field(field), query);
        if (topK <= 0) return SearchResult.EMPTY;
        if (topK > 10000)
            throw new IllegalArgumentException("Native KNN supports at most 10000 candidates");
        Map<String, Object> knn =
                NativeJson.object(
                        "field",
                        field,
                        "query_vector",
                        query.clone(),
                        "k",
                        topK,
                        "num_candidates",
                        Math.min(10000L, Math.max((long) topK, 4L * topK)));
        Set<Long> allowedRows = null;
        if (candidateIds != null) {
            if (candidateIds.length == 0) return SearchResult.EMPTY;
            List<String> ids = new ArrayList<>();
            allowedRows = new HashSet<>();
            for (long row : candidateIds) {
                // The original Java prefilter ignores out-of-range rows and
                // uses a bit set, so duplicate candidates are idempotent.
                if (row >= 0 && row < manifest.rows && allowedRows.add(row)) {
                    ids.add(Long.toString(row));
                }
            }
            if (ids.isEmpty()) return SearchResult.EMPTY;
            knn.put("filter", NativeJson.object("ids", NativeJson.object("values", ids)));
        }
        if (manifest.rows == 0) return SearchResult.EMPTY;
        Map<String, Object> source =
                NativeJson.object(
                        "from",
                        0,
                        "size",
                        topK,
                        "track_total_hits",
                        true,
                        "_source",
                        false,
                        "knn",
                        knn);
        SearchResult result = client.query(handle, NativeJson.encode(source), topK, false);
        Set<Long> unique = new HashSet<>();
        for (long row : result.ids) {
            NativeClient.require(
                    row < manifest.rows && unique.add(row),
                    "Native vector row escaped its sealed span or repeated");
            if (allowedRows != null)
                NativeClient.require(
                        allowedRows.contains(row), "Native vector escaped its prefilter");
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        if (handle != 0) {
            client.close(handle);
            handle = 0;
        }
        if (provider != null) {
            provider.close();
            provider = null;
        }
        if (directory != null) {
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path file : (Iterable<Path>) files::iterator) Files.delete(file);
            }
            Files.delete(directory);
            directory = null;
        }
        loaded = false;
        closed = true;
    }
}
