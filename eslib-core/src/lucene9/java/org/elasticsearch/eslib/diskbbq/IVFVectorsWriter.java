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
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatFieldVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Sorter;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.LongValues;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.IOUtils;

import org.elasticsearch.eslib.cluster.IndexedFloatVectorValues;
import org.elasticsearch.eslib.cluster.KmeansFloatVectorValues;
import org.elasticsearch.eslib.diskbbq.CentroidAssignments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader.SIMILARITY_FUNCTIONS;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

/**
 * Base class for IVF vectors writer.
 */
public abstract class IVFVectorsWriter extends KnnVectorsWriter {

    private static final Logger LOG = LoggerFactory.getLogger(IVFVectorsWriter.class);

    private final List<FieldWriter> fieldWriters = new ArrayList<>();
    private final IndexOutput ivfCentroids, ivfClusters;
    private final IndexOutput ivfMeta;
    private final String rawVectorFormatName;
    private final int writeVersion;
    private final Boolean useDirectIOReads;
    private final FlatVectorsWriter rawVectorDelegate;

    @SuppressWarnings("this-escape")
    protected IVFVectorsWriter(
        SegmentWriteState state,
        String rawVectorFormatName,
        Boolean useDirectIOReads,
        FlatVectorsWriter rawVectorDelegate,
        int writeVersion
    ) throws IOException {
        // if version >= VERSION_DIRECT_IO, useDirectIOReads should have a value
        if ((writeVersion >= PaimonDiskBBQVectorsFormat.VERSION_DIRECT_IO) == (useDirectIOReads == null)) throw new IllegalArgumentException(
            "Write version " + writeVersion + " does not match direct IO value " + useDirectIOReads
        );

        this.rawVectorFormatName = rawVectorFormatName;
        this.writeVersion = writeVersion;
        this.useDirectIOReads = useDirectIOReads;
        this.rawVectorDelegate = rawVectorDelegate;
        final String metaFileName = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            PaimonDiskBBQVectorsFormat.IVF_META_EXTENSION
        );

