/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.elasticsearch.eslib.diskbbq.VectorIndexParts;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Vector index builder interface for Paimon integration.
 * Usage: create -> addVector(loop) -> build -> writeToDir -> close
 */
public interface VectorIndexBuilder extends Closeable {

    /**
     * Add a vector to the index.
     * @param id vector ID (equals Paimon rowId, starting from 0)
     * @param vector float vector
     */
    void addVector(long id, float[] vector) throws IOException;

    /**
     * Trigger index construction. Call after all vectors have been added.
     */
    void build() throws IOException;

    /**
     * Copy the raw Lucene segment files to a target directory.
     * The directory must exist. Each file is copied individually.
     */
    void writeToDir(Path targetDir) throws IOException;

    /**
     * Export the IVF index as three separate Lucene files for split-storage.
     * Call after build(). Files keep Lucene CodecUtil headers intact.
     *
     * @param targetDir directory to write the files into (must exist)
     * @return paths to the three exported files (.mivf, .cenivf, .clivf)
     */
    VectorIndexParts writeParts(Path targetDir) throws IOException;
}
