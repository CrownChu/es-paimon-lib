/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.builder;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.elasticsearch.eslib.adapter.LuceneAdapterFactory;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultESIndexBuilderTest {

    @Test
    void rejectsFieldConfigWhoseMapKeyDoesNotMatchItsFieldName() {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "alias",
                FieldIndexConfig.builder("actual", FieldIndexConfig.IndexType.FULLTEXT)
                        .build());

        assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultESIndexBuilder(configs));
    }

    @TempDir
    Path tempDir;

    @Test
    void finishDocumentKeepsPendingMemoryBoundedAndPreservesGaps() throws Exception {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "value",
                FieldIndexConfig.builder("value", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());

        Path indexDir = tempDir.resolve("streaming-index");
        Files.createDirectories(indexDir);
        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            for (int docId = 0; docId < 5_000; docId++) {
                if (docId != 123) {
                    builder.addScalarField("value", docId, docId, ScalarFieldType.INT);
                }
                builder.finishDocument(docId);
                assertEquals(0, builder.pendingDocumentCount());
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> builder.addScalarField("value", 10, 10, ScalarFieldType.INT));
            builder.build();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherWrites() throws Exception {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "value",
                FieldIndexConfig.builder("value", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        Path indexDir = tempDir.resolve("closed-builder");
        Files.createDirectories(indexDir);

        DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir);
        builder.close();
        builder.close();
        assertThrows(IllegalStateException.class, () -> builder.addNullDoc(0));
    }

    @Test
    void scalarWritesMustMatchTheConfiguredFieldAndType() throws Exception {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "value",
                FieldIndexConfig.builder("value", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        configs.put(
                "text",
                FieldIndexConfig.builder("text", FieldIndexConfig.IndexType.FULLTEXT)
                        .analyzer(org.elasticsearch.eslib.api.model.BuiltinAnalyzer.STANDARD)
                        .build());
        Path indexDir = tempDir.resolve("scalar-validation");
        Files.createDirectories(indexDir);

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.addScalarField("missing", 0, 1, ScalarFieldType.INT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.addScalarField("text", 0, 1, ScalarFieldType.INT));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.addScalarField("value", 0, 1L, ScalarFieldType.LONG));
            assertThrows(
                    NullPointerException.class,
                    () -> builder.addScalarField("value", 0, null, ScalarFieldType.INT));
        }
    }

    @Test
    void rejectsDocumentIdsOutsideLuceneRange() throws Exception {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "value",
                FieldIndexConfig.builder("value", FieldIndexConfig.IndexType.SCALAR)
                        .scalarType(ScalarFieldType.INT)
                        .build());
        Path indexDir = tempDir.resolve("invalid-document-id");
        Files.createDirectories(indexDir);

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            assertThrows(IllegalArgumentException.class, () -> builder.addNullDoc(-1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.addNullDoc(IndexWriter.MAX_DOCS));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> builder.finishDocument(IndexWriter.MAX_DOCS));
        }
    }

    @Test
    void innerProductUsesMaximumInnerProductSimilarity() throws Exception {
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vector",
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.HNSW)
                        .dimension(2)
                        .metric("inner_product")
                        .build());
        Path indexDir = tempDir.resolve("inner-product-index");
        Files.createDirectories(indexDir);

        try (DefaultESIndexBuilder builder = new DefaultESIndexBuilder(configs, indexDir)) {
            builder.addVector("vector", 0, new float[] {2.0f, 1.0f});
            builder.build();
        }
        try (Directory directory = FSDirectory.open(indexDir);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(
                    VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT,
                    reader.leaves()
                            .get(0)
                            .reader()
                            .getFieldInfos()
                            .fieldInfo("vector")
                            .getVectorSimilarityFunction());
        }
    }

    @Test
    void diskBBQForceMergeReadsVectorsFromMultipleSegments() throws Exception {
        int dimension = 32;
        int vectorCount = 1_000;
        Map<String, String> parameters = new HashMap<>();
        parameters.put("vectors_per_cluster", "64");
        Map<String, FieldIndexConfig> configs = new HashMap<>();
        configs.put(
                "vector",
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(dimension)
                        .metric("dot_product")
                        .algorithmParams(parameters)
                        .build());

        float[][] vectors = normalizedVectors(vectorCount, dimension);
        Path indexDir = tempDir.resolve("diskbbq-merge-index");
        Files.createDirectories(indexDir);
        try (Directory directory = FSDirectory.open(indexDir)) {
            IndexWriterConfig writerConfig = new IndexWriterConfig(new KeywordAnalyzer());
            writerConfig.setCodec(LuceneAdapterFactory.get().createCodec(configs));
            writerConfig.setUseCompoundFile(false);
            writerConfig.setMaxBufferedDocs(50);
            try (IndexWriter writer = new IndexWriter(directory, writerConfig)) {
                for (float[] vector : vectors) {
                    Document document = new Document();
                    document.add(
                            new KnnFloatVectorField(
                                    "vector", vector, VectorSimilarityFunction.DOT_PRODUCT));
                    writer.addDocument(document);
                }
                writer.forceMerge(1);
            }
        }

        try (Directory directory = FSDirectory.open(indexDir);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, reader.leaves().size(), "the fixture must exercise a real merge");
            TopDocs hits =
                    new IndexSearcher(reader)
                            .search(new KnnFloatVectorQuery("vector", vectors[0], 10), 10);
            assertTrue(hits.scoreDocs.length > 0);
            for (ScoreDoc hit : hits.scoreDocs) {
                assertTrue(Float.isFinite(hit.score));
            }
        }
    }

    private static float[][] normalizedVectors(int count, int dimension) {
        java.util.Random random = new java.util.Random(42L);
        float[][] vectors = new float[count][];
        for (int i = 0; i < count; i++) {
            float[] vector = new float[dimension];
            float squaredNorm = 0.0f;
            for (int d = 0; d < dimension; d++) {
                vector[d] = random.nextFloat() - 0.5f;
                squaredNorm += vector[d] * vector[d];
            }
            float norm = (float) Math.sqrt(squaredNorm);
            for (int d = 0; d < dimension; d++) {
                vector[d] /= norm;
            }
            vectors[i] = vector;
        }
        return vectors;
    }
}
