/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.adapter.lucene10;

import java.util.Collections;
import java.util.Map;
import org.apache.lucene.backward_codecs.lucene912.Lucene912Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat;

/**
 * Read-compatibility codec name.
 *
 * <p>Archives are produced by the Flink-side {@code lucene9} source set ({@code
 * org.elasticsearch.eslib.adapter.lucene9.PaimonLucene9Codec}) — that writer stamps the literal
 * string {@code "PaimonLucene9"} into each segment header. When ES (Lucene 10.x) opens those
 * segments it does {@code Codec.forName("PaimonLucene9")} via the JDK SPI loader, so a class with
 * that exact {@link #getName()} must exist on the read-side classpath or recovery fails with {@code
 * IllegalArgumentException: An SPI class of type org.apache.lucene.codecs.Codec with name
 * 'PaimonLucene9' does not exist}.
 *
 * <p>This thin subclass exposes the read-side name; the body delegates non-vector files to the
 * Lucene 10 backward-codecs implementation of {@link Lucene912Codec}, matching the codec used by
 * the Lucene 9 writer. Vector formats are read from the name recorded by {@link
 * PerFieldKnnVectorsFormat}; both the legacy {@link PaimonDiskBBQVectorsFormat} and the ES 9.4
 * DiskBBQ implementation are registered as Lucene SPI providers in the Lucene 10 source set.
 *
 * <p>This codec is registered for SPI via {@code META-INF/services/org.apache.lucene.codecs.Codec}
 * in the lucene10 source set.
 */
public class PaimonLucene9Codec extends FilterCodec {

    private final PerFieldKnnVectorsFormat knnFormat;

    /** No-arg SPI constructor — required by {@code Codec.forName(...)}. */
    public PaimonLucene9Codec() {
        this(Collections.emptyMap());
    }

    public PaimonLucene9Codec(Map<String, FieldIndexConfig> fieldConfigs) {
        // Do not call Codec.getDefault() from an SPI constructor: Codec's ServiceLoader is still
        // initializing and rejects the recursive lookup. More importantly, this wrapper must use
        // the 9.12 reader implementations for files written by the Lucene 9 build.
        super("PaimonLucene9", new Lucene912Codec());
        this.knnFormat = new PaimonPerFieldKnnVectorsFormat912(fieldConfigs);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }

    /**
     * Per-field KNN format resolver. On the read path {@code fieldConfigs} is empty (SPI ctor) so
     * every field falls through to the per-segment-recorded format name; the actual lookup happens
     * via the parent {@link PerFieldKnnVectorsFormat#getKnnVectorsFormatForReadField}. The map is
     * still consulted at write-time for callers that explicitly construct this codec with field
     * configs.
     */
    private static class PaimonPerFieldKnnVectorsFormat912 extends PerFieldKnnVectorsFormat {

        private final Map<String, FieldIndexConfig> fieldConfigs;

        PaimonPerFieldKnnVectorsFormat912(Map<String, FieldIndexConfig> fieldConfigs) {
            this.fieldConfigs = fieldConfigs;
        }

        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String fieldName) {
            FieldIndexConfig config = fieldConfigs.get(fieldName);
            if (config == null || config.getAlgorithm() == null) {
                return new org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat();
            }
            VectorAlgorithm algorithm = config.getAlgorithm();
            switch (algorithm) {
                case DISKBBQ:
                    int vpc =
                            config.getIntParam(
                                    "vectors_per_cluster",
                                    PaimonDiskBBQVectorsFormat.DEFAULT_VECTORS_PER_CLUSTER);
                    int cpc =
                            config.getIntParam(
                                    "centroids_per_parent_cluster",
                                    PaimonDiskBBQVectorsFormat
                                            .DEFAULT_CENTROIDS_PER_PARENT_CLUSTER);
                    return new PaimonDiskBBQVectorsFormat(vpc, cpc);
                case HNSW:
                    int m = config.getIntParam("m", 16);
                    int efConstruction = config.getIntParam("ef_construction", 100);
                    return new org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat(
                            m, efConstruction);
                default:
                    return new org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat();
            }
        }
    }
}
