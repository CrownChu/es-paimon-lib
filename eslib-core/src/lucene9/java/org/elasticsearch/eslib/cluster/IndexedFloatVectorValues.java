/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.cluster;

import java.io.IOException;

/**
 * Random-access float vector values abstraction for JDK 11 / Lucene 9.12.
 * Replaces the dependency on Lucene 10's FloatVectorValues random-access API.
 */
public abstract class IndexedFloatVectorValues {

    public abstract float[] vectorValue(int ord) throws IOException;

    public abstract int dimension();

    public abstract int size();

    public int ordToDoc(int ord) {
        return ord;
    }

    public abstract IndexedFloatVectorValues copy() throws IOException;
}
