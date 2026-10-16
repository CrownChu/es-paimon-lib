/* Licensed under the Elastic License 2.0. */
package org.apache.paimon.elasticsearch;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.elasticsearch.eslib.api.ArchiveDataProvider;
import org.elasticsearch.eslib.nativeindex.NativeArchiveMetadata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Calls the original Paimon V1 declarations, independently of the ESLib binding. */
public final class PaimonV1Fixture implements ArchiveDataProvider {
    public static final long START = 9007199254740993L;
    public static final byte[] MAPPING = ("{\"_source\":{\"enabled\":false},\"properties\":{"
            + "\"f0\":{\"type\":\"long\"},"
            + "\"f1\":{\"analyzer\":\"standard\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}},\"type\":\"text\"},"
            + "\"f2\":{\"dims\":2,\"index\":true,\"index_options\":{\"type\":\"hnsw\"},"
            + "\"similarity\":\"l2_norm\",\"type\":\"dense_vector\"}}}").getBytes(StandardCharsets.UTF_8);
    public final Map<String, long[]> offsets = new LinkedHashMap<>();
    public final PaimonElasticsearchFileMeta metadata;
    public final byte[] sealedDirectorySha256;
    public boolean closed;
    private final byte[] archive;
    private final Path directory;

    public PaimonV1Fixture(String library, String commit, String soSha256) throws Exception {
        NativeElasticppLibrary api = Native.load(library, NativeElasticppLibrary.class);
        directory = Files.createTempDirectory("paimon-original-abi-");
        byte[] table = sha(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        byte[] partition = sha(new byte[] {1}), columns = sha(new byte[] {2});
        List<PaimonElasticsearchFileMeta.FileMetadata> files = new ArrayList<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Memory name = memory(hex(table)); Memory path = memory(directory.toString());
                Memory mapping = memory(MAPPING); Memory op = memory("original-paimon-bulk")) {
            NativeElasticppLibrary.ShardIdentifier shard = new NativeElasticppLibrary.ShardIdentifier();
            shard.index = name;
            shard.write();
            NativeElasticppLibrary.ShardCreateOptionsV2 options = new NativeElasticppLibrary.ShardCreateOptionsV2();
            options.openMode = 1;
            options.closeMode = 2;
            options.maxDocumentBytes = 8L * 1024 * 1024;
            options.maxThreadStates = 1;
            options.maxPendingOperations = 1;
            options.maxPendingBytes = 16L * 1024 * 1024;
            options.documentIdMode = 1;
            options.indexSortById = 1;
            options.optionFlags = 1;
            options.initialize(2);
            options.write();
            checkedWrite(api, api.createShardV3(shard, path, options));
            try {
                NativeElasticppLibrary.ShardWriteMappingRequestV1 request = new NativeElasticppLibrary.ShardWriteMappingRequestV1();
                request.generation = 1;
                request.mappingSource = mapping;
                request.mappingSourceLength = MAPPING.length;
                request.initialize();
                request.write();
                checkedWrite(api, api.updateShardWriteMappingV1(shard, request));
                String[] documents = {"{\"f0\":9007199254740993,\"f1\":\"alpha engine\",\"f2\":[1,0]}",
                        "{}", "{\"f0\":9007199254740995,\"f1\":\"beta engine\",\"f2\":[4,2]}"};
                List<Memory> sources = new ArrayList<>();
                try {
                    int stride = new NativeElasticppLibrary.ShardU64BulkWriteItemV1().size();
                    Memory itemBuffer = new Memory(3L * stride);
                    itemBuffer.clear();
                    sources.add(itemBuffer);
                    NativeElasticppLibrary.ShardU64BulkWriteItemV1[] items = new NativeElasticppLibrary.ShardU64BulkWriteItemV1[3];
                    for (int i = 0; i < items.length; i++) {
                        items[i] = new NativeElasticppLibrary.ShardU64BulkWriteItemV1(itemBuffer.share((long) i * stride));
                        Memory source = memory(documents[i]);
                        sources.add(source);
                        items[i].documentId = START + i;
                        items[i].source = source;
                        items[i].sourceLength = source.size() - 1;
                        items[i].mappingGeneration = 1;
                        items[i].ifSequenceNumber = -2;
                        items[i].initialize();
                        items[i].write();
                    }
                    NativeElasticppLibrary.ShardU64BulkWriteRequestV1 bulk = new NativeElasticppLibrary.ShardU64BulkWriteRequestV1();
                    bulk.operationId = op;
                    bulk.operationIdLength = (int) op.size() - 1;
                    bulk.itemCount = 3;
                    bulk.items = items[0].getPointer();
                    bulk.itemStructSize = items[0].size();
                    bulk.durability = 1;
                    bulk.timeoutMillis = 30000;
                    bulk.initialize();
                    bulk.write();
                    Pointer pointer = api.bulkShardDocumentsU64V1(shard, bulk);
                    if (pointer == null) throw new AssertionError("Original Paimon bulk returned null");
                    try {
                        NativeElasticppLibrary.ShardBulkWriteResultV1 result = new NativeElasticppLibrary.ShardBulkWriteResultV1(pointer);
                        requireWrite(result.summary);
                        if (result.itemCount != 3) throw new AssertionError("Incomplete original Paimon bulk");
                        for (int i = 0; i < 3; i++) requireWrite(new NativeElasticppLibrary.ShardWriteResultV1(
                                result.itemResults.share((long) i * new NativeElasticppLibrary.ShardWriteResultV1().size())));
                    } finally { api.freeShardBulkWriteResultV1(pointer); }
                } finally { for (Memory source : sources) source.close(); }
                try (Memory commitOp = memory("original-paimon-commit")) {
                    NativeElasticppLibrary.ShardWriteControlRequestV1 control = new NativeElasticppLibrary.ShardWriteControlRequestV1();
                    control.operationId = commitOp;
                    control.operationIdLength = (int) commitOp.size() - 1;
                    control.timeoutMillis = 30000;
                    control.initialize();
                    control.write();
                    checkedWrite(api, api.commitShardV1(shard, control));
                }
                NativeElasticppLibrary.PaimonEsShardIdentityV1 identity = identity(name, 31, 7, START, 3, partition, columns, sha(MAPPING));
                NativeElasticppLibrary.PaimonEsSealRequestV1 seal = new NativeElasticppLibrary.PaimonEsSealRequestV1();
                seal.shard = shard.getPointer();
                seal.localPath = path;
                seal.localPathLength = (int) path.size() - 1;
                seal.identity = identity.getPointer();
                seal.timeoutMillis = 30000;
                seal.initialize();
                seal.write();
                Pointer pointer = api.sealShardV1(seal);
                if (pointer == null) throw new AssertionError("Original Paimon seal returned null");
                try {
                    NativeElasticppLibrary.PaimonEsSealResultV1 result = new NativeElasticppLibrary.PaimonEsSealResultV1(pointer);
                    requireStatus(result.status);
                    if (result.maxDoc != 3 || result.numDocs != 3 || result.numDeletedDocs != 0)
                        throw new AssertionError("Original Paimon seal lost rows");
                    sealedDirectorySha256 = result.directorySha256.clone();
                    DataOutputStream output = new DataOutputStream(bytes);
                    output.writeInt(Math.toIntExact(result.fileCount));
                    for (int i = 0; i < result.fileCount; i++) {
                        NativeElasticppLibrary.PaimonEsFileMetadataV1 file = new NativeElasticppLibrary.PaimonEsFileMetadataV1(
                                result.files.share((long) i * new NativeElasticppLibrary.PaimonEsFileMetadataV1().size()));
                        String fileName = new String(file.name.getByteArray(0, file.nameLength), StandardCharsets.UTF_8);
                        byte[] content = Files.readAllBytes(directory.resolve(fileName));
                        if (content.length != file.length || !Arrays.equals(sha(content), file.sha256))
                            throw new AssertionError("Original native file identity differs");
                        output.writeInt(file.nameLength);
                        output.write(fileName.getBytes(StandardCharsets.UTF_8));
                        output.writeLong(file.length);
                        long offset = bytes.size();
                        offsets.put(fileName, new long[] {offset, file.length});
                        files.add(new PaimonElasticsearchFileMeta.FileMetadata(fileName, offset, file.length, file.sha256));
                        output.write(content);
                    }
                    output.flush();
                } finally { api.freePaimonEsSealResultV1(pointer); }
            } finally { checkedWrite(api, api.removeShardV2(shard)); }
        }
        archive = bytes.toByteArray();
        List<PaimonElasticsearchFileMeta.FieldMetadata> fields = Arrays.asList(
                new PaimonElasticsearchFileMeta.FieldMetadata(0, "id", "BIGINT", true, "f0", "long", new byte[] {'{', '}'}),
                new PaimonElasticsearchFileMeta.FieldMetadata(1, "title", "STRING", true, "f1", "text", new byte[] {'{', '}'}),
                new PaimonElasticsearchFileMeta.FieldMetadata(2, "vector", "VECTOR<2, FLOAT>", true, "f2", "dense_vector", new byte[] {'{', '}'}));
        PaimonElasticsearchFileMeta encoded = new PaimonElasticsearchFileMeta(
                "paimon-elasticsearch", "2.2-SNAPSHOT", table, "main", "interop", 31, 7,
                partition, -1, START, START + 2, 3, 3, fields, MAPPING, sha(MAPPING), commit,
                1, 0, "9.4.0", "10.4.0", commit, unhex(soSha256), "x86_64-unknown-linux-gnu", "2.32",
                columns, archive.length, sha(archive), files.stream().mapToLong(f -> f.length).sum(), files);
        metadata = PaimonElasticsearchFileMeta.deserialize(encoded.serialize());
    }

