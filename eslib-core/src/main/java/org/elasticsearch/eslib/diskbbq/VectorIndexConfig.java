/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * Configuration for DiskBBQ vector index construction.
 */
public final class VectorIndexConfig {

    private final String fieldName;
    private final int dimension;
    private final VectorSimilarityFunction similarity;
    private final int vectorsPerCluster;
    private final int centroidsPerParentCluster;

    public VectorIndexConfig(
        String fieldName,
        int dimension,
        VectorSimilarityFunction similarity,
        int vectorsPerCluster,
        int centroidsPerParentCluster
    ) {
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("fieldName must not be null or empty");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be > 0, got: " + dimension);
        }
        if (similarity == VectorSimilarityFunction.COSINE) {
            throw new IllegalArgumentException("IVF does not support cosine similarity, use DOT_PRODUCT with normalized vectors");
        }
        this.fieldName = fieldName;
        this.dimension = dimension;
        this.similarity = similarity;
        this.vectorsPerCluster = vectorsPerCluster;
        this.centroidsPerParentCluster = centroidsPerParentCluster;
    }

    /** Backward-compatible: 2-arg constructor, defaults fieldName to "vector". */
    public VectorIndexConfig(int dimension, VectorSimilarityFunction similarity) {
        this("vector", dimension, similarity, 384, 16);
    }

    /** Backward-compatible: 4-arg constructor, defaults fieldName to "vector". */
    public VectorIndexConfig(int dimension, VectorSimilarityFunction similarity, int vectorsPerCluster, int centroidsPerParentCluster) {
        this("vector", dimension, similarity, vectorsPerCluster, centroidsPerParentCluster);
    }

    /** New: specify fieldName with default cluster params. */
    public VectorIndexConfig(String fieldName, int dimension, VectorSimilarityFunction similarity) {
        this(fieldName, dimension, similarity, 384, 16);
    }

    public String fieldName() { return fieldName; }
    public int dimension() { return dimension; }
    public VectorSimilarityFunction similarity() { return similarity; }
    public int vectorsPerCluster() { return vectorsPerCluster; }
    public int centroidsPerParentCluster() { return centroidsPerParentCluster; }
}
