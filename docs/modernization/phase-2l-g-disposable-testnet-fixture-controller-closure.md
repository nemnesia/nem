# Phase 2L-G — Disposable Testnet Fixture / Controller Closure

## Status

**COMPLETE — DISPOSABLE TESTNET FIXTURE CONTROLLER VALIDATION CLOSED**

The repository-owned loopback runtime now generates a deterministic, isolated Testnet-version genesis that funds the existing controller test account through a signed genesis transfer. All 16 controller integration tests pass on Java 17 and Java 25, twice per JDK from clean provisioning. Assertions and production account/balance checks remain unchanged.

This closes Phase 2L-G only. Phase 2L overall remains PARTIAL pending the externally owned Java 25 `build-ci` image, Symbol shared-library mapping, and hosted Jenkins run. Phase 2F and Phase 2K remain COMPLETE / CLOSED. The historical Phase 2L-F PARTIAL record is unchanged.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `51f52d0e4e661ecaeee8156fc171f5c643012548`
- Actual starting HEAD: `51f52d0e4e661ecaeee8156fc171f5c643012548`
- Starting local/origin divergence: 0 ahead, 0 behind
- Final implementation HEAD: `b83ca4d4c` (`[nis] test: add disposable Testnet controller fixture`)
- Documentation is a separate follow-up commit.
- Working tree at start contained the pre-existing `.gitignore` `legacy/` addition. It was not edited, staged, or committed.
- Java: `17.0.20.1` and `25.0.4.1`; Maven: `3.8.7`; compiler release remains `17`.

## Phase 2L-F root cause

The success-path tests use the existing deterministic controller test account, whose Testnet address is `TBSANAAURPWQDBZQSSQ5ZP3NYINUBSQY4RKYI6I7`.

- `AccountControllerITCase.unlockSuccessReturnsOk` received HTTP 400 `FAILURE_UNKNOWN_ACCOUNT` because the prior isolated runtime contained only genesis state and did not allocate funds to this account.
- `TransferControllerITCase.transferCorrectTransaction` received HTTP 400 `FAILURE_INSUFFICIENT_BALANCE` for the same absent/unfunded sender.

The controller checks were correct. The fixture lacked the persisted account state required by both success paths.

## Fixture design and processing path

`DisposableTestnetGenesis`, under `nis/src/it`, deterministically creates a test-only genesis resource inside the current `nis/target/nis-it.*` temporary home. It uses a custom private NIS network name with the Testnet network byte `0x98` and Testnet address prefix `T`; it is an isolated Testnet-version test chain, not a copy of public Testnet history or an accepted runtime database.

The generator:

1. Uses fixed test-only nemesis signing material and a fixed generation hash.
2. Derives the existing controller fixture account from `AcceptanceTestConstants.PRIVATE_KEY` and verifies the public key and Testnet address compatibility.
3. Creates a normal height-1 NEMESIS block signed by the test nemesis.
4. Adds a signed Testnet-version transfer transaction allocating `1,000,000 NEM` to the controller account, then signs the block.
5. Serializes the block with the production binary serializer and writes it to the disposable runtime classpath.
6. Appends the matching private test-network properties so production `NisMain` / `BlockAnalyzer` load and execute the genesis block through their normal bootstrap and block observer path.

The production `WeightedBalancesObserver` fully vests transfers originating from the nemesis block. The account therefore has `1,000,000 NEM` vested and available after genesis replay, well above the transfer request of `42 NEM` plus its `4 NEM` fee and above the configured harvesting eligibility threshold. No SQL account/balance row was written directly, no test-only balance bypass was added, and transaction validation still runs through the normal controller/NIS path.

The fixture credentials are deterministic test data only, have no production funds, and must not be reused outside this isolated CI runtime. The generated genesis binary was byte-for-byte reproducible across two clean setups: both SHA-256 values were `320ba0f80a32e63226ff9a4bdea9eb443209dfa1575e46578b86db60bb28e7b8`.

`setup_test.sh` now compiles the integration test source containing the generator, creates the runtime/test classpath, generates the binary and network properties, starts the normal `CommonStarter`, and retains the existing bounded readiness/cleanup behavior. The runtime home and H2 store remain disposable and separate for every run.

