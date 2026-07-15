/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.io;

import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.eslib.api.ArchiveDataProvider;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Lucene {@link IndexInput} backed by an {@link ArchiveDataProvider}.
 * Supports buffered sequential reads, seek, slice, and clone.
 */
public class ArchiveIndexInput extends IndexInput {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_DIRECT_READ_SIZE = 8 * 1024 * 1024;

    private final ProviderPool providerPool;
    private final boolean ownsProviderPool;
    private final long baseOffset;
    private final long sliceLength;
    private final int bufferSize;

    private byte[] buffer;
    private long bufferStart;
    private int bufferPos;
    private int bufferValid;
    private long position;
    private boolean closed;

    public ArchiveIndexInput(String resourceDesc, ArchiveDataProvider provider,
                             long baseOffset, long sliceLength) {
        this(resourceDesc, new ProviderPool(provider), true,
                baseOffset, sliceLength, DEFAULT_BUFFER_SIZE);
    }

    public ArchiveIndexInput(String resourceDesc, ArchiveDataProvider provider,
                             long baseOffset, long sliceLength, int bufferSize) {
        this(resourceDesc, new ProviderPool(provider), true,
                baseOffset, sliceLength, bufferSize);
    }

    private ArchiveIndexInput(String resourceDesc, ProviderPool providerPool,
                              boolean ownsProviderPool, long baseOffset, long sliceLength,
                              int bufferSize) {
        super(resourceDesc);
        if (baseOffset < 0 || sliceLength < 0 || baseOffset > Long.MAX_VALUE - sliceLength) {
            throw new IllegalArgumentException(
                    "Invalid archive input range: offset=" + baseOffset + ", length=" + sliceLength);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive; got: " + bufferSize);
        }
        this.providerPool = Objects.requireNonNull(providerPool, "providerPool");
        this.ownsProviderPool = ownsProviderPool;
        this.baseOffset = baseOffset;
        this.sliceLength = sliceLength;
        this.bufferSize = bufferSize;
        this.buffer = new byte[bufferSize];
        this.bufferStart = -1;
        this.bufferPos = 0;
        this.bufferValid = 0;
        this.position = 0;
    }

    @Override
    public byte readByte() throws IOException {
        ensureInputOpen();
        if (position >= sliceLength) {
            throw new EOFException("read past EOF: pos=" + position + ", length=" + sliceLength);
        }
        if (!bufferContains(position)) {
            fillBuffer(position);
        }
        byte b = buffer[bufferPos];
        bufferPos++;
        position++;
        return b;
    }

    @Override
    public void readBytes(byte[] b, int offset, int len) throws IOException {
        ensureInputOpen();
        Objects.checkFromIndexSize(offset, len, b.length);
        if (len > sliceLength - position) {
            throw new EOFException("read past EOF: pos=" + position + ", len=" + len
                    + ", length=" + sliceLength);
        }
        int remaining = len;
        int destPos = offset;
        while (remaining > 0) {
            if (!bufferContains(position)) {
                if (remaining >= bufferSize) {
                    // ArchiveDataProvider returns a newly allocated byte array. Passing a caller's
                    // entire remaining length through here can therefore temporarily double the
                    // memory of a large Lucene read (up to nearly 2 GiB). Keep direct reads large
                    // enough for remote-range efficiency, but bound each temporary allocation.
                    int directLength =
                        Math.min(remaining, Math.max(bufferSize, MAX_DIRECT_READ_SIZE));
                    byte[] data = providerPool.readRange(baseOffset + position, directLength);
                    ensureExactLength(data, directLength);
                    System.arraycopy(data, 0, b, destPos, directLength);
                    position += directLength;
                    remaining -= directLength;
                    destPos += directLength;
                    // A backward seek can leave a valid cache window ahead of the new position.
                    // If this direct read ends inside that old window, align bufferPos before the
                    // next read instead of returning a byte from the cache's previous cursor.
                    if (bufferContains(position)) {
                        bufferPos = (int) (position - bufferStart);
                    }
                    continue;
                }
                fillBuffer(position);
            }
            int available = bufferValid - bufferPos;
            int toCopy = Math.min(available, remaining);
            System.arraycopy(buffer, bufferPos, b, destPos, toCopy);
            bufferPos += toCopy;
            position += toCopy;
            remaining -= toCopy;
            destPos += toCopy;
        }
    }

    @Override
    public long getFilePointer() {
        return position;
    }

    @Override
    public void seek(long pos) throws IOException {
        ensureInputOpen();
        if (pos < 0 || pos > sliceLength) {
            throw new IllegalArgumentException(
                    "seek pos=" + pos + " out of range [0, " + sliceLength + "]");
        }
        this.position = pos;
        if (bufferContains(pos)) {
            bufferPos = (int) (pos - bufferStart);
        }
    }

