/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.compat;

import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.VectorUtil;

import java.io.IOException;

/**
 * Compatibility layer for ESVectorUtil methods used by paimon-es that are not available in ES 8.17's simdvec.
 * Delegates to real ESVectorUtil where possible, provides fallback implementations otherwise.
 */
public final class ESVectorUtil {

    private ESVectorUtil() {}

    // Added for ES940 DiskBBQ writer port: delegate to Lucene VectorUtil (byte-identical math to native simdvec scalar path).
    public static float dotProduct(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector dimensions incompatible: " + a.length + "!= " + b.length);
        }
        return VectorUtil.dotProduct(a, b);
    }

    public static float squareDistance(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vector dimensions incompatible: " + a.length + "!= " + b.length);
        }
        return VectorUtil.squareDistance(a, b);
    }

    // Verbatim scalar port of native DefaultESVectorUtilSupport.packDibitImpl (used by ES940 TWO_BIT_4BIT_QUERY encoding).
    public static void packDibit(int[] vector, byte[] packed) {
        if (packed.length * Byte.SIZE / 2 < vector.length) {
            throw new IllegalArgumentException("packed array is too small: " + packed.length * Byte.SIZE / 2 + " < " + vector.length);
        }
        int limit = vector.length - 7;
        int i = 0;
        int index = 0;
        for (; i < limit; i += 8, index++) {
            assert vector[i] >= 0 && vector[i] <= 3;
            assert vector[i + 1] >= 0 && vector[i + 1] <= 3;
            assert vector[i + 2] >= 0 && vector[i + 2] <= 3;
            assert vector[i + 3] >= 0 && vector[i + 3] <= 3;
            assert vector[i + 4] >= 0 && vector[i + 4] <= 3;
            assert vector[i + 5] >= 0 && vector[i + 5] <= 3;
            assert vector[i + 6] >= 0 && vector[i + 6] <= 3;
            assert vector[i + 7] >= 0 && vector[i + 7] <= 3;
            int lowerByte = (vector[i] & 1) << 7 | (vector[i + 1] & 1) << 6 | (vector[i + 2] & 1) << 5 | (vector[i + 3] & 1) << 4
                | (vector[i + 4] & 1) << 3 | (vector[i + 5] & 1) << 2 | (vector[i + 6] & 1) << 1 | (vector[i + 7] & 1);
            int upperByte = ((vector[i] >> 1) & 1) << 7 | ((vector[i + 1] >> 1) & 1) << 6 | ((vector[i + 2] >> 1) & 1) << 5 | ((vector[i
                + 3] >> 1) & 1) << 4 | ((vector[i + 4] >> 1) & 1) << 3 | ((vector[i + 5] >> 1) & 1) << 2 | ((vector[i + 6] >> 1) & 1) << 1
                | ((vector[i + 7] >> 1) & 1);
            packed[index] = (byte) lowerByte;
            packed[index + packed.length / 2] = (byte) upperByte;
        }
        if (i == vector.length) {
            return;
        }
        int lowerByte = 0;
        int upperByte = 0;
        for (int j = 7; i < vector.length; j--, i++) {
            assert vector[i] >= 0 && vector[i] <= 3;
            lowerByte |= (vector[i] & 1) << j;
            upperByte |= ((vector[i] >> 1) & 1) << j;
        }
        packed[index] = (byte) lowerByte;
        packed[index + packed.length / 2] = (byte) upperByte;
    }

    public static void squareDistanceBulk(float[] center, float[] c0, float[] c1, float[] c2, float[] c3, float[] scores) {
        scores[0] = VectorUtil.squareDistance(center, c0);
        scores[1] = VectorUtil.squareDistance(center, c1);
        scores[2] = VectorUtil.squareDistance(center, c2);
        scores[3] = VectorUtil.squareDistance(center, c3);
    }

    // Offset/length overloads added for ES940 ClusteringFloatVectorValues (verbatim scalar port of native DefaultESVectorUtilSupport).
    public static float squareDistance(float[] a, float[] b, int offset, int length) {
        float sum = 0f;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            float diff = a[i] - b[i];
            // native scalar path uses Math.fma when HAS_FAST_SCALAR_FMA (the common case on production CPUs)
            sum = Math.fma(diff, diff, sum);
        }
        return sum;
    }

    public static void squareDistanceBulk(
        float[] query,
        int queryOffset,
        int length,
        float[] v0,
        float[] v1,
        float[] v2,
        float[] v3,
        float[] distances
    ) {
        distances[0] = squareDistance(query, v0, queryOffset, length);
        distances[1] = squareDistance(query, v1, queryOffset, length);
        distances[2] = squareDistance(query, v2, queryOffset, length);
        distances[3] = squareDistance(query, v3, queryOffset, length);
    }

    public static float soarDistance(float[] vector, float[] centroid, float[] diffs, float soarLambda, float vectorCentroidDist) {
        float dotProduct = 0f;
        for (int i = 0; i < vector.length; i++) {
            dotProduct += (vector[i] - centroid[i]) * diffs[i];
        }
        float diffNormSq = 0f;
        for (float d : diffs) {
            diffNormSq += d * d;
        }
        float diffNorm = (float) Math.sqrt(diffNormSq);
        float projection = (diffNorm > 0) ? (dotProduct / diffNorm) : 0f;
        return vectorCentroidDist - soarLambda * projection * projection;
    }

    public static void soarDistanceBulk(
        float[] vector,
        float[] centroid,
        float[] d0, float[] d1, float[] d2, float[] d3,
        float soarLambda,
        float vectorCentroidDist,
        float[] results
    ) {
        results[0] = soarDistance(vector, centroid, d0, soarLambda, vectorCentroidDist);
        results[1] = soarDistance(vector, centroid, d1, soarLambda, vectorCentroidDist);
        results[2] = soarDistance(vector, centroid, d2, soarLambda, vectorCentroidDist);
        results[3] = soarDistance(vector, centroid, d3, soarLambda, vectorCentroidDist);
    }

    public static void transposeHalfByte(int[] input, byte[] output) {
        // bit-plane (striped) layout: the 4 bit-planes of each 4-bit value are stored in the
        // four quarters of the output, MSB-first within each byte (matches ES91OSQVectorsScorer).
        int limit = input.length - 7;
        int i = 0;
        int index = 0;
        for (; i < limit; i += 8, index++) {
            int lowerByte = (input[i] & 1) << 7 | (input[i + 1] & 1) << 6 | (input[i + 2] & 1) << 5 | (input[i + 3] & 1) << 4
                | (input[i + 4] & 1) << 3 | (input[i + 5] & 1) << 2 | (input[i + 6] & 1) << 1 | (input[i + 7] & 1);
            int lowerMiddleByte = ((input[i] >> 1) & 1) << 7 | ((input[i + 1] >> 1) & 1) << 6 | ((input[i + 2] >> 1) & 1) << 5
                | ((input[i + 3] >> 1) & 1) << 4 | ((input[i + 4] >> 1) & 1) << 3 | ((input[i + 5] >> 1) & 1) << 2
                | ((input[i + 6] >> 1) & 1) << 1 | ((input[i + 7] >> 1) & 1);
            int upperMiddleByte = ((input[i] >> 2) & 1) << 7 | ((input[i + 1] >> 2) & 1) << 6 | ((input[i + 2] >> 2) & 1) << 5
                | ((input[i + 3] >> 2) & 1) << 4 | ((input[i + 4] >> 2) & 1) << 3 | ((input[i + 5] >> 2) & 1) << 2
                | ((input[i + 6] >> 2) & 1) << 1 | ((input[i + 7] >> 2) & 1);
            int upperByte = ((input[i] >> 3) & 1) << 7 | ((input[i + 1] >> 3) & 1) << 6 | ((input[i + 2] >> 3) & 1) << 5
                | ((input[i + 3] >> 3) & 1) << 4 | ((input[i + 4] >> 3) & 1) << 3 | ((input[i + 5] >> 3) & 1) << 2
                | ((input[i + 6] >> 3) & 1) << 1 | ((input[i + 7] >> 3) & 1);
            output[index] = (byte) lowerByte;
            output[index + output.length / 4] = (byte) lowerMiddleByte;
            output[index + output.length / 2] = (byte) upperMiddleByte;
            output[index + 3 * output.length / 4] = (byte) upperByte;
        }
        if (i == input.length) {
            return;
        }
        int lowerByte = 0, lowerMiddleByte = 0, upperMiddleByte = 0, upperByte = 0;
        for (int j = 7; i < input.length; j--, i++) {
            lowerByte |= (input[i] & 1) << j;
            lowerMiddleByte |= ((input[i] >> 1) & 1) << j;
            upperMiddleByte |= ((input[i] >> 2) & 1) << j;
            upperByte |= ((input[i] >> 3) & 1) << j;
        }
        output[index] = (byte) lowerByte;
        output[index + output.length / 4] = (byte) lowerMiddleByte;
        output[index + output.length / 2] = (byte) upperMiddleByte;
        output[index + 3 * output.length / 4] = (byte) upperByte;
    }

    public static void packAsBinary(int[] quantized, byte[] binary) {
        // MSB-first bit packing (matches ES91OSQVectorsScorer / native ES920 format).
        int limit = quantized.length - 7;
        int i = 0;
        int index = 0;
        for (; i < limit; i += 8, index++) {
            int result = quantized[i] << 7 | (quantized[i + 1] << 6) | (quantized[i + 2] << 5) | (quantized[i + 3] << 4)
                | (quantized[i + 4] << 3) | (quantized[i + 5] << 2) | (quantized[i + 6] << 1) | (quantized[i + 7]);
            binary[index] = (byte) result;
        }
        if (i == quantized.length) {
            return;
        }
        byte result = 0;
        for (int j = 7; j >= 0 && i < quantized.length; i++, j--) {
            result |= (byte) ((quantized[i] & 1) << j);
        }
        binary[index] = result;
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

    public static double calculateOSQLoss(float[] vector, float lower, float upper, int points, float norm2, float lambda, int[] quantized) {
        float step = (upper - lower) / (points - 1.0f);
        if (step == 0f) return 0;
        float invStep = 1f / step;
        float xe = 0f;
        float e = 0f;
        for (int i = 0; i < vector.length; i++) {
            float xi = vector[i];
            int q = Math.round((Math.min(Math.max(xi, lower), upper) - lower) * invStep);
            quantized[i] = q;
            float xiq = lower + q * step;
            float xiiq = xi - xiq;
            e += xiiq * xiiq;
            xe += xi * xiiq;
        }
        return (1.0f - lambda) * xe * xe / norm2 + lambda * e;
    }

    public static void calculateOSQGridPoints(float[] vector, int[] quantized, int points, float[] gridScratch) {
        float daa = 0, dab = 0, dbb = 0, dax = 0, dbx = 0;
        float invPmOnes = 1f / (points - 1.0f);
        for (int i = 0; i < vector.length; i++) {
            float v = vector[i];
            float k = quantized[i];
            float s = k * invPmOnes;
            float ms = 1f - s;
            daa += ms * ms;
            dab += ms * s;
            dbb += s * s;
            dax += ms * v;
            dbx += s * v;
        }
        gridScratch[0] = daa;
        gridScratch[1] = dab;
        gridScratch[2] = dbb;
        gridScratch[3] = dax;
        gridScratch[4] = dbx;
    }

    public static void centerAndCalculateOSQStatsEuclidean(float[] target, float[] centroid, float[] centered, float[] stats) {
        org.elasticsearch.simdvec.ESVectorUtil.centerAndCalculateOSQStatsEuclidean(target, centroid, centered, stats);
    }

    public static void centerAndCalculateOSQStatsDp(float[] target, float[] centroid, float[] centered, float[] stats) {
        org.elasticsearch.simdvec.ESVectorUtil.centerAndCalculateOSQStatsDp(target, centroid, centered, stats);
    }

    public static ES91OSQVectorsScorer getES91OSQVectorsScorer(IndexInput input, int dimension) throws IOException {
        return new ES91OSQVectorsScorer(input, dimension);
    }

    public static ES92Int7VectorsScorer getES92Int7VectorsScorer(IndexInput input, int dimension) throws IOException {
        return new ES92Int7VectorsScorer(input, dimension);
    }
}
