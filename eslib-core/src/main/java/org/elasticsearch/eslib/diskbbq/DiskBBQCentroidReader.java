/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.ByteBuffersDataInput;
import org.apache.lucene.store.ByteBuffersIndexInput;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.eslib.diskbbq.ClusterReference;
import org.elasticsearch.eslib.diskbbq.VectorIndexCentroidReader;
import org.elasticsearch.eslib.diskbbq.VectorIndexConfig;
import org.elasticsearch.eslib.cluster.NeighborQueue;
import org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat;
import org.elasticsearch.eslib.diskbbq.OptimizedScalarQuantizer;
import org.elasticsearch.eslib.compat.ES92Int7VectorsScorer;
import org.elasticsearch.eslib.compat.ESVectorUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS;

/**
 * Standalone centroid reader that works with raw .mivf + .cenivf file bytes.
 * Reuses centroid scoring algorithms from ES920DiskBBQVectorsReader.
 */
public class DiskBBQCentroidReader implements VectorIndexCentroidReader {

    private int numCentroids;
    private long postingListOffset;
    private long centroidOffset;
    private float[] globalCentroid;
    private float globalCentroidDp;
    private VectorSimilarityFunction similarity;
    private int dimension;

    private IndexInput centroidInput;
    private boolean ownsCentroidInput;
    private long centroidDataStart;
    private boolean loaded;

    @Override
    public void load(IndexInput metaInput, IndexInput centroidInput, VectorIndexConfig config) throws IOException {
        this.dimension = config.dimension();
        this.similarity = config.similarity();

        parseMeta(metaInput);

        this.centroidInput = centroidInput;
        this.ownsCentroidInput = false;

        skipIndexHeader(centroidInput, PaimonDiskBBQVectorsFormat.NAME);
        this.centroidDataStart = this.centroidOffset;
        this.loaded = true;
    }

    public void load(byte[] metaData, byte[] centroidData, VectorIndexConfig config) throws IOException {
        this.dimension = config.dimension();
        this.similarity = config.similarity();

        ByteBuffersDataInput metaBbdi = new ByteBuffersDataInput(List.of(ByteBuffer.wrap(metaData)));
        try (IndexInput metaInput = new ByteBuffersIndexInput(metaBbdi, "metaData")) {
            parseMeta(metaInput);
        }

        ByteBuffersDataInput bbdi = new ByteBuffersDataInput(List.of(ByteBuffer.wrap(centroidData)));
        this.centroidInput = new ByteBuffersIndexInput(bbdi, "centroidData");
        this.ownsCentroidInput = true;

        skipIndexHeader(centroidInput, PaimonDiskBBQVectorsFormat.NAME);
        this.centroidDataStart = this.centroidOffset;
        this.loaded = true;
    }

    private void parseMeta(IndexInput meta) throws IOException {
        int version = skipIndexHeader(meta, PaimonDiskBBQVectorsFormat.NAME);

        int fieldNumber = meta.readInt();
        if (fieldNumber == -1) {
            throw new IOException("No field data in meta file");
        }

        // rawVectorFormat
        meta.readString();
        // useDirectIOReads (version >= VERSION_DIRECT_IO)
        if (version >= PaimonDiskBBQVectorsFormat.VERSION_DIRECT_IO) {
            meta.readByte();
        }
        // vectorEncoding
        meta.readInt();
        // similarityFunction
        int simOrd = meta.readInt();
        if (simOrd >= 0 && simOrd < SIMILARITY_FUNCTIONS.size()) {
            this.similarity = SIMILARITY_FUNCTIONS.get(simOrd);
        }

        this.numCentroids = meta.readInt();
        this.centroidOffset = meta.readLong();
        long centroidLength = meta.readLong();

        if (centroidLength > 0) {
            this.postingListOffset = meta.readLong();
            long postingListLength = meta.readLong();

            this.globalCentroid = new float[dimension];
            meta.readFloats(globalCentroid, 0, dimension);
            this.globalCentroidDp = Float.intBitsToFloat(meta.readInt());
        } else {
            // Legitimate empty field. The writer (IVFVectorsWriter) emits centroidLength==0 with
            // numCentroids==0 for a segment that has no centroids — e.g. a non-float field, or a
            // merge whose merged field had no live vectors / produced no clusters (it guards
            // numVectors==0 and numCentroids==0 explicitly). The ES reference reader
            // (IVFVectorsReader.readField) handles this by leaving postingListOffset/globalCentroid
            // at defaults and reporting numCentroids==0 rather than failing; mirror that here so the
            // segment can still be opened (e.g. for a merge that re-reads its raw vectors). Throwing
            // here breaks getReaderForMerge and aborts the whole merge.
            this.postingListOffset = -1;
            this.globalCentroid = new float[dimension];
            this.globalCentroidDp = 0f;
            // numCentroids is already 0 in this branch.
        }
    }

