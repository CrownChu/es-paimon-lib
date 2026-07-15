/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.compat;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.VectorUtil;

import java.io.IOException;

import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;
import static org.apache.lucene.index.VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;

/**
 * Scorer for 7-bit quantized centroids. Reads byte-encoded vectors from IndexInput.
 * Used for centroid-level scoring in hierarchical IVF.
 *
 * <p>On-disk layout matches the native ES920 writer: in a bulk group the quantized
 * vectors are written first, then all lower intervals (float), then all upper
 * intervals (float), then all target component sums (int), then all additional
 * corrections (float) — i.e. columnar. The remainder (&lt; BULK_SIZE) is written
 * per-centroid as vector, lower, upper, additionalCorrection (floats), sum (int).
 */
public class ES92Int7VectorsScorer {

    public static final int BULK_SIZE = 16;

    private static final float SEVEN_BIT_SCALE = 1f / ((1 << 7) - 1);

    private final IndexInput in;
    private final int dimensions;

    private final float[] lowerIntervals;
    private final float[] upperIntervals;
    private final int[] targetComponentSums;
    private final float[] additionalCorrections;

    public ES92Int7VectorsScorer(IndexInput in, int dimensions) {
        this(in, dimensions, BULK_SIZE);
    }

    public ES92Int7VectorsScorer(IndexInput in, int dimensions, int bulkSize) {
        this.in = in;
        this.dimensions = dimensions;
        this.lowerIntervals = new float[bulkSize];
        this.upperIntervals = new float[bulkSize];
        this.targetComponentSums = new int[bulkSize];
        this.additionalCorrections = new float[bulkSize];
    }

    /** Quantized dot product between the query and the next vector read from the input. */
    public long int7DotProduct(byte[] q) throws IOException {
        int total = 0;
        for (int i = 0; i < dimensions; i++) {
            total += in.readByte() * q[i];
        }
        return total;
    }

    public void int7DotProductBulk(byte[] q, int count, float[] scores) throws IOException {
        for (int i = 0; i < count; i++) {
            scores[i] = int7DotProduct(q);
        }
    }

    public float score(
        byte[] query, float queryLower, float queryUpper, float queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp
    ) throws IOException {
        float qcDist = int7DotProduct(query);
        in.readFloats(lowerIntervals, 0, 3);
        int addition = in.readInt();
        return applyCorrections(queryLower, queryUpper, queryComponentSum, queryAdditionalCorrection, similarityFunction,
            centroidDp, lowerIntervals[0], lowerIntervals[1], addition, lowerIntervals[2], qcDist);
    }

    public void scoreBulk(
        byte[] query, float queryLower, float queryUpper, float queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp, float[] scores
    ) throws IOException {
        int7DotProductBulk(query, BULK_SIZE, scores);
        in.readFloats(lowerIntervals, 0, BULK_SIZE);
        in.readFloats(upperIntervals, 0, BULK_SIZE);
        in.readInts(targetComponentSums, 0, BULK_SIZE);
        in.readFloats(additionalCorrections, 0, BULK_SIZE);
        for (int i = 0; i < BULK_SIZE; i++) {
            scores[i] = applyCorrections(queryLower, queryUpper, queryComponentSum, queryAdditionalCorrection,
                similarityFunction, centroidDp, lowerIntervals[i], upperIntervals[i], targetComponentSums[i],
                additionalCorrections[i], scores[i]);
        }
    }

    /**
     * Bulk-score overload used by the ES940 reader. The {@code bulkSize} parameter sizes the read,
     * and {@code queryComponentSum} is passed as an int. On-disk layout (cenivf): quantized vectors,
     * then lowerIntervals (floats), upperIntervals (floats), targetComponentSums (ints),
     * additionalCorrections (floats). Verbatim port of native ES92Int7VectorsScorer.scoreBulk.
     */
    public void scoreBulk(
        byte[] q, float queryLowerInterval, float queryUpperInterval, int queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp, float[] scores, int bulkSize
    ) throws IOException {
        int7DotProductBulk(q, bulkSize, scores);
        in.readFloats(lowerIntervals, 0, bulkSize);
        in.readFloats(upperIntervals, 0, bulkSize);
        in.readInts(targetComponentSums, 0, bulkSize);
        in.readFloats(additionalCorrections, 0, bulkSize);
        for (int i = 0; i < bulkSize; i++) {
            scores[i] = applyCorrections(queryLowerInterval, queryUpperInterval, queryComponentSum, queryAdditionalCorrection,
                similarityFunction, centroidDp, lowerIntervals[i], upperIntervals[i], targetComponentSums[i],
                additionalCorrections[i], scores[i]);
        }
    }

    private float applyCorrections(
        float queryLower, float queryUpper, float queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp,
        float lowerInterval, float upperInterval, int targetComponentSum, float additionalCorrection, float qcDist
    ) {
        float ax = lowerInterval;
        float lx = (upperInterval - ax) * SEVEN_BIT_SCALE;
        float ay = queryLower;
        float ly = (queryUpper - ay) * SEVEN_BIT_SCALE;
        float y1 = queryComponentSum;
        float score = ax * ay * dimensions + ay * lx * targetComponentSum + ax * ly * y1 + lx * ly * qcDist;

        if (similarityFunction == EUCLIDEAN) {
            score = queryAdditionalCorrection + additionalCorrection - 2 * score;
            return Math.max(1 / (1f + score), 0);
        } else {
            score += queryAdditionalCorrection + additionalCorrection - centroidDp;
            if (similarityFunction == MAXIMUM_INNER_PRODUCT) {
                return VectorUtil.scaleMaxInnerProductScore(score);
            }
            return Math.max((1f + score) / 2f, 0);
        }
    }
}