## Controller and H2 validation

The controller subset is the same four Failsafe classes as Phase 2L-F: Account (2), Block (5), Push (1), and Transfer (8), totaling 16 tests. Each run started from a newly created temporary home and generated genesis; the previous NIS process and DB were torn down first. Every selected run executed `failsafe:integration-test` followed by `failsafe:verify`.

| JDK | Run 1 | Clean reprovision repeat | Result |
|---|---|---|---|
| Java 17 | 16 tests, 0 failures, 0 errors, 0 skipped | 16 tests, 0 failures, 0 errors, 0 skipped | PASS twice |
| Java 25 | 16 tests, 0 failures, 0 errors, 0 skipped | 16 tests, 0 failures, 0 errors, 0 skipped | PASS twice |

The first broad Maven command used to reach the selected Failsafe classes ran Surefire first and stopped on the pre-existing randomized `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance`; Failsafe was not reached in that invocation. The focused runs then used `jacoco:prepare-agent`, `dependency:properties`, `failsafe:integration-test`, and `failsafe:verify` directly after clean runtime provisioning. All four focused Failsafe runs succeeded; no retry within a run or failure masking was used.

The focused H2 `H2StorageSpeedITCase` also passed on both JDKs: 1 test, 0 failures, 0 errors, 0 skipped, with `failsafe:verify`.

## Full reactor regression

No unit test was added; the integration fixture generator has no test annotation. Full reactor test count remains 6,228 (the 6,227 Phase 2L-A baseline plus the Phase 2L-F missing-route unit test).

| JDK | Command | Final successful run |
|---|---|---|
| Java 17 | `mvn -B -o clean test` | 6,228 tests, 0 failures, 0 errors, 0 skipped — PASS |
| Java 17 | `mvn -B -o clean package` | 6,228 tests, 0 failures, 0 errors, 0 skipped — PASS on full rerun |
| Java 25 | `mvn -B -o clean test` | 6,228 tests, 0 failures, 0 errors, 0 skipped — PASS |
| Java 25 | `mvn -B -o clean package` | 6,228 tests, 0 failures, 0 errors, 0 skipped — PASS on full rerun |

Both initial `clean package` runs had one failure in the existing asynchronous `AsyncTimerTest.closeStopsRefreshing` (Core module), with 0 errors and 0 skipped. Java 17's isolated 16-test `AsyncTimerTest` run passed. Java 25's first isolated 16-test run had one timing assertion failure in `initialDelayIsRespected`; its immediate isolated rerun passed all 16. Full package reruns then passed on both JDKs. These timing results are retained here; no timer test was altered, skipped, or retried as part of the controller result. The known Java 25 Guava `sun.misc.Unsafe` deprecation warning remains non-fatal.

The 79 Failsafe integration tests were not all claimed as passing. The remaining 63 retain the Phase 2L-E inventory and dependencies: public NEM peers, the separate Mijin database, random/statistical and timing/performance cases, plus local H2 seeded-data cases. No Failsafe include/exclude or Jenkins gate change was made.

## Accepted artifact integrity

The accepted runtime-derived Mainnet/Testnet databases were not opened, mounted, copied, migrated, or used to start the fixture. Start and end size, SHA-256, and mtime checks matched:

| Artifact | Size | SHA-256 | mtime (epoch seconds) |
|---|---:|---|---:|
| `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

## Commits, files, and remaining scope

- Implementation commit: `[nis] test: add disposable Testnet controller fixture`
- Validation documentation: this Phase 2L-G record, committed separately.
- Implementation files: `nis/scripts/ci/setup_test.sh`; `nis/src/it/java/org/nem/nis/controller/acceptance/DisposableTestnetGenesis.java`.
- Phase 2F: COMPLETE / CLOSED.
- Phase 2K: COMPLETE / CLOSED.
- Phase 2L-F: historical PARTIAL record remains unchanged.
- Phase 2L-G: COMPLETE — disposable Testnet fixture and controller validation closed.
- Phase 2L overall: PARTIAL. Java 25 `build-ci` image publication, Symbol shared-library Java 25 mapping, and hosted Jenkins execution remain external-owner actions; this repository change does not claim to complete them.
