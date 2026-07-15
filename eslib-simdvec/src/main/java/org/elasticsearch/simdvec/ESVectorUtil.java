package org.elasticsearch.simdvec;

import org.apache.lucene.util.BitUtil;

/**
 * Scalar fallback implementations for SIMD-accelerated vector operations.
 * JDK 21+ can override via MR-JAR with Vector API versions.
 */
public class ESVectorUtil {

    public static final int B_QUERY = 4;

    private ESVectorUtil() {}

    public static long ipByteBinByte(byte[] q, byte[] d) {
        long ret = 0;
        int binaryDims = d.length;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            for (int j = 0; j < binaryDims; j++) {
                subRet += Integer.bitCount((q[i * binaryDims + j] & d[j]) & 0xFF);
            }
            ret += subRet << (i * 8);
        }
        return ret;
    }

    public static int ipByteBit(byte[] q, byte[] d) {
        int result = 0;
        for (int i = 0; i < d.length; i++) {
            int bits = d[i] & 0xFF;
            for (int j = 0; j < 8 && (i * 8 + j) < q.length; j++) {
                if ((bits & (1 << (7 - j))) != 0) {
                    result += q[i * 8 + j];
                }
            }
        }
        return result;
    }

    public static float ipFloatBit(float[] q, byte[] d) {
        float result = 0f;
        for (int i = 0; i < d.length; i++) {
            int bits = d[i] & 0xFF;
            for (int j = 0; j < 8 && (i * 8 + j) < q.length; j++) {
                if ((bits & (1 << (7 - j))) != 0) {
                    result += q[i * 8 + j];
                }
            }
        }
        return result;
    }

    public static float ipFloatByte(float[] q, byte[] d) {
        float result = 0f;
        for (int i = 0; i < q.length; i++) {
            result += q[i] * d[i];
        }
        return result;
    }

    public static int andBitCount(byte[] a, byte[] b) {
        int distance = 0;
        for (int i = 0; i < a.length; i++) {
            distance += Integer.bitCount((a[i] & b[i]) & 0xFF);
        }
        return distance;
    }

    public static float calculateOSQLoss(float[] target, float[] interval, int points, float norm2, float lambda) {
        float step = (interval[1] - interval[0]) / (points - 1.0f);
        if (step == 0f) return 0f;
        float invStep = 1f / step;
        float loss = 0f;
        for (int i = 0; i < target.length; i++) {
            int q = Math.round((target[i] - interval[0]) * invStep);
            q = Math.max(0, Math.min(points - 1, q));
            float reconstructed = interval[0] + q * step;
            float diff = target[i] - reconstructed;
            loss += diff * diff;
        }
        return loss;
    }

    public static void calculateOSQGridPoints(float[] target, float[] interval, int points, float invStep, float[] pts) {
        float daa = 0, dab = 0, dbb = 0, dax = 0, dbx = 0;
        for (int i = 0; i < target.length; i++) {
            int q = Math.round((target[i] - interval[0]) * invStep);
            q = Math.max(0, Math.min(points - 1, q));
            float qNorm = q / (float) (points - 1);
            daa += 1;
            dab += qNorm;
            dbb += qNorm * qNorm;
            dax += target[i];
            dbx += target[i] * qNorm;
        }
        pts[0] = daa;
        pts[1] = dab;
        pts[2] = dbb;
        pts[3] = dax;
        pts[4] = dbx;
    }

    /**
     * Center {@code target} against {@code centroid} (writing the residual to {@code centered})
     * and produce the OSQ statistics that {@code OptimizedScalarQuantizer.scalarQuantize} expects.
     *
     * <p>Stats layout (must match the upstream ES simdvec contract):
     * <pre>
     *   stats[0] = vecMean      (mean of centered, computed with Welford for numerical stability)
     *   stats[1] = vecVar       (variance of centered, divided by N)
     *   stats[2] = norm2        (squared L2 norm of centered)
     *   stats[3] = min          (min component of centered)
     *   stats[4] = max          (max component of centered)
     * </pre>
     *
     * <p>Previously this method wrote {@code [norm2, min, max, sum, sumSq]} which mismatched the
     * quantizer's read order — that caused {@code vecStd = sqrt(stats[1]) = sqrt(min) = NaN} for
     * residuals with negative components, poisoning every {@code QuantizationResult.lowerInterval/
     * upperInterval} and ultimately producing {@code NaN} KNN scores.
     */
    public static void centerAndCalculateOSQStatsEuclidean(float[] target, float[] centroid, float[] centered, float[] stats) {
        float vecMean = 0f;
        float vecVar = 0f;
        float norm2 = 0f;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < target.length; i++) {
            centered[i] = target[i] - centroid[i];
            min = Math.min(min, centered[i]);
            max = Math.max(max, centered[i]);
            norm2 += centered[i] * centered[i];
            // Welford's online algorithm for mean + variance.
            float delta = centered[i] - vecMean;
            vecMean += delta / (i + 1);
            float delta2 = centered[i] - vecMean;
            vecVar += delta * delta2;
        }
        stats[0] = vecMean;
        stats[1] = vecVar / target.length;
        stats[2] = norm2;
        stats[3] = min;
        stats[4] = max;
    }

    /** {@inheritDoc Euclidean variant}; additionally writes {@code stats[5] = dotProduct(target, centroid)}. */
    public static void centerAndCalculateOSQStatsDp(float[] target, float[] centroid, float[] centered, float[] stats) {
        float vecMean = 0f;
        float vecVar = 0f;
        float norm2 = 0f;
        float centroidDot = 0f;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < target.length; i++) {
            centroidDot += target[i] * centroid[i];
            centered[i] = target[i] - centroid[i];
            min = Math.min(min, centered[i]);
            max = Math.max(max, centered[i]);
            norm2 += centered[i] * centered[i];
            float delta = centered[i] - vecMean;
            vecMean += delta / (i + 1);
            float delta2 = centered[i] - vecMean;
            vecVar += delta * delta2;
        }
        stats[0] = vecMean;
        stats[1] = vecVar / target.length;
        stats[2] = norm2;
        stats[3] = min;
        stats[4] = max;
        stats[5] = centroidDot;
    }
}