    @Override
    public long length() {
        return sliceLength;
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
        ensureInputOpen();
        if (offset < 0 || length < 0 || offset > sliceLength - length) {
            throw new IllegalArgumentException(
                    "slice(offset=" + offset + ", length=" + length
                            + ") out of range [0, " + sliceLength + ")");
        }
        return new ArchiveIndexInput(
                sliceDescription, providerPool, false,
                baseOffset + offset, length,
                Math.max(1, Math.min(bufferSize, (int) Math.min(length, Integer.MAX_VALUE))));
    }

    @Override
    public IndexInput clone() {
        ensureInputOpen();
        ArchiveIndexInput cloned = new ArchiveIndexInput(
                toString(), providerPool, false,
                baseOffset, sliceLength, bufferSize);
        cloned.position = this.position;
        return cloned;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            buffer = null;
        }
        if (ownsProviderPool) {
            providerPool.close();
        }
    }

    private boolean bufferContains(long pos) {
        return bufferStart >= 0 && pos >= bufferStart && pos < bufferStart + bufferValid;
    }

    private void fillBuffer(long pos) throws IOException {
        int toRead = (int) Math.min(bufferSize, sliceLength - pos);
        byte[] data = providerPool.readRange(baseOffset + pos, toRead);
        ensureExactLength(data, toRead);
        System.arraycopy(data, 0, buffer, 0, toRead);
        bufferStart = pos;
        bufferPos = 0;
        bufferValid = toRead;
    }

    private void ensureInputOpen() {
        if (closed) {
            throw new AlreadyClosedException("ArchiveIndexInput is closed: " + this);
        }
    }

    private static void ensureExactLength(byte[] data, int expected) throws IOException {
        if (data == null || data.length != expected) {
            throw new IOException(
                    "Archive provider returned "
                            + (data == null ? "null" : data.length)
                            + " bytes; expected "
                            + expected);
        }
    }

    /**
     * Shares a bounded set of providers between an original input and all of its clones/slices.
     * Lucene does not require cloned {@link IndexInput}s to be closed, so assigning one provider to
     * every clone leaks streams for query-scoped clones. Borrowing a provider for each atomic range
     * read preserves parallel reads while bounding the number of streams by peak read concurrency.
     */
    private static final class ProviderPool {

        private final Deque<ArchiveDataProvider> availableProviders = new ArrayDeque<>();
        private final Set<ArchiveDataProvider> allProviders =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final ArchiveDataProvider rootProvider;
        private int activeReads;
        private boolean closed;

        private ProviderPool(ArchiveDataProvider rootProvider) {
            this.rootProvider = Objects.requireNonNull(rootProvider, "rootProvider");
            this.availableProviders.add(rootProvider);
            this.allProviders.add(rootProvider);
        }

        private byte[] readRange(long offset, int length) throws IOException {
            ArchiveDataProvider provider = acquire();
            try {
                return provider.readRange(offset, length);
            } finally {
                release(provider);
            }
        }

        private synchronized ArchiveDataProvider acquire() throws IOException {
            if (closed) {
                throw new IOException("ArchiveIndexInput is closed");
            }
            ArchiveDataProvider provider = availableProviders.pollFirst();
            if (provider == null) {
                provider = rootProvider.fork();
                if (provider == null) {
                    throw new IOException("Archive provider fork returned null");
                }
                allProviders.add(provider);
            }
            activeReads++;
            return provider;
        }

        private synchronized void release(ArchiveDataProvider provider) {
            activeReads--;
            if (!closed) {
                availableProviders.addFirst(provider);
            }
            notifyAll();
        }

        private synchronized void close() throws IOException {
            closed = true;
            availableProviders.clear();
            boolean interrupted = false;
            while (activeReads > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Finish closing after the borrowed provider is returned. Returning here would
                    // strand every provider in the pool unless the caller happened to retry close.
                    interrupted = true;
                }
            }
            Throwable failure = null;
            java.util.Iterator<ArchiveDataProvider> iterator = allProviders.iterator();
            while (iterator.hasNext()) {
                ArchiveDataProvider provider = iterator.next();
                try {
                    provider.close();
                    iterator.remove();
                } catch (Throwable e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
                IOException interruptedFailure =
                        new IOException("Interrupted while waiting for archive reads to finish");
                if (failure == null) {
                    failure = interruptedFailure;
                } else {
                    failure.addSuppressed(interruptedFailure);
                }
            }
            if (failure != null) {
                if (failure instanceof IOException) {
                    throw (IOException) failure;
                }
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw new IOException("Failed to close archive providers", failure);
            }
        }
    }
}
