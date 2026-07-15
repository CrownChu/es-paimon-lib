package org.elasticsearch.eslib.adapter.lucene10;

import java.util.Collections;
import java.util.Map;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104Codec;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.elasticsearch.eslib.adapter.PaimonHnswVectorsFormat;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat;

public class PaimonLucene10Codec extends FilterCodec {

    private final PerFieldKnnVectorsFormat knnFormat;

    /** No-arg SPI constructor — required by {@code Codec.forName(...)}. */
    public PaimonLucene10Codec() {
        this(Collections.emptyMap());
    }

    public PaimonLucene10Codec(Map<String, FieldIndexConfig> fieldConfigs) {
        // Codec.getDefault() cannot be called while Lucene's Codec SPI is constructing this
        // provider. This profile is compiled specifically against Lucene 10.4.
        super("PaimonLucene10", new Lucene104Codec());
        this.knnFormat = new PaimonPerFieldKnnVectorsFormat102(fieldConfigs);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }

    private static class PaimonPerFieldKnnVectorsFormat102 extends PerFieldKnnVectorsFormat {

        private final Map<String, FieldIndexConfig> fieldConfigs;

        PaimonPerFieldKnnVectorsFormat102(Map<String, FieldIndexConfig> fieldConfigs) {
            this.fieldConfigs = fieldConfigs;
        }

        @Override
        public KnnVectorsFormat getKnnVectorsFormatForField(String fieldName) {
            FieldIndexConfig config = fieldConfigs.get(fieldName);
            if (config == null || config.getAlgorithm() == null) {
                return new PaimonHnswVectorsFormat();
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
                    return new PaimonHnswVectorsFormat(m, efConstruction);
                case NATIVE:
                    throw new UnsupportedOperationException(
                            "Native vector format requires eslib-native module");
                default:
                    return new PaimonHnswVectorsFormat();
            }
        }
    }
}
