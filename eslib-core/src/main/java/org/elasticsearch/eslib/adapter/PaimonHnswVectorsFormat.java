package org.elasticsearch.eslib.adapter;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

/**
 * Lucene99 HNSW with Elasticsearch-compatible dimension validation.
 *
 * <p>Lucene's default {@link Lucene99HnswVectorsFormat#getMaxDimensions(String)} is 1024, while
 * Elasticsearch dense_vector allows 4096 dimensions. The actual segment format remains Lucene99 HNSW;
 * this wrapper only lifts the validation limit used by FieldInfos during offline index build.
 */
public final class PaimonHnswVectorsFormat extends KnnVectorsFormat {

    public static final int MAX_DIMENSIONS = 4096;
    private static final String LUCENE99_HNSW_FORMAT_NAME = "Lucene99HnswVectorsFormat";

    private final Lucene99HnswVectorsFormat delegate;

    public PaimonHnswVectorsFormat() {
        this(new Lucene99HnswVectorsFormat());
    }

    public PaimonHnswVectorsFormat(int maxConn, int beamWidth) {
        this(new Lucene99HnswVectorsFormat(maxConn, beamWidth));
    }

    private PaimonHnswVectorsFormat(Lucene99HnswVectorsFormat delegate) {
        super(LUCENE99_HNSW_FORMAT_NAME);
        this.delegate = delegate;
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
        return "PaimonHnswVectorsFormat(delegate=" + delegate + ", maxDimensions=" + MAX_DIMENSIONS + ")";
    }
}
