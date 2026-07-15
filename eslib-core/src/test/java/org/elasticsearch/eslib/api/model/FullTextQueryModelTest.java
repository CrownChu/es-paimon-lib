/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FullTextQueryModelTest {

    @Test
    void rejectsInvalidScoringParameters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextParams(null, Float.NaN, 0, 50, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextParams(null, -1.0f, 0, 50, 0));

        FullTextQuerySpec match =
                new FullTextQuerySpec.Match("text", "query", FullTextParams.defaults());
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextQuerySpec.Boost(match, match, -0.01f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextQuerySpec.Boost(match, match, Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextQuerySpec.Boost(match, match, 1.01f));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullTextQuerySpec.Phrase("text", "query", -1));
    }

    @Test
    void booleanClausesAreDefensivelyCopiedAndNullListsBecomeEmpty() {
        FullTextQuerySpec match =
                new FullTextQuerySpec.Match("text", "query", FullTextParams.defaults());
        List<FullTextQuerySpec> must = new ArrayList<>();
        must.add(match);
        FullTextQuerySpec.Bool bool = new FullTextQuerySpec.Bool(must, null, null);

        must.clear();
        assertEquals(1, bool.must().size());
        assertEquals(Collections.emptyList(), bool.should());
        assertEquals(Collections.emptyList(), bool.mustNot());
        assertThrows(UnsupportedOperationException.class, () -> bool.must().add(match));
    }
}
