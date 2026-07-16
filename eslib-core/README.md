# eslib-core

`eslib-core` provides the public indexing APIs and the Lucene-backed implementations used by
ES-Paimon-Lib. It can build and search HNSW, DiskBBQ, full-text, and scalar indexes and can read
archive files through a caller-provided data source or Aliyun OSS range reads.

## Source layout

The common implementation is combined with exactly one Lucene-specific source set:

```text
src/main/java       common APIs and implementations
src/lucene9/java    Lucene 9.12 adapters and codecs
src/lucene10/java   Lucene 10.4 adapters and codecs
```

Build the Paimon/JDK 11 line with:

```shell
./gradlew :eslib-core:test -Plucene=9
```

Build the Lucene 10/JDK 21 compatibility line with:

```shell
./gradlew :eslib-core:test -Plucene=10
```

## DiskBBQ compatibility

The Lucene 9, Lucene 10, and Elasticsearch DiskBBQ implementations must remain byte-compatible.
Changes to file layouts, quantization, bit packing, scoring, or version constants must be applied
consistently and covered by cross-version compatibility tests.

## SIMD dependency

Standalone users load `eslib-simdvec`, which supplies the `org.elasticsearch.simdvec` fallback
classes used by DiskBBQ. An Elasticsearch plugin must instead use Elasticsearch's own SIMD and
Lucene classes and consume `eslib-core` without transitive dependencies to avoid duplicate classes:

```gradle
implementation('io.github.paimon.eslib:eslib-core-lucene9:1.0.7') {
    transitive = false
}
```

Apache Paimon and other standalone consumers should resolve both `eslib-core` and
`eslib-simdvec` normally.
