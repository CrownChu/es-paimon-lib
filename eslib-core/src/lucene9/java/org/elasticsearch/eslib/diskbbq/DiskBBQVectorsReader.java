/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * Licensed under the Elastic License 2.0.
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.hnsw.FlatVectorScorerUtil;
import org.apache.lucene.codecs.hnsw.FlatVectorsReader;
import org.apache.lucene.codecs.lucene99.Lucene99FlatVectorsFormat;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopKnnCollector;
import org.apache.lucene.search.VectorScorer;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.eslib.compat.ES91OSQVectorsScorer;
import org.elasticsearch.eslib.compat.ESVectorUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.elasticsearch.eslib.diskbbq.BQVectorUtils.discretize;
import static org.elasticsearch.eslib.diskbbq.OptimizedScalarQuantizer.DEFAULT_LAMBDA;
import static org.elasticsearch.eslib.compat.ES91OSQVectorsScorer.BULK_SIZE;
import static org.elasticsearch.eslib.compat.ESVectorUtil.transposeHalfByte;

/**
 * Real KnnVectorsReader for DiskBBQ IVF format.
 * Reads centroid and cluster data from the Directory (backed by ArchiveDirectory),
 * and implements search() with acceptDocs filtering at the cluster level (same as ES9).
 * Supports parallel cluster search when nprobe exceeds the configured threshold.
 */
public class DiskBBQVectorsReader extends KnnVectorsReader {

    private static final int QUERY_BITS = 4;

    private final SegmentReadState state;
    private final DiskBBQCentroidReader centroidReader;
    private final IndexInput clusterInput;
    private final VectorIndexConfig config;
    private final int dimension;
    private final VectorSimilarityFunction similarity;
    private final int discretizedDimensions;
    private final long quantizedByteLength;
    private final long quantizedVectorByteSize;
    private final int parallelThreshold;
    private final ExecutorService executor;
    // Underlying raw vector reader: required so merge can iterate raw vectors via
    // MergedVectorValues.mergeFloatVectorValues. Without it, mergedFloatVectorValues.size() returns 0
    // and we lose all data during force-merge even though per-segment flush wrote real centroids/clusters.
    private final FlatVectorsReader rawVectorsReader;

    public DiskBBQVectorsReader(SegmentReadState state,
                                int vectorsPerCluster,
                                int centroidsPerParentCluster,
                                int parallelThreshold,
                                ExecutorService executor) throws IOException {
        this.state = state;
        this.parallelThreshold = parallelThreshold;
        this.executor = executor;

        // Resolve this segment's own files by name. Using directory.listAll() + endsWith is wrong
        // when multiple segments coexist (e.g. during a merge: the directory holds every segment's
        // .mivf/.cenivf/.clivf plus the half-written merge target), which leads to reading the
        // wrong (or empty) file and EOFException. Lucene names per-segment files as
        // <segmentName>_<segmentSuffix>.<ext>.
        String mivfFile = IndexFileNames.segmentFileName(
                state.segmentInfo.name, state.segmentSuffix,
                PaimonDiskBBQVectorsFormat.IVF_META_EXTENSION);
        String cenivfFile = IndexFileNames.segmentFileName(
                state.segmentInfo.name, state.segmentSuffix,
                PaimonDiskBBQVectorsFormat.CENTROID_EXTENSION);
        String clivfFile = IndexFileNames.segmentFileName(
                state.segmentInfo.name, state.segmentSuffix,
                PaimonDiskBBQVectorsFormat.CLUSTER_EXTENSION);

        org.apache.lucene.index.FieldInfo vectorFieldInfo = null;
        for (org.apache.lucene.index.FieldInfo fi : state.fieldInfos) {
            if (fi.getVectorDimension() > 0) {
                vectorFieldInfo = fi;
                break;
            }
        }
        if (vectorFieldInfo == null) {
            throw new IOException("No vector field found in segment");
        }

        int dim = vectorFieldInfo.getVectorDimension();
        VectorSimilarityFunction sim = vectorFieldInfo.getVectorSimilarityFunction();
        this.config = new VectorIndexConfig(
                vectorFieldInfo.name, dim, sim, vectorsPerCluster, centroidsPerParentCluster);
        this.dimension = dim;
        this.similarity = sim;
        this.discretizedDimensions = discretize(dimension, 64);
        this.quantizedVectorByteSize = discretizedDimensions / 8;
        this.quantizedByteLength = quantizedVectorByteSize + (Float.BYTES * 3) + Short.BYTES;

        IndexInput metaInput = state.directory.openInput(mivfFile, state.context);
        IndexInput centroidInput = state.directory.openInput(cenivfFile, state.context);
        this.clusterInput = state.directory.openInput(clivfFile, state.context);

        this.centroidReader = new DiskBBQCentroidReader();
        centroidReader.load(metaInput, centroidInput, config);

        // Open the underlying raw vectors reader (reads .vec/.vem produced by Lucene99FlatVectorsWriter).
        // This MUST match the FlatVectorsFormat that IVFVectorsWriter delegates raw writes to in
        // PaimonDiskBBQVectorsFormat. Required so merge sees real FloatVectorValues for each segment.
        this.rawVectorsReader = new Lucene99FlatVectorsFormat(
                FlatVectorScorerUtil.getLucene99FlatVectorsScorer()).fieldsReader(state);
    }

