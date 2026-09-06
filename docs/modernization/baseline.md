# NIS modernization — Phase 0 baseline

## Scope

This document fixes the observable baseline of the NEM Infrastructure Server (NIS)
before Java, dependency, cache, or persistent-state work. Phase 0 only records the
current implementation, test state, deterministic comparison points, and the
procedure for collecting runtime measurements.

No production source, Java release, dependency, Maven plugin, cache
implementation, validation, observer, serialization, or database schema was
changed for this baseline.

Baseline date: 2026-09-06 (Asia/Tokyo)

## Repository and environment

| Item | Baseline |
| --- | --- |
| Original baseline branch | `dev` |
| Phase 0 artifact branch | `agent/nis-phase0-baseline` |
| Original baseline HEAD | `3620879f2bf8a81091e2d5a75a576b301d78d3fa` |
| Phase 0 artifact commit | `8cb326951595afa84913e5b68f18d2c0b87fae87` |
| Phase 0 artifact status | Committed; production source changes: none |
| Phase 0 closure / Phase 1 starting point | The HEAD of the separate `[nis] docs: close Phase 0 baseline metadata` commit |
| Version | `0.6.102` |
| Supported build JDK in repository | Java 11+; CI and the NIS Docker image explicitly use Java 11 |
| JDK used for this survey | OpenJDK 17.0.20, Ubuntu, `/usr/lib/jvm/java-17-openjdk-amd64` |
| Maven requirement | No Maven wrapper/version pin found; repository invokes `mvn` |
| Maven used for this survey | Apache Maven 3.8.7 |
| Host | Linux 5.15.167.4-microsoft-standard-WSL2-custom, amd64 |
| Network | Network-dependent peer tests are not reliable in this environment |

Java 11 is not installed in the survey environment. The compiler therefore
compiled with `javac --release 11` under JDK 17; a Java 11 run remains a
pre-Phase-1 condition.

The top-level Maven reactor contains:

| Module | Role | Main/test source inventory |
| --- | --- | --- |
| `core` | cryptography, models, serialization, math | 2361 unit-test cases reported |
| `deploy` | launcher and deployment support | 65 unit-test cases reported |
| `peer` | peer networking and synchronization | 306 unit-test cases reported |
| `nis` | NIS services, database mapping, chain execution, REST | 3486 unit-test cases reported |

`org.nem.deploy.CommonStarter` is the executable main class used by the README
and Docker image. `org.nem.nis.NisMain` is the Spring `@PostConstruct`
application component that initializes the database and starts block analysis.
Unit sources live under each module's `src/test/java`; integration sources live
under `src/it/java` and are added by `build-helper-maven-plugin` for Failsafe.
There are no deploy-module integration source files in this checkout.

Repository commands:

~~~bash
mvn package
mvn test
mvn failsafe:integration-test
~~~

The NIS CI test script (`nis/scripts/ci/test.sh`) runs `mvn test -B` followed by
`mvn failsafe:integration-test -B`. The Docker build uses
`mvn clean package -DskipTests=true`; its runtime defaults are `MEMORY_MS=6G`
and `MEMORY_MX=6G`. The package scripts also contain a `-Xms4G -Xmx6G`
launcher variant.

CI is present in both GitHub Actions and Jenkins configuration. The CodeQL
workflow runs on pushes to `dev`/`main`, pull requests to `dev`, and a weekly
schedule using Zulu Java 11 and CodeQL autobuild. Module Jenkinsfiles use the
shared `defaultCiPipeline`; the NIS pipeline packages and publishes the
Docker image `nemofficial/nis-client` using `nis/Dockerfile`. Dependabot and
its PR-combination workflow are also present. None of these CI definitions
were changed.

## Configuration and database

The common/default configuration is in
`nis/src/main/resources/config-default.properties`. Packaged configuration is
under `infra/package/nis`; the network is selected by `nem.network` and defaults
to `mainnet`. Mainnet and testnet peer seed lists are:

- `nis/src/main/resources/peers-config_mainnet.json`
- `nis/src/main/resources/peers-config_testnet.json`

The nemesis block fixtures are in `core/src/main/resources`, including the
mainnet/testnet binary and JSON resources. The NIS database is H2:

