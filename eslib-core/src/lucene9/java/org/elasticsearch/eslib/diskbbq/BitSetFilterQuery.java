/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.eslib.diskbbq;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;

public class BitSetFilterQuery extends Query {

    private final FixedBitSet bitSet;

    public BitSetFilterQuery(FixedBitSet bitSet) {
        this.bitSet = bitSet;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new ConstantScoreWeight(this, boost) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                int docBase = context.docBase;
                int maxDoc = context.reader().maxDoc();
                FixedBitSet leafBits = new FixedBitSet(maxDoc);
                for (int doc = 0; doc < maxDoc; doc++) {
                    int globalDoc = doc + docBase;
                    if (globalDoc < bitSet.length() && bitSet.get(globalDoc)) {
                        leafBits.set(doc);
                    }
                }
                DocIdSetIterator iterator = new BitSetIterator(leafBits);
                return new ConstantScoreScorer(this, score(), scoreMode, iterator);
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false;
            }
        };
    }

    @Override
    public String toString(String field) {
        return "BitSetFilterQuery(cardinality=" + bitSet.cardinality() + ")";
    }

    @Override
    public void visit(QueryVisitor visitor) {
        visitor.visitLeaf(this);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BitSetFilterQuery)) return false;
        BitSetFilterQuery that = (BitSetFilterQuery) other;
        return bitSet.equals(that.bitSet);
    }

    @Override
    public int hashCode() {
        return 31 * classHash() + bitSet.hashCode();
    }

    private static class BitSetIterator extends DocIdSetIterator {
        private final FixedBitSet bits;
        private int doc = -1;

        BitSetIterator(FixedBitSet bits) {
            this.bits = bits;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            doc = bits.nextSetBit(doc + 1);
            if (doc == DocIdSetIterator.NO_MORE_DOCS || doc == -1) {
                doc = DocIdSetIterator.NO_MORE_DOCS;
            }
            return doc;
        }

        @Override
        public int advance(int target) {
            doc = bits.nextSetBit(target);
            if (doc == DocIdSetIterator.NO_MORE_DOCS || doc == -1) {
                doc = DocIdSetIterator.NO_MORE_DOCS;
            }
            return doc;
        }

        @Override
        public long cost() {
            return bits.cardinality();
        }
    }
}
