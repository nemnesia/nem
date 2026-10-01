# Phase 2L-D — Jenkins integration-test environment closure

## Decision

**PARTIAL — JENKINS INTEGRATION ENVIRONMENT NOT CLOSED.** The current Failsafe lane is fail-closed after the Phase 2L-C `test.sh` correction, but its 79 tests do not form a reproducible Jenkins environment: 16 controller acceptance tests need a running local NIS node, the connector tests contact public NEM peers, four storage tests expect separate pre-provisioned H2 state, and several simulation/performance tests have nondeterministic or machine-dependent thresholds. The repository contains no NIS `scripts/ci/setup_test.sh` to provision those requirements. The historical Jenkins contract did run the Failsafe goal, so there is not enough evidence to silently remove these checks from the Jenkins gate.

Phase 2L-C prospective-container runs remain the comparable Java 17/25 evidence. They found no Java-version-specific integration failure, but the tests fail under both runtimes. This phase did not change source, test behavior, dependencies, or CI scripts. A focused Maven rerun attempted in this environment could not resolve Maven plugins because network access is unavailable and the supplied temporary Maven cache does not contain those plugins; Docker API access was also denied. This phase therefore records the audited contract and root causes without claiming a new successful rerun or hosted Jenkins run.

Java 25 repository/runtime compatibility and the earlier Java 25 unit/full-reactor results remain valid. They do not close either the unprovisioned integration environment or the external Java 25 hosted Jenkins image/mapping requirement.

## Repository and start state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `a85b6b5abb510ac0080bc5d3fd177dfa1e9a0c20`
- Actual starting HEAD: `a85b6b5abb510ac0080bc5d3fd177dfa1e9a0c20` (matches)
- Starting local and `origin/agent/nis-phase0-baseline` HEAD: equal; divergence `0 0`.
- Starting working tree: only the pre-existing `.gitignore` addition of `legacy/`; it was not changed, staged, or committed.
- No application, test, dependency, database, protocol, consensus, or Jenkins script changes were made in this phase.
- Prior Phase 2L-C change remains: `nis/scripts/ci/test.sh` runs `mvn test failsafe:integration-test failsafe:verify -B` in one Maven process. This is required for Mockito agent resolution and to propagate integration failures; it was not reverted.
- Phase 2F and Phase 2K remain **COMPLETE / CLOSED**.

## Jenkins test contract

The NIS Jenkinsfile delegates stages to the pinned Symbol shared library. The shared library calls the package's `scripts/ci/setup_test.sh` path in its test setup stage and then calls `scripts/ci/test.sh`. NIS has no `scripts/ci/setup_test.sh`, so no repository-owned test service, DB, or node is provisioned. The corrected `test.sh` includes the Failsafe execution and `failsafe:verify`; integration tests are therefore an effective part of the Jenkins test stage and a Failsafe failure fails the stage.

`nis/pom.xml` adds `nis/src/it/java` as test source via build-helper. Failsafe 3.5.6 is configured with `failIfNoTests=true`; the Mockito agent property is populated by the Maven dependency-plugin execution. All 79 tests below are in module `nis` and are Failsafe integration tests, not Surefire unit tests. There is no NIS-specific Maven profile, environment variable, system-property contract, or external-suite preflight for them. The paths/endpoints are embedded in test code/configuration. The reactor unit tests are a separate 3,496-test Surefire population.

The originating Jenkins integration change (`2ac19b6ff`, `[monorepo] task: Jenkins integration`) already invoked Failsafe from the NIS `test.sh`; it did not provision the node/database assumptions now visible in source. Phase 2L-C's correction (`d594ce5bd`, `[nis] test: ensure Failsafe failures fail Jenkins`) makes that stage fail honestly. The historical intent is therefore to run these tests, but the required environment contract was never made reproducible in the NIS repository.

## Integration-test inventory and current failure classification

Every class is under `nis/src/it/java`, module `nis`, and Failsafe. Test counts below match the Failsafe XML reports from the Java 25.0.4.1 / Maven 3.9.12 prospective-container run and Java 17.0.20.1 / Maven 3.9.12 matched-tooling run recorded in Phase 2L-C.