~~~text
org.h2.Driver
jdbc:h2:${nem.folder}/nis/data/nis5_${nem.network};DB_CLOSE_DELAY=-1
~~~

Flyway applies the H2 migrations in
`nis/src/main/resources/db/h2/V1.0.0__initial.sql` through `V1.0.7`. The schema
contains blocks, accounts, transfers, importance transfers, multisig data,
namespaces, namespace provisions, mosaic definitions/properties, mosaic supply
changes, and transferred mosaics. H2 is also used by the database unit tests
and in-memory test contexts.

## Current state and cache inventory

`DefaultNisCache` is the central mutable-state facade. Its six synchronized
components are copied and committed together:

| Component | Current contents and observed behavior |
| --- | --- |
| `DefaultAccountCache` / `SynchronizedAccountCache` | Address-to-`Account` data, including known public keys; `ImmutableObjectDeltaMap`, initial capacity 65536 |
| `DefaultAccountStateCache` / `SynchronizedAccountStateCache` | Mutable `AccountState`: balance/account info, weighted and vested balances, importance and historical importance, remote links/outlinks, multisig links, account mosaic-id set, creation height; `MutableObjectAwareDeltaMap`, initial capacity 2048 |
| `DefaultPoxFacade` / `SynchronizedPoxFacade` | Last grouped importance recalculation height, last POI vector size, and the `ImportanceCalculator` invocation |
| `DefaultHashCache` / `SynchronizedHashCache` | Transaction hash metadata and timestamp index; `ImmutableObjectDeltaMap` initial capacity 50000, retention configured to 36 hours by default (minimum 36 unless `-1`) |
| `DefaultNamespaceCache` / `SynchronizedNamespaceCache` | Root namespace histories and child namespace entries, including namespace-owned mosaic entries; mutable delta map initial capacity 100; historical depth is retained until pruning |
| `DefaultExpiredMosaicCache` / `SynchronizedExpiredMosaicCache` | Expiration-height groups containing mosaic id, copied owner balances, and expiration type; mutable delta map initial capacity 100 |

`DefaultMosaicIdCache` is a separate bidirectional map used for database
mosaic-id to model-mosaic-id translation. It retains historical database ids
for redefined mosaics and always seeds `nem:xem` with database id `0`.

`PushService` also has a request-level hash map for duplicate push/request
handling; it is separate from `DefaultHashCache` and must be measured
separately.

The important mutable population is therefore not only one account map:
`AccountState` owns nested weighted-balance histories, historical
importance/outlink structures, remote/multisig links, and mosaic-id sets.
POI calculation additionally creates account-index maps, vectors, and a sparse
outlink matrix for eligible harvesting accounts.

## Startup and replay behavior

The current startup path is:

1. Spring constructs the NIS components and calls `NisMain.init()`.
2. `NisMain` deserializes and hashes the network-specific nemesis block without
   initially updating the live cache.
3. If the H2 `blocks` table is empty, it inserts the nemesis block.
4. `NisMain` starts asynchronous analysis. With `nis.delayBlockLoading=false`,
   startup waits for the future; the default is `true`.
5. `NisMain.analyzeBlocks()` creates `ReadOnlyNisCache.copy()`, yielding
   copy-on-write/rebased maps rather than a full deep copy.
6. `BlockAnalyzer` seeds the nemesis account balance and weighted balance at
   height 1, checks the database nemesis hash and generation hash, then starts
   at block 1.
7. Each database block is mapped by `NisDbModelToModelMapper` (using the account
   cache for account identity/public-key reuse), executed by `chain.BlockExecutor`,
   and applied through the observer aggregate. Database blocks are fetched
   consecutively in batches of 100.
8. During execution, observers mutate account balances/vesting, account
   heights, harvesting rewards, remote and multisig links, transaction hashes,
   namespace/mosaic definitions and supply, expired-mosaic records, account
   mosaic ids, and POI/outlink state. Pruning observers run according to the
   configured feature options.
9. `BlockAnalyzer` updates the chain score for each parent/current block pair,
   records the last analyzed database block in `BlockChainLastBlockLayer`, and
   performs a final importance recalculation at the last replayed height.
10. `BlockChainLastBlockLayer.setLoaded()` marks block loading complete.
    `NisMain` commits the copied cache component by component; the POX facade
    is shallow-copied back to the original facade.
