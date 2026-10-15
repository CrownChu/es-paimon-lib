/* Licensed under the Elastic License 2.0. */
package org.elasticsearch.eslib.nativeindex;

import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Compiles one explicit native capability set; never changes an algorithm silently. */
final class NativeMapping {
    final Map<String, FieldIndexConfig> fields;
    final byte[] mapping, config;

    NativeMapping(Map<String, FieldIndexConfig> source) throws IOException {
        this(source, NativeIkDictionary.selected(source));
    }

    NativeMapping(Map<String, FieldIndexConfig> source, NativeIkDictionary dictionary)
            throws IOException {
        if (source == null || source.isEmpty() || source.size() > 1024)
            throw new IllegalArgumentException("Native fields must contain 1..1024 entries");
        Map<String, FieldIndexConfig> selected = new TreeMap<>(NativeJson.UTF8_ORDER);
        Map<String, Object> properties = NativeJson.object(), configs = NativeJson.object();
        if (NativeIkDictionary.required(source)) {
            if (dictionary == null)
                throw new IOException("Native IK dictionary identity is missing");
            configs.put("__native_ik_dictionary", dictionary.files);
        }
        for (Map.Entry<String, FieldIndexConfig> entry : source.entrySet()) {
            String name = entry.getKey();
            FieldIndexConfig field = entry.getValue();
            if (name == null
                    || field == null
                    || !name.equals(field.fieldName())
                    || name.isEmpty()
                    || name.startsWith("_")
                    || name.indexOf('.') >= 0
                    || name.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(
                        "Native field names must be unambiguous non-reserved top-level names");
            }
            NativeJson.utf8(name);
            selected.put(name, field);
            Map<String, Object> physical = physical(field);
            properties.put(name, physical);
            configs.put(
                    name,
                    NativeJson.object(
                            "type",
                            field.indexType().name(),
                            "algorithm",
                            field.algorithm() == null ? null : field.algorithm().name(),
                            "dimension",
                            field.dimension(),
                            "metric",
                            field.metric(),
                            "parameters",
                            new TreeMap<>(field.algorithmParams()),
                            "analyzer",
                            field.analyzer() == null ? null : field.analyzer().name(),
                            "scalarType",
                            field.scalarType() == null ? null : field.scalarType().name()));
        }
        fields = Collections.unmodifiableMap(selected);
        mapping =
                NativeJson.encode(
                        NativeJson.object(
                                "_source",
                                NativeJson.object("enabled", false),
                                "properties",
                                properties));
        config = NativeJson.encode(configs);
        if (mapping.length > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Native mapping exceeds 8 MiB");
    }

    private static Map<String, Object> physical(FieldIndexConfig field) {
        switch (field.indexType()) {
            case VECTOR:
                if (field.algorithm() != VectorAlgorithm.HNSW
                        && field.algorithm() != VectorAlgorithm.INT8_HNSW
                        && field.algorithm() != VectorAlgorithm.DISKBBQ)
                    throw unsupported(
                            "Native vector support requires HNSW, INT8_HNSW or DISKBBQ; other"
                                + " algorithms retain their explicit identity");
                if (field.dimension() <= 0 || field.dimension() > 1024)
                    throw unsupported("Native vector fields support 1..1024 dimensions");
                if (!field.algorithmParams().isEmpty())
                    throw unsupported(
                            "Native vector tuning parameters require independent compatibility"
                                    + " validation");
                return NativeJson.object(
                        "dims",
                        field.dimension(),
                        "index",
                        true,
                        "index_options",
                        NativeJson.object(
                                "type",
                                field.algorithm() == VectorAlgorithm.INT8_HNSW
                                        ? "int8_hnsw"
                                        : field.algorithm() == VectorAlgorithm.DISKBBQ
                                                ? "bbq_disk"
                                                : "hnsw"),
                        "similarity",
                        metric(field.metric()),
                        "type",
                        "dense_vector");
            case FULLTEXT:
                BuiltinAnalyzer analyzer =
                        field.analyzer() == null ? BuiltinAnalyzer.STANDARD : field.analyzer();
                return NativeJson.object("analyzer", analyzer.getName(), "type", "text");
            case KEYWORD:
                return NativeJson.object("type", "keyword");
            case DATE:
                return NativeJson.object("format", "epoch_millis", "type", "date");
            case SCALAR:
                if (field.scalarType() == null)
                    throw new IllegalArgumentException("Native scalar type is missing");
                switch (field.scalarType()) {
                    case INT:
                        return NativeJson.object("coerce", false, "type", "integer");
                    case LONG:
                        return NativeJson.object("coerce", false, "type", "long");
                    case FLOAT:
                        return NativeJson.object("coerce", false, "type", "float");
                    case DOUBLE:
                        return NativeJson.object("coerce", false, "type", "double");
                    case KEYWORD:
                        return NativeJson.object("type", "keyword");
                    case DATE:
                        return NativeJson.object("format", "epoch_millis", "type", "date");
                    default:
                        throw unsupported("Native geo mapping requires its own oracle validation");
                }
            default:
                throw unsupported("Native field type is not enabled: " + field.indexType());
        }
    }

    static String metric(String metric) {
        if ("cosine".equals(metric)) return "cosine";
        if ("l2".equals(metric) || "l2_norm".equals(metric) || "euclidean".equals(metric))
            return "l2_norm";
        if ("ip".equals(metric) || "dot_product".equals(metric)) return "dot_product";
        throw unsupported("Native vector metric requires an explicit compatible identity");
    }

    FieldIndexConfig field(String name) {
        FieldIndexConfig value = fields.get(name);
        if (value == null)
            throw new IllegalArgumentException("Unknown native index field: " + name);
        return value;
    }

    static void requireVector(FieldIndexConfig field, float[] vector) {
        if (field.indexType() != FieldIndexConfig.IndexType.VECTOR
                || vector == null
                || vector.length != field.dimension()) {
            throw new IllegalArgumentException("Native vector field or dimension differs");
        }
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value))
                throw new IllegalArgumentException("Native vectors must be finite");
            squared += (double) value * value;
        }
        if ("cosine".equals(metric(field.metric())) && squared == 0)
            throw new IllegalArgumentException("Cosine vector must be nonzero");
        if ("dot_product".equals(metric(field.metric())) && Math.abs(squared - 1.0) >= 1e-3) {
            throw new IllegalArgumentException("Native dot-product vectors must be unit length");
        }
    }

    static UnsupportedOperationException unsupported(String message) {
        return new UnsupportedOperationException(message);
    }
}
