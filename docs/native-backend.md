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
date, text, float HNSW, INT8 HNSW and DiskBBQ fields to the native writer/query ABI.
Vector dimensions are limited to 1–1024. INT8 HNSW retains its quantized graph and
raw FLOAT32 vectors. DiskBBQ uses the ES940 v2 IVF format with one-bit documents,
four-bit queries and the default clustering parameters; its native build must
enable `--define=native_disk_bbq=true`. Each algorithm keeps its own mapping and
archive identity. Null rows and holes occupy dense document slots;
`finishDocument` allows bounded native batches for rows supplied in order.

IK smart and max-word mappings use elasticpp's existing IK implementation.
Before starting the JVM, set `ES_PATH_CONF` to a configuration directory with
`analysis-ik/main.dic`, `analysis-ik/quantifier.dic` and
`analysis-ik/stopword.dic`. Keep these files immutable for the process lifetime.
Their SHA-256 digests become part of the archive's field-configuration identity;
the reader must use identical dictionary contents, even on another machine.
Changing dictionaries requires a fresh process and rebuilding the affected
index. Production dictionaries remain external; the small integration-test
dictionary is used only by the test task.

The searcher owns its `ArchiveDataProvider`. It reads the archive manifest and
copies sealed files through 256 KiB range reads into a private local directory,
validating each file's length and SHA-256. C++ opens the resulting index and
executes queries. This first adapter requires local disk space for the entire
index. Direct native OSS range reads are a later integration step.

Writer and reader must use the same native commit and shared-object SHA-256.
The native archive includes `.eslib-native-v1.json`, which the archive packer
must retain together with all sealed index files. Existing Java archives lack
that descriptor and are not accepted by this candidate.

Logical names such as `title.keyword` are supported as independently supplied
columns. The native writer maps dotted logical names to deterministic physical
names and translates queries through the same mapping. It does not synthesize
the complementary value: callers such as Paimon must supply both columns.

### Importing an original Paimon sealed archive

`NativeESIndexSearcher.loadSealedArchive(provider, offsets, physicalFields, metadata)`
accepts caller-decoded PES1 file offsets and a `NativeArchiveMetadata` descriptor.
The caller must first validate the original PES1 envelope and archive identity;
the descriptor contains its original table identity, snapshot, schema, absolute
row span, partition/column digests, canonical mapping, native commit/SO digest
and sealed file table. This reader checks file ranges, lengths, hashes and the
C++ sealed identity marker. It computes the directory commitment using the
original C++ file-table algorithm; it does not rewrite PES1 into ESLib metadata.

Pass the original physical names, such as `f0` and `f1.keyword`, as field configs
in this mode. Supported scalar, text and vector physical settings must match;
parent multi-fields and write-only numeric coercion do not change an existing
field's read semantics. Other unqualified mapping differences are rejected.

This API is for indexes written by Paimon's ordered dense-row writer. V1 queries
return shard-relative Lucene document positions. KNN `_id` prefilters address
the original absolute U64 IDs, so the adapter adds the preserved row start using
integer arithmetic. It does not subtract that offset from V1 results. Arbitrary
unordered or sparse document-to-row mappings are outside this contract.

The original Paimon writer must use the same native bundle as this reader.
This entry point imports sealed files; it does not implement the original V2
query runtime, PEQ1/PQR1 envelopes, Spark/Ray orchestration or its distribution
classifier. The reverse interoperability test probes ESLib's native files through
Paimon's original V1 ABI; it does not claim a PES1/V2 reader can directly consume
an ESLib envelope.

The following limits remain explicit:

- Proxima's `VectorAlgorithm.NATIVE` and custom vector algorithm parameters
  are rejected. The new backend's float HNSW uses the existing
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
  --dependency /absolute/artifacts/libgomp.so.1 \
  --output /absolute/new-native-bundle

JAVA_HOME=/path/to/jdk-21 ./gradlew \
  :eslib-native:test :eslib-native:nativeIntegrationTest \
  -Plucene=10 -PnativeBundleDir=/absolute/new-native-bundle
```

The integration tests call the real C++ writer and reader. They independently
run Java Lucene `CheckIndex`, check every native row document value, exercise
null holes and long values above 2^53, and compare vector results and scores with
squared-L2 arithmetic. Quantized-vector cases check the physical INT8 HNSW and
DiskBBQ codec identities, sparse rows, archive reopening and prefilters. The IK
test checks smart/max-word token sets
with an independent Java Lucene reader and executes native queries after archive
reopening. Missing native assets fail the tests rather than skip them.

Additional real-native tests call pinned original Paimon V1 declarations and
round-trip its PES1 codec, read that archive through ESLib, reject a foreign
snapshot, and check the reverse native-directory probe. Independent Java Lucene
doc values verify IDs above 2^53 without rounding. The reference commit, source
hashes and test-only adaptations are recorded alongside those reference sources
in `eslib-native/src/nativeTest/java/org/apache/paimon/elasticsearch/reference-provenance.json`.

Independent C++ tests also export real INT8/DiskBBQ/IK index files for Java
Lucene 10.4 checks. These validate the codec and analyzer implementations, while
the integration task above qualifies the Java/JNA/runtime/archive boundary.

The existing standalone Java SOAR compatibility helper is corrected to use the
loss documented by `ClusteringFloatVectorValues`: candidate squared distance
plus the nonnegative orthogonality penalty. Its bulk helper receives four
candidate centroids and one shared primary residual. The previous subtraction
and inconsistent bulk arguments fail the added hand-calculated regression
cases. Rebuilding DiskBBQ indexes may therefore change their secondary cluster
assignments; the on-disk format and existing-index read contract remain the same.

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
  -Plucene=10 -PreleaseVersion=1.1.0-native.2 \
  -PnativeBundleDir=/absolute/new-native-bundle \
  -PreleaseRepositoryDir=/absolute/staging/repository
```

The native publication tasks depend on the real native integration task. An
empty platform JAR cannot be built or published. Native dependency license and
notice coverage must be resolved for the actual bundle before public release.