    public DiskBBQVectorsReader(SegmentReadState state, VectorIndexConfig config,
                                DiskBBQCentroidReader centroidReader,
                                IndexInput clusterInput) {
        this(state, config, centroidReader, clusterInput,
                PaimonDiskBBQVectorsFormat.DEFAULT_PARALLEL_THRESHOLD, null);
    }

    public DiskBBQVectorsReader(SegmentReadState state, VectorIndexConfig config,
                                DiskBBQCentroidReader centroidReader,
                                IndexInput clusterInput,
                                int parallelThreshold, ExecutorService executor) {
        this.state = state;
        this.config = config;
        this.dimension = config.dimension();
        this.similarity = config.similarity();
        this.discretizedDimensions = discretize(dimension, 64);
        this.quantizedVectorByteSize = discretizedDimensions / 8;
        this.quantizedByteLength = quantizedVectorByteSize + (Float.BYTES * 3) + Short.BYTES;
        this.centroidReader = centroidReader;
        this.clusterInput = clusterInput;
        this.parallelThreshold = parallelThreshold;
        this.executor = executor;
        // Archive/mount path: caller has pre-built the centroid+cluster readers; no raw .vec available
        // here, so we don't open a FlatVectorsReader. This ctor isn't used by Lucene's merge path
        // (Lucene goes through the format's fieldsReader which uses the primary ctor above).
        this.rawVectorsReader = null;
    }

    @Override
    public void checkIntegrity() throws IOException {
        if (rawVectorsReader != null) {
            rawVectorsReader.checkIntegrity();
        }
    }

    @Override
    public FloatVectorValues getFloatVectorValues(String field) throws IOException {
        // Delegate to the underlying raw vectors reader so that:
        //   1. ES KnnFloatVectorQuery.approximateSearch's size-precheck passes (size > 0)
        //   2. **merge** can iterate real vectors via MergedVectorValues.mergeFloatVectorValues
        //      (previously a stub returning NO_MORE_DOCS on nextDoc() caused force-merge to drop
        //      all data: per-segment IVF/.vec were correctly written but the merged segment came
        //      out empty because mergedFloatVectorValues.size()==0).
        if (rawVectorsReader == null) {
            return null;
        }
        return rawVectorsReader.getFloatVectorValues(field);
    }

    @Override
    public ByteVectorValues getByteVectorValues(String field) throws IOException {
        return new EmptyByteVectorValues();
    }

    @Override
    public void search(String field, float[] target, KnnCollector knnCollector,
                       Bits acceptDocs) throws IOException {
        int numCentroids = centroidReader.numCentroids();
        FloatVectorValues fvv = getFloatVectorValues(field);
        int numVectors = fvv != null ? fvv.size() : 0;
        int nprobe = computeNprobe(numCentroids, numVectors, knnCollector.k());
        List<ClusterReference> clusters = centroidReader.findNearestClusters(target, nprobe);

        int scoredDocs;
        if (executor == null || clusters.size() <= parallelThreshold) {
            scoredDocs = searchClustersSerial(clusters, target, knnCollector, acceptDocs);
        } else {
            scoredDocs = searchClustersParallel(clusters, target, knnCollector, acceptDocs);
        }
        // Must update visitedCount so KnnFloatVectorQuery.TopDocs reports a non-zero
        // totalHits (otherwise the wrapping query treats this leaf's result as empty even when
        // candidates were collected). Mirrors the lucene10 ES920DiskBBQVectorsReader path.
        if (scoredDocs > 0) {
            knnCollector.incVisitedCount(scoredDocs);
        }
    }

