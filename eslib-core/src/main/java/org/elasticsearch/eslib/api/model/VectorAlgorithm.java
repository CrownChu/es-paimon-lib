/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.api.model;

/**
 * Supported vector index algorithms.
 */
public enum VectorAlgorithm {

    /** IVF + BBQ quantization. Best for very large scale (millions+), low memory. */
    DISKBBQ("diskbbq"),

    /** Standard Lucene HNSW. General purpose, moderate scale. */
    HNSW("hnsw"),

    /**
     * Havenask proxima native engine (JNI to C++ library).
     * Supports multiple sub-algorithms via builder_name parameter:
     * HnswBuilder, QcBuilder, LinearBuilder, etc.
     * Requires native library (libha_indexlib.so).
     */
    NATIVE("native");

    private final String name;

    VectorAlgorithm(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static VectorAlgorithm fromName(String name) {
        for (VectorAlgorithm algo : values()) {
            if (algo.name.equalsIgnoreCase(name)) {
                return algo;
            }
        }
        throw new IllegalArgumentException("Unknown vector algorithm: " + name
                + ". Supported: diskbbq, hnsw, native");
    }
}
