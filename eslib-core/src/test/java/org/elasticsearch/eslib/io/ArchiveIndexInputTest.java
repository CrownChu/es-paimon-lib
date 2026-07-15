/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.io;

import org.apache.lucene.store.IndexInput;
import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveIndexInputTest {

    @Test
    void rejectsOverflowAndShortProviderReads() throws Exception {
        ArchiveDataProvider shortProvider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int length) {
                        return new byte[Math.max(0, length - 1)];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        return this;
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () -> new ArchiveIndexInput("overflow", shortProvider, Long.MAX_VALUE, 1, 1));

        ArchiveIndexInput input = new ArchiveIndexInput("short", shortProvider, 0, 4, 1);
        assertThrows(IOException.class, input::readByte);
        input.close();
        assertThrows(org.apache.lucene.store.AlreadyClosedException.class, input::readByte);
    }

    @Test
    void sliceRangeCheckDoesNotOverflow() throws Exception {
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
        try (ArchiveIndexInput input =
                new ArchiveIndexInput("large", provider, 0, Long.MAX_VALUE, 1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> input.slice("overflow", Long.MAX_VALUE, 1));
            try (IndexInput empty = input.slice("empty", Long.MAX_VALUE, 0)) {
                assertEquals(0, empty.length());
            }
        }
    }

    @Test
    void directReadEndingInsideOldBufferRealignsBufferCursor() throws Exception {
        byte[] data = new byte[16];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ProviderState state = new ProviderState(data);
        try (ArchiveIndexInput input =
                new ArchiveIndexInput("test", new CountingProvider(state), 0, data.length, 4)) {
            input.seek(8);
            assertEquals(8, input.readByte());

            input.seek(0);
            byte[] prefix = new byte[10];
            input.readBytes(prefix, 0, prefix.length);

            assertEquals(10, input.getFilePointer());
            assertEquals(10, input.readByte());
        }
    }

    @Test
    void largeDirectReadsUseBoundedProviderRanges() throws Exception {
        int maxExpectedRange = 8 * 1024 * 1024;
        int length = maxExpectedRange + 4096;
        AtomicInteger largestRange = new AtomicInteger();
        AtomicInteger rangeCount = new AtomicInteger();
        ArchiveDataProvider provider =
                new ArchiveDataProvider() {
                    @Override
                    public byte[] readRange(long offset, int requestedLength) {
                        largestRange.accumulateAndGet(requestedLength, Math::max);
                        rangeCount.incrementAndGet();
                        return new byte[requestedLength];
                    }

                    @Override
                    public ArchiveDataProvider fork() {
                        return this;
                    }
                };

        try (ArchiveIndexInput input =
                new ArchiveIndexInput("large-direct", provider, 0, length, 1024)) {
            input.readBytes(new byte[length], 0, length);
        }

        assertTrue(rangeCount.get() > 1, "large reads must be split into multiple ranges");
        assertTrue(
                largestRange.get() <= maxExpectedRange,
                "provider range must stay bounded; got " + largestRange.get());
    }

    @Test
    void clonesReuseProvidersBoundedByPeakReadConcurrency() throws Exception {
        byte[] data = new byte[] {10, 11, 12, 13};
        ProviderState state = new ProviderState(data);
        ArchiveIndexInput input =
                new ArchiveIndexInput("test", new CountingProvider(state), 0, data.length, 1);
        ExecutorService executor = Executors.newFixedThreadPool(data.length);
        try {
            for (int i = 0; i < 100; i++) {
                try (IndexInput clone = input.clone()) {
                    clone.seek(i % data.length);
                    assertEquals(data[i % data.length], clone.readByte());
                }
            }
            assertEquals(0, state.forkCount.get());
            assertEquals(0, state.closeCount.get());

            runConcurrentReadRound(input, state, executor, data);
            assertEquals(data.length - 1, state.forkCount.get());

            runConcurrentReadRound(input, state, executor, data);
            assertEquals(
                    data.length - 1,
                    state.forkCount.get(),
                    "a second query round should reuse the providers from the first round");
            assertEquals(0, state.closeCount.get());
        } finally {
            executor.shutdownNow();
            input.close();
        }

        assertEquals(data.length, state.closeCount.get());
    }

    @Test
    void closeRetriesProvidersThatFailTransiently() throws Exception {
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
        ArchiveIndexInput input = new ArchiveIndexInput("test", provider, 0, 1, 1);

        assertThrows(IOException.class, input::close);
        assertEquals(1, closeAttempts.get());

        failClose.set(false);
        input.close();
        assertEquals(2, closeAttempts.get());
    }

    @Test
    void closeWaitsForBorrowedProviderRead() throws Exception {
        byte[] data = new byte[] {42};
        ProviderState state = new ProviderState(data);
        ReadGate gate = new ReadGate(1);
        state.gate = gate;
        ArchiveIndexInput input =
                new ArchiveIndexInput("test", new CountingProvider(state), 0, data.length, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch closeStarted = new CountDownLatch(1);
        try {
            Future<Byte> read =
                    executor.submit(
                            () -> {
                                try (IndexInput clone = input.clone()) {
                                    return clone.readByte();
                                }
                            });
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));

            Future<?> close =
                    executor.submit(
                            () -> {
                                closeStarted.countDown();
                                input.close();
                                return null;
                            });
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));

            gate.release.countDown();
            assertEquals(data[0], read.get(10, TimeUnit.SECONDS));
            close.get(10, TimeUnit.SECONDS);
            assertEquals(1, state.closeCount.get());
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            input.close();
        }
    }

    @Test
    void interruptedCloseStillClosesBorrowedProviders() throws Exception {
        byte[] data = new byte[] {42};
        ProviderState state = new ProviderState(data);
        ReadGate gate = new ReadGate(1);
        state.gate = gate;
        ArchiveIndexInput input =
                new ArchiveIndexInput("test", new CountingProvider(state), 0, data.length, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean closeThreadInterrupted = new AtomicBoolean();
        Thread closer =
                new Thread(
                        () -> {
                            closeStarted.countDown();
                            try {
                                input.close();
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            } finally {
                                closeThreadInterrupted.set(Thread.currentThread().isInterrupted());
                            }
                        });
        try {
            Future<Byte> read =
                    executor.submit(
                            () -> {
                                try (IndexInput clone = input.clone()) {
                                    return clone.readByte();
                                }
                            });
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));

            closer.start();
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS));
            closer.interrupt();
            gate.release.countDown();

            assertEquals(data[0], read.get(10, TimeUnit.SECONDS));
            closer.join(TimeUnit.SECONDS.toMillis(10));
            assertTrue(!closer.isAlive(), "close thread should finish after the read is released");
            assertTrue(closeFailure.get() instanceof IOException);
            assertTrue(closeThreadInterrupted.get());
            assertEquals(1, state.closeCount.get());
        } finally {
            gate.release.countDown();
            closer.interrupt();
            closer.join(TimeUnit.SECONDS.toMillis(10));
            executor.shutdownNow();
            input.close();
        }
    }

    @Test
    void closeContinuesAfterUncheckedProviderFailureAndCanRetry() throws Exception {
        byte[] data = new byte[] {10, 11};
        AtomicBoolean failOneClose = new AtomicBoolean(true);
        AtomicInteger closeAttempts = new AtomicInteger();
        AtomicInteger successfulCloses = new AtomicInteger();
        ReadGate gate = new ReadGate(2);

        class FailingProvider implements ArchiveDataProvider {
            @Override
            public byte[] readRange(long offset, int length) throws IOException {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting for concurrent reads");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for concurrent reads", e);
                }
                return new byte[] {data[Math.toIntExact(offset)]};
            }

            @Override
            public ArchiveDataProvider fork() {
                return new FailingProvider();
            }

            @Override
            public void close() {
                closeAttempts.incrementAndGet();
                if (failOneClose.compareAndSet(true, false)) {
                    throw new IllegalStateException("transient unchecked close failure");
                }
                successfulCloses.incrementAndGet();
            }
        }

        ArchiveIndexInput input =
                new ArchiveIndexInput("test", new FailingProvider(), 0, data.length, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Byte> first =
                    executor.submit(
                            () -> {
                                try (IndexInput clone = input.clone()) {
                                    return clone.readByte();
                                }
                            });
            Future<Byte> second =
                    executor.submit(
                            () -> {
                                try (IndexInput clone = input.clone()) {
                                    clone.seek(1);
                                    return clone.readByte();
                                }
                            });
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            gate.release.countDown();
            assertEquals(data[0], first.get(10, TimeUnit.SECONDS));
            assertEquals(data[1], second.get(10, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, input::close);
            assertEquals(2, closeAttempts.get(), "both providers must be offered a close attempt");
            assertEquals(1, successfulCloses.get());

            input.close();
            assertEquals(3, closeAttempts.get());
            assertEquals(2, successfulCloses.get());
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            input.close();
        }
    }

    private static void runConcurrentReadRound(
            ArchiveIndexInput input,
            ProviderState state,
            ExecutorService executor,
            byte[] expected)
            throws Exception {
        ReadGate gate = new ReadGate(expected.length);
        state.gate = gate;
        List<Future<Byte>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < expected.length; i++) {
                final int position = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    try (IndexInput clone = input.clone()) {
                                        clone.seek(position);
                                        return clone.readByte();
                                    }
                                }));
            }
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            gate.release.countDown();
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], futures.get(i).get(10, TimeUnit.SECONDS));
            }
        } finally {
            gate.release.countDown();
            state.gate = null;
        }
    }

    private static final class ProviderState {
        private final byte[] data;
        private final AtomicInteger forkCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile ReadGate gate;

        private ProviderState(byte[] data) {
            this.data = data;
        }
    }

    private static final class ReadGate {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);

        private ReadGate(int readers) {
            this.entered = new CountDownLatch(readers);
        }
    }

    private static final class CountingProvider implements ArchiveDataProvider {
        private final ProviderState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CountingProvider(ProviderState state) {
            this.state = state;
        }

        @Override
        public byte[] readRange(long offset, int length) throws IOException {
            ReadGate gate = state.gate;
            if (gate != null) {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting for concurrent reads");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for concurrent reads", e);
                }
            }
            byte[] bytes = new byte[length];
            System.arraycopy(state.data, Math.toIntExact(offset), bytes, 0, length);
            return bytes;
        }

        @Override
        public ArchiveDataProvider fork() {
            state.forkCount.incrementAndGet();
            return new CountingProvider(state);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.closeCount.incrementAndGet();
            }
        }
    }
}
