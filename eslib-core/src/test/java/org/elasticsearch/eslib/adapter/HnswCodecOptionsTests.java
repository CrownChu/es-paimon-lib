/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.adapter;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat;
import org.junit.jupiter.api.Test;

class HnswCodecOptionsTests {

    @Test
    void configuredParametersReachLuceneHnswFormat() throws Exception {
        FieldIndexConfig config =
                FieldIndexConfig.builder("emb", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.HNSW)
                        .dimension(768)
                        .metric("cosine")
                        .algorithmParams(Map.of("m", "30", "ef_construction", "360"))
                        .build();

        Codec codec = createProfileCodec(Map.of("emb", config));
        PerFieldKnnVectorsFormat perField = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
        KnnVectorsFormat format = perField.getKnnVectorsFormatForField("emb");

        assertInstanceOf(PaimonHnswVectorsFormat.class, format);
        assertTrue(format.toString().contains("maxConn=30"), format.toString());
        assertTrue(format.toString().contains("beamWidth=360"), format.toString());
    }

    @Test
    void diskBBQUsesTheSameDefaultsInEveryLuceneProfile() throws Exception {
        FieldIndexConfig config =
                FieldIndexConfig.builder("emb", FieldIndexConfig.IndexType.VECTOR)
                        .algorithm(VectorAlgorithm.DISKBBQ)
                        .dimension(768)
                        .metric("cosine")
                        .build();

        Codec codec = createProfileCodec(Map.of("emb", config));
        PerFieldKnnVectorsFormat perField = (PerFieldKnnVectorsFormat) codec.knnVectorsFormat();
        KnnVectorsFormat format = perField.getKnnVectorsFormatForField("emb");

        assertTrue(
                format.toString()
                        .contains(
                                "vectorPerCluster="
                                        + PaimonDiskBBQVectorsFormat.DEFAULT_VECTORS_PER_CLUSTER),
                format.toString());
    }

    private static Codec createProfileCodec(Map<String, FieldIndexConfig> configs)
            throws Exception {
        String className;
        try {
            Class.forName("org.elasticsearch.eslib.adapter.lucene9.PaimonLucene9Codec");
            className = "org.elasticsearch.eslib.adapter.lucene9.PaimonLucene9Codec";
        } catch (ClassNotFoundException e) {
            className = "org.elasticsearch.eslib.adapter.lucene10.PaimonLucene10Codec";
        }
        return (Codec) Class.forName(className).getConstructor(Map.class).newInstance(configs);
    }
}
