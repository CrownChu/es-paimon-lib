/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

/**
 * Reference to a cluster's posting list inside the .clivf file.
 *
 * @param clusterId ordinal of the centroid
 * @param offset    absolute byte offset in the .clivf file (ready for HTTP Range Read)
 * @param length    byte length of this cluster's posting list
 * @param score     centroid similarity score (higher = closer match)
 */
public final class ClusterReference {

    private final int clusterId;
    private final long offset;
    private final long length;
    private final float score;

    public ClusterReference(int clusterId, long offset, long length, float score) {
        this.clusterId = clusterId;
        this.offset = offset;
        this.length = length;
        this.score = score;
    }

    public int clusterId() { return clusterId; }
    public long offset() { return offset; }
    public long length() { return length; }
    public float score() { return score; }
}
