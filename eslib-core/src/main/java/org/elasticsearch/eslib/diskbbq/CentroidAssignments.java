/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.diskbbq;

public final class CentroidAssignments {

    private final int numCentroids;
    private final float[][] centroids;
    private final int[] assignments;
    private final int[] overspillAssignments;
    private final float[] globalCentroid;

    public CentroidAssignments(
        int numCentroids,
        float[][] centroids,
        int[] assignments,
        int[] overspillAssignments,
        float[] globalCentroid
    ) {
        this.numCentroids = numCentroids;
        this.centroids = centroids;
        this.assignments = assignments;
        this.overspillAssignments = overspillAssignments;
        this.globalCentroid = globalCentroid;
    }

    public CentroidAssignments(int dims, float[][] centroids, int[] assignments, int[] overspillAssignments) {
        this(centroids.length, centroids, assignments, overspillAssignments, computeGlobalCentroid(dims, centroids));
        assert assignments.length == overspillAssignments.length || overspillAssignments.length == 0
            : "assignments and overspillAssignments must have the same length";

    }

    public int numCentroids() { return numCentroids; }
    public float[][] centroids() { return centroids; }
    public int[] assignments() { return assignments; }
    public int[] overspillAssignments() { return overspillAssignments; }
    public float[] globalCentroid() { return globalCentroid; }

    private static float[] computeGlobalCentroid(int dims, float[][] centroids) {
        final float[] globalCentroid = new float[dims];
        // TODO: push this logic into vector util?
        for (float[] centroid : centroids) {
            assert centroid.length == dims;
            for (int j = 0; j < centroid.length; j++) {
                globalCentroid[j] += centroid[j];
            }
        }
        for (int j = 0; j < globalCentroid.length; j++) {
            globalCentroid[j] /= centroids.length;
        }
        return globalCentroid;
    }
}
