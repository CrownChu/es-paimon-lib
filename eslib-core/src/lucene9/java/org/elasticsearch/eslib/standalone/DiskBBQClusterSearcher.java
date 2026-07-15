/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.eslib.diskbbq.SearchResult;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;
import org.elasticsearch.eslib.diskbbq.DocIdsWriter;
import org.elasticsearch.eslib.diskbbq.OptimizedScalarQuantizer;
import org.elasticsearch.eslib.compat.ES91OSQVectorsScorer;
import org.elasticsearch.eslib.compat.ESVectorUtil;

import java.io.IOException;

import static org.apache.lucene.index.VectorSimilarityFunction.COSINE;
import static org.elasticsearch.eslib.diskbbq.BQVectorUtils.discretize;
import static org.elasticsearch.eslib.diskbbq.OptimizedScalarQuantizer.DEFAULT_LAMBDA;
import static org.elasticsearch.eslib.compat.ES91OSQVectorsScorer.BULK_SIZE;
import static org.elasticsearch.eslib.compat.ESVectorUtil.transposeHalfByte;

/**
 * Scores vectors within a single cluster's posting list data.
 * Reuses scoring algorithms from ES920DiskBBQVectorsReader.MemorySegmentPostingsVisitor.
 */
public class DiskBBQClusterSearcher implements VectorIndexClusterSearcher {

    /**
     * JDK 11 port: Lucene102BinaryQuantizedVectorsFormat.QUERY_BITS is Lucene 10+.
     * Inline as the 4-bit query encoding constant used by OSQ quantizer.
     */
    private static final int QUERY_BITS = 4;

    private VectorIndexConfig config;
    private int dimension;
    private VectorSimilarityFunction similarity;
    private int discretizedDimensions;
    private long quantizedByteLength;
    private long quantizedVectorByteSize;
    private boolean initialized;

    @Override
    public void init(VectorIndexConfig config) {
        this.config = config;
        this.dimension = config.dimension();
        this.similarity = config.similarity();
        this.discretizedDimensions = discretize(dimension, 64);
        this.quantizedVectorByteSize = discretizedDimensions / 8;
        this.quantizedByteLength = quantizedVectorByteSize + (Float.BYTES * 3) + Short.BYTES;
        this.initialized = true;
    }

