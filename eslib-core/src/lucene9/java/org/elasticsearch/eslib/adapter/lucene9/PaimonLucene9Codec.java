package org.elasticsearch.eslib.adapter.lucene9;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene912.Lucene912Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.adapter.PaimonHnswVectorsFormat;
import org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat;
import org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat;
import org.elasticsearch.eslib.searcher.SearchExecutorHolder;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class PaimonLucene9Codec extends FilterCodec {

    private final PerFieldKnnVectorsFormat knnFormat;

    /** No-arg constructor required by Lucene SPI (Codec.forName). Picks up executor from ThreadLocal. */
    public PaimonLucene9Codec() {
        this(Collections.emptyMap(), Collections.emptyMap(), SearchExecutorHolder.get());
    }

    public PaimonLucene9Codec(Map<String, FieldIndexConfig> fieldConfigs) {
        this(fieldConfigs, Collections.emptyMap(), null);
    }

    public PaimonLucene9Codec(Map<String, FieldIndexConfig> fieldConfigs,
                                ExecutorService searchExecutor) {
        this(fieldConfigs, Collections.emptyMap(), searchExecutor);
    }

    /**
     * @param fieldConfigs     per-field index config
     * @param overrideFormats  externally-provided KnnVectorsFormat instances keyed by algorithm name
     *                         (e.g. "NATIVE" → HavenaskNativeKnnVectorsFormat). Allows eslib-native
     *                         to inject its format without eslib-core depending on it at compile time.
     * @param searchExecutor   shared thread pool for parallel cluster search (null = serial only)
     */
    public PaimonLucene9Codec(Map<String, FieldIndexConfig> fieldConfigs,
                                Map<String, KnnVectorsFormat> overrideFormats,
                                ExecutorService searchExecutor) {
        super("PaimonLucene9", new Lucene912Codec());
        this.knnFormat = new PaimonPerFieldKnnVectorsFormat912(fieldConfigs, overrideFormats, searchExecutor);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }

    private static class PaimonPerFieldKnnVectorsFormat912 extends PerFieldKnnVectorsFormat {

        private static final org.slf4j.Logger LOG_PFKVF =
                org.slf4j.LoggerFactory.getLogger(PaimonPerFieldKnnVectorsFormat912.class);

        private final Map<String, FieldIndexConfig> fieldConfigs;
        private final Map<String, KnnVectorsFormat> overrideFormats;
        private final ExecutorService searchExecutor;

        PaimonPerFieldKnnVectorsFormat912(Map<String, FieldIndexConfig> fieldConfigs,
                                         Map<String, KnnVectorsFormat> overrideFormats,
                                         ExecutorService searchExecutor) {
            this.fieldConfigs = fieldConfigs;
            this.overrideFormats = overrideFormats;
            this.searchExecutor = searchExecutor;
            if (LOG_PFKVF.isDebugEnabled()) {
                LOG_PFKVF.debug(
                    "[codec] PaimonPerFieldKnnVectorsFormat912 ctor; fieldConfigs.keys={}, overrideFormats.keys={}",
                    fieldConfigs.keySet(), overrideFormats.keySet());
            }
        }

        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String fieldName) {
            FieldIndexConfig config = fieldConfigs.get(fieldName);
            LOG_PFKVF.debug("[codec] getKnnVectorsFormatForField('{}') — config={}, algorithm={}",
                    fieldName,
                    config,
                    (config == null ? "null-config" : String.valueOf(config.getAlgorithm())));
            if (config == null || config.getAlgorithm() == null) {
                return new PaimonHnswVectorsFormat();
            }
            VectorAlgorithm algorithm = config.getAlgorithm();
            switch (algorithm) {
                case DISKBBQ:
                    // ES940 DiskBBQ (second-level parent clustering + asymmetric int7 centroid
                    // quantization). Default cluster size 384 matches native bbq_disk for parity;
                    // segment codec name is PaimonES940DiskBBQVectorsFormat -> paimon-store ES940
                    // reader (OSS bulk-prefetch + mount visit-cap) is dispatched on read.
                    int vpc = config.getIntParam("vectors_per_cluster",
                            ES940DiskBBQVectorsFormat.DEFAULT_VECTORS_PER_CLUSTER);
                    int cpc = config.getIntParam("centroids_per_parent_cluster",
                            ES940DiskBBQVectorsFormat.DEFAULT_CENTROIDS_PER_PARENT_CLUSTER);
                    return new ES940DiskBBQVectorsFormat(vpc, cpc);
                case HNSW:
                    int m = config.getIntParam("m", 16);
                    int efConstruction = config.getIntParam("ef_construction", 100);
                    return new PaimonHnswVectorsFormat(m, efConstruction);
                case NATIVE:
                    KnnVectorsFormat nativeFormat = overrideFormats.get("NATIVE");
                    if (nativeFormat != null) {
                        return nativeFormat;
                    }
                    throw new UnsupportedOperationException(
                        "Native vector format not available. Ensure eslib-native is on the classpath "
                            + "and pass the format via overrideFormats."
                    );
                default:
                    return new PaimonHnswVectorsFormat();
            }
        }
    }
}
