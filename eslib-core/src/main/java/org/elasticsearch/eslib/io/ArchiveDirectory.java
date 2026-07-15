/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.io;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.NoLockFactory;
import org.elasticsearch.eslib.api.ArchiveDataProvider;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only Lucene {@link Directory} backed by an archive file accessed via
 * {@link ArchiveDataProvider}. Each logical file is mapped to an (offset, length)
 * range within the archive.
 *
 * <p>Each {@link #openInput} call forks the provider so the returned IndexInput
 * is independent and safe for concurrent use on different threads.
 */
public class ArchiveDirectory extends Directory {

    private final ArchiveDataProvider provider;
    private final Map<String, long[]> fileOffsets;
    private volatile boolean closed;
    private boolean providerClosed;

    /**
     * @param provider    root provider for the archive
     * @param fileOffsets map of filename → [offset, length] within the archive
     */
    public ArchiveDirectory(ArchiveDataProvider provider, Map<String, long[]> fileOffsets) {
        this.provider = Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(fileOffsets, "fileOffsets");
        Map<String, long[]> offsetsCopy = new LinkedHashMap<>(fileOffsets.size());
        for (Map.Entry<String, long[]> entry : fileOffsets.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "file name");
            long[] range = entry.getValue();
            if (range == null
                    || range.length != 2
                    || range[0] < 0
                    || range[1] < 0
                    || range[0] > Long.MAX_VALUE - range[1]) {
                throw new IllegalArgumentException(
                        "Invalid archive range for file '" + name + "'");
            }
            offsetsCopy.put(name, range.clone());
        }
        this.fileOffsets = Collections.unmodifiableMap(offsetsCopy);
    }

    @Override
    public String[] listAll() {
        ensureOpen();
        return fileOffsets.keySet().toArray(new String[0]);
    }

    @Override
    public long fileLength(String name) throws IOException {
        ensureOpen();
        long[] entry = getEntry(name);
        return entry[1];
    }

    @Override
    public synchronized IndexInput openInput(String name, IOContext context) throws IOException {
        if (closed) {
            throw new IOException("ArchiveDirectory is closed");
        }
        long[] entry = getEntry(name);
        long offset = entry[0];
        long length = entry[1];
        ArchiveDataProvider forked = provider.fork();
        if (forked == null) {
            throw new IOException("Archive provider fork returned null for file '" + name + "'");
        }
        return new ArchiveIndexInput("file=" + name, forked, offset, length);
    }

    @Override
    public Set<String> getPendingDeletions() {
        return Collections.emptySet();
    }

    @Override
    public Lock obtainLock(String name) throws IOException {
        ensureOpen();
        return NoLockFactory.INSTANCE.obtainLock(this, name);
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (providerClosed) {
            return;
        }
        provider.close();
        providerClosed = true;
    }

    // ---- Read-only: writes not supported ----

    @Override
    public IndexOutput createOutput(String name, IOContext context) {
        throw new UnsupportedOperationException("ArchiveDirectory is read-only");
    }

    @Override
    public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) {
        throw new UnsupportedOperationException("ArchiveDirectory is read-only");
    }

    @Override
    public void deleteFile(String name) {
        throw new UnsupportedOperationException("ArchiveDirectory is read-only");
    }

    @Override
    public void rename(String source, String dest) {
        throw new UnsupportedOperationException("ArchiveDirectory is read-only");
    }

    @Override
    public void sync(Collection<String> names) {}

    @Override
    public void syncMetaData() {}

    private long[] getEntry(String name) throws IOException {
        long[] entry = fileOffsets.get(name);
        if (entry == null) {
            throw new NoSuchFileException(name);
        }
        return entry;
    }

    @Override
    protected void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ArchiveDirectory is closed");
        }
    }
}
