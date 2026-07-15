package org.elasticsearch.eslib.adapter;

import java.io.IOException;

public interface FloatVectorAccessor {

    int size();

    int dimension();

    float[] vectorValue(int ord) throws IOException;
}
