/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

import org.apache.lucene.index.VectorSimilarityFunction;

/** Vector similarity type. */
public enum VectorSimilarityType {

    COSINE,

    DOT_PRODUCT,

    EUCLIDEAN,

    MAXIMUM_INNER_PRODUCT;

    public static VectorSimilarityType of(VectorSimilarityFunction func) {
        switch (func) {
            case EUCLIDEAN: return VectorSimilarityType.EUCLIDEAN;
            case COSINE: return VectorSimilarityType.COSINE;
            case DOT_PRODUCT: return VectorSimilarityType.DOT_PRODUCT;
            case MAXIMUM_INNER_PRODUCT: return VectorSimilarityType.MAXIMUM_INNER_PRODUCT;
            default: throw new IllegalArgumentException("Unknown: " + func);
        }
    }

    public static VectorSimilarityFunction of(VectorSimilarityType func) {
        switch (func) {
            case EUCLIDEAN: return VectorSimilarityFunction.EUCLIDEAN;
            case COSINE: return VectorSimilarityFunction.COSINE;
            case DOT_PRODUCT: return VectorSimilarityFunction.DOT_PRODUCT;
            case MAXIMUM_INNER_PRODUCT: return VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
            default: throw new IllegalArgumentException("Unknown: " + func);
        }
    }
}
