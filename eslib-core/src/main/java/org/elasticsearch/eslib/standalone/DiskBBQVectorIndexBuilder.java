/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.elasticsearch.eslib.diskbbq.DiskBBQCodec;
import org.elasticsearch.eslib.diskbbq.VectorIndexBuilder;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;
import org.elasticsearch.eslib.diskbbq.VectorIndexParts;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * DiskBBQ vector index builder implementation.
 * Uses Lucene IndexWriter with DiskBBQ codec to build the index.
 */
public class DiskBBQVectorIndexBuilder implements VectorIndexBuilder {

    /**
     * RAM buffer cap for the underlying Lucene IndexWriter. Vectors stay on heap as
     * {@code List<float[]>} until this threshold is hit, then flush to a new on-disk segment.
     * Default Lucene 10 RAMBufferSizeMB is ~1.9 GB; for big-vector workloads (e.g. 1M × 1024 dim
     * ~= 4 GB raw) that crashes addVector with OOM. Cap to 256 MB so flush kicks in early and
     * memory is bounded.
     *
     * <p>Per-segment flush also writes a per-segment IVF index that forceMerge later discards;
     * that cost is accepted as a tradeoff to bound heap. See record_streaming_open_questions.md
     * for the optimization to skip per-segment IVF building.
     */
    private static final int RAM_BUFFER_MB = 256;

    private final VectorIndexConfig config;
    private final boolean ownsTmpDir;
    private Path tmpDir;
    private MMapDirectory directory;
    private IndexWriter writer;
    private int count;
    private boolean built;
    private boolean closed;

    /** Auto-allocates a temp working directory under {@code java.io.tmpdir}. */
    public DiskBBQVectorIndexBuilder(VectorIndexConfig config) throws IOException {
        this(config, Files.createTempDirectory("diskbbq-build-"), true);
    }

    /**
     * Use an externally-provided working directory (caller-owned). Lucene IndexWriter writes
     * forceMerge output here directly so callers can read/upload from this directory without
     * an intermediate {@code Files.copy} step.
     */
    public DiskBBQVectorIndexBuilder(VectorIndexConfig config, Path workDir) throws IOException {
        this(config, workDir, false);
    }

    private DiskBBQVectorIndexBuilder(VectorIndexConfig config, Path workDir, boolean ownsTmpDir)
        throws IOException {
        this.config = config;
        this.tmpDir = workDir;
        this.ownsTmpDir = ownsTmpDir;
        this.directory = new MMapDirectory(tmpDir, NoLockFactory.INSTANCE);

        IndexWriterConfig iwc = new IndexWriterConfig();
        iwc.setCodec(new DiskBBQCodec(config.vectorsPerCluster(), config.centroidsPerParentCluster()));
        iwc.setUseCompoundFile(false);
        iwc.setRAMBufferSizeMB(RAM_BUFFER_MB);
        this.writer = new IndexWriter(directory, iwc);
        this.count = 0;
        this.built = false;
        this.closed = false;
    }

    /** The directory where forceMerge output lives. Caller can pack/upload directly from here. */
    public Path workDir() {
        return tmpDir;
    }

    @Override
    public void addVector(long id, float[] vector) throws IOException {
        if (built) {
            throw new IllegalStateException("Cannot add vectors after build()");
        }
        if (vector.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Dimension mismatch: expected " + config.dimension() + ", got " + vector.length
            );
        }
        Document doc = new Document();
        doc.add(new KnnFloatVectorField(config.fieldName(), vector, config.similarity()));
        writer.addDocument(doc);
        count++;
    }

    @Override
    public void build() throws IOException {
        if (built) {
            throw new IllegalStateException("build() already called");
        }
        built = true;
        if (count > 0) {
            writer.forceMerge(1);
            writer.commit();
        }
        writer.close();
        writer = null;
    }

    @Override
    public void writeToDir(Path targetDir) throws IOException {
        if (!built) {
            throw new IllegalStateException("Must call build() before writeToDir()");
        }
        // Fast path: if caller passed our own workDir back, the files are already there — skip
        // the redundant Files.copy. This is the common path when ESVectorGlobalIndexWriter
        // pre-allocates tempIndexDir and constructs the builder with it.
        if (targetDir.equals(tmpDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    Files.copy(entry, targetDir.resolve(entry.getFileName().toString()));
                }
            }
        }
    }

    @Override
    public VectorIndexParts writeParts(Path targetDir) throws IOException {
        if (!built) {
            throw new IllegalStateException("Must call build() before writeParts()");
        }
        Path metaFile = null, centroidFile = null, clusterFile = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".mivf")) {
                    metaFile = targetDir.resolve(name);
                    Files.copy(entry, metaFile);
                } else if (name.endsWith(".cenivf")) {
                    centroidFile = targetDir.resolve(name);
                    Files.copy(entry, centroidFile);
                } else if (name.endsWith(".clivf")) {
                    clusterFile = targetDir.resolve(name);
                    Files.copy(entry, clusterFile);
                }
            }
        }
        if (metaFile == null || centroidFile == null || clusterFile == null) {
            throw new IOException("IVF files not found in build directory. Found: meta="
                + metaFile + " centroid=" + centroidFile + " cluster=" + clusterFile);
        }
        return new VectorIndexParts(metaFile, centroidFile, clusterFile);
    }

    public int getCount() {
        return count;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
        if (directory != null) {
            try {
                directory.close();
            } catch (IOException ignored) {}
            directory = null;
        }
        if (tmpDir != null && ownsTmpDir) {
            try {
                Files.walk(tmpDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
        }
        tmpDir = null;
    }
}
