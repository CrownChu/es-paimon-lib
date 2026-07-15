/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexFilterModelTest {

    @Test
    void scalarInValuesAreDefensivelyCopied() {
        List<Object> values = new ArrayList<>(Arrays.asList(1, 2));
        ScalarPredicate predicate = ScalarPredicate.in(values);

        values.clear();
        assertEquals(Arrays.asList(1, 2), predicate.inValues());
        assertThrows(UnsupportedOperationException.class, () -> predicate.inValues().add(3));
    }

    @Test
    void rejectsInvalidFilterArguments() {
        assertThrows(NullPointerException.class, () -> IndexFilter.scalar(null));
        assertThrows(NullPointerException.class, () -> IndexFilter.TextFilter.term(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexFilter.geoDistance(91.0d, 0.0d, 1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexFilter.geoDistance(0.0d, 0.0d, -1.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> IndexFilter.geoBoundingBox(10.0d, -10.0d, 0.0d, 1.0d));
    }
}
