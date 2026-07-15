/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.searcher;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.FullTextParams;
import org.elasticsearch.eslib.api.model.FullTextQuerySpec;
import org.elasticsearch.eslib.api.model.IndexFilter;
import org.elasticsearch.eslib.api.model.SearchResult;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.ScalarPredicate;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.builder.DefaultESIndexBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultESIndexSearcherTest {

    @TempDir
    Path tempDir;

    @Test
    void collectFilterRowIdsNoScoresReturnsStoredRowIds() throws Exception {
        Path indexDir = tempDir.resolve("index");
        Files.createDirectories(indexDir);
        try (Directory directory = FSDirectory.open(indexDir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            addDoc(writer, "hit", 42L);
            addDoc(writer, "miss", 7L);
            addDoc(writer, "hit", 100L);
        }

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "label",
                FieldIndexConfig.builder("label", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.KEYWORD)
                        .build());

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            long[] rowIds =
                    searcher.collectFilterRowIdsNoScores(
                            new TermQuery(new Term("label", "hit")));

            assertArrayEquals(new long[] {42L, 100L}, rowIds);
            assertArrayEquals(
                    new long[] {42L, 7L, 100L},
                    searcher.allRowIds(),
                    "allRowIds must preserve every stored logical row ID");
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void longArrayScalarFieldsMatchAnyArrayElement() throws Exception {
        Path indexDir = tempDir.resolve("labels-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "labels",
                FieldIndexConfig.builder("labels", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.LONG)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addScalarField("labels", 0L, new long[] {3L, 7L}, ScalarFieldType.LONG);
            builder.addScalarField("labels", 1L, new long[] {5L}, ScalarFieldType.LONG);
            builder.addScalarField("labels", 2L, new long[] {7L, 11L}, ScalarFieldType.LONG);
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            long[] termHits =
                    searcher.filter("labels", IndexFilter.scalar(ScalarPredicate.eq(7L)));
            assertArrayEquals(
                    new long[] {0L, 2L},
                    termHits,
                    "a long term query should match any value in the labels array");

            long[] inHits =
                    searcher.filter(
                            "labels",
                            IndexFilter.scalar(ScalarPredicate.in(Arrays.asList(5L, 11L))));
            assertArrayEquals(
                    new long[] {1L, 2L},
                    inHits,
                    "IN should match if any labels array element is in the query set");
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void exclusiveScalarBoundsAtDomainExtremaMatchNoDocuments() throws Exception {
        Path indexDir = tempDir.resolve("scalar-extrema-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "i",
                FieldIndexConfig.builder("i", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        configs.put(
                "l",
                FieldIndexConfig.builder("l", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.LONG)
                        .build());
        configs.put(
                "f",
                FieldIndexConfig.builder("f", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.FLOAT)
                        .build());
        configs.put(
                "d",
                FieldIndexConfig.builder("d", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.DOUBLE)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addScalarField("i", 0L, Integer.MIN_VALUE, ScalarFieldType.INT);
            builder.addScalarField("l", 0L, Long.MIN_VALUE, ScalarFieldType.LONG);
            builder.addScalarField("f", 0L, Float.NEGATIVE_INFINITY, ScalarFieldType.FLOAT);
            builder.addScalarField("d", 0L, Double.NEGATIVE_INFINITY, ScalarFieldType.DOUBLE);

            builder.addScalarField("i", 1L, 0, ScalarFieldType.INT);
            builder.addScalarField("l", 1L, 0L, ScalarFieldType.LONG);
            builder.addScalarField("f", 1L, 0.0F, ScalarFieldType.FLOAT);
            builder.addScalarField("d", 1L, 0.0D, ScalarFieldType.DOUBLE);

            builder.addScalarField("i", 2L, Integer.MAX_VALUE, ScalarFieldType.INT);
            builder.addScalarField("l", 2L, Long.MAX_VALUE, ScalarFieldType.LONG);
            builder.addScalarField("f", 2L, Float.POSITIVE_INFINITY, ScalarFieldType.FLOAT);
            builder.addScalarField("d", 2L, Double.POSITIVE_INFINITY, ScalarFieldType.DOUBLE);
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "i", IndexFilter.scalar(ScalarPredicate.lt(Integer.MIN_VALUE))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "i", IndexFilter.scalar(ScalarPredicate.gt(Integer.MAX_VALUE))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter("l", IndexFilter.scalar(ScalarPredicate.lt(Long.MIN_VALUE))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter("l", IndexFilter.scalar(ScalarPredicate.gt(Long.MAX_VALUE))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "f",
                            IndexFilter.scalar(ScalarPredicate.lt(Float.NEGATIVE_INFINITY))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "f",
                            IndexFilter.scalar(ScalarPredicate.gt(Float.POSITIVE_INFINITY))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "d",
                            IndexFilter.scalar(ScalarPredicate.lt(Double.NEGATIVE_INFINITY))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "d",
                            IndexFilter.scalar(ScalarPredicate.gt(Double.POSITIVE_INFINITY))));

            assertArrayEquals(
                    new long[] {0L},
                    searcher.filter(
                            "i", IndexFilter.scalar(ScalarPredicate.lte(Integer.MIN_VALUE))));
            assertArrayEquals(
                    new long[] {2L},
                    searcher.filter(
                            "d",
                            IndexFilter.scalar(ScalarPredicate.gte(Double.POSITIVE_INFINITY))));
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void vectorSearchExactCandidatesReturnsTopKFromCandidateIds() throws Exception {
        Path indexDir = tempDir.resolve("vector-index");
        Files.createDirectories(indexDir);
        try (Directory directory = FSDirectory.open(indexDir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            addVectorDoc(writer, new float[] {0.0f, 1.0f}, 0L);
            addVectorDoc(writer, new float[] {1.0f, 0.0f}, 1L);
            addVectorDoc(writer, new float[] {0.8f, 0.6f}, 2L);
            addVectorDoc(writer, new float[] {0.6f, 0.8f}, 3L);
            addVectorDoc(writer, new float[] {0.0f, -1.0f}, 4L);
        }

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .dimension(2)
                        .metric("dot_product")
                        .build());

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            SearchResult result =
                    searcher.vectorSearchExactCandidates(
                            "vec", new float[] {1.0f, 0.0f}, 2, new long[] {3L, 0L, 2L});

            assertEquals(2, result.count);
            assertArrayEquals(new long[] {2L, 3L}, result.ids);
            assertTrue(result.scores[0] > result.scores[1]);

            SearchResult unbounded =
                    searcher.vectorSearchExactCandidates(
                            "vec",
                            new float[] {1.0f, 0.0f},
                            Integer.MAX_VALUE,
                            new long[] {3L, 0L, 2L});
            assertEquals(3, unbounded.count);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void vectorSearchCapsUnboundedTopKAtIndexSize() throws Exception {
        Path indexDir = tempDir.resolve("unbounded-top-k-vector-index");
        Files.createDirectories(indexDir);
        try (Directory directory = FSDirectory.open(indexDir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            addVectorDoc(writer, new float[] {1.0f, 0.0f}, 10L);
            addVectorDoc(writer, new float[] {0.8f, 0.6f}, 20L);
            addVectorDoc(writer, new float[] {0.0f, 1.0f}, 30L);
        }

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .dimension(2)
                        .metric("dot_product")
                        .build());

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            SearchResult result =
                    searcher.vectorSearch(
                            "vec", new float[] {1.0f, 0.0f}, Integer.MAX_VALUE, null);

            assertEquals(3, result.count);
            assertArrayEquals(new long[] {10L, 20L, 30L}, result.ids);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void failedLoadClosesItsProviderAndLeavesSearcherReusable() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        ArchiveDataProvider invalidArchive =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        throw new AssertionError("an empty directory must not fork its provider");
                    }

                    @Override
                    public void close() {
                        closeCount.incrementAndGet();
                    }
                };

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        assertThrows(
                IOException.class,
                () ->
                        searcher.load(
                                invalidArchive,
                                java.util.Collections.emptyMap(),
                                java.util.Collections.emptyMap()));
        assertEquals(1, closeCount.get());

        Path indexDir = tempDir.resolve("load-retry-index");
        Files.createDirectories(indexDir);
        try (Directory directory = FSDirectory.open(indexDir);
                IndexWriter writer =
                        new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            addDoc(writer, "hit", 42L);
        }
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "label",
                FieldIndexConfig.builder("label", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.KEYWORD)
                        .build());
        InMemoryArchive validArchive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(validArchive, validArchive.fileOffsets(), configs);
            assertArrayEquals(
                    new long[] {42L},
                    searcher.filter(
                            "label", IndexFilter.scalar(ScalarPredicate.eq("hit"))));
        } finally {
            searcher.close();
            validArchive.close();
        }
    }

    @Test
    void invalidArchiveRangesCloseProviderBeforeDirectoryConstructionCompletes() {
        AtomicInteger closeCount = new AtomicInteger();
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        throw new AssertionError("invalid ranges must fail before opening inputs");
                    }

                    @Override
                    public void close() {
                        closeCount.incrementAndGet();
                    }
                };
        Map<String, long[]> invalidOffsets = new HashMap<>();
        invalidOffsets.put("overflow", new long[] {Long.MAX_VALUE, 1});

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        searcher.load(
                                provider,
                                invalidOffsets,
                                java.util.Collections.emptyMap()));
        assertEquals(1, closeCount.get());
    }

    @Test
    void failedLoadPreservesRuntimeCleanupFailuresAsSuppressed() {
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        throw new AssertionError("an empty directory must not fork its provider");
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("close failed");
                    }
                };

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                searcher.load(
                                        provider,
                                        java.util.Collections.emptyMap(),
                                        java.util.Collections.emptyMap()));
        assertEquals(2, failure.getSuppressed().length);
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
        assertEquals("close failed", failure.getSuppressed()[1].getMessage());
    }

    @Test
    void diskBBQCosineVectorSearchNormalizesVectorsAndReturnsExactCandidates() throws Exception {
        int dim = 32;
        int numVectors = 1000;
        float[][] vectors = normalizedRandomVectors(numVectors, dim, 42L);

        Path indexDir = tempDir.resolve("diskbbq-filtered-vector-index");
        Files.createDirectories(indexDir);

        Map<String, String> params = new HashMap<>();
        params.put("vectors_per_cluster", "64");

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(dim)
                        .metric("cosine")
                        .algorithmParams(params)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int i = 0; i < numVectors; i++) {
                builder.addVector("vec", i, vectors[i]);
            }
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            long[] candidateIds = new long[100];
            for (int i = 0; i < candidateIds.length; i++) {
                candidateIds[i] = i * 10L;
            }

            float[] scaledQuery = Arrays.copyOf(vectors[0], vectors[0].length);
            for (int i = 0; i < scaledQuery.length; i++) {
                scaledQuery[i] *= 3.0f;
            }
            SearchResult result = searcher.vectorSearch("vec", scaledQuery, 10, candidateIds);

            assertTrue(result.count > 0, "filtered DiskBBQ vector search should return hits");
            assertEquals(0L, result.ids[0], "query vector row should be top-1 inside candidates");
            assertEquals(1.0f, result.scores[0], 1.0e-5f);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void diskBBQSmallSegmentSearchesSingleCentroidLayout() throws Exception {
        int dim = 32;
        int numVectors = 48;
        float[][] vectors = normalizedRandomVectors(numVectors, dim, 17L);

        Path indexDir = tempDir.resolve("diskbbq-small-vector-index");
        Files.createDirectories(indexDir);

        Map<String, String> params = new HashMap<>();
        params.put("vectors_per_cluster", "64");
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(dim)
                        .metric("l2")
                        .algorithmParams(params)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int i = 0; i < numVectors; i++) {
                builder.addVector("vec", i, vectors[i]);
            }
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            SearchResult result = searcher.vectorSearch("vec", vectors[0], 10, null);

            assertEquals(10, result.count);
            assertEquals(0L, result.ids[0]);
            assertEquals(1.0f, result.scores[0], 1.0e-6f);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void diskBBQPureVectorSearchRescoresQuantizedCandidates() throws Exception {
        int dim = 32;
        int numVectors = 1000;
        float[][] vectors = normalizedPaimonDebugVectors(numVectors, dim);

        Path indexDir = tempDir.resolve("diskbbq-pure-vector-index");
        Files.createDirectories(indexDir);

        Map<String, String> params = new HashMap<>();
        params.put("vectors_per_cluster", "64");

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(dim)
                        .metric("l2")
                        .algorithmParams(params)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int i = 0; i < numVectors; i++) {
                builder.addVector("vec", i, vectors[i]);
            }
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);

            SearchResult result = searcher.vectorSearch("vec", vectors[0], numVectors, null);

            assertTrue(result.count >= 10, "pure DiskBBQ vector search should return candidates");
            assertEquals(
                    0L,
                    result.ids[0],
                    "pure DiskBBQ candidates should be reranked by exact vector score");
            assertEquals(1.0f, result.scores[0], 1e-6f);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void diskBBQSparseEuclideanVectorsReturnFiniteHits() throws Exception {
        int dim = 32;
        int numVectors = 1_000;
        float[][] vectors = new float[numVectors][];
        for (int i = 0; i < numVectors; i++) {
            float[] vector = new float[dim];
            vector[i % dim] = 1.0f;
            vectors[i] = vector;
        }

        Path indexDir = tempDir.resolve("diskbbq-sparse-euclidean-index");
        Files.createDirectories(indexDir);

        Map<String, String> params = new HashMap<>();
        params.put("vectors_per_cluster", "64");
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vec",
                FieldIndexConfig.builder("vec", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(dim)
                        .metric("l2")
                        .algorithmParams(params)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int i = 0; i < numVectors; i++) {
                builder.addVector("vec", i, vectors[i]);
            }
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            SearchResult result = searcher.vectorSearch("vec", vectors[0], 10, null);

            assertEquals(10, result.count, "sparse L2 search must not lose NaN candidates");
            for (float score : result.scores) {
                assertTrue(Float.isFinite(score), "sparse L2 score must be finite: " + score);
            }
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void inQueriesSupportFloatingPointAndLargeValueSets() throws Exception {
        Path indexDir = tempDir.resolve("large-in-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "i",
                FieldIndexConfig.builder("i", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        configs.put(
                "f",
                FieldIndexConfig.builder("f", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.FLOAT)
                        .build());
        configs.put(
                "d",
                FieldIndexConfig.builder("d", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.DOUBLE)
                        .build());
        configs.put(
                "k",
                FieldIndexConfig.builder("k", FieldIndexConfig.IndexType.KEYWORD)
                        .scalarType(ScalarFieldType.KEYWORD)
                        .build());

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int docId = 0; docId < 3; docId++) {
                builder.addScalarField("i", docId, docId, ScalarFieldType.INT);
                builder.addScalarField("f", docId, docId + 0.25f, ScalarFieldType.FLOAT);
                builder.addScalarField("d", docId, docId + 0.5d, ScalarFieldType.DOUBLE);
                builder.addScalarField("k", docId, "k" + docId, ScalarFieldType.KEYWORD);
                builder.finishDocument(docId);
            }
            builder.build();
        }

        List<Object> manyInts = new java.util.ArrayList<>();
        List<Object> manyKeywords = new java.util.ArrayList<>();
        for (int i = 0; i < 1_500; i++) {
            manyInts.add(i);
            manyKeywords.add("k" + i);
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            assertArrayEquals(
                    new long[] {0L, 1L, 2L},
                    searcher.filter("i", IndexFilter.scalar(ScalarPredicate.in(manyInts))));
            assertArrayEquals(
                    new long[] {0L, 1L, 2L},
                    searcher.filter("k", IndexFilter.scalar(ScalarPredicate.in(manyKeywords))));
            assertArrayEquals(
                    new long[] {1L},
                    searcher.filter(
                            "f",
                            IndexFilter.scalar(
                                    ScalarPredicate.in(Arrays.asList(1.25f, 99.0f)))));
            assertArrayEquals(
                    new long[] {2L},
                    searcher.filter(
                            "d",
                            IndexFilter.scalar(
                                    ScalarPredicate.in(Arrays.asList(2.5d, 99.0d)))));
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void negativeScalarPredicatesExcludeNullDocuments() throws Exception {
        Path indexDir = tempDir.resolve("negative-scalar-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "i",
                FieldIndexConfig.builder("i", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addScalarField("i", 0, 1, ScalarFieldType.INT);
            builder.finishDocument(0);
            builder.addScalarField("i", 1, 2, ScalarFieldType.INT);
            builder.finishDocument(1);
            builder.addNullDoc(2);
            builder.finishDocument(2);
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            assertArrayEquals(
                    new long[] {1L},
                    searcher.filter("i", IndexFilter.scalar(ScalarPredicate.neq(1))));
            assertArrayEquals(
                    new long[] {1L},
                    searcher.filter(
                            "i", IndexFilter.scalar(ScalarPredicate.notIn(Arrays.asList(1)))));
            assertArrayEquals(
                    new long[0],
                    searcher.filter(
                            "i", IndexFilter.scalar(ScalarPredicate.notIn(Arrays.asList(1, 2)))));
        } finally {
            searcher.close();
            archive.close();
        }
    }

    @Test
    void emptyAndPureNegativeBooleanQueriesFollowElasticsearchSemantics() throws Exception {
        Path indexDir = tempDir.resolve("boolean-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "text",
                FieldIndexConfig.builder("text", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(BuiltinAnalyzer.STANDARD)
                        .build());
        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addTextField("text", 0, "administrator active");
            builder.finishDocument(0);
            builder.addTextField("text", 1, "viewer active");
            builder.finishDocument(1);
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            FullTextQuerySpec.Bool empty =
                    new FullTextQuerySpec.Bool(
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptyList());
            assertArrayEquals(
                    new long[] {0L, 1L}, searcher.fullTextSearch(empty, 10).ids);

            FullTextQuerySpec.Bool pureNegative =
                    new FullTextQuerySpec.Bool(
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptyList(),
                            Arrays.asList(
                                    new FullTextQuerySpec.Match(
                                            "text",
                                            "administrator",
                                            FullTextParams.defaults())));
            assertArrayEquals(
                    new long[] {1L}, searcher.fullTextSearch(pureNegative, 10).ids);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> searcher.fullTextSearch(empty, 0));
            assertThrows(
                    NullPointerException.class,
                    () -> searcher.fullTextSearch((FullTextQuerySpec) null, 10));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            searcher.filter(
                                    "text", IndexFilter.scalar(ScalarPredicate.eq("value"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> searcher.filter("missing", IndexFilter.exists()));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            searcher.fullTextSearch(
                                    new FullTextQuerySpec.Match(
                                            "missing", "viewer", FullTextParams.defaults()),
                                    10));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> searcher.fullTextSearch(deeplyNestedQuery("text", 64), 10));
        } finally {
            searcher.close();
            archive.close();
        }
    }

    private static FullTextQuerySpec deeplyNestedQuery(String field, int levels) {
        FullTextQuerySpec query =
                new FullTextQuerySpec.Match(field, "viewer", FullTextParams.defaults());
        for (int i = 0; i < levels; i++) {
            query =
                    new FullTextQuerySpec.Bool(
                            java.util.Collections.singletonList(query),
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptyList());
        }
        return query;
    }

    @Test
    void autoFuzzinessUsesAnalyzedTokenLength() throws Exception {
        Path indexDir = tempDir.resolve("auto-fuzziness-index");
        Files.createDirectories(indexDir);

        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "text",
                FieldIndexConfig.builder("text", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(BuiltinAnalyzer.STANDARD)
                        .build());
        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addTextField("text", 0, "administrator");
            builder.finishDocument(0);
            builder.addTextField("text", 1, "viewer");
            builder.finishDocument(1);
            builder.build();
        }

        DefaultESIndexSearcher searcher = new DefaultESIndexSearcher();
        InMemoryArchive archive = InMemoryArchive.fromDirectory(indexDir);
        try {
            searcher.load(archive, archive.fileOffsets(), configs);
            FullTextParams auto =
                    new FullTextParams(
                            FullTextParams.Operator.OR,
                            1.0f,
                            FullTextParams.AUTO_FUZZINESS,
                            50,
                            0);
            SearchResult result =
                    searcher.fullTextSearch(
                            new FullTextQuerySpec.Match("text", "administrater", auto), 10);
            assertArrayEquals(new long[] {0L}, result.ids);
        } finally {
            searcher.close();
            archive.close();
        }
    }

    private static float[][] normalizedRandomVectors(int numVectors, int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[][] vectors = new float[numVectors][];
        for (int i = 0; i < numVectors; i++) {
            float[] v = new float[dim];
            float norm = 0;
            for (int d = 0; d < dim; d++) {
                v[d] = rng.nextFloat() - 0.5f;
                norm += v[d] * v[d];
            }
            norm = (float) Math.sqrt(norm);
            for (int d = 0; d < dim; d++) {
                v[d] /= norm;
            }
            vectors[i] = v;
        }
        return vectors;
    }

    private static float[][] normalizedPaimonDebugVectors(int numVectors, int dim) {
        float[][] vectors = new float[numVectors][];
        for (int rowId = 0; rowId < numVectors; rowId++) {
            float[] values = new float[dim];
            int hot = (rowId * 7 + 3) % dim;
            float norm = 0.0f;
            for (int d = 0; d < dim; d++) {
                float base = d == hot ? 8.0f : 0.0f;
                float noise =
                        (float) ((((rowId + 17L) * (d + 11L) * 13L) % 997L) / 9970.0d);
                values[d] = base + noise;
                norm += values[d] * values[d];
            }
            norm = (float) Math.sqrt(norm);
            for (int d = 0; d < dim; d++) {
                values[d] /= norm;
            }
            vectors[rowId] = values;
        }
        return vectors;
    }

    private static void addDoc(IndexWriter writer, String label, long rowId) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("label", label, Field.Store.NO));
        doc.add(new SortedNumericDocValuesField(DefaultESIndexBuilder.ROW_ID_FIELD, rowId));
        writer.addDocument(doc);
    }


    private static void addVectorDoc(IndexWriter writer, float[] vector, long rowId) throws IOException {
        Document doc = new Document();
        doc.add(new KnnFloatVectorField("vec", vector, VectorSimilarityFunction.DOT_PRODUCT));
        doc.add(new SortedNumericDocValuesField(DefaultESIndexBuilder.ROW_ID_FIELD, rowId));
        writer.addDocument(doc);
    }

    private static final class InMemoryArchive implements ArchiveDataProvider {
        private final byte[] data;
        private final Map<String, long[]> fileOffsets;

        private InMemoryArchive(byte[] data, Map<String, long[]> fileOffsets) {
            this.data = data;
            this.fileOffsets = fileOffsets;
        }

        static InMemoryArchive fromDirectory(Path directory) throws IOException {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            Map<String, long[]> offsets = new LinkedHashMap<>();
            List<Path> files;
            try (Stream<Path> stream = Files.list(directory)) {
                files =
                        stream.filter(Files::isRegularFile)
                                .sorted()
                                .collect(Collectors.toList());
            }
            for (Path file : files) {
                byte[] bytes = Files.readAllBytes(file);
                long offset = archive.size();
                archive.write(bytes);
                offsets.put(file.getFileName().toString(), new long[] {offset, bytes.length});
            }
            return new InMemoryArchive(archive.toByteArray(), offsets);
        }

        Map<String, long[]> fileOffsets() {
            return fileOffsets;
        }

        @Override
        public byte[] readRange(long offset, int length) {
            int from = Math.toIntExact(offset);
            return Arrays.copyOfRange(data, from, from + length);
        }

        @Override
        public ArchiveDataProvider fork() {
            return new InMemoryArchive(data, fileOffsets);
        }
    }
}
