/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.elasticsearch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable PES1 metadata and strict codec. */
public final class PaimonElasticsearchFileMeta {

    static final int MAGIC = 0x50455331;
    static final int HEADER_LENGTH = 32;
    static final int MAX_METADATA_BYTES = 16 * 1024 * 1024;
    static final int MAX_MAPPING_BYTES = 8 * 1024 * 1024;
    static final int MAX_FIELDS = 1024;
    static final int MAX_FILES = 100000;
    static final int MAX_NAME_BYTES = 1024;
    static final long MAX_ARCHIVE_BYTES = 1L << 40;

    /** Frozen metadata for one indexed field. */
    public static final class FieldMetadata {
        public final int fieldId;
        public final String logicalName;
        public final String paimonType;
        public final boolean nullable;
        public final String physicalName;
        public final String physicalType;
        public final byte[] canonicalConfig;

        public FieldMetadata(
                int fieldId,
                String logicalName,
                String paimonType,
                boolean nullable,
                String physicalName,
                String physicalType,
                byte[] canonicalConfig) {
            this.fieldId = fieldId;
            this.logicalName = logicalName;
            this.paimonType = paimonType;
            this.nullable = nullable;
            this.physicalName = physicalName;
            this.physicalType = physicalType;
            this.canonicalConfig = canonicalConfig.clone();
        }
    }

    /** Frozen metadata for one archived Lucene file. */
    public static final class FileMetadata {
        public final String name;
        public final long archiveDataOffset;
        public final long length;
        public final byte[] sha256;

        public FileMetadata(String name, long archiveDataOffset, long length, byte[] sha256) {
            this.name = name;
            this.archiveDataOffset = archiveDataOffset;
            this.length = length;
            this.sha256 = requireDigest(sha256, "file sha256");
        }
    }

    public final String indexType;
    public final String moduleVersion;
    public final byte[] tableIdentitySha256;
    public final String branch;
    public final String indexName;
    public final long snapshotId;
    public final long schemaId;
    public final byte[] partitionSha256;
    public final int bucket;
    public final long rowStart;
    public final long rowEnd;
    public final long rowCount;
    public final long docCount;
    public final List<FieldMetadata> fields;
    public final byte[] canonicalMapping;
    public final byte[] mappingSha256;
    public final String elasticppCommit;
    public final int abiMajor;
    public final int abiMinor;
    public final String elasticsearchVersion;
    public final String luceneVersion;
    public final String nativeBuildId;
    public final byte[] sharedObjectSha256;
    public final String targetTriple;
    public final String minimumGlibc;
    public final byte[] semanticOptionsSha256;
    public final long archiveLength;
    public final byte[] archiveSha256;
    public final long uncompressedBytes;
    public final List<FileMetadata> files;

