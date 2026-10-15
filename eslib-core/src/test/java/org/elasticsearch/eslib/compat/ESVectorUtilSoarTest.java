/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ESVectorUtilSoarTest {
    @Test
    void scalarAddsOrthogonalityPenaltyToCandidateDistance() {
        float[] vector = {1, 1};
        float[] primaryResidual = {1, 0};
        // The primary centroid is [0,1]: ||residual||^2=1. Parallel
        // residual [1,0] costs 1+1=2; orthogonal [0,1] costs 1+0=1.
        assertEquals(
                2f, ESVectorUtil.soarDistance(vector, new float[] {0, 1}, primaryResidual, 1f, 1f));
        assertEquals(
                1f, ESVectorUtil.soarDistance(vector, new float[] {1, 0}, primaryResidual, 1f, 1f));
        assertEquals(
                4f,
                ESVectorUtil.soarDistance(vector, new float[] {1, -1}, primaryResidual, 1f, 1f));
        assertEquals(
                5f, ESVectorUtil.soarDistance(vector, new float[] {0, 3}, primaryResidual, 0f, 1f));
    }

    @Test
    void bulkUsesFourCandidateCentroidsWithOneSharedPrimaryResidual() {
        float[] output = new float[4];
        ESVectorUtil.soarDistanceBulk(
                new float[] {1, 1},
                new float[] {0, 1},
                new float[] {1, 0},
                new float[] {1, -1},
                new float[] {-1, 1},
                new float[] {1, 0},
                1f,
                1f,
                output);
        assertArrayEquals(new float[] {2, 1, 4, 8}, output);
    }
}
