/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.scalar;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatDocValuesField;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.eslib.api.model.ScalarFieldType;

import java.lang.reflect.Array;

public final class ScalarFieldHandler {

    private ScalarFieldHandler() {}

    public static void addToDocument(Document doc, String fieldName, Object value, ScalarFieldType type) {
        switch (type) {
            case INT: {
                if (isMultiValue(value)) {
                    forEachValue(value, v -> addInt(doc, fieldName, v, true));
                } else {
                    addInt(doc, fieldName, value, false);
                }
                break;
            }
            case LONG: {
                if (isMultiValue(value)) {
                    forEachValue(value, v -> addLong(doc, fieldName, v, true));
                } else {
                    addLong(doc, fieldName, value, false);
                }
                break;
            }
            case FLOAT: {
                ensureSingleValue(value, type);
                float v = ((Number) value).floatValue();
                doc.add(new FloatPoint(fieldName, v));
                doc.add(new FloatDocValuesField(fieldName, v));
                break;
            }
            case DOUBLE: {
                ensureSingleValue(value, type);
                double v = ((Number) value).doubleValue();
                doc.add(new DoublePoint(fieldName, v));
                doc.add(new DoubleDocValuesField(fieldName, v));
                break;
            }
            case KEYWORD: {
                if (isMultiValue(value)) {
                    forEachValue(value, v -> addKeyword(doc, fieldName, v, true));
                } else {
                    addKeyword(doc, fieldName, value, false);
                }
                break;
            }
            case DATE: {
                ensureSingleValue(value, type);
                long v = ((Number) value).longValue();
                doc.add(new LongPoint(fieldName, v));
                doc.add(new NumericDocValuesField(fieldName, v));
                break;
            }
            case GEO_POINT: {
                // value is expected to be a double[] with [lat, lon]
                double[] latLon = (double[]) value;
                doc.add(new LatLonPoint(fieldName, latLon[0], latLon[1]));
                doc.add(new LatLonDocValuesField(fieldName, latLon[0], latLon[1]));
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown scalar type: " + type);
        }
    }

    private static void addInt(Document doc, String fieldName, Object value, boolean multiValued) {
        int v = ((Number) value).intValue();
        doc.add(new IntPoint(fieldName, v));
        if (multiValued) {
            doc.add(new SortedNumericDocValuesField(fieldName, v));
        } else {
            doc.add(new NumericDocValuesField(fieldName, v));
        }
    }

    private static void addLong(Document doc, String fieldName, Object value, boolean multiValued) {
        long v = ((Number) value).longValue();
        doc.add(new LongPoint(fieldName, v));
        if (multiValued) {
            doc.add(new SortedNumericDocValuesField(fieldName, v));
        } else {
            doc.add(new NumericDocValuesField(fieldName, v));
        }
    }

    private static void addKeyword(Document doc, String fieldName, Object value, boolean multiValued) {
        String v = value.toString();
        doc.add(new StringField(fieldName, v, Field.Store.NO));
        if (multiValued) {
            doc.add(new SortedSetDocValuesField(fieldName, new BytesRef(v)));
        } else {
            doc.add(new SortedDocValuesField(fieldName, new BytesRef(v)));
        }
    }

    private static boolean isMultiValue(Object value) {
        return value != null && (value.getClass().isArray() || value instanceof Iterable<?>);
    }

    private static void ensureSingleValue(Object value, ScalarFieldType type) {
        if (isMultiValue(value)) {
            throw new IllegalArgumentException("Multi-valued scalar is not supported for type: " + type);
        }
    }

    private static void forEachValue(Object value, ValueConsumer consumer) {
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                if (element != null) {
                    consumer.accept(element);
                }
            }
            return;
        }

        for (Object element : (Iterable<?>) value) {
            if (element != null) {
                consumer.accept(element);
            }
        }
    }

    private interface ValueConsumer {
        void accept(Object value);
    }
}
