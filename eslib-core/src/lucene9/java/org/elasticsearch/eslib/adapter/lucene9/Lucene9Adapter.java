package org.elasticsearch.eslib.adapter.lucene9;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReader;
import org.elasticsearch.eslib.adapter.FloatVectorAccessor;
import org.elasticsearch.eslib.adapter.LuceneAdapter;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class Lucene9Adapter implements LuceneAdapter {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Lucene9Adapter.class);

    @Override
    public Codec createCodec(Map<String, FieldIndexConfig> fieldConfigs) {
        LOG.info("[adapter.createCodec/1arg] called — fieldConfigs.keys={}, perFieldDetail={}, callerStack(top5)={}",
                fieldConfigs == null ? "null" : fieldConfigs.keySet(),
                describeFieldConfigs(fieldConfigs),
                callerStackTop(5));
        return new PaimonLucene9Codec(fieldConfigs);
    }

    @Override
    public Codec createCodec(Map<String, FieldIndexConfig> fieldConfigs, ExecutorService searchExecutor) {
        LOG.info("[adapter.createCodec/2arg] called — fieldConfigs.keys={}, perFieldDetail={}, searchExecutor!=null={}, callerStack(top5)={}",
                fieldConfigs == null ? "null" : fieldConfigs.keySet(),
                describeFieldConfigs(fieldConfigs),
                searchExecutor != null,
                callerStackTop(5));
        return new PaimonLucene9Codec(fieldConfigs, searchExecutor);
    }

    @Override
    public Codec createCodecForBuild(
            Map<String, FieldIndexConfig> fieldConfigs, ExecutorService mergeExecutor) {
        LOG.info(
                "[adapter.createCodecForBuild] called — fieldConfigs.keys={}, "
                        + "perFieldDetail={}, mergeExecutor!=null={}",
                fieldConfigs == null ? "null" : fieldConfigs.keySet(),
                describeFieldConfigs(fieldConfigs),
                mergeExecutor != null);
        return new PaimonLucene9Codec(
                fieldConfigs, Collections.emptyMap(), null, mergeExecutor);
    }

    private static String describeFieldConfigs(Map<String, FieldIndexConfig> fieldConfigs) {
        if (fieldConfigs == null || fieldConfigs.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, FieldIndexConfig> e : fieldConfigs.entrySet()) {
            sb.append(e.getKey()).append("=>{algo=").append(e.getValue().getAlgorithm())
                    .append(",indexType=").append(e.getValue().indexType()).append("} ");
        }
        return sb.toString().trim();
    }

    private static String callerStackTop(int depth) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        // skip 0=getStackTrace, 1=callerStackTop, 2=createCodec; start from 3
        int start = 3;
        int end = Math.min(st.length, start + depth);
        for (int i = start; i < end; i++) {
            sb.append(st[i].getClassName()).append("#").append(st[i].getMethodName())
                    .append(":").append(st[i].getLineNumber()).append(" -> ");
        }
        return sb.toString();
    }

    @Override
    public FloatVectorAccessor openFloatVectors(LeafReader reader, String field) throws IOException {
        FloatVectorValues values = reader.getFloatVectorValues(field);
        if (values == null) {
            return null;
        }
        return new Lucene9FloatVectorAccessor(values);
    }

    @Override
    public String luceneVersion() {
        return "9.12.2";
    }
}
