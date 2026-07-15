/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.apache.lucene.store.ByteBuffersDataInput;
import org.apache.lucene.store.ByteBuffersIndexInput;
import org.apache.lucene.store.IndexInput;

import org.elasticsearch.eslib.diskbbq.SearchResult;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Scores vectors within a single cluster's posting list data.
 * <p>
 * Usage: init(config) -> searchCluster(data, query, topK, filter) -> mergeResults -> close
 */
public interface VectorIndexClusterSearcher extends Closeable {

    /**
     * Initialize with index configuration. Pre-allocates scratch buffers.
     */
    void init(VectorIndexConfig config) throws IOException;

    /**
     * Score vectors in one cluster's posting list via IndexInput (supports MMAP, OSS_DIRECT).
     */
    SearchResult searchCluster(IndexInput clusterInput, float[] queryVector, int topK, long[] candidateIds) throws IOException;

    /**
     * Score vectors in one cluster's posting list from raw bytes (HEAP mode convenience).
     */
    default SearchResult searchCluster(byte[] clusterData, float[] queryVector, int topK, long[] candidateIds) throws IOException {
        ByteBuffersDataInput bbdi = new ByteBuffersDataInput(List.of(ByteBuffer.wrap(clusterData)));
        try (IndexInput input = new ByteBuffersIndexInput(bbdi, "clusterData")) {
            return searchCluster(input, queryVector, topK, candidateIds);
        }
    }

    /**
     * Merge results from multiple clusters into a global top-K.
     */
    static SearchResult mergeResults(List<SearchResult> results, int topK) {
        int totalCount = 0;
        for (SearchResult r : results) {
            totalCount += r.count;
        }
        if (totalCount == 0) {
            return new SearchResult(new long[0], new float[0], 0);
        }

        long[] allIds = new long[totalCount];
        float[] allScores = new float[totalCount];
        int pos = 0;
        for (SearchResult r : results) {
            System.arraycopy(r.ids, 0, allIds, pos, r.count);
            System.arraycopy(r.scores, 0, allScores, pos, r.count);
            pos += r.count;
        }

        // sort by score descending
        for (int i = 0; i < totalCount - 1; i++) {
            for (int j = i + 1; j < totalCount; j++) {
                if (allScores[j] > allScores[i]) {
                    float tmpS = allScores[i]; allScores[i] = allScores[j]; allScores[j] = tmpS;
                    long tmpI = allIds[i]; allIds[i] = allIds[j]; allIds[j] = tmpI;
                }
            }
        }

        int k = Math.min(topK, totalCount);
        long[] ids = new long[k];
        float[] scores = new float[k];
        System.arraycopy(allIds, 0, ids, 0, k);
        System.arraycopy(allScores, 0, scores, 0, k);
        return new SearchResult(ids, scores, k);
    }
}
