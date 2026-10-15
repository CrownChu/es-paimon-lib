/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.nativeindex;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.IndexFilter;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.ScalarPredicate;
import org.elasticsearch.eslib.api.model.SearchResult;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real C++ calls are mandatory. Missing native assets fail rather than skip these tests. */
class NativeWriteReadIntegrationTest {
    @Test
    void nativeIkSmartAndMaxWordPreserveDictionaryIdentityAndTokenModes() throws Exception {
        // The test-only dictionary contains one long lexeme and its three
        // overlapping components. IK smart keeps the longest lexeme; max-word
        // preserves the overlapping lexemes. Java Lucene independently reads
        // every emitted term in addition to the actual native query checks.
        Map<String, FieldIndexConfig> fields = new LinkedHashMap<>();
        fields.put(
                "smart",
                FieldIndexConfig.builder("smart", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(BuiltinAnalyzer.IK_SMART)
                        .build());
        fields.put(
                "max",
                FieldIndexConfig.builder("max", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(BuiltinAnalyzer.IK_MAX_WORD)
                        .build());
        Path output;
        try (NativeESIndexBuilder builder = new NativeESIndexBuilder(fields)) {
            builder.addTextField("smart", 0, "中华人民共和国");
            builder.addTextField("max", 0, "中华人民共和国");
            builder.finishDocument(0);
            builder.addTextField("smart", 1, "人民");
            builder.addTextField("max", 1, "人民");
            builder.finishDocument(1);
            builder.addNullDoc(2);
            builder.finishDocument(2);
            builder.build();
            output = builder.getOutputDir();
        }
        checkJavaLucene(output, 3);
        try (FSDirectory directory = FSDirectory.open(output);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(
                    new java.util.TreeSet<>(Arrays.asList("中华人民共和国", "人民")),
                    terms(reader, "smart"));
            assertEquals(
                    new java.util.TreeSet<>(Arrays.asList("中华人民共和国", "中华", "人民", "共和国")),
                    terms(reader, "max"));
        }
        Archive archive = new Archive(output);
        try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
            searcher.load(archive, archive.offsets, fields);
            assertArrayEquals(new long[0], searcher.fullTextSearch("smart", "中华", 10).ids);
            assertArrayEquals(new long[] {0}, searcher.fullTextSearch("max", "中华", 10).ids);
            assertArrayEquals(new long[] {1}, searcher.fullTextSearch("smart", "人民", 10).ids);
            long[] maxHits = searcher.fullTextSearch("max", "人民", 10).ids;
            Arrays.sort(maxHits);
            assertArrayEquals(new long[] {0, 1}, maxHits);
            assertArrayEquals(new long[] {2}, searcher.filter("smart", IndexFilter.notExists()));
        }
        assertTrue(archive.closed);
    }

    private static java.util.Set<String> terms(DirectoryReader reader, String field)
            throws IOException {
        java.util.Set<String> values = new java.util.TreeSet<>();
        org.apache.lucene.index.TermsEnum terms =
                org.apache.lucene.index.MultiTerms.getTerms(reader, field).iterator();
        org.apache.lucene.util.BytesRef value;
        while ((value = terms.next()) != null) values.add(value.utf8ToString());
        return values;
    }

    @Test
    void nativeScalarAndTextWithHolesMatchesIndependentLuceneReader() throws Exception {
        Map<String, FieldIndexConfig> fields = new LinkedHashMap<>();
        fields.put(
                "title",
                FieldIndexConfig.builder("title", FieldIndexConfig.IndexType.FULLTEXT).build());
        fields.put(
                "id",
                FieldIndexConfig.builder("id", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.LONG)
                        .build());
        fields.put(
                "category",
                FieldIndexConfig.builder("category", FieldIndexConfig.IndexType.KEYWORD).build());
        Path output;
        try (NativeESIndexBuilder builder = new NativeESIndexBuilder(fields)) {
            builder.addTextField("title", 0, "alpha engine 中文");
            builder.addScalarField("id", 0, 9007199254740993L, ScalarFieldType.LONG);
            builder.addScalarField("category", 0, "a", ScalarFieldType.KEYWORD);
            builder.finishDocument(0);
            builder.addNullDoc(1);
            builder.finishDocument(1);
            builder.addTextField("title", 2, "beta engine");
            builder.addScalarField("id", 2, 9007199254740995L, ScalarFieldType.LONG);
            builder.addScalarField("category", 2, "a", ScalarFieldType.KEYWORD);
            builder.finishDocument(2);
            builder.addTextField("title", 3, "");
            builder.finishDocument(3);
            builder.build();
            output = builder.getOutputDir();
        }
        checkJavaLucene(output, 4);
        Archive archive = new Archive(output);
        try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
            searcher.load(archive, archive.offsets, fields);
            assertArrayEquals(new long[] {0, 1, 2, 3}, searcher.allRowIds());
            assertArrayEquals(
                    new long[] {0},
                    searcher.scalarFilter("id", ScalarPredicate.eq(9007199254740993L)));
            assertArrayEquals(
                    new long[] {0, 2}, searcher.scalarFilter("category", ScalarPredicate.eq("a")));
            assertArrayEquals(
                    new long[] {0, 2}, searcher.scalarFilter("category", ScalarPredicate.neq("b")));
            assertArrayEquals(
                    new long[] {2},
                    searcher.scalarFilter(
                            "id",
                            ScalarPredicate.notIn(Collections.singletonList(9007199254740993L))));
            assertArrayEquals(
                    new long[] {0, 2},
                    searcher.scalarFilter(
                            "id", ScalarPredicate.range(9007199254740993L, 9007199254740995L)));
            assertArrayEquals(new long[] {0}, searcher.fullTextSearch("title", "alpha", 10).ids);
            assertArrayEquals(new long[] {1}, javaMissingTextRows(output));
            assertArrayEquals(new long[] {1}, searcher.filter("title", IndexFilter.notExists()));
        }
        assertTrue(archive.closed);
    }