    @Override
    public List<ClusterReference> findNearestClusters(float[] queryVector, int topN) throws IOException {
        if (!loaded) {
            throw new IllegalStateException("Must call load() before findNearestClusters()");
        }

        // Empty field (centroidLength==0 / numCentroids==0): nothing to search in this segment.
        if (numCentroids == 0) {
            return new ArrayList<>();
        }

        int effectiveTopN = Math.min(topN, numCentroids);

        // quantize query vector against globalCentroid (7-bit)
        OptimizedScalarQuantizer quantizer = new OptimizedScalarQuantizer(similarity);
        int[] scratch = new int[dimension];
        OptimizedScalarQuantizer.QuantizationResult queryParams = quantizer.scalarQuantize(
            queryVector, new float[dimension], scratch, (byte) 7, globalCentroid
        );
        byte[] quantized = new byte[dimension];
        for (int i = 0; i < dimension; i++) {
            quantized[i] = (byte) scratch[i];
        }

        // position at start of centroid data
        centroidInput.seek(centroidDataStart);

        ES92Int7VectorsScorer scorer = ESVectorUtil.getES92Int7VectorsScorer(centroidInput, dimension);
        centroidInput.seek(centroidDataStart);

        int numParents = centroidInput.readVInt();

        List<ClusterReference> results;
        if (numParents > 0) {
            results = findWithParents(numParents, effectiveTopN, scorer, quantized, queryParams);
        } else {
            results = findWithoutParents(effectiveTopN, scorer, quantized, queryParams);
        }
        return results;
    }