    private int searchClustersSerial(List<ClusterReference> clusters, float[] target,
                                     KnnCollector collector, Bits acceptDocs) throws IOException {
        int scoredDocs = 0;
        for (ClusterReference ref : clusters) {
            IndexInput slice = clusterInput.slice(
                    "cluster-" + ref.clusterId(), ref.offset(), ref.length());
            scoredDocs += visitCluster(slice, target, collector, acceptDocs);
        }
        return scoredDocs;
    }

    /** Per-cluster result from a parallel task: collected TopDocs plus the visited-count. */
    private static final class ClusterTopDocs {
        final TopDocs topDocs;
        final int scoredDocs;

        ClusterTopDocs(TopDocs topDocs, int scoredDocs) {
            this.topDocs = topDocs;
            this.scoredDocs = scoredDocs;
        }
    }

    private int searchClustersParallel(List<ClusterReference> clusters, float[] target,
                                       KnnCollector collector, Bits acceptDocs) throws IOException {
        int topK = collector.k();
        List<Future<ClusterTopDocs>> futures = new ArrayList<>(clusters.size());

        for (ClusterReference ref : clusters) {
            futures.add(executor.submit(() -> {
                IndexInput cloned = clusterInput.clone();
                try {
                    IndexInput slice = cloned.slice(
                            "cluster-" + ref.clusterId(), ref.offset(), ref.length());
                    TopKnnCollector localCollector = new TopKnnCollector(topK, Integer.MAX_VALUE);
                    int n = visitCluster(slice, target, localCollector, acceptDocs);
                    return new ClusterTopDocs(localCollector.topDocs(), n);
                } finally {
                    cloned.close();
                }
            }));
        }

        int scoredDocs = 0;
        for (Future<ClusterTopDocs> f : futures) {
            try {
                ClusterTopDocs result = f.get();
                for (ScoreDoc sd : result.topDocs.scoreDocs) {
                    // Guard against NaN scores poisoning the merge priority queue.
                    if (Float.isNaN(sd.score) == false
                            && collector.minCompetitiveSimilarity() < sd.score) {
                        collector.collect(sd.doc, sd.score);
                    }
                }
                scoredDocs += result.scoredDocs;
            } catch (Exception e) {
                throw new IOException("Parallel cluster search failed", e);
            }
        }
        return scoredDocs;
    }

    @Override
    public void search(String field, byte[] target, KnnCollector knnCollector,
                       Bits acceptDocs) throws IOException {
        // DiskBBQ only supports float vectors
    }

