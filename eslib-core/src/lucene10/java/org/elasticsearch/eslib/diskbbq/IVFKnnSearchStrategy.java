/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.search.knn.KnnSearchStrategy;

import java.util.Objects;

/**
 * IVF search strategy that carries a user-specified visitRatio.
 * When visitRatio is {@link PaimonDiskBBQVectorsFormat#DYNAMIC_VISIT_RATIO},
 * the reader will dynamically compute the ratio based on numVectors and k.
 */
public class IVFKnnSearchStrategy extends KnnSearchStrategy {

    private final float visitRatio;

    public IVFKnnSearchStrategy(float visitRatio) {
        this.visitRatio = visitRatio;
    }

    public float getVisitRatio() {
        return visitRatio;
    }

    @Override
    public void nextVectorsBlock() {
        // no-op for standalone paimon-es usage
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IVFKnnSearchStrategy that = (IVFKnnSearchStrategy) o;
        return Float.compare(visitRatio, that.visitRatio) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(visitRatio);
    }
}