| Class | Count | Required endpoints / services / data | Filesystem, configuration, timing | Observed result and category |
|---|---:|---|---|---|
| `org.nem.nis.BasicNodeSelectorITCase` | 2 | None; in-process selector and `SecureRandom`. | Randomized chi-square checks; no endpoint or external config. | Both passed on both JDKs. **E** risk: statistical acceptance has a nonzero false-rejection probability; not a Java-specific issue. |
| `org.nem.nis.BlockScorerITCase` | 13 | None; in-process randomized key/account/block simulations. | `SecureRandom`; some tests simulate many block rounds; no wall-clock sleep. Source comment explicitly says the selfish harvester can sometimes win “due to variance.” | Java 25: 2 failures; Java 17: 4 failures. **E** randomized/probabilistic assertions. Failure sets differ across JDK runs; no **F** evidence. |
| `org.nem.nis.cache.DefaultHashCachePerformanceITCase` | 6 | None; in-process cache. | `System.currentTimeMillis`, 250,000-item workloads and fixed `<500 ms` checks; random hashes. | 3 failures on each JDK: two time thresholds and `copyPerformanceTest` expected 250,000 but observed 0. **E** for timing tests; **A/G** test/API-contract issue for the copy count is unresolved and must be investigated before assigning expected behavior. |
| `org.nem.nis.connect.HttpConnectorITCase` | 3 | Public `alice2.nem.ninja:7890` (expected peer identity in test); third test traverses the checked-in pretrusted Testnet peer list and contacts its listed nodes. | No environment override; hostnames and port are test/config values. Network timeout is the observed environment condition. | 1 failure + 1 error on both JDKs; connection timeout caused the unexpected-identity assertion to receive an inactive-peer exception. **C** external NEM peers/network, not a Java 25 defect. |
| `org.nem.nis.controller.acceptance.AccountControllerITCase` | 2 | Local NIS HTTP API at `127.0.0.1:7890`; a Testnet account key is embedded. | `LocalHostConnector` hardcodes the loopback endpoint; no environment variable/property. | 2 errors on both JDKs: connection refused. **B** missing local NIS service/configuration. |
| `org.nem.nis.controller.acceptance.BlockControllerITCase` | 5 | Local NIS HTTP API at `127.0.0.1:7890` with a chain containing height 1. | Same hard-coded loopback connector; no service bootstrap in test code. | 5 errors on both JDKs: connection refused. **B** missing local NIS service/chain. |
| `org.nem.nis.controller.acceptance.PushControllerITCase` | 1 | Local NIS HTTP API at `127.0.0.1:7890`. | Same hard-coded loopback connector. | 1 error on both JDKs: connection refused. **B** missing local NIS service. |
| `org.nem.nis.controller.acceptance.TransferControllerITCase` | 8 | Local NIS HTTP API at `127.0.0.1:7890`; Testnet account/address constants. | Same hard-coded endpoint; tests exercise transaction preparation against running node state. | 8 errors on both JDKs: connection refused. **B** missing local NIS service/configuration. |
| `org.nem.nis.dao.BlockDaoITCase` | 1 | Spring DAO context and hard-disk H2 DB at a path derived from `user.home`; intended 5,000-block data set is declared by `TestDatabase`. | `TestConfHardDisk` uses Windows backslashes and omits H2 `MODE=LEGACY;NON_KEYWORDS=VALUE` flags present in `TestConf`; `TestDatabase.databaseFileExists()` also uses a Windows-specific path. Test measures elapsed time. | 1 error on both JDKs during Flyway V1.0.0: `TRANSACTION_ID_SEQ.NEXTVAL` is parsed as a missing column. **D/A** test DB configuration defect; after bootstrap is repaired, fixture and performance setup still need an isolated reproducible contract. |
| `org.nem.nis.dao.H2ITCase` | 1 | File-backed H2 database `user.home/nem/nis/data/test`. | `@Ignore`; expensive 1,000-iteration query/GC probe has no meaningful assertion (source comment). | 1 skipped on both JDKs by source-level `@Ignore`. This is an existing explicit skip, not a Phase 2L-D change. |
| `org.nem.nis.dao.H2StorageSpeedITCase` | 1 | Pre-created `user.home/nem/nis/data/h2_speed_test` with the expected NIS schema. | Source comment says the DB must be created first; then test truncates tables and writes 100,000 accounts / 150,000 transactions. Uses `System.currentTimeMillis` and `SET FOREIGN_KEY_CHECKS=0`, a MySQL command invalid in H2. No setup script. | 1 error on both JDKs at the first H2 statement: syntax error for `SET FOREIGN_KEY_CHECKS=0`. **A/D** deterministic test SQL defect plus missing DB provisioning; this is not a Java incompatibility. |
| `org.nem.nis.dao.MissingTransactionITCase` | 1 | Separate persisted `nis5_mijinnet` database containing blocks 4984–5400 and 250,000 known spammer transfers. Source says run only when NIS is stopped because it reads that DB. | Path is `user.home/nem/nis/data/nis5_mijinnet`; there is no fixture or environment override. | 1 error on both JDKs: expected block IDs/data are absent. **D** required external/runtime-derived test data not provisioned; accepted Phase 2K Mainnet/Testnet originals were not accessed. |
| `org.nem.nis.dao.TransferDaoITCase` | 1 | Same `TestConfHardDisk` file-backed H2 context; `TestDatabase.load()` may generate 5,000 blocks × 100 transactions when the expected file is absent. | Windows-style path/incorrect H2 compatibility flags; randomized fixture generation and wall-time logging. | 1 error on both JDKs during the same Flyway `TRANSACTION_ID_SEQ.NEXTVAL` startup failure as `BlockDaoITCase`. **D/A** test DB config issue. |
| `org.nem.nis.pox.poi.PoiImportanceCalculatorITCase` | 14 | In-process account / importance fixtures only; no network or external DB. | Several large synthetic account sets; wall-clock growth checks use `System.currentTimeMillis`. | 1 failure on both JDKs: `poiCalculationPerformanceGrowthIsReasonablyBoundedAsNumberOfAccountsIncreases`. **E** performance threshold/environment sensitive; remaining functional cases passed. |
| `org.nem.nis.time.synchronization.TimeSynchronizationITCase` | 20 | In-process virtual network/node simulation; no external network endpoint or real sleep. | Simulated timestamps and randomized node populations (`Network`/`NodeSettings`); asynchronous/thread scheduling may affect execution, but the clock advances through the simulation. One long unstable-clock case is explicitly `@Ignore`. | 1 failure + 1 skipped on both JDKs. Failure: `highPercentageOfNewNodesDoesNotHaveBigInfluenceOnNetworkTime`. **E** randomized simulation assertion; ignored case is source-level, not newly skipped. |

