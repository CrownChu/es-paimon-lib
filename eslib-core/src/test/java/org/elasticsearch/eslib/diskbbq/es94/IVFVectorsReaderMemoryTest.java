/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.diskbbq.es94;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IVFVectorsReaderMemoryTest {

    @Test
    void deduplicatingCollectorKeepsOnlyTopKState() throws Exception {
        final Class<?> collectorClass;
        try {
            collectorClass =
                    Class.forName(
                            "org.elasticsearch.eslib.diskbbq.es94."
                                    + "IVFVectorsReader$DeduplicatingKnnCollector");
        } catch (ClassNotFoundException ignored) {
            // The Lucene 10 profile uses a different package and implementation.
            return;
        }

        Constructor<?>[] constructors = collectorClass.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(
                1,
                constructors[0].getParameterCount(),
                "the collector must not take maxDoc/docIdLimit state");
        assertFalse(
                Arrays.stream(collectorClass.getDeclaredFields())
                        .map(Field::getName)
                        .anyMatch(name -> name.equals("seenDocs") || name.equals("bestScores")),
                "deduplication must remain O(k), not O(maxDoc)");
    }
}
