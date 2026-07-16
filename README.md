# ES-Paimon-Lib

ES-Paimon-Lib is a standalone Lucene-based indexing library used by the optional
[Apache Paimon](https://paimon.apache.org/) ESLib global-index integration. It supports vector,
full-text, and scalar indexes without depending on Elasticsearch server classes.

This public repository contains the source corresponding to the externally published Maven
artifacts. It is maintained independently of the Apache Software Foundation and is not an ASF
release repository.

## Modules

| Module | Purpose |
|---|---|
| `eslib-core` | Public APIs, index builders/searchers, archive IO, HNSW, DiskBBQ, full-text, and scalar indexes |
| `eslib-simdvec` | Standalone fallback implementation for Elasticsearch SIMD-vector APIs used by DiskBBQ |

The former `eslib-api` module has been merged into `eslib-core`.

## Build

The `lucene` Gradle property selects the Lucene source set and Java target:

```shell
# Release line used by Apache Paimon: Lucene 9.12, JDK 11+
./gradlew clean test -Plucene=9

# Lucene 10.4 compatibility line: JDK 21+
./gradlew clean test -Plucene=10
```

To write Maven artifacts to a local directory, publish each Lucene line separately. Their artifact
IDs include the Lucene major version, so both lines can coexist in the same Maven repository:

```shell
./gradlew publish -Plucene=9 -PreleaseRepositoryDir=/absolute/output/path
JAVA_HOME=/path/to/jdk-21 ./gradlew publish -Plucene=10 -PreleaseRepositoryDir=/absolute/output/path
```

## Maven artifacts

Each released version is built from its matching source tag (for example, `eslib-1.0.7`). Binary
artifacts and checksums are stored in the public
[es-paimon-lib-releases](https://github.com/CrownChu/es-paimon-lib-releases) repository.

| Profile | Core artifact | SIMD artifact | Runtime |
|---|---|---|---|
| Lucene 9 | `eslib-core-lucene9` | `eslib-simdvec-lucene9` | JDK 11+, Lucene 9.12.0 |
| Lucene 10 | `eslib-core-lucene10` | `eslib-simdvec-lucene10` | JDK 21+, Lucene 10.4.0 |

```xml
<repositories>
    <repository>
        <id>eslib-github</id>
        <url>https://raw.githubusercontent.com/CrownChu/es-paimon-lib-releases/eslib-1.0.7/repository</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.github.paimon.eslib</groupId>
        <artifactId>eslib-core-lucene9</artifactId>
        <version>1.0.7</version>
    </dependency>
</dependencies>
```

`eslib-core-lucene9` transitively depends on `eslib-simdvec-lucene9`. For a JDK 21 / Lucene 10
runtime, use `eslib-core-lucene10`; it transitively selects `eslib-simdvec-lucene10` and Lucene
10.4.0. Do not mix artifacts from the two Lucene lines in one runtime.

## Licensing

Elasticsearch-derived portions are redistributed under the Elastic License 2.0. Files derived
from Apache Lucene remain under the Apache License 2.0. See [LICENSE.txt](LICENSE.txt),
[NOTICE.txt](NOTICE.txt), and the complete texts in [licenses](licenses/).

Every published JAR includes the same license and notice files under `META-INF/`.
