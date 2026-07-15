/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.diskbbq.es94;

import java.io.IOException;

/** Stateful iterator over the scored centroids' posting lists, in descending centroid-score order. */
interface CentroidIterator {
    boolean hasNext();

    PostingMetadata nextPosting() throws IOException;
}