11. Only after analysis does the continuation perform configured automatic
    network boot.

The undo/fork path uses the same observer model through `BlockExecutor.undo()`,
which reverses transaction order and is coordinated by block-chain update
contexts. The database remains the source of persisted blocks and transaction
records; the cache is the derived in-memory state used for validation,
execution, harvesting, and REST reads.

The main Phase-1 architectural boundaries are therefore the DB-to-model mapper,
`BlockAnalyzer` replay loop, `BlockExecutor` plus observer aggregate,
`DefaultNisCache.copy()/commit()`, `BlockChainLastBlockLayer`, chain-score
updates, and the final POI recalculation. No persistent-state design or
implementation is included here.

## Test baseline

### Unit tests

The following command was run from the repository root:

~~~bash
MAVEN_OPTS='--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.security.cert=ALL-UNNAMED' \
mvn -B -DforkCount=0 test
~~~

The `MAVEN_OPTS` and `forkCount` changes are temporary test-runner
compatibility workarounds only; they are not repository changes. The old
`mockito-all:1.10.19` test dependency cannot access several JDK 17 internals
without these opens. The validated result was:

| Module | Passed | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `core` | 2361 | 2361 | 0 | 0 | 0 | PASS |
| `deploy` | 65 | 65 | 0 | 0 | 0 | PASS |
| `peer` | 306 | 306 | 0 | 0 | 0 | PASS |
| `nis` | 3485 | 3486 | 0 | 1 | 0 | EXISTING/ENVIRONMENTAL FAILURE |
| **Total** | **6217** | **6218** | **0** | **1** | **0** | **Reactor failed on one test** |

The default `mvn -B test` invocation under JDK 17 was also attempted. It
reached Core but produced 170 legacy Mockito errors before downstream modules
could run, primarily `InaccessibleObjectException` from cglib access to
`java.lang`. This is recorded as a Java-runtime/test-harness compatibility
observation, not a production failure. Under the temporary opens above, all
non-network unit tests completed.

The one NIS error was:

~~~text
org.nem.nis.boot.NisPeerNetworkHostTest.defaultHostCanBeBooted
java.lang.IllegalStateException: network boot failed
~~~

The report records attempted connections to public peers and failures such as
connection refused, timeout, no route to host, and incompatible remote nodes.
This environment-dependent test was not repaired or disabled.

### Classification of covered test areas

The unit suite includes the requested areas:

- database: `nis/src/test/java/org/nem/nis/dao`, H2/Flyway setup, DAO/retriever
  and raw/model mapping tests;
- blockchain and block execution: `nis/.../chain`, `chain/integration`,
  `BlockAnalyzerTest`, `BlockExecutorTest`, execute/undo processor tests;
- rollback/fork: block-chain updater/update-context and fork-configuration
  tests, plus `BlockUndoProcessorTest`;
- account state and caches: `nis/.../state`, `nis/.../cache`, account and
  cache pruning tests;
- namespaces: namespace state, cache, DAO, retriever, mapper, and controller
  tests;
- mosaics: mosaic state, id cache, definition/supply/expiration observers,
  DAO, mapper, and controller tests;
- importance/PoI: importance state, `DefaultPoxFacadeTest`, POI calculator and
  observer tests;
- serialization: Core serialization/model tests and NIS DB/model
  serialization/mapping tests.

The reports above count test cases across the reactor; the category list is a
coverage map, not a claim that categories are independent Maven executions.

### Integration tests

The repository command was run as:

~~~bash
MAVEN_OPTS='--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.security.cert=ALL-UNNAMED' \
mvn -B -DforkCount=0 failsafe:integration-test
~~~

The command includes performance/stress cases such as sparse-matrix
benchmarks and 10,000-iteration block-cipher tests. Maven returned exit code 0
and printed `BUILD SUCCESS` because the `integration-test` goal alone does not
run the verification phase. The Failsafe reports nevertheless contain the
following results and must be treated as authoritative:

| Module | Passed | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `core` | 7 | 7 | 0 | 0 | 0 | PASS |
| `deploy` | 0 | 0 | 0 | 0 | 0 | NO IT |
| `peer` | 2 | 2 | 0 | 0 | 0 | PASS |
| `nis` | 50 | 79 | 8 | 19 | 2 | EXISTING/ENVIRONMENTAL FAILURES |
| **Total** | **59** | **88** | **8** | **19** | **2** | **Maven goal exit 0; Failsafe not clean** |

