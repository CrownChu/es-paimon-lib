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

    /**
     * Creates a codec for offline index construction.
     *
     * <p>The executor is dedicated to vector graph merge work and is owned by the caller. It is
     * intentionally separate from the search executor accepted by {@link #createCodec(Map,
     * ExecutorService)} so build and query lifecycles cannot accidentally share a thread pool.
     */
    default Codec createCodecForBuild(
            Map<String, FieldIndexConfig> fieldConfigs, ExecutorService mergeExecutor) {
        return createCodec(fieldConfigs);
    }

    FloatVectorAccessor openFloatVectors(LeafReader reader, String field) throws IOException;

    String luceneVersion();
}