    public PaimonElasticsearchFileMeta(
            String indexType,
            String moduleVersion,
            byte[] tableIdentitySha256,
            String branch,
            String indexName,
            long snapshotId,
            long schemaId,
            byte[] partitionSha256,
            int bucket,
            long rowStart,
            long rowEnd,
            long rowCount,
            long docCount,
            List<FieldMetadata> fields,
            byte[] canonicalMapping,
            byte[] mappingSha256,
            String elasticppCommit,
            int abiMajor,
            int abiMinor,
            String elasticsearchVersion,
            String luceneVersion,
            String nativeBuildId,
            byte[] sharedObjectSha256,
            String targetTriple,
            String minimumGlibc,
            byte[] semanticOptionsSha256,
            long archiveLength,
            byte[] archiveSha256,
            long uncompressedBytes,
            List<FileMetadata> files) {
        this.indexType = indexType;
        this.moduleVersion = moduleVersion;
        this.tableIdentitySha256 = requireDigest(tableIdentitySha256, "table identity digest");
        this.branch = branch;
        this.indexName = indexName;
        this.snapshotId = snapshotId;
        this.schemaId = schemaId;
        this.partitionSha256 = requireDigest(partitionSha256, "partition digest");
        this.bucket = bucket;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        this.rowCount = rowCount;
        this.docCount = docCount;
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        this.canonicalMapping = canonicalMapping.clone();
        this.mappingSha256 = requireDigest(mappingSha256, "mapping digest");
        this.elasticppCommit = elasticppCommit;
        this.abiMajor = abiMajor;
        this.abiMinor = abiMinor;
        this.elasticsearchVersion = elasticsearchVersion;
        this.luceneVersion = luceneVersion;
        this.nativeBuildId = nativeBuildId;
        this.sharedObjectSha256 = requireDigest(sharedObjectSha256, "shared object digest");
        this.targetTriple = targetTriple;
        this.minimumGlibc = minimumGlibc;
        this.semanticOptionsSha256 =
                requireDigest(semanticOptionsSha256, "semantic options digest");
        this.archiveLength = archiveLength;
        this.archiveSha256 = requireDigest(archiveSha256, "archive digest");
        this.uncompressedBytes = uncompressedBytes;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        validate();
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            writeString(payload, indexType);
            writeString(payload, moduleVersion);
            payload.write(tableIdentitySha256);
            writeString(payload, branch);
            writeString(payload, indexName);
            payload.writeLong(snapshotId);
            payload.writeLong(schemaId);
            payload.write(partitionSha256);
            payload.writeInt(bucket);
            payload.writeLong(rowStart);
            payload.writeLong(rowEnd);
            payload.writeLong(rowCount);
            payload.writeLong(docCount);
            payload.writeInt(fields.size());
            for (FieldMetadata field : fields) {
                payload.writeInt(field.fieldId);
                writeString(payload, field.logicalName);
                writeString(payload, field.paimonType);
                payload.writeByte(field.nullable ? 1 : 0);
                writeString(payload, field.physicalName);
                writeString(payload, field.physicalType);
                writeBytes(payload, field.canonicalConfig);
            }
            writeBytes(payload, canonicalMapping);
            payload.write(mappingSha256);
            writeString(payload, elasticppCommit);
            payload.writeInt(abiMajor);
            payload.writeInt(abiMinor);
            writeString(payload, elasticsearchVersion);
            writeString(payload, luceneVersion);
            writeString(payload, nativeBuildId);
            payload.write(sharedObjectSha256);
            writeString(payload, targetTriple);
            writeString(payload, minimumGlibc);
            payload.write(semanticOptionsSha256);
            payload.writeLong(archiveLength);
            payload.write(archiveSha256);
            payload.writeLong(uncompressedBytes);
            payload.writeInt(files.size());
            for (FileMetadata file : files) {
                writeString(payload, file.name);
                payload.writeLong(file.archiveDataOffset);
                payload.writeLong(file.length);
                payload.write(file.sha256);
            }
            payload.flush();
            byte[] body = payloadBytes.toByteArray();
            if (body.length > MAX_METADATA_BYTES - HEADER_LENGTH - Integer.BYTES) {
                throw invalid("PES1 payload exceeds the metadata limit");
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
            header.putInt(MAGIC).putShort((short) 1).putShort((short) 0).putInt(0);
            header.putInt(HEADER_LENGTH).putLong(body.length);
            byte[] headerBytes = header.array();
            header.putInt(Crc32c.compute(headerBytes, 0, 24)).putInt(0);
            ByteArrayOutputStream encoded =
                    new ByteArrayOutputStream(HEADER_LENGTH + body.length + 4);
            encoded.write(header.array());
            encoded.write(body);
            DataOutputStream output = new DataOutputStream(encoded);
            output.writeInt(Crc32c.compute(body, 0, body.length));
            output.flush();
            return encoded.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected in-memory PES1 encoding failure", e);
        }
    }

