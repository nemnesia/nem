# Phase 2L-E — Jenkins integration environment contract

## Result

**PARTIAL — REPOSITORY INTEGRATION ENVIRONMENT CONTRACT NOT FULLY VALIDATED**

This record covers repository-owned provisioning and validation only. The Phase 2L hosted Java 25 Jenkins acceptance remains PARTIAL: the Java 25 build image and Symbol shared-library mapping are still external prerequisites. Phase 2F and Phase 2K remain COMPLETE / CLOSED.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `dc82cfc09167ad123e138c72fb57be9df3119a18`
- Actual starting HEAD: `dc82cfc09167ad123e138c72fb57be9df3119a18`
- Starting local / origin divergence after fetch: `0 ahead / 0 behind`
- Implementation commit: `b89e9e428d3d1859fc2c6ac728478a0409933be7` (`[nis] test: provision isolated integration runtime`)
- Validation handoff HEAD after the implementation commit was pushed: `b89e9e428d3d1859fc2c6ac728478a0409933be7` (the documentation inventory follow-up is recorded separately in Git history).
- Changed files: `nis/pom.xml`; `nis/scripts/ci/{test,setup_test,teardown_test}.sh`; `nis/src/it/java/org/nem/nis/cache/DefaultHashCachePerformanceITCase.java`; `nis/src/it/java/org/nem/nis/dao/{BlockDaoITCase,H2Database,H2StorageSpeedITCase,MissingTransactionITCase,TestConfHardDisk,TestDatabase}.java`; `nis/src/test/java/org/nem/nis/controller/interceptors/LocalHostDetectorTest.java`; this validation record.
- JDKs: OpenJDK `17.0.20.1`; OpenJDK `25.0.4.1`
- Maven: `3.8.7`
- Compiler release: `17`
- No production dependencies, schema, migrations, protocol behavior, or production code were changed.
- Accepted legacy database artifacts were not mounted or opened by the probes. Their final size and SHA-256 matched the Phase 2K accepted values. A start-of-turn mtime snapshot was not captured, so this record does not claim a measured before/after mtime comparison; final observed mtimes are recorded below.

## Integration inventory

The NIS Failsafe suite contains 79 tests in 15 classes. The following class counts were read from its XML reports before the focused reruns in this phase; the Failsafe class selection and test gate were not narrowed.

| Class | Count | Environment and behavior |
|---|---:|---|
| `BasicNodeSelectorITCase` | 2 | In-process node fixtures; randomized `SecureRandom` inputs; no external service. |
| `BlockScorerITCase` | 13 | In-process scoring fixtures; random values and time-like inputs. |
| `DefaultHashCachePerformanceITCase` | 6 | In-memory 250,000-entry cache; random keys and wall-clock performance thresholds. |
| `HttpConnectorITCase` | 3 | Two tests contact `alice2.nem.ninja`; the pre-trusted-peer test traverses addresses from `peers-config_testnet.json`, including public peer endpoints. Requires external DNS/network and live peer availability. |
| `AccountControllerITCase` | 2 | Requires local NIS HTTP service on fixed port 7890 and account unlock state. |
| `BlockControllerITCase` | 5 | Requires local NIS HTTP service on fixed port 7890 and a loaded chain. |
| `PushControllerITCase` | 1 | Requires local NIS HTTP service on fixed port 7890. |
| `TransferControllerITCase` | 8 | Requires local NIS HTTP service on fixed port 7890 and Testnet account/transaction configuration. |
| `BlockDaoITCase` | 1 | Requires migrated disk H2 and 5,000 blocks / 500,000 transactions of test data. Data is currently generated with random accounts and transactions. |
| `H2ITCase` | 1 | Stress case is already `@Ignore`; it remains present in Failsafe inventory and is reported as skipped when the full Failsafe suite runs. |
| `H2StorageSpeedITCase` | 1 | Requires isolated H2 schema; migrates with production H2 Flyway scripts, then inserts 100,000 accounts and 150,000 transfers. Uses random keys/transactions and records elapsed time. |
| `MissingTransactionITCase` | 1 | Requires a separate NIS runtime-derived Mijin database containing transactions at heights 4,984–5,400. `NIS_IT_MIJINNET_DB` supplies a copy; accepted Mainnet/Testnet files are not used. |
| `TransferDaoITCase` | 1 | Requires the disk H2 schema and seeded 5,000-block database. |
| `PoiImportanceCalculatorITCase` | 14 | In-process graph/account fixtures; random account generation and wall-clock performance measurements. |
| `TimeSynchronizationITCase` | 20 | In-process simulated nodes and synthetic timestamps; uses async scheduling and real wall-clock reads in the simulation. No live node is required by its fixtures. |

