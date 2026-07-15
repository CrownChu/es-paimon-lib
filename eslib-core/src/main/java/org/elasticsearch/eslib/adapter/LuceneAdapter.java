package org.elasticsearch.eslib.adapter;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.LeafReader;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public interface LuceneAdapter {

    Codec createCodec(Map<String, FieldIndexConfig> fieldConfigs);

    default Codec createCodec(Map<String, FieldIndexConfig> fieldConfigs, ExecutorService searchExecutor) {
        return createCodec(fieldConfigs);
    }

    FloatVectorAccessor openFloatVectors(LeafReader reader, String field) throws IOException;

    String luceneVersion();
}
