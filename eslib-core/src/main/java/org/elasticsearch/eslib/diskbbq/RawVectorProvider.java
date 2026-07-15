/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import java.io.IOException;

/**
 * Callback for retrieving the raw (full-precision) vector of a given doc.
 * <p>
 * Used by rescore: after a quantized search collects {@code topK * oversample}
 * candidates, paimon-es lib calls back into the host to fetch the original
 * float vector and compute exact distances.
 * <p>
 * paimon-es lib stays storage-agnostic — implementors decide how to read the
 * vector (Paimon data file, in-memory map, etc.).
 */
public interface RawVectorProvider {

    /**
     * Returns the raw float vector for {@code docId}, or {@code null} if not available.
     */
    float[] getVector(long docId) throws IOException;
}