Aggregate: **79 discovered, 8 failures, 21 errors, 2 skipped on Java 25**; **79 discovered, 10 failures, 21 errors, 2 skipped on Java 17**. Failures/errors by test report and class are retained in Phase 2L-C's prospective-container record; no test was excluded, relaxed, or converted to success. The two Java-version runs had the same external-service, missing-DB, H2 bootstrap, H2 SQL, POI performance, hash-cache performance, and time-synchronization failure categories. BlockScorer's failure count varied (2 vs 4), consistent with its explicit `SecureRandom` variance. No failure was isolated as Java-25-specific.

### Failure classes (A–G)

- **A — deterministic application/test defect:** `H2StorageSpeedITCase` issues MySQL-only `SET FOREIGN_KEY_CHECKS=0` to H2. DAO integration contexts also have a deterministic H2 URL incompatibility relative to `TestConf` and the migration SQL.
- **B — missing CI-local service:** the 16 controller acceptance tests require an NIS process listening on loopback port 7890 and a suitable local chain/configuration. Jenkins' test setup path is invoked by shared-library contract, but NIS has no `scripts/ci/setup_test.sh` to start one.
- **C — intentional external NEM dependency:** `HttpConnectorITCase` uses real public peers / checked-in peer endpoints. It is not a hermetic local HTTP test.
- **D — DB/environment provisioning:** both DAO Spring contexts require an isolated hard-disk H2 setup; `H2StorageSpeedITCase` requires a schema-bearing database; `MissingTransactionITCase` requires a separate known NIS runtime data set. The failing test file URLs and required schema/data are not represented as explicit parameters or fixtures.
- **E — timing/race/statistical behavior:** BlockScorer, hash-cache/POI performance checks, and time/selection simulations use randomness or hard timing/statistical cutoffs. The failure count changes by run/JDK; no test result supports attributing these to Java 25.
- **F — Java-version-specific:** none identified. Both JDKs produce the same structural/environment failures; no Java-25-only class loading/bootstrap error remains after Phase 2L-C's single-invocation Failsafe fix.
- **G — other/unresolved:** `DefaultHashCachePerformanceITCase.copyPerformanceTest` expects `copy().size()==250000` but both captured runs report 0. This requires a specific owner decision on whether the copy API snapshots committed state or whether the benchmark setup is invalid; it is not classified as external infrastructure or Java-specific without that evidence.

The inspected integration sources contain no `System.getenv` or test-specific system-property contract for endpoint selection/provisioning. Hard-coded network endpoints are `alice2.nem.ninja:7890`, peer entries in `peers-config_testnet.json`, and loopback `127.0.0.1:7890`. File-backed H2 uses `user.home`; fixed service port is 7890. The suite has no integration-test clock injection at the endpoint layer; simulated-time classes advance virtual network time. The public-node errors were socket timeout/DNS-reachable-but-unavailable behavior; loopback errors were immediate connection refused. No HTTP mock/server provisioning is present.

## Reproduction and available validation

