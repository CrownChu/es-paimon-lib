/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.standalone;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.ByteBuffersDataInput;
import org.apache.lucene.store.ByteBuffersIndexInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.MMapDirectory;

import org.elasticsearch.eslib.diskbbq.VectorIndexCentroidReader;
import org.elasticsearch.eslib.diskbbq.DiskBBQCentroidReader;
import org.elasticsearch.eslib.io.OSSRangeIndexInput;
import org.elasticsearch.eslib.diskbbq.ClusterReference;
import org.elasticsearch.eslib.diskbbq.SearchResult;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade for two-stage vector search with configurable I/O mode.
 * Manages the lifecycle of IndexInput/Directory resources.
 */
public class VectorSearchSession implements Closeable {

    private final StorageMode mode;
    private final VectorIndexConfig config;
    private final DiskBBQCentroidReader centroidReader;
    private final IndexInput clusterInput;
    private final List<Closeable> ownedResources;

    private VectorSearchSession(
        StorageMode mode,
        VectorIndexConfig config,
        DiskBBQCentroidReader centroidReader,
        IndexInput clusterInput,
        List<Closeable> ownedResources
    ) {
        this.mode = mode;
        this.config = config;
        this.centroidReader = centroidReader;
        this.clusterInput = clusterInput;
        this.ownedResources = ownedResources;
    }

    public static VectorSearchSession fromLocalFiles(
        Path metaFile, Path centroidFile, Path clusterFile,
        VectorIndexConfig config, StorageMode mode
    ) throws IOException {
        if (mode == StorageMode.OSS_DIRECT) {
            throw new IllegalArgumentException("Use fromOSS() for OSS_DIRECT mode");
        }

        List<Closeable> resources = new ArrayList<>();
        IndexInput metaInput;
        IndexInput centroidInput;
        IndexInput clusterInput;

        if (mode == StorageMode.MMAP) {
            MMapDirectory metaDir = new MMapDirectory(metaFile.getParent());
            resources.add(metaDir);
            metaInput = metaDir.openInput(metaFile.getFileName().toString(), IOContext.DEFAULT);
            resources.add(metaInput);

            MMapDirectory centroidDir = metaDir;
            centroidInput = centroidDir.openInput(centroidFile.getFileName().toString(), IOContext.DEFAULT);
            resources.add(centroidInput);

            MMapDirectory clusterDir = new MMapDirectory(clusterFile.getParent());
            if (!clusterFile.getParent().equals(metaFile.getParent())) {
                resources.add(clusterDir);
            }
            clusterInput = clusterDir.openInput(clusterFile.getFileName().toString(), IOContext.DEFAULT);
            resources.add(clusterInput);
        } else {
            byte[] metaBytes = Files.readAllBytes(metaFile);
            metaInput = new ByteBuffersIndexInput(
                new ByteBuffersDataInput(List.of(ByteBuffer.wrap(metaBytes))), "metaData");
            resources.add(metaInput);

            byte[] centroidBytes = Files.readAllBytes(centroidFile);
            centroidInput = new ByteBuffersIndexInput(
                new ByteBuffersDataInput(List.of(ByteBuffer.wrap(centroidBytes))), "centroidData");
            resources.add(centroidInput);

            byte[] clusterBytes = Files.readAllBytes(clusterFile);
            clusterInput = new ByteBuffersIndexInput(
                new ByteBuffersDataInput(List.of(ByteBuffer.wrap(clusterBytes))), "clusterData");
            resources.add(clusterInput);
        }

        DiskBBQCentroidReader reader = new DiskBBQCentroidReader();
        resources.add(reader);
        reader.load(metaInput, centroidInput, config);

        return new VectorSearchSession(mode, config, reader, clusterInput, resources);
    }

    public static VectorSearchSession fromBytes(
        byte[] metaData, byte[] centroidData, byte[] clusterData,
        VectorIndexConfig config
    ) throws IOException {
        List<Closeable> resources = new ArrayList<>();

        IndexInput metaInput = new ByteBuffersIndexInput(
            new ByteBuffersDataInput(List.of(ByteBuffer.wrap(metaData))), "metaData");
        resources.add(metaInput);

        IndexInput centroidInput = new ByteBuffersIndexInput(
            new ByteBuffersDataInput(List.of(ByteBuffer.wrap(centroidData))), "centroidData");
        resources.add(centroidInput);

        IndexInput clusterInput = new ByteBuffersIndexInput(
            new ByteBuffersDataInput(List.of(ByteBuffer.wrap(clusterData))), "clusterData");
        resources.add(clusterInput);

        DiskBBQCentroidReader reader = new DiskBBQCentroidReader();
        resources.add(reader);
        reader.load(metaInput, centroidInput, config);

        return new VectorSearchSession(StorageMode.HEAP, config, reader, clusterInput, resources);
    }