    public static PaimonElasticsearchFileMeta deserialize(byte[] encoded) {
        if (encoded == null
                || encoded.length < HEADER_LENGTH + 4
                || encoded.length > MAX_METADATA_BYTES) {
            throw invalid("Invalid PES1 metadata length");
        }
        ByteBuffer header = ByteBuffer.wrap(encoded, 0, HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
        if (header.getInt() != MAGIC) {
            throw invalid("Invalid PES1 magic");
        }
        int major = Short.toUnsignedInt(header.getShort());
        int minor = Short.toUnsignedInt(header.getShort());
        int flags = header.getInt();
        int headerLength = header.getInt();
        long payloadLength = header.getLong();
        int headerCrc = header.getInt();
        int reserved = header.getInt();
        if (major != 1
                || minor != 0
                || flags != 0
                || headerLength != HEADER_LENGTH
                || reserved != 0) {
            throw invalid("Unsupported or invalid PES1 header");
        }
        if (headerCrc != Crc32c.compute(encoded, 0, 24)) {
            throw invalid("PES1 header CRC32C mismatch");
        }
        long expectedLength = checkedAdd(checkedAdd(HEADER_LENGTH, payloadLength), Integer.BYTES);
        if (payloadLength < 0
                || payloadLength > MAX_METADATA_BYTES - HEADER_LENGTH - Integer.BYTES
                || expectedLength != encoded.length) {
            throw invalid("PES1 payload length mismatch");
        }
        int bodyLength = Math.toIntExact(payloadLength);
        int storedPayloadCrc =
                ByteBuffer.wrap(encoded, HEADER_LENGTH + bodyLength, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .getInt();
        if (storedPayloadCrc != Crc32c.compute(encoded, HEADER_LENGTH, bodyLength)) {
            throw invalid("PES1 payload CRC32C mismatch");
        }
        try {
            DataInputStream in =
                    new DataInputStream(
                            new ByteArrayInputStream(encoded, HEADER_LENGTH, bodyLength));
            String indexType = readString(in, MAX_NAME_BYTES);
            String moduleVersion = readString(in, MAX_NAME_BYTES);
            byte[] tableIdentity = readFixed(in, 32);
            String branch = readString(in, MAX_NAME_BYTES);
            String indexName = readString(in, MAX_NAME_BYTES);
            long snapshotId = in.readLong();
            long schemaId = in.readLong();
            byte[] partition = readFixed(in, 32);
            int bucket = in.readInt();
            long rowStart = in.readLong();
            long rowEnd = in.readLong();
            long rowCount = in.readLong();
            long docCount = in.readLong();
            int fieldCount = checkedCount(in.readInt(), MAX_FIELDS, "field count");
            List<FieldMetadata> fields = new ArrayList<>(fieldCount);
            for (int i = 0; i < fieldCount; i++) {
                int fieldId = in.readInt();
                String logical = readString(in, MAX_NAME_BYTES);
                String type = readString(in, MAX_NAME_BYTES);
                int nullable = in.readUnsignedByte();
                if (nullable > 1) {
                    throw invalid("Invalid PES1 boolean");
                }
                fields.add(
                        new FieldMetadata(
                                fieldId,
                                logical,
                                type,
                                nullable == 1,
                                readString(in, MAX_NAME_BYTES),
                                readString(in, MAX_NAME_BYTES),
                                readBytes(in, MAX_MAPPING_BYTES)));
            }
            byte[] mapping = readBytes(in, MAX_MAPPING_BYTES);
            byte[] mappingSha = readFixed(in, 32);
            String commit = readString(in, MAX_NAME_BYTES);
            int abiMajor = in.readInt();
            int abiMinor = in.readInt();
            String esVersion = readString(in, MAX_NAME_BYTES);
            String luceneVersion = readString(in, MAX_NAME_BYTES);
            String buildId = readString(in, MAX_NAME_BYTES);
            byte[] soSha = readFixed(in, 32);
            String target = readString(in, MAX_NAME_BYTES);
            String glibc = readString(in, MAX_NAME_BYTES);
            byte[] semantic = readFixed(in, 32);
            long archiveLength = in.readLong();
            byte[] archiveSha = readFixed(in, 32);
            long uncompressed = in.readLong();
            int fileCount = checkedCount(in.readInt(), MAX_FILES, "file count");
            List<FileMetadata> files = new ArrayList<>(fileCount);
            String previous = null;
            for (int i = 0; i < fileCount; i++) {
                String name = readString(in, MAX_NAME_BYTES);
                if (previous != null && compareUtf8(previous, name) >= 0) {
                    throw invalid("PES1 file names are not strictly UTF-8 sorted");
                }
                previous = name;
                files.add(new FileMetadata(name, in.readLong(), in.readLong(), readFixed(in, 32)));
            }
            if (in.available() != 0) {
                throw invalid("Unexpected trailing PES1 payload bytes");
            }
            return new PaimonElasticsearchFileMeta(
                    indexType,
                    moduleVersion,
                    tableIdentity,
                    branch,
                    indexName,
                    snapshotId,
                    schemaId,
                    partition,
                    bucket,
                    rowStart,
                    rowEnd,
                    rowCount,
                    docCount,
                    fields,
                    mapping,
                    mappingSha,
                    commit,
                    abiMajor,
                    abiMinor,
                    esVersion,
                    luceneVersion,
                    buildId,
                    soSha,
                    target,
                    glibc,
                    semantic,
                    archiveLength,
                    archiveSha,
                    uncompressed,
                    files);
        } catch (EOFException e) {
            throw invalid("Truncated PES1 payload");
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse PES1", e);
        }
    }

    private void validate() {
        if (!"paimon-elasticsearch".equals(indexType)) {
            throw invalid("Unexpected PES1 index type");
        }
        requireNonEmpty(moduleVersion, "module version");
        requireNonEmpty(branch, "branch");
        requireNonEmpty(indexName, "index name");
        requireNonEmpty(elasticppCommit, "elasticpp commit");
        requireNonEmpty(elasticsearchVersion, "Elasticsearch version");
        requireNonEmpty(luceneVersion, "Lucene version");
        requireNonEmpty(nativeBuildId, "native build id");
        requireNonEmpty(targetTriple, "target triple");
        requireNonEmpty(minimumGlibc, "minimum glibc");
        if (snapshotId < 0 || schemaId < 0 || bucket != -1) {
            throw invalid("Invalid PES1 snapshot, schema, or bucket identity");
        }
        if (abiMajor <= 0 || abiMinor < 0) {
            throw invalid("Invalid PES1 native ABI version");
        }
        if (rowStart < 0 || rowCount < 0 || docCount != rowCount) {
            throw invalid("Invalid PES1 row/doc counts");
        }
        long expectedEnd =
                rowCount == 0 ? checkedAdd(rowStart, -1L) : checkedAdd(rowStart, rowCount - 1L);
        if (rowEnd != expectedEnd) {
            throw invalid("PES1 row range mismatch");
        }
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
            throw invalid("Invalid PES1 field count");
        }
        Set<Integer> fieldIds = new HashSet<>();
        Set<String> logicalNames = new HashSet<>();
        Set<String> physicalNames = new HashSet<>();
        for (FieldMetadata field : fields) {
            if (field.fieldId < 0 || !fieldIds.add(field.fieldId)) {
                throw invalid("Duplicate or invalid PES1 field id");
            }
            requireNonEmpty(field.logicalName, "logical field name");
            requireNonEmpty(field.paimonType, "Paimon field type");
            if (field.paimonType.endsWith(" NOT NULL")) {
                throw invalid("Paimon field type must have nullable root");
            }
            requireNonEmpty(field.physicalName, "physical field name");
            requireNonEmpty(field.physicalType, "physical field type");
            if (!logicalNames.add(field.logicalName) || !physicalNames.add(field.physicalName)) {
                throw invalid("Duplicate PES1 field name");
            }
            if (field.canonicalConfig.length > MAX_MAPPING_BYTES) {
                throw invalid("PES1 field config exceeds limit");
            }
        }
        if (canonicalMapping.length > MAX_MAPPING_BYTES
                || !Arrays.equals(
                        mappingSha256,
                        digest(canonicalMapping))) {
            throw invalid("PES1 mapping digest mismatch");
        }
        if (archiveLength < 0 || archiveLength > MAX_ARCHIVE_BYTES || uncompressedBytes < 0) {
            throw invalid("Invalid PES1 archive size");
        }
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw invalid("Invalid PES1 archive file count");
        }
        long previousEnd = Integer.BYTES;
        long totalFileBytes = 0L;
        String previousName = null;
        for (FileMetadata file : files) {
            validateFileName(file.name);
            if (previousName != null && compareUtf8(previousName, file.name) >= 0) {
                throw invalid("PES1 file names must be unique and UTF-8 sorted");
            }
            int nameBytes = encodeUtf8(file.name).length;
            long expectedDataOffset =
                    checkedAdd(
                            checkedAdd(checkedAdd(previousEnd, Integer.BYTES), nameBytes),
                            Long.BYTES);
            if (file.length < 0 || file.archiveDataOffset != expectedDataOffset) {
                throw invalid("Invalid PES1 file offset");
            }
            previousEnd = checkedAdd(file.archiveDataOffset, file.length);
            totalFileBytes = checkedAdd(totalFileBytes, file.length);
            if (previousEnd > archiveLength) {
                throw invalid("PES1 file exceeds archive length");
            }
            previousName = file.name;
        }
        if (previousEnd != archiveLength || totalFileBytes != uncompressedBytes) {
            throw invalid("PES1 archive length or uncompressed size does not match file layout");
        }
    }