    /**
     * Score one cluster and feed surviving docs into {@code collector}. Returns the number of
     * docs that were actually scored (used by the caller to update {@code KnnCollector.visitedCount}
     * so the wrapping {@code KnnFloatVectorQuery} sees a non-zero total-hits).
     */
    private int visitCluster(IndexInput input, float[] target,
                             KnnCollector collector, Bits acceptDocs) throws IOException {
        float[] centroid = new float[dimension];
        input.readFloats(centroid, 0, dimension);
        float centroidDp = Float.intBitsToFloat(input.readInt());

        int numVectors = input.readVInt();
        byte docEncoding = input.readByte();

        if (numVectors == 0) {
            return 0;
        }

        OptimizedScalarQuantizer quantizer =
                new OptimizedScalarQuantizer(similarity, DEFAULT_LAMBDA, 1);
        float[] scratch = new float[dimension];
        int[] quantizationScratch = new int[dimension];
        byte[] quantizedQuery = new byte[QUERY_BITS * discretizedDimensions / 8];

        OptimizedScalarQuantizer.QuantizationResult queryCorrections =
                quantizer.scalarQuantize(target, scratch, quantizationScratch, (byte) 4, centroid);
        transposeHalfByte(quantizationScratch, quantizedQuery);

        ES91OSQVectorsScorer osqScorer = ESVectorUtil.getES91OSQVectorsScorer(input, dimension);
        DocIdsWriter idsWriter = new DocIdsWriter();
        int[] docIdsScratch = new int[BULK_SIZE];
        float[] scores = new float[BULK_SIZE];
        float[] correctionsLower = new float[BULK_SIZE];
        float[] correctionsUpper = new float[BULK_SIZE];
        int[] correctionsSum = new int[BULK_SIZE];
        float[] correctionsAdd = new float[BULK_SIZE];
        float[] correctiveValues = new float[3];
        int docBase = 0;

        int scoredDocs = 0;
        int limit = numVectors - BULK_SIZE + 1;
        int i = 0;
        for (; i < limit; i += BULK_SIZE) {
            idsWriter.readInts(input, BULK_SIZE, docEncoding, docIdsScratch);
            for (int j = 0; j < BULK_SIZE; j++) {
                docBase += docIdsScratch[j];
                docIdsScratch[j] = docBase;
            }

            int docsToScore = BULK_SIZE;
            if (acceptDocs != null) {
                docsToScore = 0;
                for (int j = 0; j < BULK_SIZE; j++) {
                    if (!acceptDocs.get(docIdsScratch[j])) {
                        docIdsScratch[j] = -1;
                        continue;
                    }
                    docsToScore++;
                }
            }

            if (docsToScore == 0) {
                input.skipBytes(quantizedByteLength * BULK_SIZE);
                continue;
            }

            float maxScore;
            if (docsToScore < BULK_SIZE / 2) {
                maxScore = scoreIndividually(input, osqScorer, docIdsScratch, quantizedQuery,
                        queryCorrections, centroidDp, scores,
                        correctionsLower, correctionsUpper, correctionsSum, correctionsAdd);
            } else {
                maxScore = osqScorer.scoreBulk(
                        quantizedQuery,
                        queryCorrections.lowerInterval(),
                        queryCorrections.upperInterval(),
                        queryCorrections.quantizedComponentSum(),
                        queryCorrections.additionalCorrection(),
                        similarity,
                        centroidDp,
                        scores);
            }

            // NaN-safe comparison: NaN < anything is false, so NaN maxScore won't push.
            if (collector.minCompetitiveSimilarity() < maxScore) {
                for (int j = 0; j < BULK_SIZE; j++) {
                    if (docIdsScratch[j] != -1 && Float.isNaN(scores[j]) == false) {
                        collector.collect(docIdsScratch[j], scores[j]);
                    }
                }
            }
            scoredDocs += docsToScore;
        }

        // Process tail
        if (i < numVectors) {
            int tailSize = numVectors - i;
            idsWriter.readInts(input, tailSize, docEncoding, docIdsScratch);
            for (int j = 0; j < tailSize; j++) {
                docBase += docIdsScratch[j];
                docIdsScratch[j] = docBase;
            }

            for (int j = 0; j < tailSize; j++) {
                int doc = docIdsScratch[j];
                if (acceptDocs != null && !acceptDocs.get(doc)) {
                    input.skipBytes(quantizedByteLength);
                    continue;
                }

                float qcDist = osqScorer.quantizeScore(quantizedQuery);
                input.readFloats(correctiveValues, 0, 3);
                int quantizedComponentSum = Short.toUnsignedInt(input.readShort());
                float score = osqScorer.score(
                        queryCorrections.lowerInterval(),
                        queryCorrections.upperInterval(),
                        queryCorrections.quantizedComponentSum(),
                        queryCorrections.additionalCorrection(),
                        similarity,
                        centroidDp,
                        correctiveValues[0],
                        correctiveValues[1],
                        quantizedComponentSum,
                        correctiveValues[2],
                        qcDist);
                // Gate on competitive similarity AND NaN, matching the lucene10 reader path.
                if (Float.isNaN(score) == false && collector.minCompetitiveSimilarity() < score) {
                    collector.collect(doc, score);
                }
                scoredDocs++;
            }
        }
        return scoredDocs;
    }

