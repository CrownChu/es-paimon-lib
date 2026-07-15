/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.util.VectorUtil;

/**
 * Compatibility utilities for APIs available in ES 9.3/Lucene 10.3 but not in ES 8.17/Lucene 10.2.
 * Provides fallback implementations for missing simdvec methods.
 */
public final class VectorCompat {

    private VectorCompat() {}

    public static float normalizeDistanceToUnitInterval(float distance) {
        return 1.0f / (1.0f + distance);
    }

    public static void squareDistanceBulk(float[] center, float[] c0, float[] c1, float[] c2, float[] c3, float[] scores) {
        scores[0] = VectorUtil.squareDistance(center, c0);
        scores[1] = VectorUtil.squareDistance(center, c1);
        scores[2] = VectorUtil.squareDistance(center, c2);
        scores[3] = VectorUtil.squareDistance(center, c3);
    }

    public static float soarDistance(float[] vector, float[] centroid, float[] diffs, float soarLambda, float vectorCentroidDist) {
        float dotProduct = 0f;
        for (int i = 0; i < vector.length; i++) {
            dotProduct += (vector[i] - centroid[i]) * diffs[i];
        }
        float diffNorm = 0f;
        for (float d : diffs) {
            diffNorm += d * d;
        }
        diffNorm = (float) Math.sqrt(diffNorm);
        float projection = (diffNorm > 0) ? dotProduct / diffNorm : 0f;
        return vectorCentroidDist - soarLambda * projection * projection;
    }

    public static void soarDistanceBulk(
        float[] vector,
        float[] centroid,
        float[][] diffs,
        float soarLambda,
        float vectorCentroidDist,
        float[] results
    ) {
        for (int i = 0; i < diffs.length; i++) {
            results[i] = soarDistance(vector, centroid, diffs[i], soarLambda, vectorCentroidDist);
        }
    }

    public static void transposeHalfByte(int[] input, byte[] output) {
        int dims = input.length;
        int halfDims = dims / 2;
        for (int i = 0; i < halfDims; i++) {
            output[i] = (byte) ((input[2 * i] & 0x0F) | ((input[2 * i + 1] & 0x0F) << 4));
        }
        if (dims % 2 != 0) {
            output[halfDims] = (byte) (input[dims - 1] & 0x0F);
        }
    }

    public static void packAsBinary(int[] quantized, byte[] binary) {
        for (int i = 0; i < binary.length; i++) {
            int b = 0;
            for (int j = 0; j < 8 && (i * 8 + j) < quantized.length; j++) {
                if (quantized[i * 8 + j] > 0) {
                    b |= (1 << j);
                }
            }
            binary[i] = (byte) b;
        }
    }

    public static int quantizeVectorWithIntervals(float[] vector, int[] quantized, float lower, float upper, byte bits) {
        int numLevels = (1 << bits) - 1;
        float range = upper - lower;
        int sum = 0;
        if (range == 0f) {
            for (int i = 0; i < vector.length; i++) {
                quantized[i] = 0;
            }
            return 0;
        }
        for (int i = 0; i < vector.length; i++) {
            float normalized = (vector[i] - lower) / range;
            normalized = Math.max(0f, Math.min(1f, normalized));
            int q = Math.round(normalized * numLevels);
            quantized[i] = q;
            sum += q;
        }
        return sum;
    }
}
