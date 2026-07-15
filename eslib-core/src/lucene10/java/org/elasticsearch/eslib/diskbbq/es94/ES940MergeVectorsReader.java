/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.diskbbq.es94;

import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.FlatVectorsReader;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.search.AcceptDocs;
import org.apache.lucene.search.KnnCollector;

import java.io.IOException;

/**
 * Minimal standalone reader for the ES940 DiskBBQ format, sufficient for the OFFLINE BUILD ONLY.
 *
 * <p>Lucene opens every source segment's {@link KnnVectorsReader} to run merges. ES940's writer
 * ({@link IVFVectorsWriter#mergeOneFieldIVF}) rebuilds the IVF index from scratch by reading the raw
 * float vectors via {@code MergedVectorValues.mergeFloatVectorValues}, which only needs each source
 * segment to expose {@link #getFloatVectorValues}. It never reads back the old IVF postings or
 * centroids. So during the build we only delegate raw-vector access to the underlying flat reader;
 * {@code search} is never invoked offline (the mount path uses the full paimon-store ES940 reader
 * instead). This is why the format ships no full reader in the standalone lib.
 */
final class ES940MergeVectorsReader extends KnnVectorsReader {

    private final FlatVectorsReader rawVectorsReader;

    ES940MergeVectorsReader(FlatVectorsReader rawVectorsReader) {
        this.rawVectorsReader = rawVectorsReader;
    }

    @Override
    public void checkIntegrity() throws IOException {
        rawVectorsReader.checkIntegrity();
    }

    @Override
    public FloatVectorValues getFloatVectorValues(String field) throws IOException {
        return rawVectorsReader.getFloatVectorValues(field);
    }

    @Override
    public ByteVectorValues getByteVectorValues(String field) throws IOException {
        return rawVectorsReader.getByteVectorValues(field);
    }

    @Override
    public void search(String field, float[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) {
        throw new UnsupportedOperationException("ES940DiskBBQVectorsFormat search is build-only in the standalone lib");
    }

    @Override
    public void search(String field, byte[] target, KnnCollector knnCollector, AcceptDocs acceptDocs) {
        throw new UnsupportedOperationException("ES940DiskBBQVectorsFormat search is build-only in the standalone lib");
    }

    @Override
    public void close() throws IOException {
        rawVectorsReader.close();
    }
}