The NIS Failsafe failures are isolated to `BlockScorerITCase` (random/
statistical assertions), `DefaultHashCachePerformanceITCase` (machine-speed
thresholds), public-peer `HttpConnectorITCase`, acceptance tests that attempt
to contact a node on the local test port, and the H2 `H2StorageSpeedITCase` /
`MissingTransactionITCase` cases that assume a prepared schema/data set.
`H2ITCase` and `TimeSynchronizationITCase` account for the two skipped cases.

## Deterministic consensus/state regression baseline

No representative Mainnet/Testnet blockchain database is present in this
checkout, so no chain-wide numeric snapshot is invented or committed. The
integration run created a synthetic 5000-block H2 performance fixture under
the user data directory; it is not a Mainnet/Testnet state fixture and is not
used for consensus comparison. The comparison contract for a controlled node
or fixture is:

1. Use the same network, nemesis resource, fork-height properties, optional
   features, database schema, and replay end height.
2. Start from a clean copy of the same H2 database or deterministic fixture.
3. Capture `chain/height`, `chain/last-block`, and `chain/score`.
4. For a fixed, version-controlled list of representative addresses, capture
   `/account/get`, `/account/get/forwarded`, `/account/status`, and the complete
   entry from `/account/importances`.
5. For a fixed list of namespace names, capture `/namespace`; for a fixed list
   of mosaic ids, capture `/mosaic/definition/last`, `/mosaic/supply`, and,
   where a height is specified, `/local/mosaic/definition/supply`.
6. Capture expiration entries at selected heights with
   `/local/mosaics/expired` when `TRACK_EXPIRED_MOSAICS` is enabled.
7. Normalize JSON object keys and unordered arrays, sort snapshot files by
   logical key, and record SHA-256 checksums.
8. Compare exact values for chain and state fields. Any difference is a
   regression investigation, not a tolerance-based performance result.

For transaction behavior, use a fixed signed transaction corpus and fixed
block height/time context in the existing block-validation and execution
fixtures. Record transaction hash, accepted/rejected result, validation error
type, and resulting block/state snapshot. REST `/transaction/get` can be used
for fixed persisted hashes; time-dependent push results are not valid as a
standalone consensus baseline.

`tools/nis-phase0-snapshot.sh` implements the API capture contract without
modifying NIS. It requires a running node and explicit representative lists;
it does not download or commit a blockchain database. A sample invocation is:

~~~bash
NIS_BASE_URL=http://127.0.0.1:7890 \
NIS_SNAPSHOT_ADDRESSES='TALICE... TBOB...' \
NIS_SNAPSHOT_NAMESPACES='foo bar' \
NIS_SNAPSHOT_MOSAICS='foo:token nem:xem' \
NIS_SNAPSHOT_HEIGHT=1000 \
tools/nis-phase0-snapshot.sh /tmp/nis-phase0/snapshot
~~~

The placeholder values above must be replaced with addresses and ids belonging
to the chosen fixture. The script was not run because this checkout has no
running NIS and no sufficiently populated chain database.

## Build baseline

The production package command was executed successfully under the observed
JDK 17 environment:

~~~bash
mvn -B -DskipTests package
~~~

Result: `BUILD SUCCESS`; all five reactor projects completed. The generated
`target/` outputs remain ignored/untracked and were not added to Git.

## Memory and startup baseline

No NIS JVM with a representative full blockchain database was available, so no
heap, RSS, replay-time, or object-population numbers are claimed here.
`tools/nis-phase0-profile.sh` provides the reproducible attachment procedure.

Run it against the actual NIS PID, with output outside the repository:

~~~bash
tools/nis-phase0-profile.sh --jfr 60 12345 /tmp/nis-phase0/profile
tools/nis-phase0-profile.sh --heap-dump 12345 /tmp/nis-phase0/profile-with-heap
~~~

For a fresh launch, enable Java 11-compatible unified GC logging in addition
to the current launcher arguments:

