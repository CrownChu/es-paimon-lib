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
 * KNN search result containing matched IDs and similarity scores.
 */
public class SearchResult {
    public final long[] ids;
    public final float[] scores;
    public final int count;

    public SearchResult(long[] ids, float[] scores, int count) {
        this.ids = ids;
        this.scores = scores;
        this.count = count;
    }
}
