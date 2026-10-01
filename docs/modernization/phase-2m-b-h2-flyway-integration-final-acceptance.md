# Phase 2M-B — H2 / Flyway integration-level final acceptance

**Decision: COMPLETE — PHASE 2M H2 / FLYWAY MODERNIZATION CLOSED**

This closes Phase 2M database-runtime acceptance for the repository-controlled
NIS persistence and integration paths. It does not claim that the entire 79-test
Failsafe suite is green: tests requiring an unavailable external peer/Mijin data
set and unrelated random, timing, and performance-sensitive cases failed as
recorded below. Those failures were compared across Java 17 and Java 25 and did
not exercise H2/Flyway persistence behavior. No failures were suppressed.

Phase 2F and Phase 2K remain **COMPLETE / CLOSED**. Phase 2L-G remains
**COMPLETE / CLOSED**. Phase 2L-H remains **BLOCKED — EXTERNAL JENKINS JAVA 25
OWNER ACTION REQUIRED**, and Phase 2L overall remains **PARTIAL**. This work
does not claim hosted Jenkins Java 25 execution.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `0315ff8056c52386aaec2f4c72a3eba6b17c7264`
- Starting local and origin HEAD matched; divergence was `0/0`.
- Final tested implementation HEAD: `ddd8c091f` (`[nis] test: remove transactionless flush from DAO fixture`).
- The documentation/status commit follows the tested implementation commit;
  final local and origin HEAD are reported after push.
- Only implementation change: removed an unnecessary `Session.flush()` from
  `nis/src/it/java/org/nem/nis/dao/TestDatabase.java::readAccounts()`.
- Documentation changes: this record and the current Phase 2M status entry in
  `dependency-modernization.md`.
- No production logic, SQL, schema, migration, assertion, include/exclude,
  skip, protocol, or dependency version changed.
- The pre-existing `.gitignore` addition of `legacy/` was preserved unchanged,
  unstaged, and uncommitted.

## Dependency/runtime baseline

The effective NIS runtime dependency tree resolves exactly:

```text
com.h2database:h2:2.5.250
org.flywaydb:flyway-core:12.11.0
```

`mvn -B -o -f nis/pom.xml dependency:tree -Dscope=runtime -Dincludes=com.h2database:h2,org.flywaydb:*`
completed successfully and showed no duplicate H2 or Flyway artifact. The
Phase 2M-A record remains authoritative for the candidate rationale, clean
migration metadata comparison, accepted-database logical equivalence, and full
Mainnet/Testnet chain replay. This phase did not alter those dependencies or
repeat those destructive-copy experiments.

Runtime used for validation:

| Lane | Exact Java | Maven |
| --- | --- | --- |
| Compatibility | OpenJDK `17.0.20.1` | Apache Maven `3.8.7` |
| Primary | OpenJDK `25.0.4.1` | Apache Maven `3.8.7` |

The compiler release remains 17. Flyway migration SQL `V1.0.0`–`V1.0.7` and
the production schema remain unchanged.

## Integration suite and environment audit

The `nis` Jenkins entrypoint is `nis/scripts/ci/test.sh`. It provisions or
reuses the isolated runtime via `setup_test.sh`, runs Surefire and Failsafe in
one Maven invocation, executes `failsafe:verify`, and tears the runtime down
from an EXIT trap. The full Failsafe surface remained 79 tests in 15 classes;
no tests were excluded or newly skipped. Two skips are pre-existing: the
`H2ITCase.h2MemoryTest` `@Ignore` and
`TimeSynchronizationITCase.unstableClockWithoutPeriodicClockAdjustmentDoesNotProduceShiftInFriendlyEnvironment`
`@Ignore`.

The database/runtime-relevant repository-controlled group was:

| Group | Tests | Environment / result |
| --- | ---: | --- |
| Controller acceptance (Block, Push, Transfer, Account) | 16 | Repository-provisioned disposable Testnet NIS and deterministic funded genesis fixture; PASS on Java 17 and 25. |
| `BlockDaoITCase` | 1 | Disposable H2 seeded through `TestDatabase`; PASS on Java 17 and 25. |
| `TransferDaoITCase` | 1 | Disposable H2 seeded through the NIS integration fixture; PASS on Java 17 and 25. |
| `H2StorageSpeedITCase` | 1 | Fresh H2 schema through Flyway plus generated test records; PASS on Java 17 and 25. |
| **Database/controller group** | **19** | **19/19 PASS, 0 failures/errors/skips on both JDKs.** |

The wider suite also includes in-process random/statistical BlockScorer and POI
tests, wall-clock/scheduler TimeSynchronization tests, in-memory cache timing
thresholds, and a live public-peer connector test. They were run, not removed;
their results are listed below. These paths do not use H2/Flyway. The
`MissingTransactionITCase` is different: it requires an operator-supplied,
runtime-derived Mijin database containing a specific 250,000-transfer range.
No such artifact was supplied. The test uses raw JDBC to audit that external
dataset; it is not part of the repository-provisioned Mainnet/Testnet or
disposable Testnet H2/Flyway compatibility gate. The setup script failed closed
with its explicit precondition error. Accepted Mainnet/Testnet files were not
used as substitutes.

## Full Jenkins integration entrypoint results

Commands were run from `nis/` with `NIS_IT_MIJINNET_DB` unset:

```text
./scripts/ci/test.sh
```

This invokes `mvn test failsafe:integration-test failsafe:verify -B
-Dnis.it.home=...` and preserves Maven failure status. Both disposable runtime
setup and teardown completed. Failsafe verification reported BUILD FAILURE for
the unrelated/external categories below; it did not mask them.

| JDK | Surefire | Failsafe | Failures / errors / skips | Maven result |
| --- | --- | --- | --- | --- |
| Java 17 | 3,497; 0/0/0 | 79 | 6 / 2 / 2 | `failsafe:verify` BUILD FAILURE, expected from listed suite results |
| Java 25 | 3,497; 0/0/0 | 79 | 6 / 2 / 2 | `failsafe:verify` BUILD FAILURE, expected from listed suite results |

Observed Failsafe failures and classifications:

| Category | Java 17 | Java 25 | Classification |
| --- | --- | --- | --- |
| Public NEM peer | `HttpConnectorITCase`: 1 failure + 1 error; connection to `alice2.nem.ninja:7890` timed out / peer inactive | Same: 1 failure + 1 error | External DNS/network and live peer availability; not H2/Flyway. |
| Mijin persisted dataset | `MissingTransactionITCase`: 1 error before query because `NIS_IT_MIJINNET_DB` was not supplied | Same: 1 error | External runtime-derived Mijin DB prerequisite; accepted Mainnet/Testnet candidates were not substituted. |
| Random/statistical chain scoring | `BlockScorerITCase`: 3 failures | Same: 3 failures | Randomized in-memory competing-harvester assertions; no persistence or database access. Outcomes differ in random observed counts between runs/JDKs. |
| Timing/performance | `TimeSynchronizationITCase`: 1 failure; `DefaultHashCachePerformanceITCase`: 1 failure | `TimeSynchronizationITCase`: 0 failures; `DefaultHashCachePerformanceITCase`: 2 failures | Async/wall-clock simulation and in-memory performance thresholds. The differing case count is nondeterministic/environment-sensitive, not a database failure. |
| Pre-existing skips | 2 | 2 | The two existing `@Ignore` methods named above. |

The remaining Failsafe classes passed, including all controller cases and
database DAO/H2 cases. Java 25 had no database-specific failure. Java 17's
first full-suite run before the fixture correction also exposed the test-harness
issue below; the final Java 17 run after the correction no longer did.

## Focused persistence/controller validation and correction