        final String ivfCentroidsFileName = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            PaimonDiskBBQVectorsFormat.CENTROID_EXTENSION
        );
        final String ivfClustersFileName = IndexFileNames.segmentFileName(
            state.segmentInfo.name,
            state.segmentSuffix,
            PaimonDiskBBQVectorsFormat.CLUSTER_EXTENSION
        );
        boolean success = false;
        try {
            ivfMeta = state.directory.createOutput(metaFileName, state.context);
            CodecUtil.writeIndexHeader(
                ivfMeta,
                PaimonDiskBBQVectorsFormat.NAME,
                writeVersion,
                state.segmentInfo.getId(),
                state.segmentSuffix
            );
            ivfCentroids = state.directory.createOutput(ivfCentroidsFileName, state.context);
            CodecUtil.writeIndexHeader(
                ivfCentroids,
                PaimonDiskBBQVectorsFormat.NAME,
                writeVersion,
                state.segmentInfo.getId(),
                state.segmentSuffix
            );
            ivfClusters = state.directory.createOutput(ivfClustersFileName, state.context);
            CodecUtil.writeIndexHeader(
                ivfClusters,
                PaimonDiskBBQVectorsFormat.NAME,
                writeVersion,
                state.segmentInfo.getId(),
                state.segmentSuffix
            );
            success = true;
        } finally {
            if (success == false) {
                IOUtils.closeWhileHandlingException(this);
            }
        }
    }

    @Override
    public final KnnFieldVectorsWriter<?> addField(FieldInfo fieldInfo) throws IOException {
        LOG.info("[IVFVectorsWriter] addField called — name={}, dim={}, encoding={}, sim={}",
                fieldInfo.name, fieldInfo.getVectorDimension(),
                fieldInfo.getVectorEncoding(), fieldInfo.getVectorSimilarityFunction());
        if (fieldInfo.getVectorSimilarityFunction() == VectorSimilarityFunction.COSINE) {
            throw new IllegalArgumentException("IVF does not support cosine similarity");
        }
        final FlatFieldVectorsWriter<?> rawVectorDelegate = this.rawVectorDelegate.addField(fieldInfo);
        if (fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32)) {
            @SuppressWarnings("unchecked")
            final FlatFieldVectorsWriter<float[]> floatWriter = (FlatFieldVectorsWriter<float[]>) rawVectorDelegate;
            fieldWriters.add(new FieldWriter(fieldInfo, floatWriter));
        } else {
            // we simply write information that the field is present but we don't do anything with it.
            fieldWriters.add(new FieldWriter(fieldInfo, null));
        }
        LOG.info("[IVFVectorsWriter] addField done — fieldWriters.size now={}", fieldWriters.size());
        return rawVectorDelegate;
    }

    public abstract CentroidAssignments calculateCentroids(FieldInfo fieldInfo, IndexedFloatVectorValues floatVectorValues) throws IOException;

    public abstract CentroidAssignments calculateCentroids(FieldInfo fieldInfo, IndexedFloatVectorValues floatVectorValues, MergeState mergeState)
        throws IOException;

    // JDK 11 port: was `public record CentroidOffsetAndLength(LongValues offsets, LongValues lengths)`.
    public static final class CentroidOffsetAndLength {
        private final LongValues offsets;
        private final LongValues lengths;
        public CentroidOffsetAndLength(LongValues offsets, LongValues lengths) {
            this.offsets = offsets;
            this.lengths = lengths;
        }
        public LongValues offsets() { return offsets; }
        public LongValues lengths() { return lengths; }
    }

    public abstract void writeCentroids(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        int[] centroidAssignments,
        float[] globalCentroid,
        CentroidOffsetAndLength centroidOffsetAndLength,
        IndexOutput centroidOutput
    ) throws IOException;

    public abstract CentroidOffsetAndLength buildAndWritePostingsLists(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        IndexedFloatVectorValues floatVectorValues,
        IndexOutput postingsOutput,
        long fileOffset,
        int[] assignments,
        int[] overspillAssignments
    ) throws IOException;

    public abstract CentroidOffsetAndLength buildAndWritePostingsLists(
        FieldInfo fieldInfo,
        CentroidSupplier centroidSupplier,
        IndexedFloatVectorValues floatVectorValues,
        IndexOutput postingsOutput,
        long fileOffset,
        MergeState mergeState,
        int[] assignments,
        int[] overspillAssignments
    ) throws IOException;

    public abstract CentroidSupplier createCentroidSupplier(
        IndexInput centroidsInput,
        int numCentroids,
        FieldInfo fieldInfo,
        float[] globalCentroid
    ) throws IOException;

    @Override
    public final void flush(int maxDoc, Sorter.DocMap sortMap) throws IOException {
        LOG.info("[IVFVectorsWriter] flush called — maxDoc={}, fieldWriters.size={}",
                maxDoc, fieldWriters.size());
        long rawVecRamBefore = rawVectorDelegate.ramBytesUsed();
        rawVectorDelegate.flush(maxDoc, sortMap);
        LOG.info("[IVFVectorsWriter] flush — rawVectorDelegate.flush() returned; rawVecRamBefore={}", rawVecRamBefore);
        for (FieldWriter fieldWriter : fieldWriters) {
            if (fieldWriter.delegate == null) {
                LOG.info("[IVFVectorsWriter] flush — delegate==null for field={}, writing empty meta",
                        fieldWriter.fieldInfo.name);
                // field is not float, we just write meta information
                writeMeta(fieldWriter.fieldInfo, 0, 0, 0, 0, 0, null);
                continue;
            }
            // [DIAG-1] Check vectors actually present in delegate after rawVectorDelegate.flush
            int delegateVecCount = fieldWriter.delegate.getVectors().size();
            LOG.info("[IVFVectorsWriter] flush DIAG-1 — field={}, delegate.getVectors().size()={}, maxDoc={}",
                    fieldWriter.fieldInfo.name, delegateVecCount, maxDoc);

            // build a float vector values with random access — passing sortMap so .clivf
            // docIds are post-sort (matching the .vec layout that rawVectorDelegate.flush
            // produced above). Without this, .clivf records pre-sort docIds while .vec is
            // stored in post-sort order, causing ANN to read the wrong vector at scoring time.
            final IndexedFloatVectorValues floatVectorValues = getFloatVectorValues(fieldWriter.fieldInfo, fieldWriter.delegate, maxDoc, sortMap);
            LOG.info("[IVFVectorsWriter] flush DIAG-2 — floatVectorValues.size()={}, dim={}",
                    floatVectorValues.size(), floatVectorValues.dimension());

            // build centroids
            final CentroidAssignments centroidAssignments = calculateCentroids(fieldWriter.fieldInfo, floatVectorValues);
            LOG.info("[IVFVectorsWriter] flush DIAG-3 — calculateCentroids returned: centroids.length={}, assignments.length={}, overspillAssignments.length={}",
                    centroidAssignments.centroids() == null ? -1 : centroidAssignments.centroids().length,
                    centroidAssignments.assignments() == null ? -1 : centroidAssignments.assignments().length,
                    centroidAssignments.overspillAssignments() == null ? -1 : centroidAssignments.overspillAssignments().length);

            // wrap centroids with a supplier
            final CentroidSupplier centroidSupplier = CentroidSupplier.fromArray(
                centroidAssignments.centroids(),
                fieldWriter.fieldInfo.getVectorDimension()
            );
            // write posting lists
            final long postingListOffset = ivfClusters.alignFilePointer(Float.BYTES);
            final CentroidOffsetAndLength centroidOffsetAndLength = buildAndWritePostingsLists(
                fieldWriter.fieldInfo,
                centroidSupplier,
                floatVectorValues,
                ivfClusters,
                postingListOffset,
                centroidAssignments.assignments(),
                centroidAssignments.overspillAssignments()
            );
            final long postingListLength = ivfClusters.getFilePointer() - postingListOffset;
            LOG.info("[IVFVectorsWriter] flush DIAG-4 — buildAndWritePostingsLists wrote {} bytes (postingListOffset={}, ivfClusters.fp={})",
                    postingListLength, postingListOffset, ivfClusters.getFilePointer());

            // write centroids
            final float[] globalCentroid = centroidAssignments.globalCentroid();
            final long centroidOffset = ivfCentroids.alignFilePointer(Float.BYTES);
            writeCentroids(
                fieldWriter.fieldInfo,
                centroidSupplier,
                centroidAssignments.assignments(),
                globalCentroid,
                centroidOffsetAndLength,
                ivfCentroids
            );
            final long centroidLength = ivfCentroids.getFilePointer() - centroidOffset;
            LOG.info("[IVFVectorsWriter] flush DIAG-5 — writeCentroids wrote {} bytes; centroidSupplier.size={}, globalCentroid!=null={}",
                    centroidLength, centroidSupplier.size(), globalCentroid != null);

            // write meta file
            writeMeta(
                fieldWriter.fieldInfo,
                centroidSupplier.size(),
                centroidOffset,
                centroidLength,
                postingListOffset,
                postingListLength,
                globalCentroid
            );
        }
    }

    private static IndexedFloatVectorValues getFloatVectorValues(
        FieldInfo fieldInfo,
        FlatFieldVectorsWriter<float[]> fieldVectorsWriter,
        int maxDoc,
        Sorter.DocMap sortMap
    ) throws IOException {
        List<float[]> vectors = fieldVectorsWriter.getVectors();
        // sortMap != null when an IndexSort is configured (see DefaultESIndexBuilder's
        // setIndexSort on _ROW_ID). In that case .vec was already rewritten in post-sort
        // docId order by rawVectorDelegate.flush(maxDoc, sortMap), so .clivf MUST also
        // record post-sort docIds. Pre-sort docIds here would mismatch .vec's layout and
        // break ANN scoring at read time.
        if (vectors.size() == maxDoc) {
            // Dense case: vector[i] was originally at pre-sort docId i.
            if (sortMap == null) {
                return KmeansFloatVectorValues.build(vectors, null, fieldInfo.getVectorDimension());
            }
            final int[] docIds = new int[maxDoc];
            for (int i = 0; i < maxDoc; i++) {
                docIds[i] = sortMap.oldToNew(i);
            }
            return KmeansFloatVectorValues.build(vectors, docIds, fieldInfo.getVectorDimension());
        }
        final DocIdSetIterator iterator = fieldVectorsWriter.getDocsWithFieldSet().iterator();
        final int[] docIds = new int[vectors.size()];
        for (int i = 0; i < docIds.length; i++) {
            int oldDocId = iterator.nextDoc();
            docIds[i] = (sortMap == null) ? oldDocId : sortMap.oldToNew(oldDocId);
        }
        assert iterator.nextDoc() == NO_MORE_DOCS;
        return KmeansFloatVectorValues.build(vectors, docIds, fieldInfo.getVectorDimension());
    }

    @Override
    public final void mergeOneField(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        LOG.info("[IVFVectorsWriter] mergeOneField called — name={}, dim={}, encoding={}, sim={}, numKnnVectorsReaders={}, segmentName={}",
                fieldInfo.name, fieldInfo.getVectorDimension(),
                fieldInfo.getVectorEncoding(), fieldInfo.getVectorSimilarityFunction(),
                mergeState.knnVectorsReaders == null ? -1 : mergeState.knnVectorsReaders.length,
                mergeState.segmentInfo.name);
        if (fieldInfo.getVectorEncoding().equals(VectorEncoding.FLOAT32)) {
            mergeOneFieldIVF(fieldInfo, mergeState);
        } else {
            // we simply write information that the field is present but we don't do anything with it.
            writeMeta(fieldInfo, 0, 0, 0, 0, 0, null);
        }
        // we merge the vectors at the end so we only have two copies of the vectors on disk at the same time.
        rawVectorDelegate.mergeOneField(fieldInfo, mergeState);
        LOG.info("[IVFVectorsWriter] mergeOneField done — name={}, ivfCentroids.fp={}, ivfClusters.fp={}, ivfMeta.fp={}",
                fieldInfo.name, ivfCentroids.getFilePointer(), ivfClusters.getFilePointer(), ivfMeta.getFilePointer());
    }

    private void writeMeta(
        FieldInfo field,
        int numCentroids,
        long centroidOffset,
        long centroidLength,
        long postingListOffset,
        long postingListLength,
        float[] globalCentroid
    ) throws IOException {
        ivfMeta.writeInt(field.number);
        ivfMeta.writeString(rawVectorFormatName);
        if (writeVersion >= PaimonDiskBBQVectorsFormat.VERSION_DIRECT_IO) {
            ivfMeta.writeByte(useDirectIOReads ? (byte) 1 : 0);
        }
        ivfMeta.writeInt(field.getVectorEncoding().ordinal());
        ivfMeta.writeInt(distFuncToOrd(field.getVectorSimilarityFunction()));
        ivfMeta.writeInt(numCentroids);
        ivfMeta.writeLong(centroidOffset);
        ivfMeta.writeLong(centroidLength);
        if (centroidLength > 0) {
            ivfMeta.writeLong(postingListOffset);
            ivfMeta.writeLong(postingListLength);
            final ByteBuffer buffer = ByteBuffer.allocate(globalCentroid.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            buffer.asFloatBuffer().put(globalCentroid);
            ivfMeta.writeBytes(buffer.array(), buffer.array().length);
            ivfMeta.writeInt(Float.floatToIntBits(VectorUtil.dotProduct(globalCentroid, globalCentroid)));
        }
        doWriteMeta(ivfMeta, field, numCentroids);
    }

    protected abstract void doWriteMeta(IndexOutput metaOutput, FieldInfo field, int numCentroids) throws IOException;


    private void mergeOneFieldIVF(FieldInfo fieldInfo, MergeState mergeState) throws IOException {
        final int numVectors;
        String tempRawVectorsFileName = null;
        String docsFileName = null;
        boolean success = false;
        // build a float vector values with random access. In order to do that we dump the vectors to
        // a temporary file and if the segment is not dense, the docs to another file/
        try (
            IndexOutput vectorsOut = mergeState.segmentInfo.dir.createTempOutput(mergeState.segmentInfo.name, "ivfvec_", IOContext.DEFAULT)
        ) {
            tempRawVectorsFileName = vectorsOut.getName();
            FloatVectorValues mergedFloatVectorValues = MergedVectorValues.mergeFloatVectorValues(fieldInfo, mergeState);
            // if the segment is dense, we don't need to do anything with docIds.
            boolean dense = mergedFloatVectorValues.size() == mergeState.segmentInfo.maxDoc();
            try (
                IndexOutput docsOut = dense
                    ? null
                    : mergeState.segmentInfo.dir.createTempOutput(mergeState.segmentInfo.name, "ivfdoc_", IOContext.DEFAULT)
            ) {
                if (docsOut != null) {
                    docsFileName = docsOut.getName();
                }
                // TODO do this better, we shouldn't have to write to a temp file, we should be able to
                // to just from the merged vector values, the tricky part is the random access.
                numVectors = writeFloatVectorValues(fieldInfo, docsOut, vectorsOut, mergedFloatVectorValues);
                LOG.info("[IVFVectorsWriter] mergeOneFieldIVF DIAG-A — writeFloatVectorValues numVectors={}, maxDoc={}, dense={}, mergedFloatVectorValues.size={}",
                        numVectors, mergeState.segmentInfo.maxDoc(), dense, mergedFloatVectorValues.size());
                CodecUtil.writeFooter(vectorsOut);
                if (docsOut != null) {
                    CodecUtil.writeFooter(docsOut);
                }
                success = true;
            }
        } finally {
            if (success == false) {
                if (tempRawVectorsFileName != null) {
                    org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, tempRawVectorsFileName);
                }
                if (docsFileName != null) {
                    org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, docsFileName);
                }
            }
        }
        if (numVectors == 0) {
            long centroidOffset = ivfCentroids.getFilePointer();
            writeMeta(fieldInfo, 0, centroidOffset, 0, 0, 0, null);
            return;
        }
        // now open the temp file and build the index structures. It is expected these files to be read in sequential order.
        // Even when the file might be sample, the reads will be always in increase order, therefore we set the ReadAdvice to SEQUENTIAL
        // so the OS can optimize read ahead in low memory situations.
        try (
            // JDK 11 port: Lucene 9.12 IOContext has no withHints/DataAccessHint —
            // pass DEFAULT (sequential access is best-effort here, OS readahead handles it).
            IndexInput vectors = mergeState.segmentInfo.dir.openInput(tempRawVectorsFileName, IOContext.DEFAULT);
            IndexInput docs = docsFileName == null
                ? null
                : mergeState.segmentInfo.dir.openInput(docsFileName, IOContext.DEFAULT)
        ) {
            final IndexedFloatVectorValues floatVectorValues = getFloatVectorValues(fieldInfo, docs, vectors, numVectors);

            final long centroidOffset;
            final long centroidLength;
            final long postingListOffset;
            final long postingListLength;
            final int numCentroids;
            final int[] assignments;
            final int[] overspillAssignments;
            final float[] calculatedGlobalCentroid;
            String centroidTempName = null;
            IndexOutput centroidTemp = null;
            success = false;
            try {
                centroidTemp = mergeState.segmentInfo.dir.createTempOutput(mergeState.segmentInfo.name, "civf_", IOContext.DEFAULT);
                centroidTempName = centroidTemp.getName();
                CentroidAssignments centroidAssignments = calculateCentroids(
                    fieldInfo,
                    getFloatVectorValues(fieldInfo, docs, vectors, numVectors),
                    mergeState
                );
                // write the centroids to a temporary file so we are not holding them on heap
                final ByteBuffer buffer = ByteBuffer.allocate(fieldInfo.getVectorDimension() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (float[] centroid : centroidAssignments.centroids()) {
                    buffer.asFloatBuffer().put(centroid);
                    centroidTemp.writeBytes(buffer.array(), buffer.array().length);
                }
                numCentroids = centroidAssignments.numCentroids();
                assignments = centroidAssignments.assignments();
                calculatedGlobalCentroid = centroidAssignments.globalCentroid();
                overspillAssignments = centroidAssignments.overspillAssignments();
                LOG.info("[IVFVectorsWriter] mergeOneFieldIVF DIAG-B — calculateCentroids: numCentroids={}, assignments.length={}, overspillAssignments.length={}",
                        numCentroids,
                        assignments == null ? -1 : assignments.length,
                        overspillAssignments == null ? -1 : overspillAssignments.length);
                success = true;
            } finally {
                if (success == false && centroidTempName != null) {
                    IOUtils.closeWhileHandlingException(centroidTemp);
                    org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, centroidTempName);
                }
            }
            try {
                if (numCentroids == 0) {
                    centroidOffset = ivfCentroids.getFilePointer();
                    writeMeta(fieldInfo, 0, centroidOffset, 0, 0, 0, null);
                    CodecUtil.writeFooter(centroidTemp);
                    IOUtils.close(centroidTemp);
                    return;
                }
                CodecUtil.writeFooter(centroidTemp);
                IOUtils.close(centroidTemp);

                try (IndexInput centroidsInput = mergeState.segmentInfo.dir.openInput(centroidTempName, IOContext.DEFAULT)) {
                    CentroidSupplier centroidSupplier = createCentroidSupplier(
                        centroidsInput,
                        numCentroids,
                        fieldInfo,
                        calculatedGlobalCentroid
                    );
                    // write posting lists
                    postingListOffset = ivfClusters.alignFilePointer(Float.BYTES);
                    final CentroidOffsetAndLength centroidOffsetAndLength = buildAndWritePostingsLists(
                        fieldInfo,
                        centroidSupplier,
                        floatVectorValues,
                        ivfClusters,
                        postingListOffset,
                        mergeState,
                        assignments,
                        overspillAssignments
                    );
                    postingListLength = ivfClusters.getFilePointer() - postingListOffset;
                    // write centroids
                    centroidOffset = ivfCentroids.alignFilePointer(Float.BYTES);
                    writeCentroids(
                        fieldInfo,
                        centroidSupplier,
                        assignments,
                        calculatedGlobalCentroid,
                        centroidOffsetAndLength,
                        ivfCentroids
                    );
                    centroidLength = ivfCentroids.getFilePointer() - centroidOffset;
                    // write meta
                    writeMeta(
                        fieldInfo,
                        centroidSupplier.size(),
                        centroidOffset,
                        centroidLength,
                        postingListOffset,
                        postingListLength,
                        calculatedGlobalCentroid
                    );
                }
            } finally {
                org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, centroidTempName);
            }
        } finally {
            if (docsFileName != null) {
                org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(
                    mergeState.segmentInfo.dir,
                    tempRawVectorsFileName,
                    docsFileName
                );
            } else {
                org.apache.lucene.util.IOUtils.deleteFilesIgnoringExceptions(mergeState.segmentInfo.dir, tempRawVectorsFileName);
            }
        }
    }

    private static IndexedFloatVectorValues getFloatVectorValues(FieldInfo fieldInfo, IndexInput docs, IndexInput vectors, int numVectors)
        throws IOException {
        return KmeansFloatVectorValues.build(vectors, docs, numVectors, fieldInfo.getVectorDimension());
    }

    private static int writeFloatVectorValues(
        FieldInfo fieldInfo,
        IndexOutput docsOut,
        IndexOutput vectorsOut,
        FloatVectorValues floatVectorValues
    ) throws IOException {
        int numVectors = 0;
        final ByteBuffer buffer = ByteBuffer.allocate(fieldInfo.getVectorDimension() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        // JDK 11 port: Lucene 9.12 FloatVectorValues IS the DocIdSetIterator (cursor pattern).
        // Lucene 10 added a separate iterator() / random-access vectorValue(int) — neither is in 9.12.
        for (int docV = floatVectorValues.nextDoc(); docV != NO_MORE_DOCS; docV = floatVectorValues.nextDoc()) {
            numVectors++;
            buffer.asFloatBuffer().put(floatVectorValues.vectorValue());
            vectorsOut.writeBytes(buffer.array(), buffer.array().length);
            if (docsOut != null) {
                docsOut.writeInt(floatVectorValues.docID());
            }
        }
        return numVectors;
    }

    private static int distFuncToOrd(VectorSimilarityFunction func) {
        for (int i = 0; i < SIMILARITY_FUNCTIONS.size(); i++) {
            if (SIMILARITY_FUNCTIONS.get(i).equals(func)) {
                return (byte) i;
            }
        }
        throw new IllegalArgumentException("invalid distance function: " + func);
    }

    @Override
    public final void finish() throws IOException {
        rawVectorDelegate.finish();
        if (ivfMeta != null) {
            // write end of fields marker
            ivfMeta.writeInt(-1);
            CodecUtil.writeFooter(ivfMeta);
        }
        if (ivfCentroids != null) {
            CodecUtil.writeFooter(ivfCentroids);
        }
        if (ivfClusters != null) {
            CodecUtil.writeFooter(ivfClusters);
        }
    }

    @Override
    public final void close() throws IOException {
        IOUtils.close(rawVectorDelegate, ivfMeta, ivfCentroids, ivfClusters);
    }

    @Override
    public final long ramBytesUsed() {
        return rawVectorDelegate.ramBytesUsed();
    }

    // JDK 11 port: was `private record FieldWriter(FieldInfo, FlatFieldVectorsWriter<float[]>)`.
    private static final class FieldWriter {
        private final FieldInfo fieldInfo;
        private final FlatFieldVectorsWriter<float[]> delegate;
        FieldWriter(FieldInfo fieldInfo, FlatFieldVectorsWriter<float[]> delegate) {
            this.fieldInfo = fieldInfo;
            this.delegate = delegate;
        }
        FieldInfo fieldInfo() { return fieldInfo; }
        FlatFieldVectorsWriter<float[]> delegate() { return delegate; }
    }

}