Phase 2L-C used one prospective Jenkins-like container recipe on Ubuntu 26.04.1 / Maven 3.9.12, with JDK 25.0.4.1 and JDK 17.0.20.1. It ran NIS `setup_build.sh`, `build.sh`, and the then-current `test.sh`; both JDKs reached all 79 Failsafe tests. Unit tests in that script passed **3,496 / 0 failures / 0 errors / 0 skipped** on both JDKs. Failsafe returned failure after `failsafe:verify`, correctly reporting the counts above.

This phase checked the saved Failsafe XML and text reports for both runtimes to name failing methods and stack roots. A fresh focused Maven rerun was attempted from the disposable Java 17 source copy:

```text
mvn -B -pl nis -Dmaven.repo.local=/tmp/phase-2l-c-m2 \
  -Duser.home=/tmp/phase-2l-d-test-home -Dit.test=BlockScorerITCase \
  test-compile failsafe:integration-test failsafe:verify
```

It stopped before compiling/running tests because the temporary local Maven repository lacks required plugin descriptors and network access to Maven Central is unavailable (`No plugin found for prefix 'failsafe'`). It is an infrastructure resolution failure, not a test result. The host JDK available in this session is OpenJDK `17.0.20.1`, Maven `3.8.7`; access to `/var/run/docker.sock` is denied, so a fresh Java 25 or matched prospective-container rerun was not possible. No test run is claimed from this failed attempt.

Last accepted full-reactor evidence remains Phase 2L-A: Java 17 and Java 25 each passed `clean test` and `clean package` with **6,227 tests, 0 failures, 0 errors, 0 skipped**. Phase 2L-C additionally recorded Java 25 `clean test` and `clean package` at those same counts, plus the Jenkins-equivalent unit-only script results above. This phase changed no code, so it did not rerun the full reactor or production runtime probe.

## Changes and environment contract gap

No tests were skipped or excluded beyond the two already annotated `@Ignore`. No assertion, timeout, Maven exit handling, or script behavior was changed. No external integration was represented as passing. In particular, this phase does not split a “hermetic suite” from the existing suite: the current POM and Jenkins history treat all Failsafe tests as the test stage, and there is not yet an evidence-backed class-level contract defining which tests may leave that required gate.

To close the integration environment safely, a follow-up must determine and implement separate explicit contracts for:

1. **Local NIS acceptance:** provision an isolated disposable NIS node/config/database on a dynamically allocated or contractually fixed endpoint; avoid sharing production/operator data. Make the endpoint configurable only if the test semantics remain the same, and fail preflight if a requested service is absent.
2. **Real peer integration:** retain as an explicit external suite with documented host/identity configuration and a fail-closed preflight. It must not silently pass when Internet/node access is missing.
3. **DAO/storage performance:** provision isolated H2 schemas/data per test, correct the database path/URL and H2-specific SQL only after validating the intended test semantics, and decide whether these performance measurements are a required CI gate or a separately required benchmark gate.
4. **Random/performance assertions:** review confidence bounds and timing thresholds with test owners, replacing nondeterminism with seeded/deterministic synchronization where meaningful. Do not relax assertions solely to get a green job.
5. **Unresolved cache-copy assertion:** establish the expected `copy()` contract and add a deterministic functional test before altering the benchmark.

Until this is done, the current 79-test required Failsafe stage has a truthful failure signal but no reproducible pass contract. Phase 2L-C's Java 25 image publication/shared-library mapping/hosted run is also still an external Symbol-owned requirement; this record does not claim that Java 25 is selectable in hosted Jenkins.

## Database and modernization status

The accepted files `legacy/nis5_mainnet.mv.db` and `legacy/nis5_testnet.mv.db` were not mounted or opened. Read-only close-out identity checks matched accepted Phase 2K values:

| Artifact | Size | SHA-256 | mtime |
|---|---:|---|---|
| Mainnet | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `2026-09-28 21:40:07.899431491 +0900` |
| Testnet | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `2026-09-28 21:38:22.663025304 +0900` |

- Phase 2F: **COMPLETE / CLOSED**, unchanged.
- Phase 2K: **COMPLETE / CLOSED**, unchanged.
- Java 25 application/runtime baseline: remains accepted from Phase 2L-A.
- Phase 2L-D integration environment: **PARTIAL**; no reproducible green hermetic/external split has been established.
- Phase 2L overall hosted Java 25 Jenkins acceptance: **still blocked** by the external Java 25 image publication, shared-library selection mapping, and hosted job execution recorded in Phase 2L-C.

## Git completion

- Documentation change: this record only.
- No DB artifacts were staged or committed.
- The pre-existing `.gitignore` `legacy/` edit remains unchanged and uncommitted.
- Final local HEAD, origin HEAD, commit, and working-tree state are reported with the Phase 2L-D completion.
