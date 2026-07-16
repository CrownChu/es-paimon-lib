package org.elasticsearch.eslib.adapter;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * Lucene99 HNSW with Elasticsearch-compatible dimension validation.
 *
 * <p>Lucene's default {@link Lucene99HnswVectorsFormat#getMaxDimensions(String)} is 1024, while
 * Elasticsearch dense_vector allows 4096 dimensions. The actual segment format remains Lucene99
 * HNSW; this wrapper lifts the validation limit used by FieldInfos during offline index build and
 * lets offline builders opt into Lucene's parallel final-merge graph construction through {@value
 * #MERGE_WORKERS_PROPERTY}. The default remains one worker for compatibility.
 */
public final class PaimonHnswVectorsFormat extends KnnVectorsFormat {

    public static final int MAX_DIMENSIONS = 4096;
    public static final String MERGE_WORKERS_PROPERTY = "eslib.hnsw.merge-workers";
    public static final int DEFAULT_MERGE_WORKERS = 1;
    public static final int MAX_MERGE_WORKERS = 32;
    private static final String LUCENE99_HNSW_FORMAT_NAME = "Lucene99HnswVectorsFormat";

    private final Lucene99HnswVectorsFormat delegate;
    private final int mergeWorkers;
    private final boolean explicitMergeExecutor;

    public PaimonHnswVectorsFormat() {
        this(
            Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN,
            Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH,
            configuredMergeWorkers());
    }

    public PaimonHnswVectorsFormat(ExecutorService mergeExecutor) {
        this(
            Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN,
            Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH,
            configuredMergeWorkers(),
            mergeExecutor);
    }

    public PaimonHnswVectorsFormat(int maxConn, int beamWidth) {
        this(maxConn, beamWidth, configuredMergeWorkers());
    }

    public PaimonHnswVectorsFormat(int maxConn, int beamWidth, int mergeWorkers) {
        this(maxConn, beamWidth, mergeWorkers, null);
    }

    public PaimonHnswVectorsFormat(
            int maxConn,
            int beamWidth,
            int mergeWorkers,
            ExecutorService mergeExecutor) {
        this(
            new Lucene99HnswVectorsFormat(
                maxConn,
                beamWidth,
                validateMergeWorkers(mergeWorkers),
                mergeExecutor),
            mergeWorkers,
            mergeExecutor != null);
    }

    private PaimonHnswVectorsFormat(
            Lucene99HnswVectorsFormat delegate,
            int mergeWorkers,
            boolean explicitMergeExecutor) {
        super(LUCENE99_HNSW_FORMAT_NAME);
        this.delegate = delegate;
        this.mergeWorkers = mergeWorkers;
        this.explicitMergeExecutor = explicitMergeExecutor;
    }

    public static int configuredMergeWorkers() {
        String configured = System.getProperty(MERGE_WORKERS_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_MERGE_WORKERS;
        }
        final int workers;
        try {
            workers = Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "JVM property " + MERGE_WORKERS_PROPERTY
                    + " must be an integer; got: " + configured,
                e);
        }
        return validateMergeWorkers(workers);
    }

    static int validateMergeWorkers(int mergeWorkers) {
        if (mergeWorkers < 1 || mergeWorkers > MAX_MERGE_WORKERS) {
            throw new IllegalArgumentException(
                "HNSW merge workers must be between 1 and " + MAX_MERGE_WORKERS
                    + "; got: " + mergeWorkers);
        }
        return mergeWorkers;
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
        return MAX_DIMENSIONS;
    }

    @Override
    public String toString() {
        return "PaimonHnswVectorsFormat(delegate=" + delegate
            + ", mergeWorkers=" + mergeWorkers
            + ", explicitMergeExecutor=" + explicitMergeExecutor
            + ", maxDimensions=" + MAX_DIMENSIONS + ")";
    }
}