    static void validateFileName(String name) {
        if (name == null
                || name.isEmpty()
                || encodeUtf8(name).length > MAX_NAME_BYTES
                || name.indexOf('\0') >= 0
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.equals(".")
                || name.equals("..")) {
            throw invalid("Unsafe archive file name");
        }
    }

    static int compareUtf8(String left, String right) {
        byte[] leftBytes = encodeUtf8(left);
        byte[] rightBytes = encodeUtf8(right);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int i = 0; i < length; i++) {
            int comparison =
                    Integer.compare(
                            Byte.toUnsignedInt(leftBytes[i]), Byte.toUnsignedInt(rightBytes[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static void requireNonEmpty(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw invalid("PES1 " + name + " is empty");
        }
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw invalid("PES1 arithmetic overflow");
        }
    }

    private static int checkedCount(int value, int max, String name) {
        if (value < 0 || value > max) {
            throw invalid("Invalid PES1 " + name);
        }
        return value;
    }

    private static byte[] requireDigest(byte[] value, String name) {
        if (value == null || value.length != 32) {
            throw invalid(name + " must contain 32 bytes");
        }
        return value.clone();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = encodeUtf8(value);
        if (bytes.length > MAX_NAME_BYTES || value.indexOf('\0') >= 0) {
            throw invalid("Invalid PES1 string");
        }
        writeBytes(out, bytes);
    }

    static byte[] encodeUtf8(String value) {
        if (value == null) {
            throw invalid("PES1 string is null");
        }
        try {
            ByteBuffer encoded =
                    StandardCharsets.UTF_8
                            .newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw invalid("Invalid PES1 UTF-8 string");
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static String readString(DataInputStream in, int maximum) throws IOException {
        byte[] bytes = readBytes(in, maximum);
        try {
            String value =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (value.indexOf('\0') >= 0
                    || !Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
                throw invalid("Invalid PES1 UTF-8 string");
            }
            return value;
        } catch (CharacterCodingException e) {
            throw invalid("Invalid PES1 UTF-8 string");
        }
    }

    private static byte[] readBytes(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maximum || length > in.available()) {
            throw invalid("Invalid PES1 byte length");
        }
        return readFixed(in, length);
    }

    private static byte[] readFixed(DataInputStream in, int length) throws IOException {
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

}
