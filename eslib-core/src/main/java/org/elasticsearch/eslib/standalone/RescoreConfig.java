/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.elasticsearch.eslib.diskbbq.RawVectorProvider;

/**
 * Rescore configuration for two-phase vector search.
 * <p>
 * Phase 1 (quantized): collect {@code topK * oversampleFactor} candidates from BBQ index.
 * Phase 2 (exact): fetch raw vectors via {@link RawVectorProvider} and rerank to topK.
 *
 * @param oversampleFactor multiplier for phase-1 candidate pool size; must be {@code >= 1}.
 *                         {@code 1} means no oversampling (rescore on the same topK).
 * @param provider callback that returns raw float[] for a given docId.
 */
public final class RescoreConfig {

    private final int oversampleFactor;
    private final RawVectorProvider provider;

    public RescoreConfig(int oversampleFactor, RawVectorProvider provider) {
        if (oversampleFactor < 1) {
            throw new IllegalArgumentException("oversampleFactor must be >= 1, got " + oversampleFactor);
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        this.oversampleFactor = oversampleFactor;
        this.provider = provider;
    }

    public int oversampleFactor() { return oversampleFactor; }
    public RawVectorProvider provider() { return provider; }
}