~~~bash
time java -Xms6G -Xmx6G \
  -Xlog:gc*:file=/tmp/nis-phase0/gc.log:time,uptime,level,tags \
  -cp ./staging:./nis/target/libs/*:./nis/target/* \
  org.nem.deploy.CommonStarter
~~~

Keep the GC log, the shell `time` output, and the application log together
with the profile manifest. Do not put these potentially large files under the
repository.

The helper records:

- JVM command line and `GC.heap_info` (heap used/committed information);
- `VM.metaspace`;
- process RSS and `/proc` memory summaries;
- `jstat -gc` and `jstat -gcutil` (GC counts and cumulative pauses);
- the complete and top portion of `GC.class_histogram`;
- native-memory tracking output when NMT was enabled at launch;
- optional JFR recording;
- optional heap dump for retained-heap analysis.

For a startup/replay run, launch the unchanged package with the current
settings, record wall-clock start and exit/startup times with `time`, and
correlate the application log messages `starting analysis...` and `block
loading completed; height ...`. Capture one profile before replay, one near
the replay midpoint, and one after `setLoaded()` if the process remains
available. Use the same database and JVM flags for every comparison.

For memory attribution, inspect histogram/heap-dump dominators for:
`AccountState`, account-cache delta maps, `HashMap`/map entries, namespace
histories, mosaic/expiration entries, importance/POI vectors and sparse
matrices, transaction hash metadata/indexes, and copy-on-write/rebased map
objects. A histogram alone cannot prove retained ownership; use MAT,
VisualVM, or an equivalent dominator/retained-size analyzer for the heap dump.

Measured values in this checkout:

| Metric | Value |
| --- | --- |
| JVM heap committed/used | not measured — no representative NIS PID |
| Process RSS | not measured — no representative NIS PID |
| Metaspace | not measured — no representative NIS PID |
| GC count/pause | not measured — no representative NIS PID |
| Startup time | not measured — no full-chain NIS startup |
| Replay time | not measured — no full-chain NIS replay |
| Class histogram | not measured — no representative NIS PID |
| Heap dump / retained heap | not measured — no representative NIS PID |

## Phase 0 gates

| Gate | Assessment | Evidence / condition |
| --- | --- | --- |
| A — Build | PASS with condition | Package succeeds; Java 11 runtime must still be run before Phase 1 because it is the supported CI/Docker baseline |
| B — Tests | PASS with condition | 6217/6218 validated unit cases pass; one existing network-dependent NIS error is isolated |
| C — Consensus regression | PASS with condition | Snapshot tool and exact comparison contract exist; capture against a representative DB/fixture is still required |
| D — Memory | PASS with condition | `jcmd`/JFR/histogram/heap-dump procedure exists; actual full-chain measurements are still required |
| E — Startup architecture | PASS | Nemesis → H2 → mapper → replay/executor/observers → score/POI → cache commit path is traced |

Overall result: **PHASE 0 READY WITH CONDITIONS**

Conditions before using this as the final go/no-go reference for Phase 1:

- run the package and unit baseline on Java 11;
- run the Failsafe suite to completion and preserve its report counts;
- run the snapshot script against a fixed representative Mainnet/Testnet
  fixture or controlled database;
- collect startup/replay and memory profiles with the same JVM/database
  parameters that will be used for Phase 1 comparisons.

## Known baseline failures and open issues

- Default JDK 17 test execution is incompatible with the legacy Mockito
  reflective test setup unless temporary module opens are supplied.
- `NisPeerNetworkHostTest.defaultHostCanBeBooted` depends on external peers and
  fails in this network-restricted environment.
- No full representative blockchain database is available locally.
- Failsafe integration reports contain 8 failures, 19 errors, and 2 skips;
  they are not a clean baseline but are classified in this document.
- There is no committed full-chain state snapshot by design.

## Phase 1+ recommendations

Phase 1 should begin only after the conditional measurements above are
captured. Then, in separate reviewable steps, audit Java 25 compatibility,
freeze dependency changes behind this snapshot, and compare state after every
change. Treat `BlockAnalyzer` replay, `BlockExecutor`/observers,
`NisCache.copy()/commit()`, POI recalculation, and H2-to-model mapping as
consensus-sensitive boundaries. Cache architecture and persistent/bounded
state design should be evaluated only after the exact state and memory
snapshots are available.
