package org.elasticsearch.eslib.adapter.lucene9;

import org.apache.lucene.index.FloatVectorValues;
import org.elasticsearch.eslib.adapter.FloatVectorAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class Lucene9FloatVectorAccessor implements FloatVectorAccessor {

    private final List<float[]> vectors;
    private final int dimension;

    Lucene9FloatVectorAccessor(FloatVectorValues values) throws IOException {
        this.dimension = values.dimension();
        this.vectors = new ArrayList<>(values.size());
        int doc;
        while ((doc = values.nextDoc()) != FloatVectorValues.NO_MORE_DOCS) {
            float[] v = values.vectorValue();
            float[] copy = new float[v.length];
            System.arraycopy(v, 0, copy, 0, v.length);
            vectors.add(copy);
        }
    }

    @Override
    public int size() {
        return vectors.size();
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] vectorValue(int ord) throws IOException {
        if (ord < 0 || ord >= vectors.size()) {
            throw new IOException("ord " + ord + " out of range [0, " + vectors.size() + ")");
        }
        return vectors.get(ord);
    }
}
