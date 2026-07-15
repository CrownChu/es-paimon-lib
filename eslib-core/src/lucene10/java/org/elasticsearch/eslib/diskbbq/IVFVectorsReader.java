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
import org.apache.lucene.store.ChecksumIndexInput;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.search.AcceptDocs;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IOUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS;
import static org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat.CENTROID_EXTENSION;
import static org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat.CLUSTER_EXTENSION;
import static org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat.DYNAMIC_VISIT_RATIO;
import static org.elasticsearch.eslib.diskbbq.PaimonDiskBBQVectorsFormat.VERSION_DIRECT_IO;

/**
 * Reader for IVF vectors (standalone lib version).
 * Simplified: removed GenericFlatVectorReaders, ESAcceptDocs, IVFKnnSearchStrategy.
 */
public abstract class IVFVectorsReader extends KnnVectorsReader {

    // Dynamic visit-ratio model + segment-size cap, ported verbatim from native ES920 IVFVectorsReader.
    // Without the cap the reader visited every cluster at large k and flooded the candidate pool with
    // far-cluster false positives, pushing true neighbours out of the top-k (recall collapse at high oversample).
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

    private final IndexInput ivfCentroids, ivfClusters;
    private final SegmentReadState state;
    private final FieldInfos fieldInfos;
    protected final IntObjectHashMap<FieldEntry> fields;

    // Simplified flat vector reader management: one reader per (format, useDirectIO) combo
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
            PaimonDiskBBQVectorsFormat.IVF_META_EXTENSION
        );

        int versionMeta = -1;
        boolean success = false;
        try (ChecksumIndexInput ivfMeta = state.directory.openChecksumInput(meta)) {
            Throwable priorE = null;
            try {
                versionMeta = CodecUtil.checkIndexHeader(
                    ivfMeta,
                    PaimonDiskBBQVectorsFormat.NAME,
                    PaimonDiskBBQVectorsFormat.VERSION_START,
                    PaimonDiskBBQVectorsFormat.VERSION_CURRENT,
                    state.segmentInfo.getId(),
                    state.segmentSuffix
                );
                readFields(ivfMeta, versionMeta, loadReader);
            } catch (Throwable exception) {
                priorE = exception;
            } finally {
                CodecUtil.checkFooter(ivfMeta, priorE);
            }
            ivfCentroids = openDataInput(state, versionMeta, CENTROID_EXTENSION, PaimonDiskBBQVectorsFormat.NAME, state.context);
            ivfClusters = openDataInput(state, versionMeta, CLUSTER_EXTENSION, PaimonDiskBBQVectorsFormat.NAME, state.context);
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
                PaimonDiskBBQVectorsFormat.VERSION_START,
                PaimonDiskBBQVectorsFormat.VERSION_CURRENT,
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

    private void readFields(
        ChecksumIndexInput meta,
        int versionMeta,
        LoadFlatVectorsReader loadReader
    ) throws IOException {
        for (int fieldNumber = meta.readInt(); fieldNumber != -1; fieldNumber = meta.readInt()) {
            final FieldInfo info = fieldInfos.fieldInfo(fieldNumber);
            if (info == null) {
                throw new CorruptIndexException("Invalid field number: " + fieldNumber, meta);
            }

            FieldEntry fieldEntry = readField(meta, info, versionMeta);

            // Load flat vector reader for this field
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
        long postingListLength = -1;
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
        for (var reader : flatReaders.values()) {
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
    public final void search(String field, float[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) throws IOException {
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
        FloatVectorValues values = getReaderForField(field).getFloatVectorValues(field);
        int numVectors = values.size();
        // Lucene 10.4: the query passes AcceptDocs; unwrap to the Bits our internal posting visitors expect.
        Bits acceptBits = acceptDocs != null ? acceptDocs.bits() : null;
        float approximateCost = (acceptDocs != null) ? (float) acceptDocs.cost() : numVectors;
        if (approximateCost == 0) {
            approximateCost = numVectors;
        }
        float percentFiltered = Math.max(0f, Math.min(1f, approximateCost / numVectors));
        float visitRatio = DYNAMIC_VISIT_RATIO;

        FieldEntry entry = fields.get(fieldInfo.number);
        if (visitRatio == DYNAMIC_VISIT_RATIO) {
            int k = knnCollector.k();
            // numCands == k on this path; cap the dynamic ratio by a segment-size-aware bound (native ES920).
            visitRatio = Math.min(computeDynamicVisitRatio(k, k), computeSegmentSizeCap(numVectors));
        }
        // account for SOAR vectors: a vector may be visited twice across clusters
        long maxVectorVisited = (long) (2.0 * visitRatio * numVectors);
        IndexInput postListSlice = entry.postingListSlice(ivfClusters);
        CentroidIterator centroidPrefetchingIterator = getCentroidIterator(
            fieldInfo,
            entry.numCentroids,
            entry.centroidSlice(ivfCentroids),
            target,
            postListSlice,
            acceptBits,
            approximateCost,
            values,
            visitRatio
        );
        PostingVisitor scorer = getPostingVisitor(fieldInfo, postListSlice, target, acceptBits);
        long expectedDocs = 0;
        long actualDocs = 0;
        while (centroidPrefetchingIterator.hasNext()
            && (maxVectorVisited > expectedDocs || knnCollector.minCompetitiveSimilarity() == Float.NEGATIVE_INFINITY)) {
            CentroidOffsetAndLength offsetAndLength = centroidPrefetchingIterator.nextPostingListOffsetAndLength();
            expectedDocs += scorer.resetPostingsScorer(offsetAndLength.offset());
            actualDocs += scorer.visit(knnCollector);
        }
        if (acceptDocs != null) {
            float unfilteredRatioVisited = (float) expectedDocs / numVectors;
            int filteredVectors = (int) Math.ceil(numVectors * percentFiltered);
            float expectedScored = Math.min(2 * filteredVectors * unfilteredRatioVisited, expectedDocs / 2f);
            while (centroidPrefetchingIterator.hasNext() && (actualDocs < expectedScored || actualDocs < knnCollector.k())) {
                CentroidOffsetAndLength offsetAndLength = centroidPrefetchingIterator.nextPostingListOffsetAndLength();
                scorer.resetPostingsScorer(offsetAndLength.offset());
                actualDocs += scorer.visit(knnCollector);
            }
        }
    }

    @Override
    public final void search(String field, byte[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) throws IOException {
        final FieldInfo fieldInfo = state.fieldInfos.fieldInfo(field);
        final ByteVectorValues values = getReaderForField(field).getByteVectorValues(field);
        for (int i = 0; i < values.size(); i++) {
            final float score = fieldInfo.getVectorSimilarityFunction().compare(target, values.vectorValue(i));
            knnCollector.collect(values.ordToDoc(i), score);
            if (knnCollector.earlyTerminated()) {
                return;
            }
        }
    }

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
            float globalCentroidDp
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
    }

    public abstract PostingVisitor getPostingVisitor(FieldInfo fieldInfo, IndexInput postingsLists, float[] target, Bits needsScoring)
        throws IOException;

    public record CentroidOffsetAndLength(long offset, long length) {}

    public interface CentroidIterator {
        boolean hasNext();

        CentroidOffsetAndLength nextPostingListOffsetAndLength() throws IOException;
    }

    public interface PostingVisitor {
        int resetPostingsScorer(long offset) throws IOException;

        int visit(KnnCollector collector) throws IOException;
    }
}
