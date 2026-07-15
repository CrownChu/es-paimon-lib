/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.eslib.cluster;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.RandomAccessInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Unified class that can represent on-heap and off-heap vector values.
 */
public final class KmeansFloatVectorValues extends FloatVectorValues {

    private final VectorSupplier vectors;
    private final DocSupplier docs;
    private final int numVectors;

    private KmeansFloatVectorValues(VectorSupplier vectors, DocSupplier docs, int numVectors) {
        this.vectors = vectors;
        this.docs = docs;
        this.numVectors = numVectors;
    }

    /**
     * Build an instance from on-heap data structures.
     */
    public static KmeansFloatVectorValues build(List<float[]> vectors, int[] docs, int dim) {
        VectorSupplier vectorSupplier = new OnHeapVectorSupplier(vectors, dim);
        DocSupplier docSupplier = docs == null ? null : new OnHeapDocSupplier(docs);
        return new KmeansFloatVectorValues(vectorSupplier, docSupplier, vectors.size());
    }

    /**
     * Builds an instance from off-heap data structures. Vectors are expected to be written as
     * little endian floats one after the other. Docs are expected to be written as little endian ints
     * one after the other.
     */
    public static KmeansFloatVectorValues build(IndexInput vectors, IndexInput docs, int numVectors, int dims) throws IOException {
        long vectorLength = (long) dims * Float.BYTES;
        float[] vector = new float[dims];
        VectorSupplier vectorSupplier = new OffHeapVectorSupplier(vectors, vector, vectorLength);
        DocSupplier docSupplier;
        if (docs == null) {
            docSupplier = null;
        } else {
            RandomAccessInput randomDocs = docs.randomAccessSlice(0, docs.length());
            docSupplier = new OffHeapDocSupplier(docs, randomDocs);
        }
        return new KmeansFloatVectorValues(vectorSupplier, docSupplier, numVectors);
    }

    @Override
    public float[] vectorValue(int ord) throws IOException {
        return vectors.vector(ord);
    }

    @Override
    public FloatVectorValues copy() {
        return new KmeansFloatVectorValues(vectors.copy(), docs != null ? docs.copy() : null, numVectors);
    }

    @Override
    public int dimension() {
        return vectors.dims();
    }

    @Override
    public int size() {
        return numVectors;
    }

    @Override
    public int ordToDoc(int ord) {
        if (docs == null) {
            return ord;
        }
        return docs.ordToDoc(ord);
    }

    private interface VectorSupplier {

        float[] vector(int ord) throws IOException;

        int dims();

        VectorSupplier copy();
    }

    private static final class OnHeapVectorSupplier implements VectorSupplier {

        private final List<float[]> vectors;
        private final int dims;

        OnHeapVectorSupplier(List<float[]> vectors, int dims) {
            this.vectors = vectors;
            this.dims = dims;
        }

        @Override
        public float[] vector(int ord) {
            return vectors.get(ord);
        }

        @Override
        public int dims() {
            return dims;
        }

        @Override
        public VectorSupplier copy() {
            return this;
        }

        public List<float[]> vectors() { return vectors; }
    }

    private static final class OffHeapVectorSupplier implements VectorSupplier {

        private final IndexInput vectors;
        private final float[] vector;
        private final long vectorLength;

        OffHeapVectorSupplier(IndexInput vectors, float[] vector, long vectorLength) {
            this.vectors = vectors;
            this.vector = vector;
            this.vectorLength = vectorLength;
        }

        @Override
        public float[] vector(int ord) throws IOException {
            vectors.seek(ord * vectorLength);
            vectors.readFloats(vector, 0, vector.length);
            return vector;
        }

        @Override
        public int dims() {
            return vector.length;
        }

        @Override
        public VectorSupplier copy() {
            return new OffHeapVectorSupplier(vectors.clone(), vector.clone(), vectorLength);
        }

        public IndexInput vectors() { return vectors; }
        public float[] vector() { return vector; }
        public long vectorLength() { return vectorLength; }
    }

    private interface DocSupplier {
        int ordToDoc(int ord);

        DocSupplier copy();
    }

    private static final class OnHeapDocSupplier implements DocSupplier {

        private final int[] docs;

        OnHeapDocSupplier(int[] docs) {
            this.docs = docs;
        }

        @Override
        public int ordToDoc(int ord) {
            return docs[ord];
        }

        @Override
        public DocSupplier copy() {
            return this;
        }

        public int[] docs() { return docs; }
    }

    private static final class OffHeapDocSupplier implements DocSupplier {

        private final IndexInput docs;
        private final RandomAccessInput randomDocs;

        OffHeapDocSupplier(IndexInput docs, RandomAccessInput randomDocs) {
            this.docs = docs;
            this.randomDocs = randomDocs;
        }

        @Override
        public int ordToDoc(int ord) {
            try {
                return randomDocs.readInt((long) ord * Integer.BYTES);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public DocSupplier copy() {
            IndexInput docsCopy = docs.clone();
            try {
                RandomAccessInput randomDocsCopy = docsCopy.randomAccessSlice(0, docsCopy.length());
                return new OffHeapDocSupplier(docsCopy, randomDocsCopy);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public IndexInput docs() { return docs; }
        public RandomAccessInput randomDocs() { return randomDocs; }
    }
}
