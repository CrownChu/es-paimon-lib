/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.elasticsearch.eslib.diskbbq.SearchResult;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Vector index searcher interface for Paimon integration.
 * Usage: loadFromDir -> search(loop) -> close
 */
public interface VectorIndexSearcher extends Closeable {

    /**
     * Load index from a directory containing raw Lucene segment files.
     */
    void loadFromDir(Path indexDir) throws IOException;

    /**
     * KNN search with dynamic visitRatio computation.
     * @param queryVector query vector
     * @param topK number of nearest neighbors to return
     * @return search result with ids and scores
     */
    SearchResult search(float[] queryVector, int topK) throws IOException;

    /**
     * KNN search with a user-specified visitRatio.
     * @param queryVector query vector
     * @param topK number of nearest neighbors to return
     * @param visitRatio ratio of vectors to visit (0.0 means dynamic, &gt; 0.0 uses the specified ratio)
     * @return search result with ids and scores
     */
    default SearchResult search(float[] queryVector, int topK, float visitRatio) throws IOException {
        return search(queryVector, topK);
    }

    /**
     * KNN search with pre-filtering and dynamic visitRatio computation.
     * @param queryVector query vector
     * @param topK number of nearest neighbors to return
     * @param candidateIds candidate row IDs to search within
     * @return search result
     */
    SearchResult searchWithFilter(float[] queryVector, int topK, long[] candidateIds) throws IOException;

    /**
     * KNN search with pre-filtering and a user-specified visitRatio.
     * @param queryVector query vector
     * @param topK number of nearest neighbors to return
     * @param candidateIds candidate row IDs to search within
     * @param visitRatio ratio of vectors to visit (0.0 means dynamic, &gt; 0.0 uses the specified ratio)
     * @return search result
     */
    default SearchResult searchWithFilter(float[] queryVector, int topK, long[] candidateIds, float visitRatio) throws IOException {
        return searchWithFilter(queryVector, topK, candidateIds);
    }

    /**
     * @return total number of vectors in the index
     */
    long size();
}
