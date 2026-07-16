/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.adapter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104ScalarQuantizedVectorsFormat.ScalarEncoding;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

/** Lucene 10 scalar-quantized HNSW with Paimon's 4096-dimension validation limit. */
public final class PaimonInt8HnswVectorsFormat extends KnnVectorsFormat {

    private final Lucene104HnswScalarQuantizedVectorsFormat delegate;
    private final int mergeWorkers;
    private final boolean explicitMergeExecutor;

    public PaimonInt8HnswVectorsFormat() {
        this(16, 100);
    }

    public PaimonInt8HnswVectorsFormat(int maxConn, int beamWidth) {
        this(maxConn, beamWidth, PaimonHnswVectorsFormat.configuredMergeWorkers());
    }

    public PaimonInt8HnswVectorsFormat(ExecutorService mergeExecutor) {
        this(16, 100, PaimonHnswVectorsFormat.configuredMergeWorkers(), mergeExecutor);
    }

    public PaimonInt8HnswVectorsFormat(int maxConn, int beamWidth, int mergeWorkers) {
        this(maxConn, beamWidth, mergeWorkers, null);
    }

    public PaimonInt8HnswVectorsFormat(
            int maxConn,
            int beamWidth,
            int mergeWorkers,
            ExecutorService mergeExecutor) {
        super(Lucene104HnswScalarQuantizedVectorsFormat.NAME);
        this.mergeWorkers = PaimonHnswVectorsFormat.validateMergeWorkers(mergeWorkers);
        this.explicitMergeExecutor = mergeExecutor != null;
        this.delegate =
                new Lucene104HnswScalarQuantizedVectorsFormat(
                        ScalarEncoding.UNSIGNED_BYTE,
                        maxConn,
                        beamWidth,
                        this.mergeWorkers,
                        mergeExecutor);
    }

    @Override
    public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
        return delegate.fieldsWriter(state);
    }

    @Override
    public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
        return delegate.fieldsReader(state);
    }

    @Override
    public int getMaxDimensions(String fieldName) {
        return PaimonHnswVectorsFormat.MAX_DIMENSIONS;
    }

    @Override
    public String toString() {
        return "PaimonInt8HnswVectorsFormat(delegate="
                + delegate
                + ", mergeWorkers="
                + mergeWorkers
                + ", explicitMergeExecutor="
                + explicitMergeExecutor
                + ", maxDimensions="
                + PaimonHnswVectorsFormat.MAX_DIMENSIONS
                + ")";
    }
}
