/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.diskbbq.es94;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.FlatVectorsReader;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.internal.hppc.IntObjectHashMap;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS;
import static org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat.CENTROID_EXTENSION;
import static org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat.CLUSTER_EXTENSION;
import static org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat.DYNAMIC_VISIT_RATIO;
import static org.elasticsearch.eslib.diskbbq.es94.ES940DiskBBQVectorsFormat.VERSION_DIRECT_IO;

/**
 * Standalone-lib (Lucene 9.12 / JDK 11) reader base for the ES940 DiskBBQ IVF format.
 *
 * <p>De-genericized counterpart of the native {@code IVFVectorsReader<T>}: the native
 * {@code GenericFlatVectorReaders}, {@code ESAcceptDocs} and {@code IVFKnnSearchStrategy}
 * infrastructure (not reachable from the standalone lib) is replaced by a simplified flat-reader
 * map and a {@link Bits} accept-docs filter. The IVF search algorithm — two-level parent centroids,
 * asymmetric query quantization via {@link PostingMetadata#queryCentroidOrdinal()}, and the dynamic
 * visit-ratio budget — is preserved from native ES940. SOAR replicas (a vector that lives in two
 * clusters) are de-duplicated by {@link DeduplicatingKnnCollector}.
 *
 * <p>Differences vs the native reader: Lucene 10 {@code AcceptDocs} becomes {@link Bits}; OSS
 * bulk-prefetch, the parallel scoring pool, and per-query trace logging (all mount-only) are dropped;
 * search runs serially.
 */
public abstract class IVFVectorsReader extends KnnVectorsReader {

    private static final Logger LOG = LoggerFactory.getLogger(IVFVectorsReader.class);
    private static final boolean DISKBBQ_DEBUG =
        Boolean.getBoolean("paimon.esindex.diskbbq.debug");
    private static final int DISKBBQ_DEBUG_LIMIT =
        Integer.getInteger("paimon.esindex.diskbbq.debug.limit", 20);
    private static final AtomicInteger DISKBBQ_DEBUG_EMITTED = new AtomicInteger();

    // Dynamic visit-ratio model + segment-size cap, ported verbatim from native ES940 IVFVectorsReader.
    private static final double V_MIN = 0.003;
    private static final double V_MAX = 0.04;
    private static final double LOG1P_R_MAX = Math.log1p(10.0);
    private static final double LOG1P_K_MAX = Math.log1p(10_000.0);
    private static final double RATIO_WEIGHT = 0.85;
    private static final double K_WEIGHT = 0.15;
    private static final double CAP_COEFFICIENT = 0.045;
    private static final int CAP_REF_SIZE = 1_000_000;
    private static final double CAP_EXPONENT = 0.35;
    static final float DEFAULT_TARGET_RECALL = 0.9f;
    // Fixed final-k proxy: knnCollector.k() carries num_candidates on this path, the true top-k is not
    // passed down, so use this as the k in the num_candidates/k signal. 10 matches the recall@10 target.
    private static final int DEFAULT_VISIT_K = 10;

    static float computeDynamicVisitRatio(int numCands, int k) {
        double r = (double) numCands / Math.max(k, 1);
        double z = RATIO_WEIGHT * logScale(r - 1.0, LOG1P_R_MAX) + K_WEIGHT * logScale(k, LOG1P_K_MAX);
        return (float) (V_MIN + (V_MAX - V_MIN) * z);
    }

    private static double logScale(double value, double log1pMax) {
        return Math.max(0.0, Math.min(1.0, Math.log1p(value) / log1pMax));
    }

    static float computeSegmentSizeCap(int numVectors) {
        if (numVectors <= 0) {
            return (float) V_MAX;
        }
        double sizeScale = Math.pow((double) CAP_REF_SIZE / numVectors, CAP_EXPONENT);
        double recallScale = 0.1 / (1.0 - DEFAULT_TARGET_RECALL);
        return (float) Math.min(1.0, CAP_COEFFICIENT * sizeScale * recallScale);
    }

    protected final IndexInput ivfCentroids, ivfClusters;
    private final SegmentReadState state;
    private final FieldInfos fieldInfos;
    protected final IntObjectHashMap<FieldEntry> fields;
    protected int versionMeta = -1;

    // Simplified flat vector reader management: one reader per (format, useDirectIO) combo.
    private final Map<String, FlatVectorsReader> flatReaders = new HashMap<>();
    private final IntObjectHashMap<FlatVectorsReader> fieldToReader = new IntObjectHashMap<>();

