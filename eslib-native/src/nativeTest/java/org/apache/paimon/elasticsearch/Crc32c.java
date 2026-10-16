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

/** Java 8 compatible CRC32C implementation. */
final class Crc32c {

    private static final int[] TABLE = table();

    static int compute(byte[] value, int offset, int length) {
        int crc = 0xffffffff;
        for (int i = offset; i < offset + length; i++) {
            crc = TABLE[(crc ^ value[i]) & 0xff] ^ (crc >>> 8);
        }
        return ~crc;
    }

    private static int[] table() {
        int[] values = new int[256];
        for (int i = 0; i < values.length; i++) {
            int current = i;
            for (int bit = 0; bit < 8; bit++) {
                current = (current >>> 1) ^ ((current & 1) == 0 ? 0 : 0x82f63b78);
            }
            values[i] = current;
        }
        return values;
    }

    private Crc32c() {}
}
