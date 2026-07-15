/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat;

/**
 * Custom Lucene Codec that uses DiskBBQ for KNN vectors.
 *
 * <p>Writes the ES940 DiskBBQ format (second-level parent clustering + asymmetric int7 centroid
 * quantization) under the renamed {@code PaimonES940DiskBBQVectorsFormat} codec name, so the
 * paimon-store ES940 reader (OSS bulk-prefetch + mount visit-cap) is dispatched on read.
 */
public class DiskBBQCodec extends FilterCodec {

    private final KnnVectorsFormat knnFormat;

    public DiskBBQCodec() {
        this(384, 16);
    }

    public DiskBBQCodec(int vectorsPerCluster, int centroidsPerParentCluster) {
        // Delegate to the default codec (decoupled from a specific Lucene10xCodec class, which gets
        // relocated across Lucene minor releases); only the KNN vectors format is overridden below.
        super("DiskBBQCodec", Codec.getDefault());
        this.knnFormat = new ES940DiskBBQVectorsFormat(vectorsPerCluster, centroidsPerParentCluster);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }
}
