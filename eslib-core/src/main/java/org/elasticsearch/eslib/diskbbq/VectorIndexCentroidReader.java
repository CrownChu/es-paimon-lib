/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.store.ByteBuffersDataInput;
import org.apache.lucene.store.ByteBuffersIndexInput;
import org.apache.lucene.store.IndexInput;

import org.elasticsearch.eslib.diskbbq.ClusterReference;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Reads centroid data from .mivf + .cenivf files and finds nearest clusters.
 * <p>
 * Usage: load(metaData, centroidData, config) -> findNearestClusters(query, topN) -> close
 */
public interface VectorIndexCentroidReader extends Closeable {

    /**
     * Load centroid index from IndexInput (supports HEAP, MMAP, OSS_DIRECT).
     * The caller retains ownership of the IndexInputs — they will NOT be closed by this reader.
     */
    void load(IndexInput metaInput, IndexInput centroidInput, VectorIndexConfig config) throws IOException;

    /**
     * Load centroid index from raw Lucene file bytes (HEAP mode convenience).
     */
    default void load(byte[] metaData, byte[] centroidData, VectorIndexConfig config) throws IOException {
        IndexInput metaInput = new ByteBuffersIndexInput(
            new ByteBuffersDataInput(List.of(ByteBuffer.wrap(metaData))), "metaData");
        IndexInput centroidInput = new ByteBuffersIndexInput(
            new ByteBuffersDataInput(List.of(ByteBuffer.wrap(centroidData))), "centroidData");
        load(metaInput, centroidInput, config);
    }

    /**
     * Find the nearest clusters for a query vector.
     * Returned offsets are absolute byte positions in the .clivf file.
     *
     * @param queryVector query vector (length must equal config.dimension)
     * @param topN        max number of clusters to return
     * @return clusters sorted by descending similarity score
     */
    List<ClusterReference> findNearestClusters(float[] queryVector, int topN) throws IOException;

    int numCentroids();
}
