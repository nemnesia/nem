# Phase 2L-F — Loopback Controller Integration Validation

## Status

**PARTIAL — LOOPBACK CONTROLLER INTEGRATION NOT FULLY VALIDATED**

The repository-provisioned loopback runtime is repeatable and 14 of the 16 controller integration tests pass on both Java 17 and Java 25. Two tests require a funded Testnet account that is absent from the clean genesis-only test runtime. Making those tests pass without suitable persisted chain data would require fabricating account state or weakening their transaction/account assertions, so they remain failures. No accepted Mainnet or Testnet database was opened.

Phase 2L-F does not close the hosted Jenkins Java 25 acceptance. The Java 25 build image, Symbol shared-library mapping, and hosted job remain external-owner work. Phase 2F and Phase 2K remain COMPLETE / CLOSED.

## Repository and environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `42150d80a40bb51bffdc285e8d443d74b8c74b60`
- Actual starting HEAD: `42150d80a40bb51bffdc285e8d443d74b8c74b60`
- Starting local/origin divergence: 0 ahead, 0 behind
- Final implementation HEAD: `40b522f68` (`[nis] fix: correct loopback controller integration contracts`); the validation-record commit follows without changing implementation.
- Commits: implementation `40b522f68`; validation record recorded in the following documentation commit.
- Changed files: `nis/scripts/ci/setup_test.sh`; `nis/src/it/java/org/nem/nis/controller/acceptance/TransferControllerITCase.java`; `nis/src/main/java/org/nem/nis/controller/ExceptionControllerAdvice.java`; `nis/src/test/java/org/nem/nis/controller/ExceptionControllerAdviceTest.java`; this record.
- JDKs: Java `17.0.20.1` and Java `25.0.4.1`
- Maven: `3.8.7`; compiler release remains `17`
- Validation used repository scripts and disposable `nis/target/nis-it.*` homes. The accepted database artifacts were not opened, mounted, or used to start NIS.

The existing `.gitignore` change adding `legacy/` was pre-existing, remained untouched, and is excluded from the Phase 2L-F commits.

## Failure reproduction and root cause

Before the fixes, the same repository-provisioned runtime and selected controller Failsafe classes reproduced 16 tests on both JDKs: 5 failures, 0 errors, 0 skipped. The same five assertions failed on each JDK; this was not a Java-version-specific result.

| Test | Expected | Actual response | Classification and cause |
|---|---|---|---|
| `AccountControllerITCase.unlockSuccessReturnsOk` | Successful unlock | HTTP 400; `FAILURE_UNKNOWN_ACCOUNT` | Fixture mismatch. The fixture address (`TBsan…`) is not in the newly provisioned Testnet genesis-only chain. The node had completed startup and served requests. |
| `TransferControllerITCase.transferCorrectTransaction` | Successful transfer | HTTP 400; `FAILURE_INSUFFICIENT_BALANCE` | Fixture mismatch. The sender account is absent/unfunded in the genesis-only chain. This is a legitimate production validation response, not a readiness failure. |
| `BlockControllerITCase.wrongUrlReturnsNotFound` | HTTP 404 | HTTP 500; `No endpoint POST /wrong/at.` | Production HTTP error mapping defect. Spring 7's `NoHandlerFoundException` reached the catch-all 500 handler. |
| `TransferControllerITCase.transferIncorrectMessagePayload` | Exact malformed-hex error message | HTTP 400; `org.apache.commons.codec.DecoderException: Illegal hexadecimal character 0x47 at index 11.` | API expectation drift. Commons Codec 1.22 reports the offending byte in hex and includes a final period. |
| `TransferControllerITCase.transferIncorrectSigner` | Exact malformed-hex error message | HTTP 400; `org.apache.commons.codec.DecoderException: Illegal hexadecimal character 0x20 at index 3.` | API expectation drift, same library message-format change. |

The generated runtime logs showed Spring/context initialization, Jetty startup, database initialization, genesis block loading through height 1, and the NIS ready message before test requests. The old readiness check only observed `/heartbeat`; it could report ready without proving controller registration and loaded chain state.

## Changes made

- The test runtime readiness predicate now requires both `/heartbeat` and a successful `POST /block/at/public` request for genesis height 1. This is condition-based polling using the existing bounded startup loop, not a fixed sleep.
- `NoHandlerFoundException` now maps to HTTP 404 and preserves the established error response payload. A focused unit test covers status and message.
- Two strict malformed-hex assertions now match the exact Commons Codec 1.22 error text observed from the unchanged API response.
- No tests were excluded, disabled, retried, or weakened. No production schema, migration, protocol, consensus, persistence, dependency, or network configuration changed.

## Controller and H2 validation

Each controller run used a fresh repository-provisioned temporary NIS home; shutdown/teardown completed before the next provisioning. The controller subset was run twice from clean runtime provisioning per JDK (initial post-fix run and repeat). All runs used `failsafe:verify`, and Maven returned failure rather than masking the remaining assertions.

| JDK | Controller tests per run | Pass | Failures | Errors | Skipped | Repeat result |
|---|---:|---:|---:|---:|---:|---|
| Java 17 | 16 | 14 | 2 | 0 | 0 | Same two missing-account/funding failures on clean reprovision |
| Java 25 | 16 | 14 | 2 | 0 | 0 | Same two missing-account/funding failures on clean reprovision |

