/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.diskbbq.es94;

/**
 * Metadata for one cluster's posting list, produced by a {@link CentroidIterator} and consumed by the
 * posting-list scorer. JDK 11 build: plain class instead of a record.
 *
 * @param offset                posting-list offset within the field's posting slice
 * @param length                posting-list length in bytes (doc ids + quantized vectors)
 * @param queryCentroidOrdinal  centroid ordinal used to (re)quantize the query for this cluster, or
 *                              {@link #NO_ORDINAL} to use the global centroid
 * @param documentCentroidScore the centroid-vs-query score, carried into the final corrections
 */
final class PostingMetadata {

    /** Use the global centroid for query quantization (single-level / no-parent layout). */
    static final int NO_ORDINAL = -1;

    private final long offset;
    private final long length;
    private final int queryCentroidOrdinal;
    private final float documentCentroidScore;

    PostingMetadata(long offset, long length, int queryCentroidOrdinal, float documentCentroidScore) {
        this.offset = offset;
        this.length = length;
        this.queryCentroidOrdinal = queryCentroidOrdinal;
        this.documentCentroidScore = documentCentroidScore;
    }

    long offset() {
        return offset;
    }

    long length() {
        return length;
    }

    int queryCentroidOrdinal() {
        return queryCentroidOrdinal;
    }

    float documentCentroidScore() {
        return documentCentroidScore;
    }
}
