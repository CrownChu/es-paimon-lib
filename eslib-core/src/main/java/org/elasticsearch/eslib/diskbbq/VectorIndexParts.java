/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import java.nio.file.Path;

/**
 * Lucene native IVF files exported by {@link org.elasticsearch.eslib.diskbbq.VectorIndexBuilder#writeParts(Path)}.
 * Files keep Lucene CodecUtil headers, so ES can read them directly.
 *
 * @param metaFile     .mivf file — IVF metadata (numCentroids, globalCentroid, offsets)
 * @param centroidFile .cenivf file — quantized centroid vectors + offset table
 * @param clusterFile  .clivf file — posting lists (per-cluster centroid + docIds + quantized vectors)
 */
public final class VectorIndexParts {

    private final Path metaFile;
    private final Path centroidFile;
    private final Path clusterFile;

    public VectorIndexParts(Path metaFile, Path centroidFile, Path clusterFile) {
        this.metaFile = metaFile;
        this.centroidFile = centroidFile;
        this.clusterFile = clusterFile;
    }

    public Path metaFile() { return metaFile; }
    public Path centroidFile() { return centroidFile; }
    public Path clusterFile() { return clusterFile; }
}