    @FunctionalInterface
    public interface LoadFlatVectorsReader {
        FlatVectorsReader getReader(String formatName, boolean useDirectIO) throws IOException;
    }

    @SuppressWarnings("this-escape")
    protected IVFVectorsReader(SegmentReadState state, LoadFlatVectorsReader loadReader) throws IOException {
        this.state = state;
        this.fieldInfos = state.fieldInfos;
        this.fields = new IntObjectHashMap<>();
        String meta = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            ES940DiskBBQVectorsFormat.IVF_META_EXTENSION
        );

        int versionMeta = -1;
        boolean success = false;
        try (ChecksumIndexInput ivfMeta = state.directory.openChecksumInput(meta, state.context)) {
            Throwable priorE = null;
            try {
                versionMeta = CodecUtil.checkIndexHeader(
                    ivfMeta,
                    ES940DiskBBQVectorsFormat.NAME,
                    ES940DiskBBQVectorsFormat.VERSION_START,
                    ES940DiskBBQVectorsFormat.VERSION_CURRENT,
                    state.segmentInfo.getId(),
                    state.segmentSuffix
                );
                this.versionMeta = versionMeta;
                readFields(ivfMeta, versionMeta, loadReader);
            } catch (Throwable exception) {
                priorE = exception;
            } finally {
                CodecUtil.checkFooter(ivfMeta, priorE);
            }
            ivfCentroids = openDataInput(state, versionMeta, CENTROID_EXTENSION, ES940DiskBBQVectorsFormat.NAME, state.context);
            ivfClusters = openDataInput(state, versionMeta, CLUSTER_EXTENSION, ES940DiskBBQVectorsFormat.NAME, state.context);
            success = true;
        } finally {
            if (success == false) {
                IOUtils.closeWhileHandlingException(this);
            }
        }
    }

    public abstract CentroidIterator getCentroidIterator(
        FieldInfo fieldInfo,
        int numCentroids,
        IndexInput centroids,
        float[] target,
        IndexInput postingListSlice,
        Bits acceptDocs,
        float approximateCost,
        FloatVectorValues values,
        float visitRatio
    ) throws IOException;

    private static IndexInput openDataInput(
        SegmentReadState state,
        int versionMeta,
        String fileExtension,
        String codecName,
        IOContext context
    ) throws IOException {
        final String fileName = IndexFileNames.segmentFileName(state.segmentInfo.name, state.segmentSuffix, fileExtension);
        final IndexInput in = state.directory.openInput(fileName, context);
        boolean success = false;
        try {
            final int versionVectorData = CodecUtil.checkIndexHeader(
                in,
                codecName,
                ES940DiskBBQVectorsFormat.VERSION_START,
                ES940DiskBBQVectorsFormat.VERSION_CURRENT,
                state.segmentInfo.getId(),
                state.segmentSuffix
            );
            if (versionMeta != versionVectorData) {
                throw new CorruptIndexException(
                    "Format versions mismatch: meta=" + versionMeta + ", " + codecName + "=" + versionVectorData,
                    in
                );
            }
            CodecUtil.retrieveChecksum(in);
            success = true;
            return in;
        } finally {
            if (success == false) {
                IOUtils.closeWhileHandlingException(in);
            }
        }
    }

    private void readFields(ChecksumIndexInput meta, int versionMeta, LoadFlatVectorsReader loadReader) throws IOException {
        for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
            final FieldInfo info = fieldInfos.fieldInfo(fieldNumber);
            if (info == null) {
                throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
            }

            FieldEntry fieldEntry = readField(meta, info, versionMeta);

            // Load flat vector reader for this field.
            String key = fieldEntry.rawVectorFormatName + ":" + fieldEntry.useDirectIOReads;
            FlatVectorsReader reader = flatReaders.get(key);
            if (reader == null) {
                reader = loadReader.getReader(fieldEntry.rawVectorFormatName, fieldEntry.useDirectIOReads);
                if (reader != null) {
                    flatReaders.put(key, reader);
                }
            }
            if (reader != null) {
                fieldToReader.put(info.number, reader);
            }

            fields.put(info.number, fieldEntry);
        }
    }

    private FieldEntry readField(IndexInput input, FieldInfo info, int versionMeta) throws IOException {
        final String rawVectorFormat = input.readString();
        final boolean useDirectIOReads = versionMeta >= VERSION_DIRECT_IO && input.readByte() == 1;
        final VectorEncoding vectorEncoding = readVectorEncoding(input);
        final VectorSimilarityFunction similarityFunction = readSimilarityFunction(input);
        if (similarityFunction != info.getVectorSimilarityFunction()) {
            throw new IllegalStateException(
                "Inconsistent vector similarity function for field=\""
                    + info.name
                    + "\"; "
                    + similarityFunction
                    + " != "
                    + info.getVectorSimilarityFunction()
            );
        }
        final int numCentroids = input.readInt();
        final long centroidOffset = input.readLong();
        final long centroidLength = input.readLong();
        final float[] globalCentroid = new float[info.getVectorDimension()];
        long postingListOffset = -1;
        long postingListLength = 0;
        float globalCentroidDp = 0;
        if (centroidLength > 0) {
            postingListOffset = input.readLong();
            postingListLength = input.readLong();
            input.readFloats(globalCentroid, 0, globalCentroid.length);
            globalCentroidDp = Float.intBitsToFloat(input.readInt());
        }
        return doReadField(
            input,
            rawVectorFormat,
            useDirectIOReads,
            similarityFunction,
            vectorEncoding,
            numCentroids,
            centroidOffset,
            centroidLength,
            postingListOffset,
            postingListLength,
            globalCentroid,
            globalCentroidDp
        );
    }

    protected abstract FieldEntry doReadField(
        IndexInput input,
        String rawVectorFormat,
        boolean useDirectIOReads,
        VectorSimilarityFunction similarityFunction,
        VectorEncoding vectorEncoding,
        int numCentroids,
        long centroidOffset,
        long centroidLength,
        long postingListOffset,
        long postingListLength,
        float[] globalCentroid,
        float globalCentroidDp
    ) throws IOException;

    private static VectorSimilarityFunction readSimilarityFunction(DataInput input) throws IOException {
        final int i = input.readInt();
        if (i < 0 || i >= SIMILARITY_FUNCTIONS.size()) {
            throw new IllegalArgumentException("invalid distance function: " + i);
        }
        return SIMILARITY_FUNCTIONS.get(i);
    }

    private static VectorEncoding readVectorEncoding(DataInput input) throws IOException {
        final int encodingId = input.readInt();
        if (encodingId < 0 || encodingId >= VectorEncoding.values().length) {
            throw new CorruptIndexException("Invalid vector encoding id: " + encodingId, input);
        }
        return VectorEncoding.values()[encodingId];
    }

    @Override
    public final void checkIntegrity() throws IOException {
        for (FlatVectorsReader reader : flatReaders.values()) {
            reader.checkIntegrity();
        }
        CodecUtil.checksumEntireFile(ivfCentroids);
        CodecUtil.checksumEntireFile(ivfClusters);
    }

    private FlatVectorsReader getReaderForField(String field) {
        FieldInfo info = fieldInfos.fieldInfo(field);
        if (info == null) throw new IllegalArgumentException("Could not find field [" + field + "]");
        FlatVectorsReader reader = fieldToReader.get(info.number);
        if (reader == null) throw new IllegalArgumentException("No flat reader for field [" + field + "]");
        return reader;
    }

    @Override
    public final FloatVectorValues getFloatVectorValues(String field) throws IOException {
        return getReaderForField(field).getFloatVectorValues(field);
    }

    @Override
    public final ByteVectorValues getByteVectorValues(String field) throws IOException {
        return getReaderForField(field).getByteVectorValues(field);
    }

    @Override
    public final void search(String field, float[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
        final FieldInfo fieldInfo = state.fieldInfos.fieldInfo(field);
        if (fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32) == false) {
            getReaderForField(field).search(field, target, knnCollector, acceptDocs);
            return;
        }
        if (fieldInfo.getVectorDimension() != target.length) {
            throw new IllegalArgumentException(
                "vector query dimension: " + target.length + " differs from field dimension: " + fieldInfo.getVectorDimension()
            );
        }
        final FloatVectorValues values = getReaderForField(field).getFloatVectorValues(field);
        final int numVectors = values.size();
        // Lucene 9.12 search path has no AcceptDocs.cost(); treat the whole segment as the candidate set.
        float approximateCost = numVectors;
        float percentFiltered = 1f;
        float visitRatio = DYNAMIC_VISIT_RATIO;

        FieldEntry entry = fields.get(fieldInfo.number);
        if (visitRatio == DYNAMIC_VISIT_RATIO) {
            int numCands = Math.max(knnCollector.k(), 1);
            visitRatio = Math.min(computeDynamicVisitRatio(numCands, DEFAULT_VISIT_K), computeSegmentSizeCap(numVectors));
        }
        // account for SOAR vectors: a vector may be visited twice across clusters.
        long maxVectorVisited = (long) (2.0 * visitRatio * numVectors);
        try (IndexInput postListSlice = entry.postingListSlice(ivfClusters);
            IndexInput centroidIterSlice = entry.centroidSlice(ivfCentroids);
            IndexInput centroidScorerSlice = entry.centroidSlice(ivfCentroids)) {
            CentroidIterator centroidIterator = getCentroidIterator(
                fieldInfo,
                entry.numCentroids,
                centroidIterSlice,
                target,
                postListSlice,
                acceptDocs,
                approximateCost,
                values,
                visitRatio
            );
            PostingVisitor scorer = getPostingVisitor(
                fieldInfo,
                numVectors,
                postListSlice,
                target,
                acceptDocs,
                centroidScorerSlice
            );
            DeduplicatingKnnCollector dedupCollector = new DeduplicatingKnnCollector(knnCollector);

            long clustersVisited = 0;
            long expectedDocs = 0;
            long actualDocs = 0;
            while (centroidIterator.hasNext()
                && (maxVectorVisited > expectedDocs || dedupCollector.minCompetitiveSimilarity() == Float.NEGATIVE_INFINITY)) {
                PostingMetadata postingMetadata = centroidIterator.nextPosting();
                clustersVisited++;
                expectedDocs += scorer.resetPostingsScorer(postingMetadata);
                actualDocs += scorer.visit(dedupCollector);
            }
            if (acceptDocs != null) {
                float unfilteredRatioVisited = (float) expectedDocs / numVectors;
                int filteredVectors = (int) Math.ceil(numVectors * percentFiltered);
                float expectedScored = Math.min(2 * filteredVectors * unfilteredRatioVisited, expectedDocs / 2f);
                while (centroidIterator.hasNext() && (actualDocs < expectedScored || actualDocs < knnCollector.k())) {
                    PostingMetadata postingMetadata = centroidIterator.nextPosting();
                    clustersVisited++;
                    scorer.resetPostingsScorer(postingMetadata);
                    actualDocs += scorer.visit(dedupCollector);
                }
            }
            logDiskBBQDebug(
                field,
                numVectors,
                entry.numCentroids,
                knnCollector.k(),
                visitRatio,
                maxVectorVisited,
                clustersVisited,
                expectedDocs,
                actualDocs,
                acceptDocs != null,
                dedupCollector
            );
            dedupCollector.drainTo(knnCollector);
        }
    }

    private static void logDiskBBQDebug(
        String field,
        int numVectors,
        int numCentroids,
        int collectorK,
        float visitRatio,
        long maxVectorVisited,
        long clustersVisited,
        long expectedDocs,
        long actualDocs,
        boolean filtered,
        DeduplicatingKnnCollector dedupCollector
    ) {
        if (!DISKBBQ_DEBUG || DISKBBQ_DEBUG_EMITTED.getAndIncrement() >= DISKBBQ_DEBUG_LIMIT) {
            return;
        }
        TopDocs topDocs = dedupCollector.topDocs();
        LOG.warn(
            "PAIMON_DISKBBQ_DEBUG field={} numVectors={} numCentroids={} collectorK={} visitRatio={} maxVectorVisited={} "
                + "clustersVisited={} expectedPostingDocs={} actualScoredDocs={} filtered={} collectAttempts={} "
                + "topKAdmissions={} topKDuplicateCollects={} visitedCount={} topDocs={}",
            field,
            numVectors,
            numCentroids,
            collectorK,
            visitRatio,
            maxVectorVisited,
            clustersVisited,
            expectedDocs,
            actualDocs,
            filtered,
            dedupCollector.collectAttempts(),
            dedupCollector.topKAdmissions(),
            dedupCollector.topKDuplicateCollects(),
            dedupCollector.visitedCount(),
            formatTopDocs(topDocs, 16)
        );
    }

    private static String formatTopDocs(TopDocs topDocs, int limit) {
        StringBuilder builder = new StringBuilder("[");
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        int size = Math.min(scoreDocs.length, limit);
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(scoreDocs[i].doc).append(':').append(scoreDocs[i].score);
        }
        if (scoreDocs.length > limit) {
            builder.append(",...");
        }
        return builder.append(']').toString();
    }

    /**
     * A {@link KnnCollector} that keeps the best score per doc id before draining unique hits into the
     * delegate. SOAR replicates a vector into a second cluster, so the same doc can be scored more than
     * once; this collapses those replicas (keeping the highest score) so the wrapping query sees clean,
     * unique top-k results.
     */
    static final class DeduplicatingKnnCollector implements KnnCollector {
        private final KnnCollector delegate;
        private final int[] topDocs;
        private final float[] topScores;
        private final Map<Integer, Integer> topPositions;
        private int topSize;
        private int worstIndex = -1;
        private long visitedCount;
        private long collectAttempts;
        private long topKDuplicateCollects;
        private long topKAdmissions;

        DeduplicatingKnnCollector(KnnCollector delegate) {
            this.delegate = delegate;
            this.topDocs = new int[Math.max(0, delegate.k())];
            this.topScores = new float[topDocs.length];
            this.topPositions = new HashMap<>(Math.max(1, delegate.k()));
        }

        @Override
        public boolean earlyTerminated() {
            return delegate.earlyTerminated() || visitedCount >= visitLimit();
        }

        @Override
        public void incVisitedCount(int count) {
            if (count > 0) {
                visitedCount += count;
            }
        }

        @Override
        public long visitedCount() {
            return visitedCount;
        }

        @Override
        public long visitLimit() {
            return delegate.visitLimit();
        }

        @Override
        public int k() {
            return delegate.k();
        }

        @Override
        public boolean collect(int docId, float similarity) {
            if (docId < 0 || topDocs.length == 0 || Float.isNaN(similarity)) {
                return false;
            }
            collectAttempts++;
            // A separate maxDoc-sized seen/scores table made every query allocate O(maxDoc)
            // memory. Keeping only the current top-k is sufficient: if an evicted doc is seen
            // again, it matters only when its new score beats the current worst entry.
            Integer existingPosition = topPositions.get(docId);
            if (existingPosition != null) {
                topKDuplicateCollects++;
                if (better(
                        similarity,
                        docId,
                        topScores[existingPosition],
                        docId) == false) {
                    return false;
                }
                topScores[existingPosition] = similarity;
                recomputeWorst();
                return true;
            }
            if (topSize < topDocs.length) {
                topDocs[topSize] = docId;
                topScores[topSize] = similarity;
                topPositions.put(docId, topSize);
                topSize++;
                topKAdmissions++;
                recomputeWorst();
                return true;
            }
            if (better(similarity, docId, topScores[worstIndex], topDocs[worstIndex])) {
                topPositions.remove(topDocs[worstIndex]);
                topDocs[worstIndex] = docId;
                topScores[worstIndex] = similarity;
                topPositions.put(docId, worstIndex);
                topKAdmissions++;
                recomputeWorst();
                return true;
            }
            return false;
        }

        long collectAttempts() {
            return collectAttempts;
        }

        long topKDuplicateCollects() {
            return topKDuplicateCollects;
        }

        long topKAdmissions() {
            return topKAdmissions;
        }

        @Override
        public float minCompetitiveSimilarity() {
            return topSize >= k() && worstIndex >= 0 ? topScores[worstIndex] : Float.NEGATIVE_INFINITY;
        }

        @Override
        public TopDocs topDocs() {
            Integer[] order = new Integer[topSize];
            for (int i = 0; i < topSize; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (left, right) -> {
                int scoreCompare = Float.compare(topScores[right], topScores[left]);
                if (scoreCompare != 0) {
                    return scoreCompare;
                }
                return Integer.compare(topDocs[left], topDocs[right]);
            });
            ScoreDoc[] scoreDocs = new ScoreDoc[topSize];
            for (int i = 0; i < order.length; i++) {
                int idx = order[i];
                scoreDocs[i] = new ScoreDoc(topDocs[idx], topScores[idx]);
            }
            TotalHits.Relation relation = earlyTerminated() ? TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO : TotalHits.Relation.EQUAL_TO;
            return new TopDocs(new TotalHits(visitedCount, relation), scoreDocs);
        }

        void drainTo(KnnCollector target) {
            for (ScoreDoc scoreDoc : topDocs().scoreDocs) {
                target.collect(scoreDoc.doc, scoreDoc.score);
            }
            long remaining = visitedCount;
            while (remaining > 0) {
                int count = (int) Math.min(Integer.MAX_VALUE, remaining);
                target.incVisitedCount(count);
                remaining -= count;
            }
        }

        private void recomputeWorst() {
            worstIndex = -1;
            for (int i = 0; i < topSize; i++) {
                if (worstIndex == -1 || worse(topScores[i], topDocs[i], topScores[worstIndex], topDocs[worstIndex])) {
                    worstIndex = i;
                }
            }
        }

        private static boolean better(float score, int doc, float otherScore, int otherDoc) {
            int scoreCompare = Float.compare(score, otherScore);
            return scoreCompare > 0 || (scoreCompare == 0 && doc < otherDoc);
        }

        private static boolean worse(float score, int doc, float otherScore, int otherDoc) {
            int scoreCompare = Float.compare(score, otherScore);
            return scoreCompare < 0 || (scoreCompare == 0 && doc > otherDoc);
        }
    }

    @Override
    public final void search(String field, byte[] target, KnnCollector knnCollector, Bits acceptDocs) throws IOException {
        // DiskBBQ only supports float vectors; byte search is a no-op (matches the ES920 standalone reader).
    }

    @Override
    public long ramBytesUsed() {
        return 0L;
    }

    // Note: not an @Override — Lucene 9.12 KnnVectorsReader has no getOffHeapByteSize (10.x only).
    public Map<String, Long> getOffHeapByteSize(FieldInfo fieldInfo) {
        FieldEntry fe = fields.get(fieldInfo.number);
        if (fe == null) {
            return Map.of();
        }
        return Map.of(CENTROID_EXTENSION, fe.centroidLength, CLUSTER_EXTENSION, fe.postingListLength);
    }

    @Override
    public void close() throws IOException {
        List<Closeable> closeables = new ArrayList<>(flatReaders.values());
        Collections.addAll(closeables, ivfCentroids, ivfClusters);
        IOUtils.close(closeables);
    }

    protected static class FieldEntry {
        protected final String rawVectorFormatName;
        protected final boolean useDirectIOReads;
        protected final VectorSimilarityFunction similarityFunction;
        protected final VectorEncoding vectorEncoding;
        protected final int numCentroids;
        protected final long centroidOffset;
        protected final long centroidLength;
        protected final long postingListOffset;
        protected final long postingListLength;
        protected final float[] globalCentroid;
        protected final float globalCentroidDp;
        protected final int bulkSize;

        protected FieldEntry(
            String rawVectorFormatName,
            boolean useDirectIOReads,
            VectorSimilarityFunction similarityFunction,
            VectorEncoding vectorEncoding,
            int numCentroids,
            long centroidOffset,
            long centroidLength,
            long postingListOffset,
            long postingListLength,
            float[] globalCentroid,
            float globalCentroidDp,
            int bulkSize
        ) {
            this.rawVectorFormatName = rawVectorFormatName;
            this.useDirectIOReads = useDirectIOReads;
            this.similarityFunction = similarityFunction;
            this.vectorEncoding = vectorEncoding;
            this.numCentroids = numCentroids;
            this.centroidOffset = centroidOffset;
            this.centroidLength = centroidLength;
            this.postingListOffset = postingListOffset;
            this.postingListLength = postingListLength;
            this.globalCentroid = globalCentroid;
            this.globalCentroidDp = globalCentroidDp;
            this.bulkSize = bulkSize;
        }

        public String rawVectorFormatName() {
            return rawVectorFormatName;
        }

        public boolean useDirectIOReads() {
            return useDirectIOReads;
        }

        public int numCentroids() {
            return numCentroids;
        }

        public float[] globalCentroid() {
            return globalCentroid;
        }

        public float globalCentroidDp() {
            return globalCentroidDp;
        }

        public VectorSimilarityFunction similarityFunction() {
            return similarityFunction;
        }

        public IndexInput centroidSlice(IndexInput centroidFile) throws IOException {
            return centroidFile.slice("centroids", centroidOffset, centroidLength);
        }

        public IndexInput postingListSlice(IndexInput postingListFile) throws IOException {
            return postingListFile.slice("postingLists", postingListOffset, postingListLength);
        }

        public int getBulkSize() {
            return bulkSize;
        }
    }

    public abstract PostingVisitor getPostingVisitor(
        FieldInfo fieldInfo,
        int numVectors,
        IndexInput postingsLists,
        float[] target,
        Bits needsScoring,
        IndexInput centroidSlice
    ) throws IOException;

    public interface PostingVisitor {
        /** returns the number of documents in the posting list */
        int resetPostingsScorer(PostingMetadata metadata) throws IOException;

        /** returns the number of scored documents */
        int visit(KnnCollector collector) throws IOException;
    }
}
