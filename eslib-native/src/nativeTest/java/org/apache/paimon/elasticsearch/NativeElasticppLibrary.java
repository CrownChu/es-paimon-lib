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

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/** Test-only unchanged V1 declarations extracted from the pinned Paimon baseline. */
interface NativeElasticppLibrary extends Library {
    int ABI_VERSION = 1;

    abstract class AbiStructure extends Structure {
        AbiStructure() {}

        AbiStructure(Pointer pointer) {
            super(pointer);
        }

        final void initialize() {
            initialize(ABI_VERSION);
        }

        final void initialize(int version) {
            writeField("abiVersion", version);
            writeField("structSize", size());
        }

        final int offset(String field) {
            return fieldOffset(field);
        }
    }

    @Structure.FieldOrder({"index", "shardId"})
    class ShardIdentifier extends Structure {
        ShardIdentifier() {}
        ShardIdentifier(Pointer original) { super(original); }

        public Pointer index;
        public long shardId;
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "openMode",
        "closeMode",
        "ramBufferBytes",
        "maxDocumentBytes",
        "maxBufferedDocs",
        "maxThreadStates",
        "maxPendingOperations",
        "reserved0",
        "maxPendingBytes",
        "documentIdMode",
        "indexSortById",
        "optionFlags",
        "reserved1",
        "reserved"
    })
    class ShardCreateOptionsV2 extends AbiStructure {
        ShardCreateOptionsV2() {}
        ShardCreateOptionsV2(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public int openMode;
        public int closeMode;
        public long ramBufferBytes;
        public long maxDocumentBytes;
        public int maxBufferedDocs;
        public int maxThreadStates;
        public int maxPendingOperations;
        public int reserved0;
        public long maxPendingBytes;
        public int documentIdMode;
        public int indexSortById;
        public int optionFlags;
        public int reserved1;
        public long[] reserved = new long[2];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "success",
        "errorCode",
        "state",
        "visibility",
        "flags",
        "reserved0",
        "operationToken",
        "sequenceNumber",
        "executeTimeUs",
        "exceptionType",
        "exceptionTypeLength",
        "reserved1",
        "exceptionMessage",
        "exceptionMessageLength",
        "reserved2",
        "reserved"
    })
    class ShardWriteResultV1 extends AbiStructure {
        public int abiVersion;
        public int structSize;
        public int success;
        public int errorCode;
        public int state;
        public int visibility;
        public int flags;
        public int reserved0;
        public long operationToken;
        public long sequenceNumber;
        public long executeTimeUs;
        public Pointer exceptionType;
        public int exceptionTypeLength;
        public int reserved1;
        public Pointer exceptionMessage;
        public int exceptionMessageLength;
        public int reserved2;
        public long[] reserved = new long[4];

        public ShardWriteResultV1() {}

        public ShardWriteResultV1(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "generation",
        "expectedCurrentGeneration",
        "mappingSource",
        "mappingSourceLength",
        "reserved"
    })
    class ShardWriteMappingRequestV1 extends AbiStructure {
        ShardWriteMappingRequestV1() {}
        ShardWriteMappingRequestV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public long generation;
        public long expectedCurrentGeneration;
        public Pointer mappingSource;
        public long mappingSourceLength;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "operationType",
        "reserved0",
        "documentId",
        "source",
        "sourceLength",
        "mappingGeneration",
        "ifSequenceNumber",
        "ifPrimaryTerm",
        "reserved"
    })
    class ShardU64BulkWriteItemV1 extends AbiStructure {
        ShardU64BulkWriteItemV1() {}
        ShardU64BulkWriteItemV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public int operationType;
        public int reserved0;
        public long documentId;
        public Pointer source;
        public long sourceLength;
        public long mappingGeneration;
        public long ifSequenceNumber;
        public long ifPrimaryTerm;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion", "structSize", "operationId", "operationIdLength", "itemCount",
        "items", "itemStructSize", "durability", "refreshPolicy", "reserved0",
        "timeoutMillis", "reserved"
    })
    class ShardU64BulkWriteRequestV1 extends AbiStructure {
        ShardU64BulkWriteRequestV1() {}
        ShardU64BulkWriteRequestV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public Pointer operationId;
        public int operationIdLength;
        public int itemCount;
        public Pointer items;
        public int itemStructSize;
        public int durability;
        public int refreshPolicy;
        public int reserved0;
        public long timeoutMillis;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "operationId",
        "operationIdLength",
        "reserved0",
        "timeoutMillis",
        "reserved"
    })
    class ShardWriteControlRequestV1 extends AbiStructure {
        ShardWriteControlRequestV1() {}
        ShardWriteControlRequestV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public Pointer operationId;
        public int operationIdLength;
        public int reserved0;
        public long timeoutMillis;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "summary",
        "itemCount",
        "itemResults",
        "reserved"
    })
    class ShardBulkWriteResultV1 extends AbiStructure {
        public int abiVersion;
        public int structSize;
        public ShardWriteResultV1 summary = new ShardWriteResultV1();
        public long itemCount;
        public Pointer itemResults;
        public long[] reserved = new long[4];

        public ShardBulkWriteResultV1() {}

        public ShardBulkWriteResultV1(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "success",
        "errorCode",
        "retryable",
        "mutationStarted",
        "rebuildRequired",
        "reserved0",
        "diagnostic",
        "diagnosticLength",
        "reserved1",
        "reserved"
    })
    class PaimonEsStatusV1 extends AbiStructure {
        public int abiVersion;
        public int structSize;
        public int success;
        public int errorCode;
        public int retryable;
        public int mutationStarted;
        public int rebuildRequired;
        public int reserved0;
        public Pointer diagnostic;
        public int diagnosticLength;
        public int reserved1;
        public long[] reserved = new long[4];

        public PaimonEsStatusV1() {}

        public PaimonEsStatusV1(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "tableUuid",
        "tableUuidLength",
        "reserved0",
        "snapshotId",
        "schemaId",
        "rowStart",
        "rowCount",
        "partitionSha256",
        "indexColumnsSha256",
        "mappingSha256",
        "reserved"
    })
    class PaimonEsShardIdentityV1 extends AbiStructure {
        PaimonEsShardIdentityV1() {}
        PaimonEsShardIdentityV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public Pointer tableUuid;
        public int tableUuidLength;
        public int reserved0;
        public long snapshotId;
        public long schemaId;
        public long rowStart;
        public long rowCount;
        public byte[] partitionSha256 = new byte[32];
        public byte[] indexColumnsSha256 = new byte[32];
        public byte[] mappingSha256 = new byte[32];
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "shard",
        "localPath",
        "localPathLength",
        "reserved0",
        "identity",
        "timeoutMillis",
        "reserved"
    })
    class PaimonEsSealRequestV1 extends AbiStructure {
        PaimonEsSealRequestV1() {}
        PaimonEsSealRequestV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public Pointer shard;
        public Pointer localPath;
        public int localPathLength;
        public int reserved0;
        public Pointer identity;
        public long timeoutMillis;
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({"name", "nameLength", "reserved0", "length", "sha256", "reserved"})
    class PaimonEsFileMetadataV1 extends Structure {
        public Pointer name;
        public int nameLength;
        public int reserved0;
        public long length;
        public byte[] sha256 = new byte[32];
        public long[] reserved = new long[2];

        public PaimonEsFileMetadataV1() {}

        public PaimonEsFileMetadataV1(Pointer pointer) {
            super(pointer);
            read();
        }

        int offset(String field) {
            return fieldOffset(field);
        }
    }

    @Structure.FieldOrder({
        "status",
        "commitGeneration",
        "maxDoc",
        "numDocs",
        "numDeletedDocs",
        "directorySha256",
        "fileCount",
        "files",
        "reserved"
    })
    class PaimonEsSealResultV1 extends Structure {
        public PaimonEsStatusV1 status = new PaimonEsStatusV1();
        public long commitGeneration;
        public long maxDoc;
        public long numDocs;
        public long numDeletedDocs;
        public byte[] directorySha256 = new byte[32];
        public long fileCount;
        public Pointer files;
        public long[] reserved = new long[4];

        public PaimonEsSealResultV1() {}

        public PaimonEsSealResultV1(Pointer pointer) {
            super(pointer);
            read();
        }

        int offset(String field) {
            return fieldOffset(field);
        }
    }

    @Structure.FieldOrder({
        "abiVersion",
        "structSize",
        "localPath",
        "localPathLength",
        "reserved0",
        "identity",
        "expectedDirectorySha256",
        "reserved"
    })
    class PaimonEsProbeRequestV1 extends AbiStructure {
        PaimonEsProbeRequestV1() {}
        PaimonEsProbeRequestV1(Pointer original) { super(original); }

        public int abiVersion;
        public int structSize;
        public Pointer localPath;
        public int localPathLength;
        public int reserved0;
        public Pointer identity;
        public byte[] expectedDirectorySha256 = new byte[32];
        public long[] reserved = new long[4];
    }

    @Structure.FieldOrder({
        "status",
        "commitGeneration",
        "maxDoc",
        "numDocs",
        "numDeletedDocs",
        "directorySha256",
        "reserved"
    })
    class PaimonEsProbeResultV1 extends Structure {
        public PaimonEsStatusV1 status = new PaimonEsStatusV1();
        public long commitGeneration;
        public long maxDoc;
        public long numDocs;
        public long numDeletedDocs;
        public byte[] directorySha256 = new byte[32];
        public long[] reserved = new long[4];

        public PaimonEsProbeResultV1() {}

        public PaimonEsProbeResultV1(Pointer pointer) {
            super(pointer);
            read();
        }

        int offset(String field) {
            return fieldOffset(field);
        }
    }

    String havenask_version();

    long getShardWriteCapabilitiesV1();


    Pointer createShardV3(ShardIdentifier shard, Pointer indexPath, ShardCreateOptionsV2 options);

    Pointer removeShardV2(ShardIdentifier shard);

    Pointer updateShardWriteMappingV1(ShardIdentifier shard, ShardWriteMappingRequestV1 request);

    Pointer bulkShardDocumentsU64V1(ShardIdentifier shard, ShardU64BulkWriteRequestV1 request);

    Pointer commitShardV1(ShardIdentifier shard, ShardWriteControlRequestV1 request);




    Pointer getNativeEsCapabilitiesV1();

    Pointer sealShardV1(PaimonEsSealRequestV1 request);

    Pointer probeArchivedShardV1(PaimonEsProbeRequestV1 request);

    void freeShardWriteResultV1(Pointer result);

    void freeShardBulkWriteResultV1(Pointer result);

    void freeNativeEsCapabilitiesV1(Pointer result);

    void freePaimonEsSealResultV1(Pointer result);

    void freePaimonEsProbeResultV1(Pointer result);
}
