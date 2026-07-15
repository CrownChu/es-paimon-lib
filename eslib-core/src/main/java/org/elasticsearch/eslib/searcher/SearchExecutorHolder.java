package org.elasticsearch.eslib.searcher;

import java.util.concurrent.ExecutorService;

/**
 * Thread-local holder for the search executor. Set before DirectoryReader.open() so
 * that the codec's no-arg constructor (invoked by Lucene SPI) can pick it up.
 * Always clear after open completes.
 */
public final class SearchExecutorHolder {

    private static final ThreadLocal<ExecutorService> HOLDER = new ThreadLocal<>();

    private SearchExecutorHolder() {}

    public static void set(ExecutorService executor) {
        HOLDER.set(executor);
    }

    public static ExecutorService get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
