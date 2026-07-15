/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene912.Lucene912Codec;
import org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat;

public class DiskBBQCodec extends FilterCodec {

    private final KnnVectorsFormat knnFormat;

    public DiskBBQCodec() {
        this(384, 16);
    }

    public DiskBBQCodec(int vectorsPerCluster, int centroidsPerParentCluster) {
        // Writes the ES940 DiskBBQ format under the renamed PaimonES940DiskBBQVectorsFormat codec name
        // (consistent with the lucene10 DiskBBQCodec), so the paimon-store ES940 reader is dispatched on read.
        super("DiskBBQCodec", new Lucene912Codec());
        this.knnFormat = new ES940DiskBBQVectorsFormat(vectorsPerCluster, centroidsPerParentCluster);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return knnFormat;
    }
}