    private List<ClusterReference> findWithoutParents(
        int topN,
        ES92Int7VectorsScorer scorer,
        byte[] quantized,
        OptimizedScalarQuantizer.QuantizationResult queryParams
    ) throws IOException {
        NeighborQueue queue = new NeighborQueue(numCentroids, true);
        float[] scores = new float[ES92Int7VectorsScorer.BULK_SIZE];
        score(queue, numCentroids, 0, scorer, quantized, queryParams, globalCentroidDp, similarity, scores);

        // read offset table position: after all quantized centroids
        long offsetTableStart = centroidInput.getFilePointer();

        int resultCount = Math.min(topN, queue.size());
        List<ClusterReference> results = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount && queue.size() > 0; i++) {
            float s = queue.topScore();
            int centroidOrd = queue.pop();
            centroidInput.seek(offsetTableStart + (long) Long.BYTES * 2 * centroidOrd);
            long relativeOffset = centroidInput.readLong();
            long length = centroidInput.readLong();
            results.add(new ClusterReference(centroidOrd, postingListOffset + relativeOffset, length, s));
        }
        return results;
    }

    private List<ClusterReference> findWithParents(
        int numParents,
        int topN,
        ES92Int7VectorsScorer scorer,
        byte[] quantized,
        OptimizedScalarQuantizer.QuantizationResult queryParams
    ) throws IOException {
        int maxChildrenSize = centroidInput.readVInt();

        NeighborQueue parentsQueue = new NeighborQueue(numParents, true);
        NeighborQueue currentParentQueue = new NeighborQueue(maxChildrenSize, true);

        float centroidOversampling = (float) numCentroids / (2 * numParents);
        int bufferSize = (int) Math.min(Math.max(centroidOversampling * numCentroids, 1), numCentroids);
        bufferSize = Math.min(bufferSize, topN * 4);
        bufferSize = Math.max(bufferSize, topN);
        NeighborQueue neighborQueue = new NeighborQueue(bufferSize, true);

        float[] scores = new float[ES92Int7VectorsScorer.BULK_SIZE];

        // score parents
        score(parentsQueue, numParents, 0, scorer, quantized, queryParams, globalCentroidDp, similarity, scores);

        long centroidQuantizeSize = dimension + 3 * Float.BYTES + Integer.BYTES;
        long parentTableOffset = centroidInput.getFilePointer();
        long childrenOffset = parentTableOffset + (long) Long.BYTES * numParents;

        // wait, the parent table stores (int childrenOrdinal, int numChildren) per parent = 2*4 bytes
        // Let me re-check... In writeCentroidsWithParents:
        // for each parent: centroidOutput.writeInt(offset), centroidOutput.writeInt(numChildren)
        childrenOffset = parentTableOffset + (long) Integer.BYTES * 2 * numParents;

        // populate children queue
        while (parentsQueue.size() > 0 && neighborQueue.size() < bufferSize) {
            int parentOrd = parentsQueue.pop();
            populateChildren(
                currentParentQueue, centroidInput,
                parentTableOffset + (long) Integer.BYTES * 2 * parentOrd,
                childrenOffset, centroidQuantizeSize,
                scorer, quantized, queryParams, scores
            );
            while (currentParentQueue.size() > 0 && neighborQueue.size() < bufferSize) {
                float s = currentParentQueue.topScore();
                int child = currentParentQueue.pop();
                neighborQueue.add(child, s);
            }
        }

        // read offset table: after all children quantized vectors
        long childrenFileOffsets = childrenOffset + centroidQuantizeSize * numCentroids;

        int resultCount = Math.min(topN, neighborQueue.size());
        List<ClusterReference> results = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount && neighborQueue.size() > 0; i++) {
            float s = neighborQueue.topScore();
            int centroidOrd = neighborQueue.pop();
            centroidInput.seek(childrenFileOffsets + (long) Long.BYTES * 2 * centroidOrd);
            long relativeOffset = centroidInput.readLong();
            long length = centroidInput.readLong();
            results.add(new ClusterReference(centroidOrd, postingListOffset + relativeOffset, length, s));
        }
        return results;
    }

    private void populateChildren(
        NeighborQueue queue,
        IndexInput centroids,
        long parentOffset,
        long childrenOffset,
        long centroidQuantizeSize,
        ES92Int7VectorsScorer scorer,
        byte[] quantized,
        OptimizedScalarQuantizer.QuantizationResult queryParams,
        float[] scores
    ) throws IOException {
        centroids.seek(parentOffset);
        int childrenOrdinal = centroids.readInt();
        int numChildren = centroids.readInt();
        centroids.seek(childrenOffset + centroidQuantizeSize * childrenOrdinal);
        score(queue, numChildren, childrenOrdinal, scorer, quantized, queryParams, globalCentroidDp, similarity, scores);
    }

    private static void score(
        NeighborQueue queue,
        int size,
        int scoresOffset,
        ES92Int7VectorsScorer scorer,
        byte[] quantized,
        OptimizedScalarQuantizer.QuantizationResult queryParams,
        float centroidDp,
        VectorSimilarityFunction similarityFunction,
        float[] scores
    ) throws IOException {
        int limit = size - ES92Int7VectorsScorer.BULK_SIZE + 1;
        int i = 0;
        for (; i < limit; i += ES92Int7VectorsScorer.BULK_SIZE) {
            scorer.scoreBulk(
                quantized,
                queryParams.lowerInterval(),
                queryParams.upperInterval(),
                queryParams.quantizedComponentSum(),
                queryParams.additionalCorrection(),
                similarityFunction,
                centroidDp,
                scores
            );
            for (int j = 0; j < ES92Int7VectorsScorer.BULK_SIZE; j++) {
                queue.add(scoresOffset + i + j, scores[j]);
            }
        }
        for (; i < size; i++) {
            float s = scorer.score(
                quantized,
                queryParams.lowerInterval(),
                queryParams.upperInterval(),
                queryParams.quantizedComponentSum(),
                queryParams.additionalCorrection(),
                similarityFunction,
                centroidDp
            );
            queue.add(scoresOffset + i, s);
        }
    }

    @Override
    public int numCentroids() {
        return numCentroids;
    }

    /**
     * @return postingListOffset from .mivf — needed if caller wants to compute absolute offsets manually
     */
    public long postingListOffset() {
        return postingListOffset;
    }

    @Override
    public void close() throws IOException {
        if (centroidInput != null && ownsCentroidInput) {
            centroidInput.close();
        }
        centroidInput = null;
        loaded = false;
    }

    static int skipIndexHeader(IndexInput input, String expectedCodec) throws IOException {
        // Codec header magic and version are always big-endian (CodecUtil.writeBEInt/readBEInt),
        // while Lucene 10.x DataInput.readInt() is little-endian.
        int magic = readBEInt(input);
        if (magic != CodecUtil.CODEC_MAGIC) {
            throw new IOException("Codec header mismatch: expected 0x"
                + Integer.toHexString(CodecUtil.CODEC_MAGIC) + ", got 0x" + Integer.toHexString(magic));
        }
        String codec = input.readString();
        if (!codec.equals(expectedCodec)) {
            throw new IOException("Codec mismatch: expected " + expectedCodec + ", got " + codec);
        }
        int version = readBEInt(input);
        // skip segmentID (16 bytes)
        input.skipBytes(16);
        // skip suffix (1 byte length + N bytes)
        int suffixLength = input.readByte() & 0xFF;
        input.skipBytes(suffixLength);
        return version;
    }

    private static int readBEInt(IndexInput in) throws IOException {
        return ((in.readByte() & 0xFF) << 24)
            | ((in.readByte() & 0xFF) << 16)
            | ((in.readByte() & 0xFF) << 8)
            | (in.readByte() & 0xFF);
    }
}
