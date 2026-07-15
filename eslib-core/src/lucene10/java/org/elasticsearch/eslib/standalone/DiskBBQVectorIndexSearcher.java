/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.knn.KnnSearchStrategy;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.FixedBitSet;
import org.elasticsearch.eslib.diskbbq.BitSetFilterQuery;
import org.elasticsearch.eslib.diskbbq.SearchResult;
import org.elasticsearch.eslib.diskbbq.IVFKnnSearchStrategy;

import java.io.IOException;
import java.nio.file.Path;

/**
 * DiskBBQ vector index searcher implementation.
 * Uses Lucene DirectoryReader + IndexSearcher to search the index.
 */
public class DiskBBQVectorIndexSearcher implements VectorIndexSearcher {

    private MMapDirectory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private boolean closed;

    @Override
    public void loadFromDir(Path indexDir) throws IOException {
        directory = new MMapDirectory(indexDir);
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    @Override
    public SearchResult search(float[] queryVector, int topK) throws IOException {
        return search(queryVector, topK, 0.0f);
    }

    @Override
    public SearchResult search(float[] queryVector, int topK, float visitRatio) throws IOException {
        if (reader == null) {
            throw new IllegalStateException("Must call load() before search()");
        }
        int effectiveK = Math.min(topK, reader.numDocs());
        if (effectiveK <= 0) {
            return new SearchResult(new long[0], new float[0], 0);
        }
        KnnSearchStrategy strategy = new IVFKnnSearchStrategy(visitRatio);
        KnnFloatVectorQuery query = new KnnFloatVectorQuery("vector", queryVector, effectiveK, null, strategy);
        TopDocs topDocs = searcher.search(query, effectiveK);
        return toSearchResult(topDocs);
    }

    @Override
    public SearchResult searchWithFilter(float[] queryVector, int topK, long[] candidateIds) throws IOException {
        return searchWithFilter(queryVector, topK, candidateIds, 0.0f);
    }

    @Override
    public SearchResult searchWithFilter(float[] queryVector, int topK, long[] candidateIds, float visitRatio) throws IOException {
        if (reader == null) {
            throw new IllegalStateException("Must call load() before search()");
        }
        int effectiveK = Math.min(topK, reader.numDocs());
        if (effectiveK <= 0 || candidateIds == null || candidateIds.length == 0) {
            return new SearchResult(new long[0], new float[0], 0);
        }

        int maxDoc = reader.maxDoc();
        FixedBitSet bitSet = new FixedBitSet(maxDoc);
        for (long id : candidateIds) {
            if (id >= 0 && id < maxDoc) {
                bitSet.set((int) id);
            }
        }

        Query filter = new BitSetFilterQuery(bitSet);
        KnnSearchStrategy strategy = new IVFKnnSearchStrategy(visitRatio);
        KnnFloatVectorQuery query = new KnnFloatVectorQuery("vector", queryVector, effectiveK, filter, strategy);
        TopDocs topDocs = searcher.search(query, effectiveK);
        return toSearchResult(topDocs);
    }

    @Override
    public long size() {
        return reader != null ? reader.numDocs() : 0;
    }

    private static SearchResult toSearchResult(TopDocs topDocs) {
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        long[] ids = new long[scoreDocs.length];
        float[] scores = new float[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            ids[i] = scoreDocs[i].doc;
            scores[i] = scoreDocs[i].score;
        }
        return new SearchResult(ids, scores, scoreDocs.length);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        Throwable first = null;
        if (reader != null) {
            try { reader.close(); } catch (Throwable t) { first = t; }
            reader = null;
        }
        if (directory != null) {
            try { directory.close(); } catch (Throwable t) {
                if (first == null) first = t; else first.addSuppressed(t);
            }
            directory = null;
        }
        if (first != null) {
            if (first instanceof IOException) throw (IOException) first;
            throw new RuntimeException(first);
        }
    }
}
