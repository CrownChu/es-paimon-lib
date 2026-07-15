/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.io;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;

import org.apache.lucene.store.IndexInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lucene IndexInput backed by OSS HTTP Range Read.
 * Supports seek, slice, and buffered sequential reads, plus an async prefetch hook
 * (single-slot CompletableFuture) so callers like the DiskBBQ posting iterator can
 * overlap one OSS GET with computation for the prior cluster.
 */
public class OSSRangeIndexInput extends IndexInput {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    /**
     * The buffer size used when a budget {@link ByteBudget#tryReserve(long)} call fails — small
     * enough that we deliberately exempt it from accounting (i.e. {@code <=} this size always
     * succeeds, regardless of budget). 64 KB matches the historical {@link #DEFAULT_BUFFER_SIZE}
     * so a budget-pressured slice behaves exactly like a pre-budget slice did.
     */
    private static final int FALLBACK_BUFFER_SIZE = 64 * 1024;

    /** Bounded daemon pool shared across all instances. Sized for typical nprobe up to 16. */
    private static final ExecutorService PREFETCH_POOL = Executors.newFixedThreadPool(
        Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors())),
        new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "oss-range-prefetch-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    );

    private final OSS ossClient;
    private final boolean ownsClient;
    private final String bucket;
    private final String key;
    private final long baseOffset;
    private final long sliceLength;
    private final int bufferSize;
    /**
     * Memory accountant; {@link ByteBudget#UNLIMITED} when the caller didn't supply one. Slices
     * inherit the parent's budget so the entire archive's IndexInput tree shares a single cap.
     */
    private final ByteBudget budget;
    /** Bytes reserved against {@link #budget} for {@link #buffer}; released on {@link #close()}. */
    private final long reservedBytes;

    private byte[] buffer;
    private long bufferStart;
    private int bufferPos;
    private int bufferValid;
    private long position;
    private boolean closed;

    /** Async prefetch slot. The future, when complete, holds bytes covering [prefetchOffset, prefetchOffset+prefetchLength). */
    private volatile CompletableFuture<byte[]> prefetchFuture;
    private volatile long prefetchOffset = -1L;
    private volatile int prefetchLength;

    /**
     * Multi-slot prefetch: extra in-flight reads keyed by start offset. The single-slot
     * {@link #prefetchFuture} above remains the "primary" used by {@link #tryConsumePrefetch}
     * fast path; this map holds additional windows scheduled via
     * {@link #prefetchBulk(long[], int[])} so a caller can fan out N parallel OSS GETs
     * (DiskBBQ cluster posting list fetch). The slow path checks here on miss.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, PendingPrefetch> extraPrefetches =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** JDK11 source set — can't use {@code record}; plain immutable struct instead. */
    private static final class PendingPrefetch {
        final long offset;
        final int length;
        final CompletableFuture<byte[]> future;
        PendingPrefetch(long offset, int length, CompletableFuture<byte[]> future) {
            this.offset = offset;
            this.length = length;
            this.future = future;
        }
        long offset() { return offset; }
        int length() { return length; }
        CompletableFuture<byte[]> future() { return future; }
    }

    public OSSRangeIndexInput(
        String endpoint, String accessKeyId, String accessKeySecret,
        String bucket, String key
    ) throws IOException {
        this(endpoint, accessKeyId, accessKeySecret, bucket, key, DEFAULT_BUFFER_SIZE);
    }

    public OSSRangeIndexInput(
        String endpoint, String accessKeyId, String accessKeySecret,
        String bucket, String key, int bufferSize
    ) throws IOException {
        super("OSSRangeIndexInput(" + key + ")");
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.ownsClient = true;
        this.bucket = bucket;
        this.key = key;
        this.bufferSize = bufferSize;
        this.baseOffset = 0;
        // The standalone (own-client) ctor is used by Flink-side writer paths that don't have a
        // node-wide budget. Skip accounting entirely — UNLIMITED never refuses a reservation.
        this.budget = ByteBudget.UNLIMITED;
        this.reservedBytes = 0L;

        ObjectMetadata meta = ossClient.getObjectMetadata(bucket, key);
        this.sliceLength = meta.getContentLength();

        this.buffer = new byte[bufferSize];
        this.bufferStart = -1;
        this.bufferPos = 0;
        this.bufferValid = 0;
        this.position = 0;
    }

    /**
     * Open an IndexInput on an OSS object using a caller-managed {@link OSS} client.
     * The instance does not shut down the client on close; the caller controls its lifetime.
     * Useful when many IndexInputs share a single archive (oss_direct / hybrid storage).
     */
    public static OSSRangeIndexInput overSharedClient(
        String resourceDesc, OSS ossClient, String bucket, String key
    ) throws IOException {
        return overSharedClient(resourceDesc, ossClient, bucket, key, ByteBudget.UNLIMITED);
    }

    /**
     * Same as {@link #overSharedClient(String, OSS, String, String)} but binds the resulting
     * input (and every slice derived from it) to a {@link ByteBudget}. The budget caps the
     * total bytes the buffer trees can pin; a slice whose requested buffer would push the
     * total over the cap silently falls back to {@link #FALLBACK_BUFFER_SIZE} rather than
     * blocking the query.
     */
    public static OSSRangeIndexInput overSharedClient(
        String resourceDesc, OSS ossClient, String bucket, String key, ByteBudget budget
    ) throws IOException {
        ObjectMetadata meta = ossClient.getObjectMetadata(bucket, key);
        long length = meta.getContentLength();
        return new OSSRangeIndexInput(
            resourceDesc, ossClient, bucket, key, 0L, length, DEFAULT_BUFFER_SIZE, budget
        );
    }

    private OSSRangeIndexInput(
        String resourceDesc, OSS ossClient, String bucket, String key,
        long baseOffset, long sliceLength, int bufferSize, ByteBudget budget
    ) {
        super(resourceDesc);
        this.ossClient = ossClient;
        this.ownsClient = false;
        this.bucket = bucket;
        this.key = key;
        this.baseOffset = baseOffset;
        this.sliceLength = sliceLength;
        this.budget = budget != null ? budget : ByteBudget.UNLIMITED;

        // Reserve under the budget. Buffers at or below the fallback size are exempt — they
        // represent the pre-budget historical footprint, accounting them would just add noise
        // without bounding anything new. If the larger reservation fails (cap reached), drop
        // to FALLBACK_BUFFER_SIZE silently: search latency must not stall on budget pressure.
        int actualBufferSize;
        long reserved;
        if (bufferSize <= FALLBACK_BUFFER_SIZE) {
            actualBufferSize = bufferSize;
            reserved = 0L;
        } else if (this.budget.tryReserve(bufferSize)) {
            actualBufferSize = bufferSize;
            reserved = bufferSize;
        } else {
            actualBufferSize = FALLBACK_BUFFER_SIZE;
            reserved = 0L;
        }
        this.bufferSize = actualBufferSize;
        this.reservedBytes = reserved;

        this.buffer = new byte[actualBufferSize];
        this.bufferStart = -1;
        this.bufferPos = 0;
        this.bufferValid = 0;
        this.position = 0;
    }

    @Override
    public byte readByte() throws IOException {
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
        if (position + len > sliceLength) {
            throw new EOFException("read past EOF: pos=" + position + ", len=" + len + ", length=" + sliceLength);
        }
        int remaining = len;
        int destPos = offset;
        while (remaining > 0) {
            if (!bufferContains(position)) {
                if (remaining >= bufferSize) {
                    int toRead = remaining;
                    readRange(position, b, destPos, toRead);
                    position += toRead;
                    remaining -= toRead;
                    destPos += toRead;
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
        if (pos < 0 || pos > sliceLength) {
            throw new IllegalArgumentException("seek pos=" + pos + " out of range [0, " + sliceLength + "]");
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
        // Default slice: inherit the parent's buffer size. Lucene-internal callers (e.g.
        // CompoundFormat) go through this path and don't have a tuning opinion.
        return slice(sliceDescription, offset, length, this.bufferSize);
    }

    /**
     * Like {@link #slice(String, long, long)} but lets the caller pick the buffer size of the
     * returned slice. Used by paimon-store's Directory.openInput to give DiskBBQ cluster posting
     * files ({@code .clivf}) and raw-vector files ({@code .vec}) a much larger buffer than the
     * default 64 KB so a sequential scan does ~few HTTP Range Reads instead of hundreds.
     *
     * <p>The slice inherits the parent's {@link ByteBudget}; the actual buffer size is clamped
     * against the slice length and may be downgraded to {@link #FALLBACK_BUFFER_SIZE} if the
     * budget can't accommodate the request (see the private ctor).
     */
    public IndexInput slice(
        String sliceDescription, long offset, long length, int sliceBufferSize
    ) throws IOException {
        if (offset < 0 || length < 0 || offset + length > sliceLength) {
            throw new IllegalArgumentException(
                "slice(offset=" + offset + ", length=" + length + ") out of range [0, " + sliceLength + ")");
        }
        int effectiveBufferSize = Math.min(sliceBufferSize, (int) Math.min(length, Integer.MAX_VALUE));
        return new OSSRangeIndexInput(
            sliceDescription, ossClient, bucket, key,
            baseOffset + offset, length,
            effectiveBufferSize,
            this.budget
        );
    }

    /**
     * Schedule an asynchronous Range Read covering [offset, offset+length) on a shared
     * prefetch pool. A subsequent read whose buffer fill falls entirely inside the
     * scheduled window will wait on the future instead of issuing a new GET.
     *
     * <p>Single-slot: a new prefetch supersedes any in-flight one.
     */
    public void prefetch(long offset, long length) throws IOException {
        if (closed) return;
        if (offset < 0 || length <= 0 || offset >= sliceLength) return;
        long clamped = Math.min(length, sliceLength - offset);
        if (clamped > Integer.MAX_VALUE) return;
        int len = (int) clamped;
        // Skip if exactly the same window is already pending.
        if (prefetchFuture != null && prefetchOffset == offset && prefetchLength == len) {
            return;
        }
        final long capturedOffset = offset;
        final int capturedLen = len;
        CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
            byte[] buf = new byte[capturedLen];
            try {
                readRange(capturedOffset, buf, 0, capturedLen);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return buf;
        }, PREFETCH_POOL);
        this.prefetchOffset = capturedOffset;
        this.prefetchLength = capturedLen;
        this.prefetchFuture = f;
    }

    /**
     * Bulk prefetch — schedule N asynchronous Range Reads in parallel, one per (offset,length)
     * pair. Used by DiskBBQ's centroid iterator to fan out cluster posting list fetches
     * before the serial scoring loop starts, so the scorer's per-cluster waits-on-bytes
     * happen against already-in-flight downloads. The default single-slot
     * {@link #prefetch(long, long)} is still set to the FIRST window for back-compat
     * with {@link #tryConsumePrefetch}.
     */
    public void prefetchBulk(long[] offsets, int[] lengths) {
        if (closed || offsets == null || lengths == null || offsets.length == 0) return;
        if (offsets.length != lengths.length) {
            throw new IllegalArgumentException("offsets/lengths length mismatch");
        }
        for (int i = 0; i < offsets.length; i++) {
            long off = offsets[i];
            int len = lengths[i];
            if (off < 0 || len <= 0 || off >= sliceLength) continue;
            long clamped = Math.min(len, sliceLength - off);
            if (clamped > Integer.MAX_VALUE) continue;
            final int actualLen = (int) clamped;
            final long capturedOff = off;
            // Skip if we already have a pending window at this offset.
            if (extraPrefetches.containsKey(capturedOff)) continue;
            if (prefetchFuture != null && prefetchOffset == capturedOff && prefetchLength == actualLen) continue;
            CompletableFuture<byte[]> f = CompletableFuture.supplyAsync(() -> {
                byte[] buf = new byte[actualLen];
                try {
                    readRange(capturedOff, buf, 0, actualLen);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return buf;
            }, PREFETCH_POOL);
            extraPrefetches.put(capturedOff, new PendingPrefetch(capturedOff, actualLen, f));
        }
    }

    /** Try to satisfy a fillBuffer at {@code pos} from the pending prefetch slot. */
    private boolean tryConsumePrefetch(long pos) throws IOException {
        // Fast path: primary single slot.
        CompletableFuture<byte[]> f = this.prefetchFuture;
        if (f != null) {
            long pStart = this.prefetchOffset;
            int pLen = this.prefetchLength;
            long needEnd = Math.min(pos + bufferSize, sliceLength);
            if (pos >= pStart && needEnd <= pStart + pLen) {
                return drainAndFill(f, pos, pStart, pLen, /* primary */ true, 0L);
            }
        }
        // Slow path: search the bulk-prefetch map for a window that contains [pos, pos+bufferSize).
        // Typical caller (DiskBBQ cluster scan) requests bytes at the start of each cluster's
        // posting list; the map size is O(N_PROBE) (~8), so linear scan is fine.
        long needEndGlobal = Math.min(pos + bufferSize, sliceLength);
        for (PendingPrefetch p : extraPrefetches.values()) {
            if (pos >= p.offset() && needEndGlobal <= p.offset() + p.length()) {
                return drainAndFill(p.future(), pos, p.offset(), p.length(), /* primary */ false, p.offset());
            }
        }
        return false;
    }

    /**
     * Resolve a prefetched future into the read buffer; on success the primary slot or the
     * extras map entry that backed it is cleared so memory can be released.
     */
    private boolean drainAndFill(CompletableFuture<byte[]> f, long pos, long pStart, int pLen,
                                  boolean primary, long extrasKey) throws IOException {
        try {
            byte[] data = f.get();
            int srcOffset = (int) (pos - pStart);
            int valid = Math.min(bufferSize, data.length - srcOffset);
            if (valid > buffer.length) {
                buffer = new byte[Math.max(bufferSize, valid)];
            }
            System.arraycopy(data, srcOffset, buffer, 0, valid);
            bufferStart = pos;
            bufferPos = 0;
            bufferValid = valid;
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ee) {
            return false;
        } finally {
            if (primary) {
                this.prefetchFuture = null;
                this.prefetchOffset = -1L;
                this.prefetchLength = 0;
            } else {
                extraPrefetches.remove(extrasKey);
            }
        }
    }

    @Override
    public IndexInput clone() {
        OSSRangeIndexInput cloned = new OSSRangeIndexInput(
            getFullSliceDescription(toString()), ossClient, bucket, key,
            baseOffset, sliceLength, bufferSize, this.budget
        );
        cloned.position = this.position;
        return cloned;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        buffer = null;
        // Release the budget reservation taken at ctor time. {@link ByteBudget#release} clamps
        // against under-flow, so a double-close (unlikely with the closed-flag guard above) is
        // safe; passing {@code reservedBytes == 0} is a no-op for both branches.
        if (reservedBytes > 0L) {
            budget.release(reservedBytes);
        }
        CompletableFuture<byte[]> f = this.prefetchFuture;
        if (f != null) {
            f.cancel(false);
            this.prefetchFuture = null;
        }
        if (ownsClient) {
            ossClient.shutdown();
        }
    }

    private boolean bufferContains(long pos) {
        return bufferStart >= 0 && pos >= bufferStart && pos < bufferStart + bufferValid;
    }

    private void fillBuffer(long pos) throws IOException {
        if (tryConsumePrefetch(pos)) {
            return;
        }
        long absPos = baseOffset + pos;
        long end = Math.min(absPos + bufferSize, baseOffset + sliceLength);
        int toRead = (int) (end - absPos);
        readRange(pos, buffer, 0, toRead);
        bufferStart = pos;
        bufferPos = 0;
        bufferValid = toRead;
    }

    private void readRange(long relativePos, byte[] dest, int destOffset, int length) throws IOException {
        long absStart = baseOffset + relativePos;
        long absEnd = absStart + length - 1;
        GetObjectRequest req = new GetObjectRequest(bucket, key);
        req.setRange(absStart, absEnd);
        try (OSSObject obj = ossClient.getObject(req)) {
            InputStream in = obj.getObjectContent();
            int remaining = length;
            int pos = destOffset;
            while (remaining > 0) {
                int read = in.read(dest, pos, remaining);
                if (read == -1) {
                    throw new EOFException("OSS Range Read returned short data: expected " + length
                        + " bytes from offset " + absStart + ", got " + (length - remaining));
                }
                pos += read;
                remaining -= read;
            }
        }
    }
}
