/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.builder;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.eslib.adapter.LuceneAdapter;
import org.elasticsearch.eslib.adapter.LuceneAdapterFactory;
import org.elasticsearch.eslib.analyzer.BuiltinAnalyzers;
import org.elasticsearch.eslib.api.ESIndexBuilder;
import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.scalar.ScalarFieldHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class DefaultESIndexBuilder implements ESIndexBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultESIndexBuilder.class);

    private final Map<String, FieldIndexConfig> fieldConfigs;
    private final Path outputDir;
    private final IndexWriter writer;
    private final Directory directory;
    private final Map<String, Analyzer> fieldAnalyzers;
    private final Analyzer defaultAnalyzer;
    private final Analyzer indexAnalyzer;
    private final Map<Long, Document> pendingDocs;
    private final boolean normalizeVectors;
    private boolean built;
    private boolean closeStarted;
    private boolean closed;
    private long lastFlushedDocId = -1;

    /**
     * JVM property name controlling whether {@link #addVector} L2-normalizes the input
     * vector before handing it to Lucene. When the underlying KnnVectorsFormat indexes
     * by L2 distance (e.g. PaimonDiskBBQVectorsFormat) and the ground truth is cosine,
     * normalizing both stored and query vectors to unit length collapses
     * {@code L2² = 2(1 - cos)}, so L2 ordering recovers cosine ordering. Disabled by
     * default to preserve raw vector bytes for tables whose distance is already L2.
     * Set via {@code -Deslib.normalize-vector=true} on the BuildIndexJob JVM.
     * Clients must mirror — submit normalized query vectors to ES at query time, or
     * recall stays L2-vs-cos misaligned.
     */
    public static final String NORMALIZE_VECTOR_PROP = "eslib.normalize-vector";

    // Diagnostic counters (per-instance, single-thread use per shard task — no atomics needed).
    private long addVectorCalls = 0;
    private long addScalarCalls = 0;
    private long addTextCalls = 0;
    private long firstDocId = Long.MAX_VALUE;
    private long lastDocId = Long.MIN_VALUE;
    private final Map<String, Long> perFieldVectorCount = new HashMap<>();
    private boolean loggedFirstVectorSample = false;

    public DefaultESIndexBuilder(Map<String, FieldIndexConfig> fieldConfigs) throws IOException {
        this(
            validateFieldConfigs(fieldConfigs),
            Files.createTempDirectory("eslib-index-"),
            true);
    }

    public DefaultESIndexBuilder(Map<String, FieldIndexConfig> fieldConfigs, Path outputDir) throws IOException {
        this(validateFieldConfigs(fieldConfigs), outputDir, false);
    }

    private DefaultESIndexBuilder(
            Map<String, FieldIndexConfig> fieldConfigs,
            Path outputDir,
            boolean deleteOutputOnFailure) throws IOException {
        this.fieldConfigs = new HashMap<>(Objects.requireNonNull(fieldConfigs, "fieldConfigs"));
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.pendingDocs = new HashMap<>();
        this.normalizeVectors = Boolean.parseBoolean(System.getProperty(NORMALIZE_VECTOR_PROP, "false"));
        if (normalizeVectors) {
            LOG.debug("[{}] enabled — addVector will L2-normalize inputs to unit length before indexing", NORMALIZE_VECTOR_PROP);
        }

        Map<String, Analyzer> analyzers = new HashMap<>();
        Analyzer candidateDefaultAnalyzer = null;
        Analyzer candidateIndexAnalyzer = null;
        Directory candidateDirectory = null;
        IndexWriter candidateWriter = null;
        try {
            for (Map.Entry<String, FieldIndexConfig> entry : this.fieldConfigs.entrySet()) {
                if (entry.getValue().indexType() == FieldIndexConfig.IndexType.FULLTEXT) {
                    BuiltinAnalyzer ba = entry.getValue().analyzer();
                    analyzers.put(entry.getKey(), BuiltinAnalyzers.getAnalyzer(ba));
                }
            }

            LuceneAdapter adapter = LuceneAdapterFactory.get();
            Codec codec = adapter.createCodec(this.fieldConfigs);

            // Use the per-field configured analyzer at write time so it matches the analyzer the
            // searcher applies at query time. Fields without a configured analyzer (and all non-
            // FULLTEXT fields) fall back to StandardAnalyzer.
            candidateDefaultAnalyzer = BuiltinAnalyzers.getAnalyzer(BuiltinAnalyzer.STANDARD);
            candidateIndexAnalyzer =
                    new org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper(
                            candidateDefaultAnalyzer, analyzers);
            IndexWriterConfig iwc = new IndexWriterConfig(candidateIndexAnalyzer);
            iwc.setCodec(codec);
            iwc.setUseCompoundFile(false);

        // Sort segments by the paimon global _ROW_ID NumericDocValuesField. This
        // ensures (a) intra-segment docs flushed mid-build are stored by _ROW_ID and
        // (b) forceMerge(1) emits a globally-sorted final segment — fixing the
        // .vec ↔ docId misalignment we previously saw when TieredMergePolicy
        // concatenated input segments in tier order rather than insertion order.
        // The fetch-side `_ROW_ID = rangeFrom + docId` arithmetic then holds because
        // post-sort docId K = the K-th smallest _ROW_ID in the shard.
        // Use SortedNumericSortField (not a plain SortField): Lucene accepts a plain
        // SortField(LONG) as an index sort, but Elasticsearch's Segment.writeSegmentSort only
        // serializes SortedNumericSortField/SortedSetSortField for numeric index sorts and rejects
        // a plain numeric SortField with "invalid index sort field:<long: \"_ROW_ID\">". Pairing
        // it requires _ROW_ID to be a SortedNumericDocValuesField (see getOrCreateDoc) — Lucene's
        // index-time validateIndexSortDVType demands the field doc-values type match the sort.
            iwc.setIndexSort(
                    new Sort(new SortedNumericSortField(ROW_ID_FIELD, SortField.Type.LONG)));

            candidateDirectory = FSDirectory.open(outputDir);
            candidateWriter = new IndexWriter(candidateDirectory, iwc);
        } catch (IOException | RuntimeException | Error failure) {
            closeOnConstructionFailure(
                    failure,
                    candidateWriter,
                    candidateDirectory,
                    candidateIndexAnalyzer,
                    analyzers.values(),
                    candidateDefaultAnalyzer);
            if (deleteOutputOnFailure) {
                try {
                    IOUtils.rm(outputDir);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        this.fieldAnalyzers = analyzers;
        this.defaultAnalyzer = candidateDefaultAnalyzer;
        this.indexAnalyzer = candidateIndexAnalyzer;
        this.directory = candidateDirectory;
        this.writer = candidateWriter;
    }

    private static Map<String, FieldIndexConfig> validateFieldConfigs(
            Map<String, FieldIndexConfig> fieldConfigs) {
        Objects.requireNonNull(fieldConfigs, "fieldConfigs");
        Map<String, FieldIndexConfig> copy = new HashMap<>(fieldConfigs.size());
        for (Map.Entry<String, FieldIndexConfig> entry : fieldConfigs.entrySet()) {
            String fieldName = Objects.requireNonNull(entry.getKey(), "field config name");
            FieldIndexConfig config =
                Objects.requireNonNull(entry.getValue(), "field config for " + fieldName);
            if (!fieldName.equals(config.fieldName())) {
                throw new IllegalArgumentException(
                    "Field config map key '"
                        + fieldName
                        + "' does not match config field name '"
                        + config.fieldName()
                        + "'");
            }
            copy.put(fieldName, config);
        }
        return copy;
    }

    private static void closeOnConstructionFailure(
            Throwable failure,
            Closeable writer,
            Closeable directory,
            Closeable indexAnalyzer,
            Collection<? extends Closeable> fieldAnalyzers,
            Closeable defaultAnalyzer) {
        List<Closeable> resources = new ArrayList<>();
        resources.add(writer);
        resources.add(directory);
        resources.add(indexAnalyzer);
        resources.addAll(fieldAnalyzers);
        resources.add(defaultAnalyzer);
        for (Closeable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** NumericDocValues field name used as the index-sort key. */
    public static final String ROW_ID_FIELD = "_ROW_ID";

    @Override
    public void addVector(String fieldName, long docId, float[] vector) throws IOException {
        checkNotBuilt();
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.VECTOR) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not configured as VECTOR");
        }
        if (vector == null || vector.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Vector field '" + fieldName + "' expects dimension " + config.dimension()
                    + " but received " + (vector == null ? "null" : vector.length));
        }
        boolean diskBBQCosine = isDiskBBQCosine(config);
        VectorSimilarityFunction sim =
            diskBBQCosine
                ? VectorSimilarityFunction.DOT_PRODUCT
                : parseSimilarity(config.metric());
        // tp14: opt-in L2-normalize so that L2 ranking == cosine ranking on the indexed side.
        // Operates on a fresh copy — caller's array is left untouched (caller may still want
        // the raw vector for source hydration). When disabled this is a no-op.
        float[] toIndex =
            diskBBQCosine
                ? VectorUtil.l2normalize(Arrays.copyOf(vector, vector.length))
                : vector;
        if (!diskBBQCosine && normalizeVectors && vector.length > 0) {
            double sumSq = 0.0;
            for (float v : vector) sumSq += (double) v * v;
            if (sumSq > 0.0) {
                double inv = 1.0 / Math.sqrt(sumSq);
                toIndex = new float[vector.length];
                for (int i = 0; i < vector.length; i++) {
                    toIndex[i] = (float) (vector[i] * inv);
                }
            }
        }
        Document doc = getOrCreateDoc(docId);
        KnnFloatVectorField knnField = new KnnFloatVectorField(fieldName, toIndex, sim);
        doc.add(knnField);
        addVectorCalls++;
        perFieldVectorCount.merge(fieldName, 1L, Long::sum);
        if (LOG.isDebugEnabled() && !loggedFirstVectorSample) {
            int previewLen = Math.min(3, vector == null ? 0 : vector.length);
            float[] preview = vector == null ? new float[0] : Arrays.copyOf(vector, previewLen);
            LOG.debug(
                "[addVector] FIRST sample — field='{}' docId={} dim={} sim={} first3={}",
                fieldName, docId, (vector == null ? -1 : vector.length), sim, Arrays.toString(preview));
            // Self-check: what does IndexWriter actually SEE about this field?
            // If vectorDimension() reports 0, IndexingChain bypasses KnnVectorsWriter → no .vec/.cenivf
            // files. Also dump Document.add result by re-iterating doc.getFields() to confirm the
            // field really landed and exposes the right type.
            try {
                org.apache.lucene.index.IndexableFieldType ft = knnField.fieldType();
                LOG.debug(
                    "[addVector] knnField self-check — fieldType.class={}, vectorDimension={}, "
                        + "vectorEncoding={}, vectorSimilarityFunction={}, knnField.class={}",
                    ft.getClass().getName(),
                    ft.vectorDimension(),
                    ft.vectorEncoding(),
                    ft.vectorSimilarityFunction(),
                    knnField.getClass().getName());
                int fieldsInDoc = 0;
                int vectorFieldsInDoc = 0;
                String firstVecClass = "none";
                int firstVecDim = -1;
                for (org.apache.lucene.index.IndexableField f : doc.getFields()) {
                    fieldsInDoc++;
                    if (f.fieldType().vectorDimension() > 0) {
                        if (vectorFieldsInDoc == 0) {
                            firstVecClass = f.getClass().getName();
                            firstVecDim = f.fieldType().vectorDimension();
                        }
                        vectorFieldsInDoc++;
                    }
                }
                LOG.debug(
                    "[addVector] doc inventory — totalFields={}, vectorFields={}, firstVecClass={}, firstVecDim={}",
                    fieldsInDoc, vectorFieldsInDoc, firstVecClass, firstVecDim);
            } catch (RuntimeException selfCheckErr) {
                LOG.warn("[addVector] self-check failed: {}", selfCheckErr.toString());
            }
            loggedFirstVectorSample = true;
        }
        if (LOG.isDebugEnabled() && addVectorCalls % 100_000L == 0L) {
            LOG.debug(
                "[addVector] {} calls; rowId range so far=[{}, {}]; per-field counts={}",
                addVectorCalls, firstDocId, lastDocId, perFieldVectorCount);
        }
        // [DIAG-av] First 20 + every 100K addVector calls — checkpoint at the Lucene-builder
        // boundary. If [DIAG-pe] vector @ rowId X == [DIAG-wr] vector @ docId X == [DIAG-av]
        // vector @ docId X, write path is consistent up to here. Anything still wrong must be
        // in IndexWriter.addDocument / segment flush / merge.
        if (LOG.isDebugEnabled() && (addVectorCalls <= 20 || addVectorCalls % 100_000L == 0L)) {
            int n = Math.min(3, vector == null ? 0 : vector.length);
            StringBuilder sb = new StringBuilder();
            sb.append("[DIAG-av] addVectorCalls=").append(addVectorCalls)
              .append(" field=").append(fieldName)
              .append(" docId=").append(docId);
            sb.append(" vector(len=").append(vector == null ? -1 : vector.length).append(", first3=[");
            for (int k = 0; k < n; k++) {
                if (k > 0) sb.append(",");
                sb.append(vector[k]);
            }
            sb.append("])");
            LOG.debug(sb.toString());
        }
    }

    @Override
    public void addTextField(String fieldName, long docId, String text) throws IOException {
        checkNotBuilt();
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not configured as FULLTEXT");
        }
        Objects.requireNonNull(text, "text");
        Document doc = getOrCreateDoc(docId);
        doc.add(new TextField(fieldName, text, Field.Store.NO));
        addTextCalls++;
    }

    @Override
    public void addScalarField(String fieldName, long docId, Object value, ScalarFieldType scalarType) throws IOException {
        checkNotBuilt();
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null
                || (config.indexType() != FieldIndexConfig.IndexType.SCALAR
                    && config.indexType() != FieldIndexConfig.IndexType.KEYWORD
                    && config.indexType() != FieldIndexConfig.IndexType.DATE
                    && config.indexType() != FieldIndexConfig.IndexType.GEO_POINT)) {
            throw new IllegalArgumentException(
                "Field '" + fieldName + "' is not configured as a scalar field");
        }
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(scalarType, "scalarType");
        if (config.scalarType() == null) {
            throw new IllegalArgumentException(
                "Field '" + fieldName + "' has no scalar type configuration");
        }
        if (config.scalarType() != scalarType) {
            throw new IllegalArgumentException(
                "Field '" + fieldName + "' is configured as " + config.scalarType()
                    + " but received " + scalarType);
        }
        Document doc = getOrCreateDoc(docId);
        ScalarFieldHandler.addToDocument(doc, fieldName, value, scalarType);
        addScalarCalls++;
    }

    // tp13: setRowId(...) removed. Stamp is now attached automatically inside
    // getOrCreateDoc using the builder-local docId as the sort key. See the comment
    // there for the monotonicity argument.

    @Override
    public void build() throws IOException {
        checkNotBuilt();
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "[build] starting — fieldConfigs={}, addVector={}, addScalar={}, addText={}, pendingDocs.size={}, codec={}",
                fieldConfigs.keySet(), addVectorCalls, addScalarCalls, addTextCalls,
                pendingDocs.size(), writer.getConfig().getCodec().getClass().getSimpleName());
        }
        flushPendingDocs();
        long t0 = System.currentTimeMillis();
        writer.forceMerge(1);
        long t1 = System.currentTimeMillis();
        long mergeMs = t1 - t0;
        int segCount = writer.getDocStats().numDocs;
        LOG.debug("[build] forceMerge(1) done in {}ms; writer.numDocs={}", mergeMs, segCount);
        writer.close();
        built = true;
        LOG.debug("[build] done — numDocs={}, mergeMs={}, indexDir={}", segCount, mergeMs, outputDir);
    }

    @Override
    public Path getOutputDir() {
        return outputDir;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closeStarted = true;
        List<Closeable> resources = new ArrayList<>();
        if (!built && writer.isOpen()) {
            resources.add(writer);
        }
        resources.add(indexAnalyzer);
        resources.addAll(fieldAnalyzers.values());
        resources.add(defaultAnalyzer);
        resources.add(directory);
        IOUtils.close(resources);
        closed = true;
    }

    @Override
    public void addNullDoc(long docId) {
        checkNotBuilt();
        // Occupy this rowId's slot with an empty doc (no indexed field). getOrCreateDoc stamps
        // _ROW_ID=docId so index-sort places it in slot docId and the positional read mapping
        // (rangeFrom + docId) stays correct; the absent field makes notExists/IS NULL see it as null.
        // Idempotent per docId; also advances lastDocId so trailing null rows are covered.
        getOrCreateDoc(docId);
    }

    @Override
    public void finishDocument(long docId) throws IOException {
        checkNotBuilt();
        validateDocumentId(docId);
        if (docId < lastFlushedDocId) {
            throw new IllegalArgumentException(
                "Documents must be finished in non-decreasing row-id order: last="
                    + lastFlushedDocId + ", current=" + docId);
        }
        if (docId == lastFlushedDocId) {
            return;
        }

        // Preserve the dense positional mapping. A gap is materialized as an empty document with
        // its row-id sort key, exactly as the build-time back-stop does.
        for (long id = lastFlushedDocId + 1; id <= docId; id++) {
            Document doc = pendingDocs.remove(id);
            writer.addDocument(doc == null ? emptyDocument(id) : doc);
            lastFlushedDocId = id;
        }
    }

    private Document getOrCreateDoc(long docId) {
        validateDocumentId(docId);
        if (docId <= lastFlushedDocId) {
            throw new IllegalStateException(
                "Document " + docId + " has already been flushed (last=" + lastFlushedDocId + ")");
        }
        if (docId < firstDocId) firstDocId = docId;
        if (docId > lastDocId) lastDocId = docId;
        // tp13: stamp every doc with `docId` as its sort key. Because GenericIndexTopoBuilder
        // enforces monotonic _ROW_ID ingestion (IllegalStateException on out-of-order rows)
        // and the caller supplies `docId = paimon._ROW_ID - shardRangeFrom`, `docId` is
        // guaranteed monotonic. So sorting
        // by this stamp recovers paimon row order after TieredMergePolicy's tier-based
        // segment merge, without having to thread the actual _ROW_ID column through
        // GenericIndexTopoBuilder / ESIndexGlobalIndexWriter. See ROW_ID_FIELD declaration
        // and IWC.setIndexSort below.
        return pendingDocs.computeIfAbsent(docId, id -> {
            Document doc = new Document();
            // SortedNumericDocValuesField (not NumericDocValuesField): the index sort uses a
            // SortedNumericSortField on _ROW_ID, and Lucene's index-time validateIndexSortDVType
            // requires the field's doc-values type to match exactly (SORTED_NUMERIC), else it
            // throws "expected field [_ROW_ID] to be SORTED_NUMERIC but it is [NUMERIC]". This
            // field is only the sort key — nothing reads it back (read side is rangeFrom + docId).
            doc.add(new SortedNumericDocValuesField(ROW_ID_FIELD, id));
            return doc;
        });
    }

    /**
     * Flush all accumulated {@link #pendingDocs} to the Lucene writer, padding from 0 to maxDocId
     * with empty docs so that {@code luceneDocId == paimonRowId} (direct mapping — the query side
     * doesn't need a separate rowId↔docId translation). Logged with full counts so we can see
     * exactly what addVector delivered, including any "empty" rowIds (which under the dense-row
     * assumption should not exist within the shard's range).
     */
    private void flushPendingDocs() throws IOException {
        int total = pendingDocs.size();
        int withVector = 0;
        for (Document d : pendingDocs.values()) {
            for (org.apache.lucene.index.IndexableField f : d.getFields()) {
                if (f instanceof KnnFloatVectorField) { withVector++; break; }
            }
        }
        long minId = firstDocId == Long.MAX_VALUE ? -1 : firstDocId;
        long maxId = lastDocId == Long.MIN_VALUE ? -1 : lastDocId;
        long span = (minId < 0 || maxId < 0) ? -1 : maxId - minId;
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "[flushPendingDocs] BEFORE: pendingDocs={}, addVector calls={} (rowId range=[{},{}], span={}), "
                    + "docs-with-vector-field={}, addScalar={}, addText={}, perField vectors={}",
                total, addVectorCalls, minId, maxId, span,
                withVector, addScalarCalls, addTextCalls, perFieldVectorCount);
        }

        if (total == 0) {
            LOG.debug("[flushPendingDocs] no pending documents to flush.");
            return;
        }

        // Original semantics: pad from 0 to maxDocId so luceneDocId == paimonRowId.
        long maxDocId = -1;
        for (Long id : pendingDocs.keySet()) {
            if (id > maxDocId) maxDocId = id;
        }
        long t0 = System.currentTimeMillis();
        long flushed = 0;
        long paddedEmpty = 0;
        long lastIdx = maxDocId;  // capture for "last N" log window
        for (long i = lastFlushedDocId + 1; i <= maxDocId; i++) {
            Document doc = pendingDocs.get(i);
            // [DIAG-fp] First 20 + last 5 addDocument calls — last checkpoint before the
            // doc enters the Lucene IndexWriter. Logs the vector content too so we can
            // correlate to [DIAG-av] (same docId) and to the post-build SegmentDump of
            // .vec[i]. If [DIAG-av] == [DIAG-fp] but .vec[i] differs, the bug is inside
            // Lucene's flush/merge.
            if (LOG.isDebugEnabled() && (i < 20 || i >= lastIdx - 4)) {
                String preview = "(empty-padding)";
                if (doc != null) {
                    KnnFloatVectorField vf = null;
                    for (org.apache.lucene.index.IndexableField f : doc.getFields()) {
                        if (f instanceof KnnFloatVectorField) {
                            vf = (KnnFloatVectorField) f;
                            break;
                        }
                    }
                    if (vf != null) {
                        float[] v = vf.vectorValue();
                        int n = Math.min(3, v.length);
                        StringBuilder sb = new StringBuilder("vec[").append(v.length).append("]=[");
                        for (int k = 0; k < n; k++) {
                            if (k > 0) sb.append(",");
                            sb.append(v[k]);
                        }
                        sb.append("]");
                        preview = sb.toString();
                    } else {
                        preview = "(no-vector-field)";
                    }
                }
                LOG.debug("[DIAG-fp] addDocument i={} luceneDocIdWillBe={} {}", i, flushed, preview);
            }
            if (doc != null) {
                writer.addDocument(doc);
            } else {
                // Empty padding for a skipped (null) row. It MUST carry _ROW_ID=i: the index is
                // sorted by _ROW_ID and the read side maps luceneDocId -> rowId positionally
                // (rangeFrom + docId). Without this stamp the padding doc has a missing sort value,
                // so index-sort would not place it in slot i and every doc after the gap would shift,
                // breaking docId<->rowId alignment.
                writer.addDocument(emptyDocument(i));
                paddedEmpty++;
            }
            lastFlushedDocId = i;
            flushed++;
            if (LOG.isDebugEnabled() && flushed % 1_000_000L == 0L) {
                LOG.debug(
                    "[flushPendingDocs] progress: {} addDocument calls so far ({} empty-padding), "
                        + "elapsed={}ms",
                    flushed, paddedEmpty, System.currentTimeMillis() - t0);
            }
        }
        long t1 = System.currentTimeMillis();
        pendingDocs.clear();
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                "[flushPendingDocs] AFTER: flushed {} docs total (real={}, empty-padding={}, "
                    + "padding-ratio={}%) in {}ms.",
                flushed, total, paddedEmpty,
                (flushed > 0
                    ? String.format(Locale.ROOT, "%.1f", paddedEmpty * 100.0 / flushed)
                    : "0"),
                t1 - t0);
        }
    }

    private void checkNotBuilt() {
        if (closeStarted) {
            throw new IllegalStateException("Index builder is closed");
        }
        if (built) {
            throw new IllegalStateException("Index already built");
        }
    }

    int pendingDocumentCount() {
        return pendingDocs.size();
    }

    private static Document emptyDocument(long docId) {
        Document empty = new Document();
        empty.add(new SortedNumericDocValuesField(ROW_ID_FIELD, docId));
        return empty;
    }

    private static void validateDocumentId(long docId) {
        if (docId < 0 || docId >= IndexWriter.MAX_DOCS) {
            throw new IllegalArgumentException(
                "Document id must be between 0 and "
                    + (IndexWriter.MAX_DOCS - 1L)
                    + "; got: "
                    + docId);
        }
    }

    private static boolean isDiskBBQCosine(FieldIndexConfig config) {
        return config.algorithm() == VectorAlgorithm.DISKBBQ
            && config.metric() != null
            && "cosine".equalsIgnoreCase(config.metric());
    }

    private static VectorSimilarityFunction parseSimilarity(String metric) {
        if (metric == null) {
            return VectorSimilarityFunction.EUCLIDEAN;
        }
        switch (metric.toLowerCase(Locale.ROOT)) {
            case "l2":
            case "euclidean":
                return VectorSimilarityFunction.EUCLIDEAN;
            case "dot_product":
            case "dp":
                return VectorSimilarityFunction.DOT_PRODUCT;
            case "inner_product":
            case "mip":
            case "maximum_inner_product":
                return VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
            case "cosine":
                return VectorSimilarityFunction.COSINE;
            default:
                throw new IllegalArgumentException("Unknown vector similarity metric: " + metric);
        }
    }
}
