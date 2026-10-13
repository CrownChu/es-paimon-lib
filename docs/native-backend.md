# Native ESLib candidate

This branch introduces an explicit C++ backend. It is a development candidate,
not a published native release. The Java backend continues to use the existing
`DefaultESIndexBuilder` and `DefaultESIndexSearcher` classes.

## Dependency boundary

| Artifact | Java target | Role |
|---|---|---|
| `eslib-api` | 11 | Original public interfaces and model classes, with unchanged packages |
| `eslib-native` | 11 | JNA ownership, mapping and query translation, builder and searcher |
| `eslib-native-runtime-linux-x86_64` | 11 | Verified C++ shared libraries and native manifest |
| `eslib-core-lucene9` / `eslib-core-lucene10` | 11 / 21 | Existing Java implementations and compatibility test oracle |

The native binding depends on the API, JNA, Jackson and the platform runtime.
It does not depend on Java Lucene. Java Lucene 10.4 is used only by the separate
native integration test source set.

The first runtime follows the native clang build's x86-64-v3 CPU contract and
its reported glibc baseline (currently 2.32). The loader checks CPU features and
glibc before opening elasticpp. Bundle verification also checks ELF glibc symbol
requirements and rejects runtime paths outside `$ORIGIN`.

## Current contract

Use `org.elasticsearch.eslib.nativeindex.NativeESIndexBuilder` and
`NativeESIndexSearcher` explicitly. They implement the original public APIs.

The candidate translates scalar integer/long/float/double, keyword, epoch-millis
date, text and float HNSW fields to the existing native writer/query ABI. HNSW
dimensions are limited to 1–1024. Null rows and holes occupy dense document slots;
`finishDocument` allows bounded native batches for rows supplied in order.

The searcher owns its `ArchiveDataProvider`. It reads the archive manifest and
copies sealed files through 256 KiB range reads into a private local directory,
validating each file's length and SHA-256. C++ opens the resulting index and
executes queries. This first adapter requires local disk space for the entire
index. Direct native OSS range reads are a later integration step.

Writer and reader must use the same native commit and shared-object SHA-256.
The native archive includes `.eslib-native-v1.json`, which the archive packer
must retain together with all sealed index files. Existing Java archives lack
that descriptor and are not accepted by this candidate.

The following limits remain explicit:

- INT8_HNSW, DiskBBQ, Proxima's `VectorAlgorithm.NATIVE`, custom HNSW parameters
  and IK analyzers are rejected. The new backend's float HNSW uses the existing
  `VectorAlgorithm.HNSW` enum value.
- GEO filters, fuzzy/regexp/wildcard filters and nonzero match fuzziness are rejected.
- Repeated values for one field in a row are rejected pending multi-value compatibility.
- Filter and row enumeration require at most 1,000,000 rows per archive.
- KNN TopK and the native candidate count are limited to 10,000. Duplicate or
  out-of-range prefilter rows are treated like the original Java bit-set filter.
- Queries have bounded result windows and bytes. Native errors or incomplete
  results raise exceptions; they do not trigger Java query retries.
- The existing API describes a single merged segment. The current native seal
  path may retain multiple segments; positional row mapping must be independently
  verified before any default backend switch.

Only real native integration evidence can qualify these translated operations.
Protocol unit tests and compilation alone do not establish native correctness.

## Verification

Protocol and ownership tests do not need a native binary:

```sh
./gradlew :eslib-native:test -Plucene=9
```

Prepare a platform bundle from real, approved, packaged native artifacts. Supply
every non-system ELF dependency. The tool probes the loaded C++ library for its
commit, runtime contract and own digest; those values are not handwritten.

```sh
python3 tools/prepare_native_bundle.py \
  --cpu-isa x86-64-v3 \
  --library /absolute/artifacts/libelasticpp.so \
  --dependency /absolute/artifacts/libdadi.so \
  --output /absolute/new-native-bundle

JAVA_HOME=/path/to/jdk-21 ./gradlew \
  :eslib-native:test :eslib-native:nativeIntegrationTest \
  -Plucene=10 -PnativeBundleDir=/absolute/new-native-bundle
```

The integration tests call the real C++ writer and reader. They independently
run Java Lucene `CheckIndex`, check every native row document value, exercise
null holes and long values above 2^53, and compare vector results and scores with
squared-L2 arithmetic. Missing native assets fail the tests rather than skip them.

## Publication order

1. Pass the real native integration tests with a frozen bundle.
2. Generate and verify Maven POM and Gradle module coordinates, including the
   native platform dependency and absence of Java Lucene from native metadata.
3. Stage API, native runtime and binding artifacts at one new immutable version.
4. Publish the matching source and release repository tags.
5. Download dependencies in a fresh process from that public tag and run actual
   native write/read checks before changing the Paimon GitHub dependency.

For example, staging the candidate requires a real native bundle:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew \
  verifyPublicationCoordinates \
  :eslib-api:publish :eslib-native-runtime:publish :eslib-native:publish \
  -Plucene=10 -PreleaseVersion=1.1.0-native.1 \
  -PnativeBundleDir=/absolute/new-native-bundle \
  -PreleaseRepositoryDir=/absolute/staging/repository
```

The native publication tasks depend on the real native integration task. An
empty platform JAR cannot be built or published. Native dependency license and
notice coverage must be resolved for the actual bundle before public release.
