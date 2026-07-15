/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.searcher;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LatLonPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.function.FunctionScoreQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.VectorScorer;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.eslib.analyzer.BuiltinAnalyzers;
import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.elasticsearch.eslib.api.ESIndexSearcher;
import org.elasticsearch.eslib.builder.DefaultESIndexBuilder;
import org.elasticsearch.eslib.diskbbq.BitSetFilterQuery;
import org.elasticsearch.eslib.api.model.BuiltinAnalyzer;
import org.elasticsearch.eslib.api.model.FieldIndexConfig;
import org.elasticsearch.eslib.api.model.FullTextParams;
import org.elasticsearch.eslib.api.model.FullTextQuerySpec;
import org.elasticsearch.eslib.api.model.IndexFilter;
import org.elasticsearch.eslib.api.model.ScalarFieldType;
import org.elasticsearch.eslib.api.model.ScalarPredicate;
import org.elasticsearch.eslib.api.model.SearchResult;
import org.elasticsearch.eslib.api.model.VectorAlgorithm;
import org.elasticsearch.eslib.io.ArchiveDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

public class DefaultESIndexSearcher implements ESIndexSearcher {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultESIndexSearcher.class);
    private static final String DEBUG_PROPERTY = "paimon.eslib.debug";
    private static final String DIRECT_DOC_ID_FILTER_PROPERTY = "eslib.docid.filter.direct.enabled";
    private static final String EXACT_CANDIDATE_THRESHOLD_PROPERTY = "eslib.vector.filter.exact.threshold";
    private static final int DEFAULT_EXACT_CANDIDATE_THRESHOLD = 100_000;
    private static final int MAX_FULL_TEXT_QUERY_DEPTH = 64;

    private Map<String, FieldIndexConfig> fieldConfigs;
    private Directory directory;
    private DirectoryReader reader;
    private IndexSearcher searcher;
    private boolean loaded;

    @Override
    public void load(ArchiveDataProvider dataProvider,
                     Map<String, long[]> fileOffsets,
                     Map<String, FieldIndexConfig> fieldConfigs) throws IOException {
        load(dataProvider, fileOffsets, fieldConfigs, null);
    }

    @Override
    public void load(ArchiveDataProvider dataProvider,
                     Map<String, long[]> fileOffsets,
                     Map<String, FieldIndexConfig> fieldConfigs,
                     ExecutorService searchExecutor) throws IOException {
        if (loaded || reader != null || directory != null) {
            throw new IllegalStateException("Searcher is already loaded or still owns resources");
        }

        Directory candidateDirectory = null;
        DirectoryReader candidateReader = null;
        try {
            Map<String, FieldIndexConfig> candidateFieldConfigs =
                validateFieldConfigs(fieldConfigs);
            candidateDirectory = new ArchiveDirectory(dataProvider, fileOffsets);
            SearchExecutorHolder.set(searchExecutor);
            try {
                candidateReader = DirectoryReader.open(candidateDirectory);
            } finally {
                SearchExecutorHolder.clear();
            }

            IndexSearcher candidateSearcher = new IndexSearcher(candidateReader);
            this.fieldConfigs = candidateFieldConfigs;
            this.directory = candidateDirectory;
            this.reader = candidateReader;
            this.searcher = candidateSearcher;
            this.loaded = true;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailedLoad(failure, candidateReader, candidateDirectory, dataProvider);
            throw failure;
        }
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

    private static void closeAfterFailedLoad(
            Throwable failure,
            DirectoryReader candidateReader,
            Directory candidateDirectory,
            ArchiveDataProvider dataProvider) {
        if (candidateReader != null) {
            try {
                candidateReader.close();
            } catch (Throwable cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
        }
        boolean providerNeedsClose = candidateDirectory == null;
        if (candidateDirectory != null) {
            try {
                candidateDirectory.close();
                providerNeedsClose = false;
            } catch (Throwable cleanupFailure) {
                providerNeedsClose = true;
                addSuppressed(failure, cleanupFailure);
            }
        }
        if (providerNeedsClose && dataProvider != null) {
            try {
                dataProvider.close();
            } catch (Throwable cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
        }
    }

    private static void addSuppressed(Throwable failure, Throwable suppressed) {
        if (failure != suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    @Override
    public SearchResult vectorSearch(String fieldName, float[] queryVector,
                                     int topK, long[] candidateIds) throws IOException {
        checkLoaded();
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.VECTOR) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not configured as VECTOR");
        }
        if (queryVector == null || queryVector.length != config.dimension()) {
            throw new IllegalArgumentException(
                "Vector field '" + fieldName + "' expects query dimension " + config.dimension()
                    + " but received " + (queryVector == null ? "null" : queryVector.length));
        }
        validateTopK(topK);
        float[] effectiveQueryVector = prepareQueryVector(config, queryVector);
        // Lucene's regular TopDocs collectors cap requested hits to maxDoc, but its KNN
        // collectors allocate their heap directly from k.  A legal but unreasonable caller
        // value (for example Integer.MAX_VALUE) would therefore allocate before Lucene can
        // discover that the index only contains a few documents.
        int effectiveTopK = Math.min(topK, reader.maxDoc());
        if (effectiveTopK == 0) {
            return SearchResult.EMPTY;
        }

        Query filterQuery = null;
        if (candidateIds != null) {
            if (candidateIds.length == 0) {
                return SearchResult.EMPTY;
            }
            if (useExactCandidateVectorSearch(candidateIds.length)) {
                return vectorSearchExactCandidates(
                    fieldName, effectiveQueryVector, effectiveTopK, candidateIds);
            }
            filterQuery = buildDocIdFilterQuery(candidateIds);
        }

        KnnFloatVectorQuery knnQuery =
            new KnnFloatVectorQuery(fieldName, effectiveQueryVector, effectiveTopK, filterQuery);
        long searchStartNanos = System.nanoTime();
        TopDocs topDocs = searcher.search(knnQuery, effectiveTopK);
        TopDocs rescoredTopDocs =
            rescoreVectorTopDocs(fieldName, effectiveQueryVector, topDocs);
        if (debugEnabled()) {
            LOG.debug(
                "PAIMON_ESLIB_VECTOR_SEARCH_DONE field={} topK={} effectiveTopK={} candidates={} hits={} elapsedMs={}",
                fieldName,
                topK,
                effectiveTopK,
                candidateIds == null ? -1 : candidateIds.length,
                rescoredTopDocs.scoreDocs.length,
                elapsedMs(searchStartNanos));
        }
        return toSearchResult(rescoredTopDocs);
    }

    private static float[] prepareQueryVector(
            FieldIndexConfig config, float[] queryVector) {
        VectorUtil.checkFinite(queryVector);
        if (!isDiskBBQCosine(config)) {
            return queryVector;
        }
        return VectorUtil.l2normalize(Arrays.copyOf(queryVector, queryVector.length));
    }

    private static boolean isDiskBBQCosine(FieldIndexConfig config) {
        return config.algorithm() == VectorAlgorithm.DISKBBQ
            && config.metric() != null
            && "cosine".equalsIgnoreCase(config.metric());
    }

    @Override
    public SearchResult fullTextSearch(String fieldName, String queryText,
                                       int topK) throws IOException {
        checkLoaded();
        validateTopK(topK);
        Objects.requireNonNull(queryText, "queryText");
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not configured as FULLTEXT");
        }

        BuiltinAnalyzer ba = config.analyzer();
        Analyzer analyzer = BuiltinAnalyzers.getAnalyzer(ba);
        try {
            Query query = buildTextQuery(fieldName, queryText, analyzer);
            TopDocs topDocs = searcher.search(query, topK);
            return toSearchResult(topDocs);
        } finally {
            analyzer.close();
        }
    }

    @Override
    public SearchResult fullTextSearch(String fieldName, String queryText,
                                       int topK, FullTextParams params) throws IOException {
        if (params == null) {
            return fullTextSearch(fieldName, queryText, topK);
        }
        checkLoaded();
        validateTopK(topK);
        Objects.requireNonNull(queryText, "queryText");
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException("Field '" + fieldName + "' is not configured as FULLTEXT");
        }

        BuiltinAnalyzer ba = config.analyzer();
        Analyzer analyzer = BuiltinAnalyzers.getAnalyzer(ba);
        try {
            Query query = buildMatchQuery(fieldName, queryText, params, analyzer);
            TopDocs topDocs = searcher.search(query, topK);
            return toSearchResult(topDocs);
        } finally {
            analyzer.close();
        }
    }

    /**
     * Builds an Elasticsearch-style {@code match} query: the text is analyzed into tokens, each
     * token becomes a {@link TermQuery} (or {@link FuzzyQuery} when fuzziness &gt; 0), the tokens
     * are combined with MUST (AND) or SHOULD (OR) per the operator, and the whole query is wrapped
     * in a {@link BoostQuery} when boost != 1.0. This honours the parameters that the plain
     * QueryParser path silently ignored.
     */
    private Query buildMatchQuery(String fieldName, String queryText,
                                  FullTextParams params, Analyzer analyzer) throws IOException {
        int fuzziness = params.fuzziness() == null ? 0 : params.fuzziness();
        BooleanClause.Occur occur = params.operator() == FullTextParams.Operator.AND
            ? BooleanClause.Occur.MUST
            : BooleanClause.Occur.SHOULD;

        List<String> tokens = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(fieldName, queryText)) {
            CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                tokens.add(termAttr.toString());
            }
            ts.end();
        }

        Query query;
        if (tokens.isEmpty()) {
            query = new MatchNoDocsQuery("no analyzable tokens in match query");
        } else if (tokens.size() == 1) {
            query = termOrFuzzy(fieldName, tokens.get(0), fuzziness, params);
        } else {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (String token : tokens) {
                builder.add(termOrFuzzy(fieldName, token, fuzziness, params), occur);
            }
            query = builder.build();
        }

        if (params.boost() != 1.0f) {
            query = new BoostQuery(query, params.boost());
        }
        return query;
    }

    private Query termOrFuzzy(String fieldName, String token, int fuzziness, FullTextParams params) {
        Term term = new Term(fieldName, token);
        if (fuzziness == FullTextParams.AUTO_FUZZINESS) {
            int length = token.codePointCount(0, token.length());
            fuzziness = length <= 2 ? 0 : (length <= 5 ? 1 : 2);
        }
        if (fuzziness <= 0) {
            return new TermQuery(term);
        }
        return new FuzzyQuery(
            term, fuzziness, params.prefixLength(), params.maxExpansions(), true);
    }

    @Override
    public SearchResult fullTextSearch(FullTextQuerySpec spec, int topK) throws IOException {
        checkLoaded();
        validateTopK(topK);
        Objects.requireNonNull(spec, "spec");
        Query query = buildSpecQuery(spec, 0);
        TopDocs topDocs = searcher.search(query, topK);
        return toSearchResult(topDocs);
    }

    /** Recursively builds a Lucene query from a {@link FullTextQuerySpec} tree. */
    private Query buildSpecQuery(FullTextQuerySpec spec, int depth) throws IOException {
        if (depth >= MAX_FULL_TEXT_QUERY_DEPTH) {
            throw new IllegalArgumentException(
                "Full-text query exceeds the maximum nesting depth of "
                    + MAX_FULL_TEXT_QUERY_DEPTH);
        }
        if (spec instanceof FullTextQuerySpec.Match) {
            FullTextQuerySpec.Match m = (FullTextQuerySpec.Match) spec;
            requireFullTextField(m.field());
            Analyzer analyzer = fieldAnalyzer(m.field());
            try {
                return buildMatchQuery(m.field(), m.text(), m.params(), analyzer);
            } finally {
                analyzer.close();
            }
        } else if (spec instanceof FullTextQuerySpec.Phrase) {
            FullTextQuerySpec.Phrase p = (FullTextQuerySpec.Phrase) spec;
            requireFullTextField(p.field());
            Analyzer analyzer = fieldAnalyzer(p.field());
            try {
                // Analyzer-aware phrase build (uses the same analyzer as indexing); needs indexed
                // positions, which TextField provides by default.
                Query phrase = new QueryBuilder(analyzer).createPhraseQuery(p.field(), p.text(), p.slop());
                return phrase == null ? new MatchNoDocsQuery("empty phrase") : phrase;
            } finally {
                analyzer.close();
            }
        } else if (spec instanceof FullTextQuerySpec.Bool) {
            FullTextQuerySpec.Bool b = (FullTextQuerySpec.Bool) spec;
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            if ((b.must() == null || b.must().isEmpty())
                    && (b.should() == null || b.should().isEmpty())) {
                // Elasticsearch adjusts a bool with no positive clauses with match_all. This
                // covers both an empty bool (match every document) and a pure-negative bool;
                // Lucene otherwise makes either shape match no documents.
                builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
            }
            addClauses(builder, b.must(), BooleanClause.Occur.MUST, depth + 1);
            addClauses(builder, b.should(), BooleanClause.Occur.SHOULD, depth + 1);
            addClauses(builder, b.mustNot(), BooleanClause.Occur.MUST_NOT, depth + 1);
            return builder.build();
        } else if (spec instanceof FullTextQuerySpec.Boost) {
            // Lucene 9 removed BoostingQuery; FunctionScoreQuery.boostByQuery multiplies the score
            // of docs matching the negative query by negativeBoost (<1 demotes), matching the
            // positive/negative/negativeBoost semantics.
            FullTextQuerySpec.Boost bo = (FullTextQuerySpec.Boost) spec;
            return FunctionScoreQuery.boostByQuery(
                buildSpecQuery(bo.positive(), depth + 1),
                buildSpecQuery(bo.negative(), depth + 1),
                bo.negativeBoost());
        }
        throw new IllegalArgumentException(
            "Unsupported full-text query spec: " + spec.getClass().getName());
    }

    private void addClauses(
            BooleanQuery.Builder builder,
            java.util.List<FullTextQuerySpec> specs,
            BooleanClause.Occur occur,
            int depth)
            throws IOException {
        if (specs == null) {
            return;
        }
        for (FullTextQuerySpec sub : specs) {
            builder.add(buildSpecQuery(sub, depth), occur);
        }
    }

    private void requireFullTextField(String fieldName) {
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null || config.indexType() != FieldIndexConfig.IndexType.FULLTEXT) {
            throw new IllegalArgumentException(
                "Field '" + fieldName + "' is not configured as FULLTEXT");
        }
    }

    private Analyzer fieldAnalyzer(String fieldName) {
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        BuiltinAnalyzer ba = config == null ? BuiltinAnalyzer.STANDARD : config.analyzer();
        return BuiltinAnalyzers.getAnalyzer(ba);
    }

    @Override
    public long[] scalarFilter(String fieldName, ScalarPredicate predicate) throws IOException {
        return filter(fieldName, IndexFilter.scalar(predicate));
    }

    @Override
    public long[] filter(String fieldName, IndexFilter filter) throws IOException {
        checkLoaded();
        Objects.requireNonNull(filter, "filter");
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        if (config == null) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' is not configured for filtering");
        }

        Query query;
        switch (filter.filterType()) {
            case SCALAR:
                requireIndexType(
                        fieldName,
                        config,
                        FieldIndexConfig.IndexType.SCALAR,
                        FieldIndexConfig.IndexType.KEYWORD,
                        FieldIndexConfig.IndexType.DATE);
                query = buildScalarFilterQuery(fieldName, (IndexFilter.ScalarFilter) filter);
                break;
            case TEXT:
                requireIndexType(
                        fieldName,
                        config,
                        FieldIndexConfig.IndexType.FULLTEXT,
                        FieldIndexConfig.IndexType.KEYWORD);
                query = buildTextFilterQuery(fieldName, (IndexFilter.TextFilter) filter);
                break;
            case GEO:
                requireIndexType(
                        fieldName, config, FieldIndexConfig.IndexType.GEO_POINT);
                query = buildGeoFilterQuery(fieldName, (IndexFilter.GeoFilter) filter);
                break;
            case EXISTS:
                query = buildExistsFilterQuery(fieldName, (IndexFilter.ExistsFilter) filter);
                break;
            default:
                throw new IllegalArgumentException("Unknown filter type: " + filter.filterType());
        }

        long startNanos = System.nanoTime();
        long[] ids = collectFilterRowIdsNoScores(query);
        if (debugEnabled()) {
            LOG.debug(
                "PAIMON_ESLIB_FILTER_ITERATOR_DONE field={} filter={} hits={} elapsedMs={}",
                fieldName,
                filter.filterType(),
                ids.length,
                elapsedMs(startNanos));
        }
        return ids;
    }

    @Override
    public long[] allRowIds() throws IOException {
        checkLoaded();
        return collectFilterRowIdsNoScores(new MatchAllDocsQuery());
    }

    @Override
    public void close() throws IOException {
        Throwable failure = null;
        if (reader != null) {
            try {
                reader.close();
                reader = null;
                searcher = null;
            } catch (Throwable cleanupFailure) {
                failure = cleanupFailure;
            }
        }
        if (directory != null) {
            try {
                directory.close();
                directory = null;
            } catch (Throwable cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    addSuppressed(failure, cleanupFailure);
                }
            }
        }
        loaded = false;
        if (failure != null) {
            rethrowCloseFailure(failure);
        }
    }

    private static void rethrowCloseFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IOException("Failed to close ESLib searcher", failure);
    }

    private void checkLoaded() {
        if (!loaded) {
            throw new IllegalStateException("Must call load() before searching");
        }
    }

    // =================== Query builders ===================

    private Query buildScalarFilterQuery(String fieldName, IndexFilter.ScalarFilter filter) {
        FieldIndexConfig config = fieldConfigs.get(fieldName);
        ScalarFieldType scalarType = config.scalarType();
        if (scalarType == null) {
            throw new IllegalArgumentException(
                    "Field '" + fieldName + "' has no scalar type configuration");
        }
        return buildScalarQuery(fieldName, filter.predicate(), scalarType);
    }

    private static void requireIndexType(
            String fieldName,
            FieldIndexConfig config,
            FieldIndexConfig.IndexType... expectedTypes) {
        for (FieldIndexConfig.IndexType expectedType : expectedTypes) {
            if (config.indexType() == expectedType) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Field '"
                        + fieldName
                        + "' is configured as "
                        + config.indexType()
                        + " and does not support this filter");
    }

    private Query buildTextFilterQuery(String fieldName, IndexFilter.TextFilter filter)
            throws IOException {
        switch (filter.op()) {
            case TERM:
                return new TermQuery(new Term(fieldName, filter.value()));
            case PREFIX:
                return new PrefixQuery(new Term(fieldName, filter.value()));
            case WILDCARD:
                return new WildcardQuery(new Term(fieldName, filter.value()));
            case REGEXP:
                return new RegexpQuery(new Term(fieldName, filter.value()));
            case FUZZY:
                return new FuzzyQuery(new Term(fieldName, filter.value()));
            case MATCH:
                FieldIndexConfig config = fieldConfigs.get(fieldName);
                if (config != null && config.analyzer() != null) {
                    Analyzer analyzer = BuiltinAnalyzers.getAnalyzer(config.analyzer());
                    try {
                        return buildTextQuery(fieldName, filter.value(), analyzer);
                    } finally {
                        analyzer.close();
                    }
                }
                return new TermQuery(new Term(fieldName, filter.value()));
            default:
                throw new IllegalArgumentException("Unknown text filter operation: " + filter.op());
        }
    }

    private Query buildGeoFilterQuery(String fieldName, IndexFilter.GeoFilter filter) {
        switch (filter.op()) {
            case DISTANCE:
                return LatLonPoint.newDistanceQuery(
                        fieldName, filter.lat(), filter.lon(), filter.radiusMeters());
            case BOUNDING_BOX:
                return LatLonPoint.newBoxQuery(
                        fieldName, filter.minLat(), filter.maxLat(),
                        filter.minLon(), filter.maxLon());
            default:
                throw new IllegalArgumentException("Unknown geo filter operation: " + filter.op());
        }
    }

    private Query buildExistsFilterQuery(String fieldName, IndexFilter.ExistsFilter filter) {
        Query existsQuery = new FieldExistsQuery(fieldName);
        if (filter.mustExist()) {
            return existsQuery;
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        builder.add(existsQuery, BooleanClause.Occur.MUST_NOT);
        return builder.build();
    }


    SearchResult vectorSearchExactCandidates(String fieldName, float[] queryVector,
                                             int topK, long[] candidateIds) throws IOException {
        long startNanos = System.nanoTime();
        if (topK <= 0 || candidateIds == null || candidateIds.length == 0) {
            return SearchResult.EMPTY;
        }

        List<LeafReaderContext> leaves = reader.leaves();
        List<ScoredDoc> scoredDocs = scoreDocsExactly(fieldName, queryVector, candidateIds, leaves);
        SortedNumericDocValues[] rowIdsByLeaf = new SortedNumericDocValues[leaves.size()];
        boolean[] rowIdsInitialized = new boolean[leaves.size()];
        // Every exactly-scored document is materialized below. Using topK * 4 as the capacity can
        // overflow for a legal large topK and provides no benefit because scoredDocs already gives
        // the exact required capacity.
        List<ScoredRow> scoredRows = new ArrayList<>(scoredDocs.size());

        for (ScoredDoc scoredDoc : scoredDocs) {
            int subIndex = scoredDoc.subIndex;
            LeafReaderContext leaf = leaves.get(subIndex);
            if (!rowIdsInitialized[subIndex]) {
                rowIdsByLeaf[subIndex] =
                        leaf.reader().getSortedNumericDocValues(DefaultESIndexBuilder.ROW_ID_FIELD);
                rowIdsInitialized[subIndex] = true;
            }
            long rowId = rowIdForLeafDoc(leaf, scoredDoc.leafDocId, rowIdsByLeaf[subIndex]);
            scoredRows.add(new ScoredRow(rowId, scoredDoc.score));
        }

        scoredRows.sort(
                (left, right) -> {
                    int scoreCompare = Float.compare(right.score, left.score);
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Long.compare(left.rowId, right.rowId);
                });
        int count = Math.min(topK, scoredRows.size());
        long[] ids = new long[count];
        float[] scores = new float[count];
        for (int i = 0; i < count; i++) {
            ScoredRow row = scoredRows.get(i);
            ids[i] = row.rowId;
            scores[i] = row.score;
        }
        if (debugEnabled()) {
            LOG.debug(
                    "PAIMON_ESLIB_VECTOR_SEARCH_EXACT_DONE field={} topK={} candidates={} threshold={} scored={} hits={} elapsedMs={}",
                    fieldName,
                    topK,
                    candidateIds.length,
                    exactCandidateThreshold(),
                    scoredDocs.size(),
                    count,
                    elapsedMs(startNanos));
        }
        return new SearchResult(ids, scores, count);
    }

    private TopDocs rescoreVectorTopDocs(String fieldName, float[] queryVector, TopDocs topDocs)
            throws IOException {
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        if (scoreDocs.length == 0) {
            return topDocs;
        }

        long[] docIds = new long[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            docIds[i] = scoreDocs[i].doc;
        }
        List<ScoredDoc> rescoredDocs =
                scoreDocsExactly(fieldName, queryVector, docIds, reader.leaves());
        rescoredDocs.sort(
                (left, right) -> {
                    int scoreCompare = Float.compare(right.score, left.score);
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return Integer.compare(left.docId, right.docId);
                });

        ScoreDoc[] rescoredScoreDocs = new ScoreDoc[rescoredDocs.size()];
        for (int i = 0; i < rescoredDocs.size(); i++) {
            ScoredDoc scoredDoc = rescoredDocs.get(i);
            rescoredScoreDocs[i] = new ScoreDoc(scoredDoc.docId, scoredDoc.score);
        }
        return new TopDocs(topDocs.totalHits, rescoredScoreDocs);
    }

    private List<ScoredDoc> scoreDocsExactly(
            String fieldName, float[] queryVector, long[] docIds, List<LeafReaderContext> leaves)
            throws IOException {
        long[] sortedDocIds = Arrays.copyOf(docIds, docIds.length);
        Arrays.sort(sortedDocIds);
        VectorScorer[] scorers = new VectorScorer[leaves.size()];
        boolean[] scorerInitialized = new boolean[leaves.size()];
        List<ScoredDoc> scoredDocs = new ArrayList<>(sortedDocIds.length);
        int maxDoc = reader.maxDoc();
        long previousDoc = Long.MIN_VALUE;
        boolean hasPreviousDoc = false;

        for (long candidateDocId : sortedDocIds) {
            if (hasPreviousDoc && candidateDocId == previousDoc) {
                continue;
            }
            hasPreviousDoc = true;
            previousDoc = candidateDocId;
            if (candidateDocId < 0 || candidateDocId >= maxDoc) {
                continue;
            }

            int docId = Math.toIntExact(candidateDocId);
            int subIndex = ReaderUtil.subIndex(docId, leaves);
            LeafReaderContext leaf = leaves.get(subIndex);
            int leafDocId = docId - leaf.docBase;

            if (!scorerInitialized[subIndex]) {
                FloatVectorValues vectors = leaf.reader().getFloatVectorValues(fieldName);
                scorers[subIndex] = vectors == null ? null : vectors.scorer(queryVector);
                scorerInitialized[subIndex] = true;
            }
            VectorScorer scorer = scorers[subIndex];
            if (scorer == null) {
                continue;
            }

            DocIdSetIterator iterator = scorer.iterator();
            int currentDoc = iterator.docID();
            int advancedDoc;
            if (currentDoc == leafDocId) {
                advancedDoc = currentDoc;
            } else if (currentDoc < leafDocId) {
                advancedDoc = iterator.advance(leafDocId);
            } else {
                continue;
            }
            if (advancedDoc == leafDocId) {
                scoredDocs.add(new ScoredDoc(docId, subIndex, leafDocId, scorer.score()));
            }
        }
        return scoredDocs;
    }

    private Query buildDocIdFilterQuery(long[] candidateIds) throws IOException {
        if (!directDocIdFilterEnabled()) {
            return buildDocIdFilterQueryByScanning(candidateIds);
        }

        long startNanos = System.nanoTime();
        int maxDoc = reader.maxDoc();
        FixedBitSet internalDocIds = new FixedBitSet(maxDoc);
        int inRange = 0;
        int outOfRange = 0;
        for (long candidateId : candidateIds) {
            if (candidateId >= 0 && candidateId < maxDoc) {
                internalDocIds.set((int) candidateId);
                inRange++;
            } else {
                outOfRange++;
            }
        }
        if (debugEnabled()) {
            LOG.debug(
                "PAIMON_ESLIB_DOCID_FILTER_DIRECT candidates={} inRange={} outOfRange={} maxDoc={} elapsedMs={}",
                candidateIds.length,
                inRange,
                outOfRange,
                maxDoc,
                elapsedMs(startNanos));
        }
        return new BitSetFilterQuery(internalDocIds);
    }

    private Query buildDocIdFilterQueryByScanning(long[] candidateIds) throws IOException {
        long startNanos = System.nanoTime();
        long[] sortedIds = Arrays.copyOf(candidateIds, candidateIds.length);
        Arrays.sort(sortedIds);
        FixedBitSet internalDocIds = new FixedBitSet(reader.maxDoc());
        int matched = 0;
        for (LeafReaderContext leaf : reader.leaves()) {
            SortedNumericDocValues rowIds =
                    leaf.reader().getSortedNumericDocValues(DefaultESIndexBuilder.ROW_ID_FIELD);
            if (rowIds == null) {
                continue;
            }
            int maxDoc = leaf.reader().maxDoc();
            for (int leafDocId = 0; leafDocId < maxDoc; leafDocId++) {
                if (rowIds.advanceExact(leafDocId) && rowIds.docValueCount() > 0) {
                    long rowId = rowIds.nextValue();
                    if (Arrays.binarySearch(sortedIds, rowId) >= 0) {
                        internalDocIds.set(leaf.docBase + leafDocId);
                        matched++;
                    }
                }
            }
        }
        if (debugEnabled()) {
            LOG.debug(
                "PAIMON_ESLIB_DOCID_FILTER_SCAN candidates={} matched={} maxDoc={} elapsedMs={}",
                candidateIds.length,
                matched,
                reader.maxDoc(),
                elapsedMs(startNanos));
        }
        return new BitSetFilterQuery(internalDocIds);
    }

    private Query buildTextQuery(String fieldName, String queryText, Analyzer analyzer) throws IOException {
        org.apache.lucene.queryparser.classic.QueryParser parser =
            new org.apache.lucene.queryparser.classic.QueryParser(fieldName, analyzer);
        try {
            return parser.parse(queryText);
        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            return new TermQuery(new Term(fieldName, queryText));
        }
    }

    private Query buildScalarQuery(String fieldName, ScalarPredicate predicate, ScalarFieldType type) {
        switch (predicate.op()) {
            case EQUAL:
                return buildEqualQuery(fieldName, predicate.value(), type);
            case RANGE:
                return buildRangeQuery(fieldName, predicate.value(), predicate.upperValue(), type);
            case LESS_THAN:
                return buildUpperBoundQuery(fieldName, predicate.value(), type, true);
            case LESS_OR_EQUAL:
                return buildUpperBoundQuery(fieldName, predicate.value(), type, false);
            case GREATER_THAN:
                return buildLowerBoundQuery(fieldName, predicate.value(), type, true);
            case GREATER_OR_EQUAL:
                return buildLowerBoundQuery(fieldName, predicate.value(), type, false);
            case IN:
                return buildInQuery(fieldName, predicate.inValues(), type);
            case NOT_EQUAL:
                return existingAndNot(
                    fieldName, buildEqualQuery(fieldName, predicate.value(), type));
            case NOT_IN:
                return existingAndNot(
                    fieldName, buildInQuery(fieldName, predicate.inValues(), type));
            default:
                throw new IllegalArgumentException(
                    "Unknown scalar predicate operation: " + predicate.op());
        }
    }

    private static Query existingAndNot(String fieldName, Query matchingQuery) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new FieldExistsQuery(fieldName), BooleanClause.Occur.MUST);
        builder.add(matchingQuery, BooleanClause.Occur.MUST_NOT);
        return builder.build();
    }

    private Query buildEqualQuery(String fieldName, Object value, ScalarFieldType type) {
        switch (type) {
            case INT: return IntPoint.newExactQuery(fieldName, ((Number) value).intValue());
            case LONG: return LongPoint.newExactQuery(fieldName, ((Number) value).longValue());
            case FLOAT: return FloatPoint.newExactQuery(fieldName, ((Number) value).floatValue());
            case DOUBLE: return DoublePoint.newExactQuery(fieldName, ((Number) value).doubleValue());
            case KEYWORD: return new TermQuery(new Term(fieldName, new BytesRef(value.toString())));
            default: throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    private Query buildRangeQuery(String fieldName, Object lower, Object upper, ScalarFieldType type) {
        switch (type) {
            case INT: return IntPoint.newRangeQuery(fieldName,
                ((Number) lower).intValue(), ((Number) upper).intValue());
            case LONG: return LongPoint.newRangeQuery(fieldName,
                ((Number) lower).longValue(), ((Number) upper).longValue());
            case FLOAT: return FloatPoint.newRangeQuery(fieldName,
                ((Number) lower).floatValue(), ((Number) upper).floatValue());
            case DOUBLE: return DoublePoint.newRangeQuery(fieldName,
                ((Number) lower).doubleValue(), ((Number) upper).doubleValue());
            default: throw new IllegalArgumentException("Range not supported for type: " + type);
        }
    }

    private Query buildUpperBoundQuery(String fieldName, Object value, ScalarFieldType type, boolean exclusive) {
        switch (type) {
            case INT: {
                int v = ((Number) value).intValue();
                if (exclusive && v == Integer.MIN_VALUE) {
                    return new MatchNoDocsQuery("no int value is less than Integer.MIN_VALUE");
                }
                return IntPoint.newRangeQuery(fieldName, Integer.MIN_VALUE, exclusive ? v - 1 : v);
            }
            case LONG: {
                long v = ((Number) value).longValue();
                if (exclusive && v == Long.MIN_VALUE) {
                    return new MatchNoDocsQuery("no long value is less than Long.MIN_VALUE");
                }
                return LongPoint.newRangeQuery(fieldName, Long.MIN_VALUE, exclusive ? v - 1 : v);
            }
            case FLOAT: {
                float v = ((Number) value).floatValue();
                if (exclusive && v == Float.NEGATIVE_INFINITY) {
                    return new MatchNoDocsQuery("no float value is less than negative infinity");
                }
                return FloatPoint.newRangeQuery(fieldName, Float.NEGATIVE_INFINITY,
                    exclusive ? Math.nextDown(v) : v);
            }
            case DOUBLE: {
                double v = ((Number) value).doubleValue();
                if (exclusive && v == Double.NEGATIVE_INFINITY) {
                    return new MatchNoDocsQuery("no double value is less than negative infinity");
                }
                return DoublePoint.newRangeQuery(fieldName, Double.NEGATIVE_INFINITY,
                    exclusive ? Math.nextDown(v) : v);
            }
            default: throw new IllegalArgumentException("Range not supported for type: " + type);
        }
    }

    private Query buildLowerBoundQuery(String fieldName, Object value, ScalarFieldType type, boolean exclusive) {
        switch (type) {
            case INT: {
                int v = ((Number) value).intValue();
                if (exclusive && v == Integer.MAX_VALUE) {
                    return new MatchNoDocsQuery("no int value is greater than Integer.MAX_VALUE");
                }
                return IntPoint.newRangeQuery(fieldName, exclusive ? v + 1 : v, Integer.MAX_VALUE);
            }
            case LONG: {
                long v = ((Number) value).longValue();
                if (exclusive && v == Long.MAX_VALUE) {
                    return new MatchNoDocsQuery("no long value is greater than Long.MAX_VALUE");
                }
                return LongPoint.newRangeQuery(fieldName, exclusive ? v + 1 : v, Long.MAX_VALUE);
            }
            case FLOAT: {
                float v = ((Number) value).floatValue();
                if (exclusive && v == Float.POSITIVE_INFINITY) {
                    return new MatchNoDocsQuery("no float value is greater than positive infinity");
                }
                return FloatPoint.newRangeQuery(fieldName,
                    exclusive ? Math.nextUp(v) : v, Float.POSITIVE_INFINITY);
            }
            case DOUBLE: {
                double v = ((Number) value).doubleValue();
                if (exclusive && v == Double.POSITIVE_INFINITY) {
                    return new MatchNoDocsQuery("no double value is greater than positive infinity");
                }
                return DoublePoint.newRangeQuery(fieldName,
                    exclusive ? Math.nextUp(v) : v, Double.POSITIVE_INFINITY);
            }
            default: throw new IllegalArgumentException("Range not supported for type: " + type);
        }
    }

    private Query buildInQuery(String fieldName, List<Object> values, ScalarFieldType type) {
        if (values == null || values.isEmpty()) {
            return new MatchNoDocsQuery("empty IN predicate");
        }
        switch (type) {
            case INT: {
                int[] ints = new int[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    ints[i] = ((Number) values.get(i)).intValue();
                }
                return IntPoint.newSetQuery(fieldName, ints);
            }
            case LONG: {
                long[] longs = new long[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    longs[i] = ((Number) values.get(i)).longValue();
                }
                return LongPoint.newSetQuery(fieldName, longs);
            }
            case FLOAT: {
                float[] floats = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    floats[i] = ((Number) values.get(i)).floatValue();
                }
                return FloatPoint.newSetQuery(fieldName, floats);
            }
            case DOUBLE: {
                double[] doubles = new double[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    doubles[i] = ((Number) values.get(i)).doubleValue();
                }
                return DoublePoint.newSetQuery(fieldName, doubles);
            }
            case KEYWORD: {
                List<BytesRef> terms = new ArrayList<>(values.size());
                for (Object value : values) {
                    terms.add(new BytesRef(value.toString()));
                }
                return new TermInSetQuery(fieldName, terms);
            }
            default:
                throw new IllegalArgumentException("IN query not supported for type: " + type);
        }
    }

    long[] collectFilterRowIdsNoScores(Query query) throws IOException {
        Query rewritten = searcher.rewrite(query);
        Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
        long[] ids = new long[Math.min(Math.max(reader.maxDoc(), 16), 1024)];
        int count = 0;
        for (LeafReaderContext leaf : reader.leaves()) {
            Scorer scorer = weight.scorer(leaf);
            if (scorer == null) {
                continue;
            }
            SortedNumericDocValues rowIds =
                    leaf.reader().getSortedNumericDocValues(DefaultESIndexBuilder.ROW_ID_FIELD);
            DocIdSetIterator iterator = scorer.iterator();
            for (int leafDocId = iterator.nextDoc();
                    leafDocId != DocIdSetIterator.NO_MORE_DOCS;
                    leafDocId = iterator.nextDoc()) {
                if (count == ids.length) {
                    ids = Arrays.copyOf(ids, ids.length * 2);
                }
                ids[count++] = rowIdForLeafDoc(leaf, leafDocId, rowIds);
            }
        }
        return Arrays.copyOf(ids, count);
    }

    private static boolean debugEnabled() {
        return Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));
    }



    private static boolean useExactCandidateVectorSearch(int candidateCount) {
        int threshold = exactCandidateThreshold();
        return threshold > 0 && candidateCount <= threshold;
    }

    private static int exactCandidateThreshold() {
        String value = System.getProperty(EXACT_CANDIDATE_THRESHOLD_PROPERTY);
        if (value == null || value.isEmpty()) {
            return DEFAULT_EXACT_CANDIDATE_THRESHOLD;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return DEFAULT_EXACT_CANDIDATE_THRESHOLD;
        }
    }

    private static boolean directDocIdFilterEnabled() {
        return Boolean.parseBoolean(System.getProperty(DIRECT_DOC_ID_FILTER_PROPERTY, "true"));
    }

    private static void validateTopK(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive; got: " + topK);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private SearchResult toSearchResult(TopDocs topDocs) throws IOException {
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        long[] ids = new long[scoreDocs.length];
        float[] scores = new float[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            ids[i] = rowIdForDoc(scoreDocs[i].doc);
            scores[i] = scoreDocs[i].score;
        }
        return new SearchResult(ids, scores, scoreDocs.length);
    }

    private long rowIdForDoc(int docId) throws IOException {
        List<LeafReaderContext> leaves = reader.leaves();
        int subIndex = ReaderUtil.subIndex(docId, leaves);
        LeafReaderContext leaf = leaves.get(subIndex);
        int leafDocId = docId - leaf.docBase;
        SortedNumericDocValues rowIds =
                leaf.reader().getSortedNumericDocValues(DefaultESIndexBuilder.ROW_ID_FIELD);
        return rowIdForLeafDoc(leaf, leafDocId, rowIds);
    }

    private long rowIdForLeafDoc(
            LeafReaderContext leaf, int leafDocId, SortedNumericDocValues rowIds)
            throws IOException {
        if (rowIds != null && rowIds.advanceExact(leafDocId) && rowIds.docValueCount() > 0) {
            return rowIds.nextValue();
        }
        return leaf.docBase + leafDocId;
    }

    private static final class ScoredRow {
        private final long rowId;
        private final float score;

        private ScoredRow(long rowId, float score) {
            this.rowId = rowId;
            this.score = score;
        }
    }

    private static final class ScoredDoc {
        private final int docId;
        private final int subIndex;
        private final int leafDocId;
        private final float score;

        private ScoredDoc(int docId, int subIndex, int leafDocId, float score) {
            this.docId = docId;
            this.subIndex = subIndex;
            this.leafDocId = leafDocId;
            this.score = score;
        }
    }
}
