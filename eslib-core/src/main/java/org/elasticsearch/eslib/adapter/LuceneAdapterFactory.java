package org.elasticsearch.eslib.adapter;

import java.util.ServiceLoader;

public final class LuceneAdapterFactory {

    private static volatile LuceneAdapter instance;

    private LuceneAdapterFactory() {}

    public static LuceneAdapter get() {
        if (instance == null) {
            synchronized (LuceneAdapterFactory.class) {
                if (instance == null) {
                    instance = loadAdapter();
                }
            }
        }
        return instance;
    }

    private static LuceneAdapter loadAdapter() {
        ServiceLoader<LuceneAdapter> loader = ServiceLoader.load(LuceneAdapter.class);
        for (LuceneAdapter adapter : loader) {
            return adapter;
        }
        String[] candidates = {
            "org.elasticsearch.eslib.adapter.lucene9.Lucene9Adapter",
            "org.elasticsearch.eslib.adapter.lucene10.Lucene10Adapter"
        };
        for (String className : candidates) {
            try {
                Class<?> clazz = Class.forName(className);
                return (LuceneAdapter) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException(
            "No LuceneAdapter found. Build with -Plucene=912 or -Plucene=102"
        );
    }
}