    @Override
    public SearchResult searchCluster(IndexInput input, float[] queryVector, int topK, long[] candidateIds)
        throws IOException {
        if (!initialized) {
            throw new IllegalStateException("Must call init() before searchCluster()");
        }

        float[] centroid = new float[dimension];
        input.readFloats(centroid, 0, dimension);
        float centroidDp = Float.intBitsToFloat(input.readInt());

        int numVectors = input.readVInt();
        byte docEncoding = input.readByte();

        if (numVectors == 0 || topK <= 0) {
            return new SearchResult(new long[0], new float[0], 0);
        }

        FixedBitSet filter = null;
        if (candidateIds != null && candidateIds.length > 0) {
            int maxDoc = 0;
            for (long id : candidateIds) {
                if (id > maxDoc) maxDoc = (int) id;
            }
            filter = new FixedBitSet(maxDoc + 1);
            for (long id : candidateIds) {
                if (id >= 0) filter.set((int) id);
            }
        }

        OptimizedScalarQuantizer quantizer = new OptimizedScalarQuantizer(similarity, DEFAULT_LAMBDA, 1);
        float[] scratch = new float[dimension];
        int[] quantizationScratch = new int[dimension];
        byte[] quantizedQuery = new byte[QUERY_BITS * discretizedDimensions / 8];

        assert similarity != COSINE || VectorUtil.isUnitVector(queryVector);
        OptimizedScalarQuantizer.QuantizationResult queryCorrections =
            quantizer.scalarQuantize(queryVector, scratch, quantizationScratch, (byte) 4, centroid);
        transposeHalfByte(quantizationScratch, quantizedQuery);

        ES91OSQVectorsScorer osqScorer = ESVectorUtil.getES91OSQVectorsScorer(input, dimension);

        TopKnnCollector collector = new TopKnnCollector(topK, Integer.MAX_VALUE);
        DocIdsWriter idsWriter = new DocIdsWriter();
        int[] docIdsScratch = new int[BULK_SIZE];
        float[] scores = new float[BULK_SIZE];
        float[] correctionsLower = new float[BULK_SIZE];
        float[] correctionsUpper = new float[BULK_SIZE];
        int[] correctionsSum = new int[BULK_SIZE];
        float[] correctionsAdd = new float[BULK_SIZE];
        float[] correctiveValues = new float[3];
        int docBase = 0;
        int scoredDocs = 0;

        int limit = numVectors - BULK_SIZE + 1;
        int i = 0;
        for (; i < limit; i += BULK_SIZE) {
            idsWriter.readInts(input, BULK_SIZE, docEncoding, docIdsScratch);
            for (int j = 0; j < BULK_SIZE; j++) {
                docBase += docIdsScratch[j];
                docIdsScratch[j] = docBase;
            }

            int docsToScore = BULK_SIZE;
            if (filter != null) {
                docsToScore = 0;
                for (int j = 0; j < BULK_SIZE; j++) {
                    if (docIdsScratch[j] < filter.length() && filter.get(docIdsScratch[j])) {
                        docsToScore++;
                    } else {
                        docIdsScratch[j] = -1;
                    }
                }
            }

            if (docsToScore == 0) {
                input.skipBytes(quantizedByteLength * BULK_SIZE);
                continue;
            }

            float maxScore;
            if (docsToScore < BULK_SIZE / 2) {
                maxScore = scoreIndividually(
                    input, osqScorer, docIdsScratch, quantizedQuery,
                    queryCorrections, centroidDp, scores,
                    correctionsLower, correctionsUpper, correctionsSum, correctionsAdd
                );
            } else {
                maxScore = osqScorer.scoreBulk(
                    quantizedQuery,
                    queryCorrections.lowerInterval(),
                    queryCorrections.upperInterval(),
                    queryCorrections.quantizedComponentSum(),
                    queryCorrections.additionalCorrection(),
                    similarity,
                    centroidDp,
                    scores
                );
            }

            if (collector.minCompetitiveSimilarity() < maxScore) {
                for (int j = 0; j < BULK_SIZE; j++) {
                    if (docIdsScratch[j] != -1) {
                        collector.collect(docIdsScratch[j], scores[j]);
                    }
                }
            }
            scoredDocs += docsToScore;
        }

        // process tail
        if (i < numVectors) {
            int tailSize = numVectors - i;
            idsWriter.readInts(input, tailSize, docEncoding, docIdsScratch);
            for (int j = 0; j < tailSize; j++) {
                docBase += docIdsScratch[j];
                docIdsScratch[j] = docBase;
            }

            for (int j = 0; j < tailSize; j++) {
                int doc = docIdsScratch[j];
                if (filter != null && (doc >= filter.length() || !filter.get(doc))) {
                    input.skipBytes(quantizedByteLength);
                    continue;
                }

                float qcDist = osqScorer.quantizeScore(quantizedQuery);
                input.readFloats(correctiveValues, 0, 3);
                int quantizedComponentSum = Short.toUnsignedInt(input.readShort());
                float score = osqScorer.score(
                    queryCorrections.lowerInterval(),
                    queryCorrections.upperInterval(),
                    queryCorrections.quantizedComponentSum(),
                    queryCorrections.additionalCorrection(),
                    similarity,
                    centroidDp,
                    correctiveValues[0],
                    correctiveValues[1],
                    quantizedComponentSum,
                    correctiveValues[2],
                    qcDist
                );
                scoredDocs++;
                collector.collect(doc, score);
            }
        }

        collector.incVisitedCount(scoredDocs);
        return toSearchResult(collector.topDocs());
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

    private float scoreIndividually(
        IndexInput input,
        ES91OSQVectorsScorer osqScorer,
        int[] docIds,
        byte[] quantizedQuery,
        OptimizedScalarQuantizer.QuantizationResult queryCorrections,
        float centroidDp,
        float[] scores,
        float[] correctionsLower,
        float[] correctionsUpper,
        int[] correctionsSum,
        float[] correctionsAdd
    ) throws IOException {
        float maxScore = Float.NEGATIVE_INFINITY;
        for (int j = 0; j < BULK_SIZE; j++) {
            if (docIds[j] != -1) {
                scores[j] = osqScorer.quantizeScore(quantizedQuery);
            } else {
                input.skipBytes(quantizedVectorByteSize);
            }
        }
        input.readFloats(correctionsLower, 0, BULK_SIZE);
        input.readFloats(correctionsUpper, 0, BULK_SIZE);
        for (int j = 0; j < BULK_SIZE; j++) {
            correctionsSum[j] = Short.toUnsignedInt(input.readShort());
        }
        input.readFloats(correctionsAdd, 0, BULK_SIZE);
        for (int j = 0; j < BULK_SIZE; j++) {
            if (docIds[j] != -1) {
                scores[j] = osqScorer.score(
                    queryCorrections.lowerInterval(),
                    queryCorrections.upperInterval(),
                    queryCorrections.quantizedComponentSum(),
                    queryCorrections.additionalCorrection(),
                    similarity,
                    centroidDp,
                    correctionsLower[j],
                    correctionsUpper[j],
                    correctionsSum[j],
                    correctionsAdd[j],
                    scores[j]
                );
                if (scores[j] > maxScore) {
                    maxScore = scores[j];
                }
            }
        }
        return maxScore;
    }

    @Override
    public void close() {
        initialized = false;
    }
}
