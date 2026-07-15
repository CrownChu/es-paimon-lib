/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsFormat;
import org.apache.lucene.codecs.hnsw.FlatVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;
import java.util.Map;

/**
 * Codec format for Inverted File Vector indexes (standalone lib version).
 * Simplified from Elasticsearch's ES920DiskBBQVectorsFormat: fixed to FLOAT32, no DirectIO, no BFloat16.
 */
public class PaimonDiskBBQVectorsFormat extends KnnVectorsFormat {

    public static final String NAME = "PaimonDiskBBQVectorsFormat";
    public static final String CENTROID_EXTENSION = "cenivf";
    public static final String CLUSTER_EXTENSION = "clivf";
    static final String IVF_META_EXTENSION = "mivf";

    public static final int VERSION_START = 0;
    public static final int VERSION_DIRECT_IO = 1;
    public static final int VERSION_CURRENT = VERSION_DIRECT_IO;

    private static final FlatVectorsFormat float32VectorFormat = new Lucene99FlatVectorsFormat(
        FlatVectorScorerUtil.getLucene99FlatVectorsScorer()
    );

    public static final float DYNAMIC_VISIT_RATIO = 0.0f;
    public static final int DEFAULT_VECTORS_PER_CLUSTER = 384;
    public static final int MIN_VECTORS_PER_CLUSTER = 64;
    public static final int MAX_VECTORS_PER_CLUSTER = 1 << 16;
    public static final int DEFAULT_CENTROIDS_PER_PARENT_CLUSTER = 16;
    public static final int MIN_CENTROIDS_PER_PARENT_CLUSTER = 2;
    public static final int MAX_CENTROIDS_PER_PARENT_CLUSTER = 1 << 8;

    private final int vectorPerCluster;
    private final int centroidsPerParentCluster;
    private final FlatVectorsFormat rawVectorFormat;

    public PaimonDiskBBQVectorsFormat(int vectorPerCluster, int centroidsPerParentCluster) {
        super(NAME);
        if (vectorPerCluster < MIN_VECTORS_PER_CLUSTER || vectorPerCluster > MAX_VECTORS_PER_CLUSTER) {
            throw new IllegalArgumentException(
                "vectorsPerCluster must be between "
                    + MIN_VECTORS_PER_CLUSTER
                    + " and "
                    + MAX_VECTORS_PER_CLUSTER
                    + ", got: "
                    + vectorPerCluster
            );
        }
        if (centroidsPerParentCluster < MIN_CENTROIDS_PER_PARENT_CLUSTER
            || centroidsPerParentCluster > MAX_CENTROIDS_PER_PARENT_CLUSTER) {
            throw new IllegalArgumentException(
                "centroidsPerParentCluster must be between "
                    + MIN_CENTROIDS_PER_PARENT_CLUSTER
                    + " and "
                    + MAX_CENTROIDS_PER_PARENT_CLUSTER
                    + ", got: "
                    + centroidsPerParentCluster
            );
        }
        this.vectorPerCluster = vectorPerCluster;
        this.centroidsPerParentCluster = centroidsPerParentCluster;
        this.rawVectorFormat = float32VectorFormat;
    }

    public PaimonDiskBBQVectorsFormat() {
        this(DEFAULT_VECTORS_PER_CLUSTER, DEFAULT_CENTROIDS_PER_PARENT_CLUSTER);
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return new ES920DiskBBQVectorsWriter(
            state,
            rawVectorFormat.getName(),
            false,
            rawVectorFormat.fieldsWriter(state),
            vectorPerCluster,
            centroidsPerParentCluster
        );
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return new ES920DiskBBQVectorsReader(state, (f, dio) -> {
            if (f.equals(rawVectorFormat.getName())) {
                return rawVectorFormat.fieldsReader(state);
            }
            return null;
        });
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return 4096;
    }

    @Override
    public String toString() {
        return "PaimonDiskBBQVectorsFormat(" + "vectorPerCluster=" + vectorPerCluster + ')';
    }
}