The categories overlap: several in-process classes use randomness or performance/time assertions. These tests were not excluded or renamed. The existing Failsafe execution and `failsafe:verify` remain in `nis/scripts/ci/test.sh`, so failure status reaches the Jenkins shell stage.

## Repository-owned provisioning

`nis/scripts/ci/setup_test.sh` now creates `nis/target/nis-it.*`, generates an isolated Testnet configuration, computes the production runtime classpath from the NIS and Deploy Maven modules, and starts the regular `org.nem.deploy.CommonStarter` application. It waits for the `/heartbeat` endpoint (bounded 90-second polling), captures the PID and startup log, and fails on startup/readiness errors. The runtime uses its temporary `user.home`, so its H2 database is separate from the accepted Mainnet/Testnet artifacts.

The controller fixtures hard-code `127.0.0.1:7890`, so provisioning checks that port and refuses to adopt an unrelated pre-existing service. The regular Jetty connector is created without a bind-host override; its configured `nem.host` is an advertised node address, not a connector bind restriction. Therefore this implementation verifies a loopback test target but does not claim that the process is network-interface isolated. Adding a production bind-address setting would change production server behavior and was left out of scope.

`nis/scripts/ci/test.sh` provisions the runtime if absent, passes the isolated home to Failsafe, runs unit tests and Failsafe in the same Maven invocation, runs `failsafe:verify`, and always attempts teardown while preserving the Maven exit status. `teardown_test.sh` requests normal shutdown, waits for the tracked process, verifies process identity before terminating a stuck CommonStarter, and removes only the expected temporary home. A failed provisioning or cleanup exits non-zero.

`nis/pom.xml` passes the explicit `nis.it.home` value as `user.home` to Failsafe forks. The default remains the user's current home for direct Maven runs; CI uses the isolated value forwarded by `test.sh`.

### H2 fixture contract

- `H2Database` creates `$user.home/nem/nis/data` and uses portable normalized paths.
- The disk DAO test URL uses H2 `MODE=LEGACY`, `NON_KEYWORDS=VALUE`, and `DB_CLOSE_DELAY=-1`.
- DAO tests keep their own H2 test database; they do not open accepted legacy artifacts.
- `BlockDaoITCase` now calls `TestDatabase.load()` before querying. `TestDatabase` checks actual block count instead of a Windows-only physical filename check, and reads seeded accounts when the 5,000-block fixture already exists.
- `H2StorageSpeedITCase` applies Flyway migrations to its isolated H2 database. Its cleanup uses H2 `SET REFERENTIAL_INTEGRITY` and restores referential integrity in `finally`, replacing MySQL-only `FOREIGN_KEY_CHECKS` statements.
- The storage fixture binds public keys, hashes, and signatures as raw bytes to their `VARBINARY` columns. It does not change the migration SQL or application mappings.
- `MissingTransactionITCase` gives a direct precondition error if its distinct Mijin database is absent. If `NIS_IT_MIJINNET_DB` is set, setup copies that file into the disposable test home. A Mainnet or Testnet artifact is never substituted.

## Determinism and connector findings

- `LocalHostDetectorTest` previously resolved `bob.nem.ninja` during a unit test. It now uses the system `localhost` hostname for the configured hostname case and a reserved numeric TEST-NET address for the remote case, removing public DNS as a unit-test prerequisite while retaining a hostname-resolution assertion.
- `DefaultHashCachePerformanceITCase` now commits the cache baseline before measuring `copy()`, so the test measures copying committed state as intended.
- `TestDatabase`, `H2StorageSpeedITCase`, `BlockScorerITCase`, and `PoiImportanceCalculatorITCase` still use unseeded randomness. Several cache, database, POI, and synchronization cases still depend on elapsed time or asynchronous scheduling. They remain candidates for a separate narrowly scoped determinism pass; no timeout/assertion was weakened here.
- Public-peer connector coverage is unchanged and still requires public network services. It was not replaced with mocks or removed from the Jenkins test gate.

## Validation performed

The final root full-reactor commands succeeded on both JDKs after the local DNS test was made independent of a public hostname. Commands used Maven `3.8.7` with `-B -o` against the existing local artifact cache; Java 25 emitted only the known Maven Guava `sun.misc.Unsafe` deprecation warning:

