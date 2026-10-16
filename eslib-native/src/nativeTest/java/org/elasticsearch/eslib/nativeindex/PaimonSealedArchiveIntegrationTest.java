/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.nativeindex;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.NativeLibrary;

import org.apache.paimon.elasticsearch.PaimonElasticsearchFileMeta;
import org.apache.paimon.elasticsearch.PaimonV1Fixture;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.ScalarPredicate;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local V1 archive interoperability; does not claim PEQ1/PQR1 or distributed query acceptance. */
class PaimonSealedArchiveIntegrationTest {
    private static Map<String, FieldIndexConfig> fields() {
        Map<String, FieldIndexConfig> fields = new LinkedHashMap<>();
        fields.put("f0", FieldIndexConfig.builder("f0", FieldIndexConfig.IndexType.SCALAR)
                .scalarType(ScalarFieldType.LONG).build());
        fields.put("f1", FieldIndexConfig.builder("f1", FieldIndexConfig.IndexType.FULLTEXT).build());
        fields.put("f1.keyword", FieldIndexConfig.builder("f1.keyword", FieldIndexConfig.IndexType.KEYWORD).build());
        fields.put("f2", FieldIndexConfig.builder("f2", FieldIndexConfig.IndexType.VECTOR)
                .algorithm(VectorAlgorithm.HNSW).dimension(2).metric("l2").build());
        return fields;
    }

    private static String library() throws Exception {
        NativeRuntime runtime = NativeRuntime.load();
        java.lang.reflect.Field anchor = NativeRuntime.class.getDeclaredField("anchor");
        anchor.setAccessible(true);
        return ((NativeLibrary) anchor.get(runtime)).getFile().getAbsolutePath();
    }

    @Test
    void readsOriginalPaimonSealedFilesWithExactU64IdentityAndRelativeRows() throws Exception {
        NativeRuntime runtime = NativeRuntime.load();
        PaimonV1Fixture archive = new PaimonV1Fixture(library(), runtime.commit, runtime.sharedObjectSha256);
        try {
            // Independently inspect the exact U64 IDs; the V1 query result itself contains doc positions.
            try (org.apache.lucene.store.FSDirectory directory = org.apache.lucene.store.FSDirectory.open(archive.directory());
                    org.apache.lucene.index.DirectoryReader reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
                assertEquals(3, reader.maxDoc());
                for (var leaf : reader.leaves()) {
                    var ids = leaf.reader().getNumericDocValues("__native_rowid");
                    assertNotNull(ids);
                    for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                        assertTrue(ids.advanceExact(doc));
                        assertEquals(PaimonV1Fixture.START + leaf.docBase + doc,
                                ids.longValue() ^ Long.MIN_VALUE);
                    }
                }
            }
            NativeIndexManifest imported = archive.descriptor(31).manifest(NativeMapping.physicalQueryFields(fields()));
            assertEquals(NativeJson.hex(archive.sealedDirectorySha256), imported.directorySha256);
            // The original codec verifies PES1 CRCs and the original file table before import.
            byte[] corrupt = archive.metadata.serialize();
            corrupt[corrupt.length - 1] ^= 1;
            assertThrows(IllegalArgumentException.class,
                    () -> PaimonElasticsearchFileMeta.deserialize(corrupt));
            assertThrows(IOException.class, imported::encode);
            try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
                searcher.loadSealedArchive(archive, archive.offsets, fields(), archive.descriptor(31));
                assertArrayEquals(new long[] {0, 1, 2}, searcher.allRowIds());
                assertArrayEquals(new long[] {2}, searcher.scalarFilter("f0", ScalarPredicate.eq(9007199254740995L)));
                assertArrayEquals(new long[] {0}, searcher.fullTextSearch("f1", "alpha", 10).ids);
                assertArrayEquals(new long[] {0}, searcher.scalarFilter("f1.keyword", ScalarPredicate.eq("alpha engine")));
                assertArrayEquals(new long[] {0, 2}, searcher.vectorSearch("f2", new float[] {0, 0}, 2, null).ids);
                assertArrayEquals(new long[] {2}, searcher.vectorSearch("f2", new float[] {0, 0}, 2,
                        new long[] {-1, 2, 2, Long.MAX_VALUE}).ids);
            }
            assertTrue(archive.closed);
        } finally { archive.delete(); }
    }

    @Test
    void rejectsTheWrongSnapshotAtTheNativeIdentityMarker() throws Exception {
        NativeRuntime runtime = NativeRuntime.load();
        PaimonV1Fixture archive = new PaimonV1Fixture(library(), runtime.commit, runtime.sharedObjectSha256);
        try {
            try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
                assertThrows(IOException.class, () -> searcher.loadSealedArchive(
                        archive, archive.offsets, fields(), archive.descriptor(32)));
            }
            assertTrue(archive.closed);
        } finally { archive.delete(); }
    }

    @Test
    void originalPaimonAbiProbesAnEslibWrittenDirectory() throws Exception {
        Path directory;
        try (NativeESIndexBuilder builder = new NativeESIndexBuilder(fields())) {
            builder.addScalarField("f0", 0, 13L, ScalarFieldType.LONG);
            builder.finishDocument(0);
            builder.build();
            directory = builder.getOutputDir();
        }
        try {
            NativeIndexManifest manifest = NativeIndexManifest.decode(
                    Files.readAllBytes(directory.resolve(NativeIndexManifest.NAME)));
            // The ESLib envelope belongs to the containing archive, outside the native file table.
            Files.delete(directory.resolve(NativeIndexManifest.NAME));
            PaimonV1Fixture.probe(library(), directory, manifest.identity, 0, 0, 0, manifest.rows,
                    NativeJson.digest(new byte[0]), NativeJson.unhex(manifest.configSha256),
                    NativeJson.unhex(manifest.mappingSha256), NativeJson.unhex(manifest.directorySha256));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.delete(path);
            }
        }
    }
}