    @Test
    void nativeFloatHnswAndPrefilterMatchIndependentSquaredL2GroundTruth() throws Exception {
        FieldIndexConfig vector =
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.HNSW)
                        .dimension(2)
                        .metric("l2")
                        .build();
        Map<String, FieldIndexConfig> fields = Collections.singletonMap("vector", vector);
        float[][] values = {{2, 1}, {1, 0}, {0, 2}, {4, 2}};
        Path output;
        try (NativeESIndexBuilder builder = new NativeESIndexBuilder(fields)) {
            for (int row = 0; row < values.length; row++) {
                builder.addVector("vector", row, values[row]);
                builder.finishDocument(row);
            }
            builder.build();
            output = builder.getOutputDir();
        }
        checkJavaLucene(output, 4);
        Archive archive = new Archive(output);
        try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
            searcher.load(archive, archive.offsets, fields);
            SearchResult nearest = searcher.vectorSearch("vector", new float[] {0, 0}, 3, null);
            assertArrayEquals(new long[] {1, 2, 0}, nearest.ids);
            for (int i = 0; i < nearest.count; i++)
                assertEquals(exactL2Score(values[(int) nearest.ids[i]]), nearest.scores[i], 1e-5f);
            SearchResult filtered =
                    searcher.vectorSearch("vector", new float[] {0, 0}, 2, new long[] {0, 3});
            assertArrayEquals(new long[] {0, 3}, filtered.ids);
            for (int i = 0; i < filtered.count; i++)
                assertEquals(
                        exactL2Score(values[(int) filtered.ids[i]]), filtered.scores[i], 1e-5f);
        }
    }

    @Test
    void nativeEmptyIndexIsARealSealedAndReadableCommit() throws Exception {
        Map<String, FieldIndexConfig> fields =
                Collections.singletonMap(
                        "title",
                        FieldIndexConfig.builder("title", FieldIndexConfig.IndexType.FULLTEXT)
                                .build());
        Path output;
        try (NativeESIndexBuilder builder = new NativeESIndexBuilder(fields)) {
            builder.build();
            output = builder.getOutputDir();
        }
        checkJavaLucene(output, 0);
        Archive archive = new Archive(output);
        try (NativeESIndexSearcher searcher = new NativeESIndexSearcher()) {
            searcher.load(archive, archive.offsets, fields);
            assertEquals(0, searcher.allRowIds().length);
        }
    }

    private static float exactL2Score(float[] vector) {
        double squared = 0;
        for (float value : vector) squared += (double) value * value;
        return (float) (1.0 / (1.0 + squared));
    }

    private static long[] javaMissingTextRows(Path output) throws Exception {
        try (FSDirectory directory = FSDirectory.open(output);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            BooleanQuery query =
                    new BooleanQuery.Builder()
                            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                            .add(new FieldExistsQuery("title"), BooleanClause.Occur.MUST_NOT)
                            .build();
            return Arrays.stream(new IndexSearcher(reader).search(query, 10).scoreDocs)
                    .mapToLong(hit -> hit.doc)
                    .sorted()
                    .toArray();
        }
    }

    private static void checkJavaLucene(Path output, int expected) throws Exception {
        try (FSDirectory directory = FSDirectory.open(output);
                CheckIndex checker = new CheckIndex(directory)) {
            assertTrue(checker.checkIndex().clean, "Independent Java Lucene CheckIndex failed");
        }
        try (FSDirectory directory = FSDirectory.open(output);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(expected, reader.maxDoc());
            assertEquals(expected, reader.numDocs());
            assertFalse(reader.hasDeletions());
            for (LeafReaderContext leaf : reader.leaves()) {
                NumericDocValues rows = leaf.reader().getNumericDocValues("__native_rowid");
                for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                    assertNotNull(rows);
                    assertTrue(rows.advanceExact(doc));
                    assertEquals((long) leaf.docBase + doc, rows.longValue() ^ Long.MIN_VALUE);
                }
            }
        }
    }

    private static final class Archive implements ArchiveDataProvider {
        final byte[] bytes;
        final Map<String, long[]> offsets = new LinkedHashMap<>();
        boolean closed;

        Archive(Path output) throws IOException {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            try (java.util.stream.Stream<Path> files = Files.list(output)) {
                for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                    if (file.getFileName().toString().equals("write.lock")) continue;
                    byte[] value = Files.readAllBytes(file);
                    offsets.put(
                            file.getFileName().toString(), new long[] {data.size(), value.length});
                    data.write(value);
                }
            }
            bytes = data.toByteArray();
        }

        @Override
        public byte[] readRange(long offset, int length) throws IOException {
            if (closed || offset < 0 || offset > bytes.length - length)
                throw new IOException("Invalid fixture archive range");
            return Arrays.copyOfRange(bytes, (int) offset, (int) offset + length);
        }

        @Override
        public ArchiveDataProvider fork() {
            throw new UnsupportedOperationException("Fixture uses serial archive materialization");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