    private float scoreIndividually(
            IndexInput input, ES91OSQVectorsScorer osqScorer,
            int[] docIds, byte[] quantizedQuery,
            OptimizedScalarQuantizer.QuantizationResult queryCorrections,
            float centroidDp, float[] scores,
            float[] correctionsLower, float[] correctionsUpper,
            int[] correctionsSum, float[] correctionsAdd) throws IOException {
        float maxScore = Float.NEGATIVE_INFINITY;
        for (int j = 0; j < BULK_SIZE; j++) {
            if (docIds[j] != -1) {
                scores[j] = osqScorer.quantizeScore(quantizedQuery);
            } else {
                input.skipBytes(quantizedVectorByteSize);
            }
        }
        input.readFloats(correctionsLower, 0, BULK_SIZE);
        input.readFloats(correctionsUpper, 0, BULK_SIZE);
        for (int j = 0; j < BULK_SIZE; j++) {
            correctionsSum[j] = Short.toUnsignedInt(input.readShort());
        }
        input.readFloats(correctionsAdd, 0, BULK_SIZE);
        for (int j = 0; j < BULK_SIZE; j++) {
            if (docIds[j] != -1) {
                scores[j] = osqScorer.score(
                        queryCorrections.lowerInterval(),
                        queryCorrections.upperInterval(),
                        queryCorrections.quantizedComponentSum(),
                        queryCorrections.additionalCorrection(),
                        similarity,
                        centroidDp,
                        correctionsLower[j],
                        correctionsUpper[j],
                        correctionsSum[j],
                        correctionsAdd[j],
                        scores[j]);
                if (scores[j] > maxScore) {
                    maxScore = scores[j];
                }
            }
        }
        return maxScore;
    }

    // Native ES920 visit-ratio model + segment-size cap, translated to a cluster count.
    // The previous heuristic (max(topK/10, sqrt(numCentroids))) had no segment-size cap and probed
    // too many clusters at large topK, flooding the candidate pool with far-cluster false positives.
    private static final double V_MIN = 0.003;
    private static final double V_MAX = 0.04;
    private static final double LOG1P_K_MAX = Math.log1p(10_000.0);
    private static final double K_WEIGHT = 0.15;
    private static final double CAP_COEFFICIENT = 0.045;
    private static final int CAP_REF_SIZE = 1_000_000;
    private static final double CAP_EXPONENT = 0.35;
    private static final float DEFAULT_TARGET_RECALL = 0.9f;

    private static int computeNprobe(int numCentroids, int numVectors, int topK) {
        if (numCentroids <= 0) {
            return 1;
        }
        if (numVectors <= 0) {
            return Math.max(1, Math.min((int) Math.ceil(Math.sqrt(numCentroids)), numCentroids));
        }
        // numCands == topK on this path; r == 1 so the ratio term drops out (matches native default).
        double z = K_WEIGHT * Math.max(0.0, Math.min(1.0, Math.log1p(topK) / LOG1P_K_MAX));
        double dynamicVisitRatio = V_MIN + (V_MAX - V_MIN) * z;
        double sizeScale = Math.pow((double) CAP_REF_SIZE / numVectors, CAP_EXPONENT);
        double cap = Math.min(1.0, CAP_COEFFICIENT * sizeScale * (0.1 / (1.0 - DEFAULT_TARGET_RECALL)));
        double visitRatio = Math.min(dynamicVisitRatio, cap);
        // factor 2 accounts for SOAR (a vector may live in 2 clusters), matching native maxVectorVisited
        int budget = (int) Math.ceil(2.0 * visitRatio * numCentroids);
        // ensure enough clusters to fill topK results
        int avgClusterSize = Math.max(1, numVectors / numCentroids);
        int minVisit = Math.max(1, (int) Math.ceil((double) topK / avgClusterSize));
        return Math.max(minVisit, Math.min(budget, numCentroids));
    }

    @Override
    public long ramBytesUsed() {
        return 0;
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(centroidReader, clusterInput, rawVectorsReader);
    }

    private static class EmptyByteVectorValues extends ByteVectorValues {
        @Override public int dimension() { return 0; }
        @Override public int size() { return 0; }
        @Override public byte[] vectorValue() { return new byte[0]; }
        @Override public VectorScorer scorer(byte[] query) { return null; }
        @Override public int docID() { return NO_MORE_DOCS; }
        @Override public int nextDoc() { return NO_MORE_DOCS; }
        @Override public int advance(int target) { return NO_MORE_DOCS; }
    }
}