    public static VectorSearchSession fromOSS(
        String endpoint, String bucket, String accessKeyId, String accessKeySecret,
        String metaKey, String centroidKey, String clusterKey,
        VectorIndexConfig config
    ) throws IOException {
        List<Closeable> resources = new ArrayList<>();

        OSSRangeIndexInput metaInput = new OSSRangeIndexInput(
            endpoint, accessKeyId, accessKeySecret, bucket, metaKey);
        resources.add(metaInput);

        OSSRangeIndexInput centroidInput = new OSSRangeIndexInput(
            endpoint, accessKeyId, accessKeySecret, bucket, centroidKey);
        resources.add(centroidInput);

        OSSRangeIndexInput clusterInput = new OSSRangeIndexInput(
            endpoint, accessKeyId, accessKeySecret, bucket, clusterKey);
        resources.add(clusterInput);

        DiskBBQCentroidReader reader = new DiskBBQCentroidReader();
        resources.add(reader);
        reader.load(metaInput, centroidInput, config);

        return new VectorSearchSession(StorageMode.OSS_DIRECT, config, reader, clusterInput, resources);
    }

    public SearchResult search(float[] queryVector, int topK, int nProbe, long[] candidateIds) throws IOException {
        return search(queryVector, topK, nProbe, candidateIds, null);
    }

    /**
     * Two-phase search with optional rescore.
     * <p>
     * If {@code rescore} is non-null, phase-1 collects {@code topK * oversampleFactor}
     * quantized candidates per cluster; after merge, phase-2 fetches raw vectors via
     * {@link RescoreConfig#provider()} and reranks to {@code topK}.
     */
    public SearchResult search(float[] queryVector, int topK, int nProbe, long[] candidateIds, RescoreConfig rescore)
        throws IOException {
        List<ClusterReference> clusters = centroidReader.findNearestClusters(queryVector, nProbe);
        if (clusters.isEmpty()) {
            return new SearchResult(new long[0], new float[0], 0);
        }

        int phase1TopK = (rescore == null) ? topK : topK * rescore.oversampleFactor();

        List<SearchResult> clusterResults = new ArrayList<>(clusters.size());
        try (DiskBBQClusterSearcher searcher = new DiskBBQClusterSearcher()) {
            searcher.init(config);
            for (ClusterReference ref : clusters) {
                IndexInput slice = clusterInput.slice(
                    "cluster-" + ref.clusterId(), ref.offset(), ref.length());
                clusterResults.add(searcher.searchCluster(slice, queryVector, phase1TopK, candidateIds));
            }
        }

        SearchResult merged = VectorIndexClusterSearcher.mergeResults(clusterResults, phase1TopK);
        if (rescore == null || merged.count == 0) {
            return merged;
        }
        return rerankWithRawVectors(merged, queryVector, topK, rescore);
    }

    private SearchResult rerankWithRawVectors(
        SearchResult candidates, float[] queryVector, int topK, RescoreConfig rescore
    ) throws IOException {
        return rerankWithRawVectors(candidates, queryVector, topK, rescore, config.similarity());
    }

    /**
     * Re-rank candidates by computing exact distance against raw vectors fetched via the provider.
     * Candidates with no available raw vector keep their original quantized score.
     * <p>
     * Exposed as a public static helper so hosts that drive their own search loop (without
     * {@link VectorSearchSession}) can still reuse the rerank algorithm.
     */
    public static SearchResult rerankWithRawVectors(
        SearchResult candidates, float[] queryVector, int topK,
        RescoreConfig rescore, VectorSimilarityFunction similarity
    ) throws IOException {
        int n = candidates.count;
        long[] ids = new long[n];
        float[] scores = new float[n];
        System.arraycopy(candidates.ids, 0, ids, 0, n);
        System.arraycopy(candidates.scores, 0, scores, 0, n);

        for (int i = 0; i < n; i++) {
            float[] raw = rescore.provider().getVector(ids[i]);
            if (raw != null) {
                scores[i] = similarity.compare(queryVector, raw);
            }
        }

        // selection sort to top-K (n is small: topK*oversample, typically <= a few hundred)
        int k = Math.min(topK, n);
        for (int i = 0; i < k; i++) {
            int best = i;
            for (int j = i + 1; j < n; j++) {
                if (scores[j] > scores[best]) best = j;
            }
            if (best != i) {
                float ts = scores[i]; scores[i] = scores[best]; scores[best] = ts;
                long ti = ids[i]; ids[i] = ids[best]; ids[best] = ti;
            }
        }

        long[] outIds = new long[k];
        float[] outScores = new float[k];
        System.arraycopy(ids, 0, outIds, 0, k);
        System.arraycopy(scores, 0, outScores, 0, k);
        return new SearchResult(outIds, outScores, k);
    }

    public StorageMode storageMode() {
        return mode;
    }

    public VectorIndexCentroidReader centroidReader() {
        return centroidReader;
    }

    @Override
    public void close() throws IOException {
        Throwable first = null;
        for (int i = ownedResources.size() - 1; i >= 0; i--) {
            try {
                ownedResources.get(i).close();
            } catch (Throwable t) {
                if (first == null) first = t;
                else first.addSuppressed(t);
            }
        }
        ownedResources.clear();
        if (first != null) {
            if (first instanceof IOException) {
                throw (IOException) first;
            }
            throw new RuntimeException(first);
        }
    }
}