Before the correction, the full Java 17 Failsafe run reported
`BlockDaoITCase.getBlocksAfterItCase` as an error:
`TransactionRequiredException: No active transaction`, originating from
`TestDatabase.readAccounts()` calling `session.flush()` after a read-only HQL
query and without a transaction. The flush performed no intended write. Removing
that single test-fixture flush restored the read-only setup behavior; no
production code or assertion changed.

After the correction:

- Java 17 focused Failsafe run: 19 tests, 0 failures, 0 errors, 0 skips; included
  all 16 controllers, BlockDao, TransferDao, and H2StorageSpeed; `failsafe:verify`
  passed.
- Java 25 full Jenkins run: the same 19 database/controller tests passed within
  the full 79-test execution; `BlockDaoITCase`, `TransferDaoITCase`,
  `H2StorageSpeedITCase`, and all four controller classes reported zero
  failures/errors/skips.
- Java 25 focused controller verification: 16 tests, 0 failures, 0 errors,
  0 skips, `failsafe:verify` passed.
- A first Java 25 focused selector invocation from the reactor root selected the
  wrong module and failed before test execution. It was corrected by using the
  NIS module/normal Jenkins entrypoint; that invocation error is not counted as
  a test failure.
- Two early Java 25 focused attempts ended before a complete Failsafe summary
  was produced and are not acceptance evidence. The final full Jenkins entrypoint
  run completed and is the authoritative 79-test result above.

The full Java 17 and Java 25 root `clean test` runs each discovered 6,228 tests
and passed with 0 failures, 0 errors, and 0 skipped. The root
`mvn -B -o clean package` run passed on both JDKs. Exact logs and exit results
were captured during this validation; package lifecycle is separate from the
full Failsafe entrypoint above.

## Phase 2M-A database guarantees carried forward

Phase 2M-A validated fresh unchanged production migration replay: 8/8
migrations `1.0.0` through `1.0.7`, final schema version `1.0.7`, 0 pending,
with no new migration. Its disposable persisted-copy comparison and NIS replay
preserved Mainnet and Testnet logical fingerprints, schema-history rows and
checksums, DAO reads, chain state, and replay results under Java 17 and Java 25.
This phase made only the read-only test-fixture correction above and did not
modify runtime persistence configuration or those database guarantees.

The carried-forward exact NIS replay fingerprints from Phase 2M-A are:

| Network | Marker | Genesis | Height / blocks | Tip | Replay score | Account / state cache |
| --- | --- | --- | ---: | --- | ---: | ---: |
| Mainnet | `0x68` | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | 2,001 | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `24964532368849513` | 1,377 / 1,377 |
| Testnet | `0x98` | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | 1,601 | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `79553937490635759` | 21 / 21 |

The production H2/Flyway fresh-schema path was exercised again by H2StorageSpeed
on both JDKs; it migrated the disposable H2 database successfully. Existing
production schema SQL and Flyway history were not changed.

Accepted originals were only stat'ed and hashed at the end of this phase; no
NIS/H2/Flyway/Hibernate process opened, copied into a runtime, or mounted them.
Current values match the accepted Phase 2M-A before values:

| Artifact | Size | SHA-256 | mtime epoch |
| --- | ---: | --- | ---: |
| Mainnet `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| Testnet `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

## Final status

**Phase 2M: COMPLETE / CLOSED.** The complete relevant repository-controlled
H2/Flyway persistence and disposable controller integration group passed on
Java 17 and Java 25, with Failsafe verification active. The non-green full
79-test Failsafe run is reported without qualification as green; its failures
are individually classified above. The external Mijin artifact and public
peer are outside the repository-controlled H2/Flyway gate; random/time/cache
failures are database-independent. No test was weakened, skipped, excluded,
or masked by this phase.

Phase 2L-H remains independently **BLOCKED** until external owners publish a
Java 25 `build-ci` image, expose a selectable Symbol shared-library mapping,
and execute/record the hosted NIS Jenkins job on that image. This database
acceptance does not satisfy that hosted-CI requirement.
