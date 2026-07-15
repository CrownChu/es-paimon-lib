/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.simdvec;

import org.apache.lucene.util.SharedSessionUtil;

import java.lang.foreign.MemorySegment;

public class JNIVectorAccess {

    public static boolean IS_DISABLE_JNI_VEC;

    private static native int dotproduct7u(long a, long b, int dims);

    private static native int sqr7u(long a, long b, int dims);

    static {
        IS_DISABLE_JNI_VEC = "true".equalsIgnoreCase(System.getProperty("disableJniVec"));
    }

    public static int dotProduct7u2(MemorySegment a, MemorySegment b, int length, SharedSessionUtil.ScopeStats scopeStats) {
        assert length >= 0;
        if (a.byteSize() != b.byteSize()) {
            throw new IllegalArgumentException("dimensions differ: " + a.byteSize() + "!=" + b.byteSize());
        }
        if (length > a.byteSize()) {
            throw new IllegalArgumentException("length: " + length + ", greater than vector dimensions: " + a.byteSize());
        }
        try {
            SharedSessionUtil.acquire(scopeStats);
            return dotproduct7u(a.address(), b.address(), length);
        } catch (Throwable t) {
            throw new AssertionError(t);
        } finally {
            SharedSessionUtil.release(scopeStats);
        }
    }

    public static int squareDistance7u2(MemorySegment a, MemorySegment b, int length, SharedSessionUtil.ScopeStats scopeStats) {
        assert length >= 0;
        if (a.byteSize() != b.byteSize()) {
            throw new IllegalArgumentException("dimensions differ: " + a.byteSize() + "!=" + b.byteSize());
        }
        if (length > a.byteSize()) {
            throw new IllegalArgumentException("length: " + length + ", greater than vector dimensions: " + a.byteSize());
        }
        try {
            SharedSessionUtil.acquire(scopeStats);
            return sqr7u(a.address(), b.address(), length);
        } catch (Throwable t) {
            throw new AssertionError(t);
        } finally {
            SharedSessionUtil.release(scopeStats);
        }
    }
}