The fixed 404 and both malformed-payload assertions pass on both JDKs. The two remaining failures are `unlockSuccessReturnsOk` and `transferCorrectTransaction`; they show the same HTTP 400 responses described above on every run.

The focused `H2StorageSpeedITCase` passed on both JDKs: 1 test, 0 failures, 0 errors, 0 skipped. Its setup uses disposable H2 storage and does not use either accepted legacy database.

### Why the remaining two were not changed

The repository-owned runtime starts with a clean Testnet genesis block and has no fixture funding the controller test key. To make the success-path assertions pass, the test environment needs a valid persisted chain/account state containing the intended account and balance. The accepted Mainnet/Testnet artifacts are explicitly out of scope and were not used. Injecting a fake balance, bypassing account validation, lowering/removing the assertions, or adding an invalid block would falsify production behavior or chain invariants. A separately supplied, disposable runtime-derived Testnet fixture containing the required account state (or a repository-owned valid chain fixture with equivalent provenance) is needed to close these two cases.

## Full reactor validation

The new `ExceptionControllerAdviceTest` adds one unit test, so the reactor count rises from 6,227 to 6,228. Root `clean test` and `clean package` run Surefire; the 79 Failsafe integration tests are exercised separately by the Jenkins integration entrypoint.

| JDK | Command | Result |
|---|---|---|
| Java 17 | `mvn -B -o clean test` | PASS — 6,228 tests, 0 failures, 0 errors, 0 skipped |
| Java 17 | `mvn -B -o clean package` | PASS on full rerun — 6,228 tests, 0 failures, 0 errors, 0 skipped |
| Java 25 | `mvn -B -o clean test` | PASS — 6,228 tests, 0 failures, 0 errors, 0 skipped |
| Java 25 | `mvn -B -o clean package` | PASS — 6,228 tests, 0 failures, 0 errors, 0 skipped |

The first Java 17 `clean package` run hit the existing timing-sensitive `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance` assertion. The test passed alone (13 tests in its class, no failures) and the full clean package rerun passed. The failure was recorded rather than suppressed. Java 25 emits the already-known Guava `sun.misc.Unsafe` deprecation warning; it is not a test or runtime failure.

## Failsafe failure propagation

The Jenkins test entrypoint still runs Surefire, `failsafe:integration-test`, and `failsafe:verify` in one Maven invocation. A selected controller run with the two real assertions failing exited non-zero with `BUILD FAILURE`; teardown preserved that exit status. The suite was not excluded or reported as passing based only on XML output.

## Remaining 79-test inventory

The NIS Failsafe inventory remains 79 tests in 15 classes. The focused 16-controller subset above is 14 passing plus 2 failing; the other 63 tests retain their existing coverage and were not used to claim a full integration-suite pass.

| Class/group | Count | Remaining environment/dependency classification |
|---|---:|---|
| `BasicNodeSelectorITCase` | 2 | Repository-local in-process fixtures; `SecureRandom` makes some statistical inputs nondeterministic. |
| `BlockScorerITCase` | 13 | Repository-local scoring fixtures; random/time-like input dependency. |
| `DefaultHashCachePerformanceITCase` | 6 | Local in-memory cache; random keys and wall-clock performance thresholds. |
| `HttpConnectorITCase` | 3 | Public NEM peer/DNS/network dependency; external peer availability and latency. |
| `BlockDaoITCase` | 1 | Local disk H2 schema and large generated block/transaction seed. |
| `H2ITCase` | 1 | Existing stress test is source-level ignored; remains in suite inventory and is reported skipped. No new skip was added. |
| `H2StorageSpeedITCase` | 1 | Repository-local disposable H2 + production Flyway schema; random data and elapsed-time measurement. Passed in focused JDK 17/25 runs. |
| `MissingTransactionITCase` | 1 | Separate operator-supplied runtime-derived Mijin DB via `NIS_IT_MIJINNET_DB`; not the accepted Mainnet/Testnet artifacts. |
| `TransferDaoITCase` | 1 | Local disk H2 schema and seeded chain/transaction data. |
| `PoiImportanceCalculatorITCase` | 14 | Local in-process graph/account fixtures; random inputs and performance timing. One pre-existing timing assertion flaked once on Java 17, then passed in isolation and full rerun. |
| `TimeSynchronizationITCase` | 20 | Local simulated nodes; randomness, async scheduling, and simulated/wall-clock inputs. |

Thus the 63 outside the controller subset comprise repository-local groups with random/timing or large database-fixture requirements, 3 public-peer tests, and 1 Mijin-data test. The separate `H2ITCase` remains a pre-existing ignored stress case. Public-peer and Mijin prerequisites remain out of scope for this controller closure.

## Artifact integrity and status

Accepted artifact checks at the beginning and after validation matched exactly; the files were not opened or mounted:

| Artifact | Size | SHA-256 | mtime (epoch seconds) |
|---|---:|---|---:|
| `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

- Phase 2F: COMPLETE / CLOSED; unchanged.
- Phase 2K: COMPLETE / CLOSED; unchanged.
- Phase 2L-F: PARTIAL. Fourteen controller tests are validated, but two success-path tests need a valid funded-account fixture before they can pass without changing their semantics.
- Phase 2L overall / hosted Java 25 Jenkins acceptance: remains PARTIAL. Java 25 image publication, selectable Symbol shared-library mapping, and hosted Jenkins execution remain owned by the Symbol build-ci/shared-library and Jenkins infrastructure administrators.