| JDK | Command | Tests | Failures | Errors | Skipped | Result |
|---|---|---:|---:|---:|---:|---|
| 17.0.20.1 | `mvn -B -o clean test` | 6,227 | 0 | 0 | 0 | PASS |
| 17.0.20.1 | `mvn -B -o clean package` | 6,227 | 0 | 0 | 0 | PASS; final run after all source changes |
| 25.0.4.1 | `mvn -B -o clean test` | 6,227 | 0 | 0 | 0 | PASS; final run after all source changes |
| 25.0.4.1 | `mvn -B -o clean package` | 6,227 | 0 | 0 | 0 | PASS; final run after all source changes |

The full root test/package runs execute Surefire, not the NIS Failsafe suite. Surefire totals were Core 2,364; Deploy 61; Peer 306; NIS 3,496.

Focused Failsafe validation used the real isolated application startup and `failsafe:verify`:

| JDK | Scope | Tests | Failures | Errors | Skipped | Maven result |
|---|---|---:|---:|---:|---:|---|
| 17.0.20.1 | Four `*ControllerITCase` classes | 16 | 5 | 0 | 0 | BUILD FAILURE, as required by Failsafe |
| 25.0.4.1 | Same four controller classes and same provisioning | 16 | 5 | 0 | 0 | BUILD FAILURE, as required by Failsafe |
| 17.0.20.1 | `H2StorageSpeedITCase` on disposable disk H2 | 1 | 0 | 0 | 0 | PASS |
| 25.0.4.1 | Same H2 test on a separate disposable home | 1 | 0 | 0 | 0 | PASS |

The same five loopback assertions failed on both JDKs: `AccountControllerITCase.unlockSuccessReturnsOk` (400 instead of 200), `BlockControllerITCase.wrongUrlReturnsNotFound` (500 instead of 404), `TransferControllerITCase.transferCorrectTransaction` (400 instead of 200), and two exact Commons Codec error-message expectations whose observed messages include hexadecimal byte notation. These are deterministic endpoint/assertion differences, not Java-version-specific failures. Assertions were not altered.

The root `mvn clean test` attempt under the default sandbox initially failed in Core WireMock tests because the sandbox denied socket creation. Re-running the same command with loopback-capable permissions passed. A focused NIS Surefire run initially encountered a public DNS error before reaching Failsafe; the DNS-dependent unit test was then made local and both complete root regressions passed. Docker was not used. Maven was run offline (`-o`) from the existing dependency cache; no alternate repository was added.

The complete 79-test Failsafe suite was not run end-to-end in this phase. The prior Phase 2L-D prospective-container result remains historical evidence only: Java 25 had 8 failures / 21 errors / 2 skips and Java 17 had 10 failures / 21 errors / 2 skips. It is not represented here as a rerun. This phase did run all 16 loopback controller tests and the H2 speed test on both JDKs.

## Accepted artifact integrity

The two runtime-derived accepted DB artifacts were not opened or mounted by these integration probes. Their final size and SHA-256 match the accepted baseline values. The mtime column is the final observed metadata only; the start-of-turn mtime was not captured, so no before/after mtime comparison is asserted:

| Artifact | Size | SHA-256 | mtime (Unix seconds) |
|---|---:|---|---:|
| `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

Both files are real NIS runtime-derived data and remain outside Git. Phase 2F remains COMPLETE / CLOSED; Phase 2K remains COMPLETE / CLOSED. No production schema or Flyway history changed.

## Remaining work and ownership

Repository-owned provisioning now supports an isolated production-bootstrap NIS node, disk H2 setup, cleanup, and fail-closed Failsafe behavior. The repository contract is still PARTIAL because:

1. Five loopback controller assertions fail identically on Java 17 and Java 25 and need root-cause review before any expected behavior is changed.
2. `HttpConnectorITCase` still depends on public NEM peers and external network/DNS.
3. `MissingTransactionITCase` requires an operator-supplied, runtime-derived Mijin DB through `NIS_IT_MIJINNET_DB`.
4. DAO seeded-data cases and the remaining random/timing-sensitive cases were not all executed through Failsafe in this phase.
5. The Jetty runtime has no repository-level loopback bind option; the ephemeral test process uses the same production connector behavior.
6. Hosted Java 25 Jenkins is still blocked on a published `symbolplatform/build-ci` Java 25 image, a selectable Symbol shared-library mapping, and a hosted Jenkins run. Owners remain the build-ci/shared-library and Jenkins infrastructure administrators.

This record closes only the implementation work demonstrated above. It does not claim that the 79-test suite is hermetic or fully green, and it does not close hosted Java 25 Jenkins acceptance or Phase 2L overall.
