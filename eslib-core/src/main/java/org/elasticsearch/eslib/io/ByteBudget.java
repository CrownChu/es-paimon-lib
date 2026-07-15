/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.io;

/**
 * A pluggable memory-accounting contract that {@link OSSRangeIndexInput} uses to bound the
 * total bytes its read buffers can pin at any given moment.
 *
 * <p>Layering note: a concrete budget implementation (e.g. an atomic CAS counter scoped to a
 * paimon-store node, or an LRU-eviction-aware cache budget) lives upstream in the
 * paimon-store-common layer as {@code MemoryBudget} / {@code AtomicMemoryBudget}. {@code
 * eslib-core} only declares the minimal contract here because {@link OSSRangeIndexInput}
 * lives here too, and we deliberately avoid an {@code eslib-core → paimon-store-common}
 * dependency (the layering is the other way around).
 *
 * <p>Semantics — modeled after the pattern in the 817 paimon-source fork
 * ({@code storage/MemoryBudget.java}):
 * <ul>
 *   <li>{@link #tryReserve(long)} returns {@code true} only if reserving {@code bytes} would
 *       keep total reserved ≤ {@link #maxBytes()}. On {@code false} the caller is expected
 *       to fall back to a smaller buffer (graceful degradation), <strong>not</strong> block
 *       — search latency must not stall on budget pressure.</li>
 *   <li>{@link #release(long)} is idempotent against under-flow (clamped at 0).</li>
 *   <li>{@link #UNLIMITED} disables accounting entirely; passing it is equivalent to passing
 *       {@code null} but keeps the call sites null-free.</li>
 * </ul>
 */
public interface ByteBudget {

    /**
     * Try to reserve {@code bytes} from the budget. Returns {@code true} on success and
     * {@code false} if the reservation would exceed {@link #maxBytes()}. Callers MUST call
     * {@link #release(long)} with the same value once the underlying buffer is freed.
     */
    boolean tryReserve(long bytes);

    /** Release {@code bytes} previously reserved. */
    void release(long bytes);

    /** The total budget cap. {@code 0} means accounting is effectively off. */
    long maxBytes();

    /**
     * A no-op budget that allows every reservation. Used as the default when callers don't
     * pass one explicitly, so {@link OSSRangeIndexInput} can keep its old behavior unchanged.
     */
    ByteBudget UNLIMITED = new ByteBudget() {
        @Override public boolean tryReserve(long bytes) { return true; }
        @Override public void release(long bytes) { /* no-op */ }
        @Override public long maxBytes() { return Long.MAX_VALUE; }
    };
}
