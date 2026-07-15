package org.elasticsearch.eslib.adapter.lucene10;

import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.elasticsearch.eslib.adapter.FloatVectorAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class Lucene10FloatVectorAccessor implements FloatVectorAccessor {

    private final float[][] vectors;
    private final int dimension;

    Lucene10FloatVectorAccessor(FloatVectorValues values) throws IOException {
        this.dimension = values.dimension();
        List<float[]> vectorList = new ArrayList<>();
        var iterator = values.iterator();
        while (iterator.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            float[] vec = values.vectorValue(iterator.index());
            vectorList.add(vec.clone());
        }
        this.vectors = vectorList.toArray(new float[0][]);
    }

    @Override
    public int size() {
        return vectors.length;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] vectorValue(int ord) throws IOException {
        return vectors[ord];
    }
}
