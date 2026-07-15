/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.api.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldIndexConfigTest {

    @Test
    void algorithmParametersAreDefensivelyCopied() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("m", "16");
        FieldIndexConfig config =
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .dimension(4)
                        .algorithmParams(parameters)
                        .build();

        parameters.put("m", "32");
        assertEquals("16", config.algorithmParams().get("m"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.algorithmParams().put("m", "64"));
    }

    @Test
    void rejectsInvalidVectorShapeAndMalformedIntegerParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> FieldIndexConfig.builder("", FieldIndexConfig.IndexType.SCALAR).build());

        FieldIndexConfig config =
                FieldIndexConfig.builder("vector", FieldIndexConfig.IndexType.VECTOR)
                        .dimension(4)
                        .algorithmParams(Map.of("m", "not-an-integer"))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> config.getIntParam("m", 16));
    }

    @Test
    void queryModelsRejectInvalidOrMutableResultShapes() {
        assertThrows(NullPointerException.class, () -> ScalarPredicate.eq(null));
        assertThrows(NullPointerException.class, () -> ScalarPredicate.in(null));
        assertThrows(
                NullPointerException.class,
                () -> ScalarPredicate.in(Arrays.asList(1, null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SearchResult(new long[1], new float[1], 2));
        assertThrows(
                NullPointerException.class,
                () -> new SearchResult(null, new float[0], 0));
    }
}
