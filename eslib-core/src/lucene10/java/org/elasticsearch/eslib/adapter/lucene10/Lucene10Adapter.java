package org.elasticsearch.eslib.adapter.lucene10;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReader;
import org.elasticsearch.eslib.adapter.FloatVectorAccessor;
import org.elasticsearch.eslib.adapter.LuceneAdapter;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class Lucene10Adapter implements LuceneAdapter {

    @Override
    public Codec createCodec(Map<String, FieldIndexConfig> fieldConfigs) {
        return new PaimonLucene10Codec(fieldConfigs);
    }

    @Override
    public Codec createCodecForBuild(
            Map<String, FieldIndexConfig> fieldConfigs, ExecutorService mergeExecutor) {
        return new PaimonLucene10Codec(fieldConfigs, mergeExecutor);
    }

    @Override
    public FloatVectorAccessor openFloatVectors(LeafReader reader, String field) throws IOException {
        FloatVectorValues values = reader.getFloatVectorValues(field);
        if (values == null) {
            return null;
        }
        return new Lucene10FloatVectorAccessor(values);
    }

    @Override
    public String luceneVersion() {
        return "10.2.1";
    }
}
