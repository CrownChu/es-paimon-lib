/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;

import java.lang.reflect.Constructor;

public final class BuiltinAnalyzers {

    private BuiltinAnalyzers() {}

    public static Analyzer getAnalyzer(BuiltinAnalyzer name) {
        if (name == null) {
            return new StandardAnalyzer();
        }
        switch (name) {
            case STANDARD:
                return new StandardAnalyzer();
            case WHITESPACE:
                return new WhitespaceAnalyzer();
            case SIMPLE:
                return new SimpleAnalyzer();
            case KEYWORD:
                return new KeywordAnalyzer();
            case IK_SMART:
                return loadIkAnalyzer(true);
            case IK_MAX_WORD:
                return loadIkAnalyzer(false);
            default:
                return new StandardAnalyzer();
        }
    }

    private static Analyzer loadIkAnalyzer(boolean useSmart) {
        try {
            Class<?> clazz = Class.forName("org.wltea.analyzer.lucene.IKAnalyzer");
            Constructor<?> ctor = clazz.getConstructor(boolean.class);
            return (Analyzer) ctor.newInstance(useSmart);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "IK Analyzer not on classpath. Add org.wltea.analyzer:ik-analyzer dependency.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate IKAnalyzer", e);
        }
    }
}