    public NativeArchiveMetadata descriptor(long snapshotId) throws Exception {
        Map<String, NativeArchiveMetadata.File> files = new LinkedHashMap<>();
        for (PaimonElasticsearchFileMeta.FileMetadata file : metadata.files)
            files.put(file.name, new NativeArchiveMetadata.File(file.length, hex(file.sha256)));
        return new NativeArchiveMetadata(hex(metadata.tableIdentitySha256), snapshotId, metadata.schemaId,
                metadata.rowStart, metadata.rowCount, hex(metadata.partitionSha256),
                hex(metadata.semanticOptionsSha256), metadata.canonicalMapping,
                metadata.elasticppCommit, hex(metadata.sharedObjectSha256), files);
    }

    public Path directory() { return directory; }

    public static void probe(String library, Path directory, String table, long snapshot, long schema,
            long start, long rows, byte[] partition, byte[] columns, byte[] mapping, byte[] digest) {
        NativeElasticppLibrary api = Native.load(library, NativeElasticppLibrary.class);
        try (Memory name = memory(table); Memory path = memory(directory.toString())) {
            NativeElasticppLibrary.PaimonEsShardIdentityV1 identity = identity(name, snapshot, schema, start, rows, partition, columns, mapping);
            NativeElasticppLibrary.PaimonEsProbeRequestV1 request = new NativeElasticppLibrary.PaimonEsProbeRequestV1();
            request.localPath = path;
            request.localPathLength = (int) path.size() - 1;
            request.identity = identity.getPointer();
            request.expectedDirectorySha256 = digest.clone();
            request.initialize();
            request.write();
            Pointer pointer = api.probeArchivedShardV1(request);
            if (pointer == null) throw new AssertionError("Original Paimon probe returned null");
            try { requireStatus(new NativeElasticppLibrary.PaimonEsProbeResultV1(pointer).status); }
            finally { api.freePaimonEsProbeResultV1(pointer); }
        }
    }

