/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Vector index metadata for serialization into Paimon ResultEntry.meta.
 *
 * <p>Format versions:
 * <ul>
 *   <li>v1: dimension, metricType, vectorsPerCluster, centroidsPerParentCluster, vectorCount</li>
 *   <li>v2: + fieldName (the vector field name the index was built for)</li>
 * </ul>
 * Older v1 archives deserialize with {@code fieldName="vector"} (the historical default).
 */
public class VectorIndexMeta {

    private static final int CURRENT_VERSION = 2;
    private static final String DEFAULT_FIELD_NAME = "vector";

    public int dimension;
    public String metricType;
    public int vectorsPerCluster;
    public int centroidsPerParentCluster;
    public long vectorCount;
    public String fieldName;

    public VectorIndexMeta() {
        this.fieldName = DEFAULT_FIELD_NAME;
    }

    public VectorIndexMeta(VectorIndexConfig config, long vectorCount) {
        this.dimension = config.dimension();
        this.metricType = config.similarity().name();
        this.vectorsPerCluster = config.vectorsPerCluster();
        this.centroidsPerParentCluster = config.centroidsPerParentCluster();
        this.vectorCount = vectorCount;
        this.fieldName = config.fieldName();
    }

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(CURRENT_VERSION);
        dos.writeInt(dimension);
        dos.writeUTF(metricType);
        dos.writeInt(vectorsPerCluster);
        dos.writeInt(centroidsPerParentCluster);
        dos.writeLong(vectorCount);
        dos.writeUTF(fieldName != null ? fieldName : DEFAULT_FIELD_NAME);
        dos.flush();
        return baos.toByteArray();
    }

    public static VectorIndexMeta deserialize(byte[] data) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int version = dis.readInt();
        if (version != 1 && version != CURRENT_VERSION) {
            throw new IOException("Unsupported VectorIndexMeta version: " + version);
        }
        VectorIndexMeta meta = new VectorIndexMeta();
        meta.dimension = dis.readInt();
        meta.metricType = dis.readUTF();
        meta.vectorsPerCluster = dis.readInt();
        meta.centroidsPerParentCluster = dis.readInt();
        meta.vectorCount = dis.readLong();
        if (version >= 2) {
            meta.fieldName = dis.readUTF();
        } else {
            meta.fieldName = DEFAULT_FIELD_NAME;
        }
        return meta;
    }
}
