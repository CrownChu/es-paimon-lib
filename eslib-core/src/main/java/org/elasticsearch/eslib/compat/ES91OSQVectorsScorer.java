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
import org.apache.lucene.util.BitUtil;
import org.apache.lucene.util.VectorUtil;

import java.io.IOException;

import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;
import static org.apache.lucene.index.VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;

/**
 * Local copy of ES91OSQVectorsScorer for paimon-es. Scores quantized vectors from IndexInput.
 */
public class ES91OSQVectorsScorer {

    public static final int BULK_SIZE = 16;

    protected static final float FOUR_BIT_SCALE = 1f / ((1 << 4) - 1);

    protected final IndexInput in;
    protected final int length;
    protected final int dimensions;

    protected final float[] lowerIntervals = new float[BULK_SIZE];
    protected final float[] upperIntervals = new float[BULK_SIZE];
    protected final int[] targetComponentSums = new int[BULK_SIZE];
    protected final float[] additionalCorrections = new float[BULK_SIZE];

    public ES91OSQVectorsScorer(IndexInput in, int dimensions) {
        this.in = in;
        this.dimensions = dimensions;
        this.length = discretize(dimensions, 64) / 8;
    }

    static int discretize(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    public long quantizeScore(byte[] q) throws IOException {
        assert q.length == length * 4;
        final int size = length;
        long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
        int r = 0;
        for (final int upperBound = size & -Long.BYTES; r < upperBound; r += Long.BYTES) {
            final long value = in.readLong();
            subRet0 += Long.bitCount((long) BitUtil.VH_LE_LONG.get(q, r) & value);
            subRet1 += Long.bitCount((long) BitUtil.VH_LE_LONG.get(q, r + size) & value);
            subRet2 += Long.bitCount((long) BitUtil.VH_LE_LONG.get(q, r + 2 * size) & value);
            subRet3 += Long.bitCount((long) BitUtil.VH_LE_LONG.get(q, r + 3 * size) & value);
        }
        for (final int upperBound = size & -Integer.BYTES; r < upperBound; r += Integer.BYTES) {
            final int value = in.readInt();
            subRet0 += Integer.bitCount((int) BitUtil.VH_LE_INT.get(q, r) & value);
            subRet1 += Integer.bitCount((int) BitUtil.VH_LE_INT.get(q, r + size) & value);
            subRet2 += Integer.bitCount((int) BitUtil.VH_LE_INT.get(q, r + 2 * size) & value);
            subRet3 += Integer.bitCount((int) BitUtil.VH_LE_INT.get(q, r + 3 * size) & value);
        }
        for (; r < size; r++) {
            final byte value = in.readByte();
            subRet0 += Integer.bitCount((q[r] & value) & 0xFF);
            subRet1 += Integer.bitCount((q[r + size] & value) & 0xFF);
            subRet2 += Integer.bitCount((q[r + 2 * size] & value) & 0xFF);
            subRet3 += Integer.bitCount((q[r + 3 * size] & value) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    public void quantizeScoreBulk(byte[] q, int count, float[] scores) throws IOException {
        for (int i = 0; i < count; i++) {
            scores[i] = quantizeScore(q);
        }
    }

    public float score(
        float queryLower, float queryUpper, float queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp,
        float lowerInterval, float upperInterval, int targetComponentSum, float additionalCorrection,
        float qcDist
    ) {
        float ax = lowerInterval;
        float lx = upperInterval - ax;
        float ay = queryLower;
        float ly = (queryUpper - ay) * FOUR_BIT_SCALE;
        float y1 = queryComponentSum;
        float score = ax * ay * dimensions + ay * lx * (float) targetComponentSum + ax * ly * y1 + lx * ly * qcDist;
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

    public float scoreBulk(
        byte[] q, float queryLower, float queryUpper, float queryComponentSum, float queryAdditionalCorrection,
        VectorSimilarityFunction similarityFunction, float centroidDp, float[] scores
    ) throws IOException {
        quantizeScoreBulk(q, BULK_SIZE, scores);
        in.readFloats(lowerIntervals, 0, BULK_SIZE);
        in.readFloats(upperIntervals, 0, BULK_SIZE);
        for (int i = 0; i < BULK_SIZE; i++) {
            targetComponentSums[i] = Short.toUnsignedInt(in.readShort());
        }
        in.readFloats(additionalCorrections, 0, BULK_SIZE);
        float maxScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < BULK_SIZE; i++) {
            scores[i] = score(queryLower, queryUpper, queryComponentSum, queryAdditionalCorrection,
                similarityFunction, centroidDp,
                lowerIntervals[i], upperIntervals[i], targetComponentSums[i], additionalCorrections[i],
                scores[i]);
            if (scores[i] > maxScore) maxScore = scores[i];
        }
        return maxScore;
    }
}