    private static NativeElasticppLibrary.PaimonEsShardIdentityV1 identity(Memory name, long snapshot,
            long schema, long start, long rows, byte[] partition, byte[] columns, byte[] mapping) {
        NativeElasticppLibrary.PaimonEsShardIdentityV1 value = new NativeElasticppLibrary.PaimonEsShardIdentityV1();
        value.tableUuid = name;
        value.tableUuidLength = (int) name.size() - 1;
        value.snapshotId = snapshot;
        value.schemaId = schema;
        value.rowStart = start;
        value.rowCount = rows;
        value.partitionSha256 = partition.clone();
        value.indexColumnsSha256 = columns.clone();
        value.mappingSha256 = mapping.clone();
        value.initialize();
        value.write();
        return value;
    }

    private static void checkedWrite(NativeElasticppLibrary api, Pointer pointer) {
        if (pointer == null) throw new AssertionError("Original Paimon write returned null");
        try { requireWrite(new NativeElasticppLibrary.ShardWriteResultV1(pointer)); }
        finally { api.freeShardWriteResultV1(pointer); }
    }

    private static void requireWrite(NativeElasticppLibrary.ShardWriteResultV1 result) {
        if (result.success != 1 || result.errorCode != 0)
            throw new AssertionError("Original Paimon write failed: " + result.errorCode + " "
                    + (result.exceptionMessage == null ? "" : result.exceptionMessage.getString(0)));
    }

    private static void requireStatus(NativeElasticppLibrary.PaimonEsStatusV1 result) {
        if (result.success != 1 || result.errorCode != 0)
            throw new AssertionError("Original Paimon ABI failed: " + result.errorCode + " "
                    + (result.diagnostic == null ? "" : result.diagnostic.getString(0)));
    }

    private static Memory memory(String value) { return memory(value.getBytes(StandardCharsets.UTF_8)); }
    private static Memory memory(byte[] bytes) {
        Memory memory = new Memory(bytes.length + 1L);
        memory.clear();
        memory.write(0, bytes, 0, bytes.length);
        return memory;
    }
    private static byte[] sha(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
    private static byte[] unhex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
    @Override public byte[] readRange(long offset, int length) {
        return Arrays.copyOfRange(archive, Math.toIntExact(offset), Math.toIntExact(offset + length));
    }
    @Override public ArchiveDataProvider fork() {
        return new ArchiveDataProvider() {
            @Override public byte[] readRange(long offset, int length) {
                return PaimonV1Fixture.this.readRange(offset, length);
            }
            @Override public ArchiveDataProvider fork() { return PaimonV1Fixture.this.fork(); }
        };
    }
    @Override public void close() { closed = true; }
    public void delete() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                Files.delete(path);
        }
    }
}
