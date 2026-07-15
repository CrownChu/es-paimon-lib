/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.io;

import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.apache.lucene.store.IOContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveDirectoryTest {

    @Test
    void constructorRejectsInvalidOrOverflowingRanges() {
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        return this;
                    }
                };
        Map<String, long[]> invalid = new HashMap<>();
        invalid.put("negative", new long[] {-1, 1});
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArchiveDirectory(provider, invalid));

        Map<String, long[]> overflowing = new HashMap<>();
        overflowing.put("overflow", new long[] {Long.MAX_VALUE, 1});
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArchiveDirectory(provider, overflowing));
    }

    @Test
    void closeRetriesRootProviderThatFailsTransiently() throws Exception {
        AtomicBoolean failClose = new AtomicBoolean(true);
        AtomicInteger closeAttempts = new AtomicInteger();
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        throw new AssertionError("fork is not expected");
                    }

                    @Override
                    public void close() throws IOException {
                        closeAttempts.incrementAndGet();
                        if (failClose.get()) {
                            throw new IOException("transient close failure");
                        }
                    }
                };
        ArchiveDirectory directory = new ArchiveDirectory(provider, Collections.emptyMap());

        assertThrows(IOException.class, directory::close);
        assertEquals(1, closeAttempts.get());

        failClose.set(false);
        directory.close();
        assertEquals(2, closeAttempts.get());
    }

    @Test
    void openInputRejectsNullProviderFork() throws Exception {
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[length];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        return null;
                    }
                };
        ArchiveDirectory directory =
                new ArchiveDirectory(provider, Map.of("segment", new long[] {0, 1}));
        try {
            assertThrows(
                    IOException.class,
                    () -> directory.openInput("segment", IOContext.DEFAULT));
        } finally {
            directory.close();
        }
    }
}
