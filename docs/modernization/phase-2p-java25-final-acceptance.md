# Phase 2P — Java 25 Final Acceptance

**Decision: COMPLETE / CLOSED — PHASE 2 JAVA 25 MODERNIZATION.**

Phase 2P closes repository-controlled NIS Java 25 build, test, package,
runtime, and dependency compatibility. The external hosted Jenkins Java 25
image/mapping remains tracked under Phase 2L-H and is not represented as
validated here.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `0315ff8056c52386aaec2f4c72a3eba6b17c7264`
- Actual starting HEAD: `a8790c7823172d2eb8f6045dd6e88f4eb0463440`
- The actual starting revision includes the accepted Phase 2M-B implementation
  and documentation commits. Local and origin both started at this revision
  (`0/0` divergence).
- Java 25: OpenJDK `25.0.4.1`; Java 17: OpenJDK `17.0.20.1`; Maven:
  Apache Maven `3.8.7` for both.
- Compiler `<release>` remains `17` in all four application modules.
- The existing `.gitignore` `legacy/` change was preserved byte-for-byte,
  unstaged, and uncommitted.
- Implementation change: `nis/pom.xml` updates Hibernate ORM to
  `7.4.11.Final` and H2 to `2.5.252`. Validation and audit records are added
  separately. No production code, migration SQL, schema, protocol, consensus,
  serialization, or accepted legacy database artifact was changed.

## Runtime and dependency acceptance

The effective runtime graph resolves one coherent stack:

```text
Spring 7.0.9
Hibernate ORM 7.4.11.Final
Jakarta Persistence 3.2.0
Jetty 12.1.13 EE11
Hibernate Validator 9.1.4.Final
Byte Buddy 1.18.8 (Hibernate runtime dependency)
H2 2.5.252
Flyway 12.11.0
Bouncy Castle 1.86
```

The runtime graph and packaged `nis/target/libs` contain one H2 and one
Flyway, and no duplicate old generation. They contain no legacy
`javax.persistence`, `javax.servlet`, or legacy Bean Validation API; no Spring
5/6, Hibernate ORM 5/6, Jetty EE8, Mockito, JUnit, WireMock, or Spring Test
runtime leakage. Jetty/Jakarta APIs are the intended EE11 generation. The
dependency tree and packaged libraries agree. Byte Buddy 1.18.8 is the
version resolved by Hibernate 7.4.11; exactly one Byte Buddy runtime JAR is
packaged.

Phase 2O selected only evidence-based updates: Hibernate 7.2.25 → 7.4.11 and
H2 2.5.250 → 2.5.252. Spring remains on the 7.0 GA line; Flyway remains
12.11.0. Other newer direct-library patches and unrelated tooling cleanup
were classified as KEEP or DEFER in the [Phase 2O audit](phase-2o-final-dependency-modernization-audit.md).

## Java 25 and Java 17 build gates

The root clean-test runs completed on both JDKs. First full executions each
showed one known nondeterministic test failure, both of which passed isolated
rerun; the subsequent full clean reruns passed. These initial results are
retained rather than hidden.

| Runtime | Command | Final result |
| --- | --- | --- |
| Java 25 | `mvn -B -o clean test` | PASS: 6,228 tests; 0 failures, 0 errors, 0 skipped. Per-module totals: Core 2,364; Deploy 61; Peer 306; NIS 3,497. |
| Java 25 | `mvn -B -o -DskipTests clean package` | PASS. Packaging-only gate; it is not used as a test result. |
| Java 17 | `mvn -B -o clean test` | PASS on full rerun: 6,228 tests; 0 failures, 0 errors, 0 skipped. Same per-module totals. |
| Java 17 | `mvn -B -o -DskipTests clean package` | PASS. |

Initial Java 25 clean-test failure: randomized `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance`; isolated rerun passed 1/1 and full rerun passed. Initial Java 17 failure: timing-sensitive `AsyncTimerTest.visitorIsNotifiedOfSuccessfulCompletions`; isolated rerun passed 1/1 and full rerun passed. Neither was introduced by the DB dependency changes. Java 25 emitted the known Maven/Guava `sun.misc.Unsafe` deprecation warning; there was no related runtime failure. An initial sandbox run could not bind local test sockets; reruns with loopback access executed the suite normally.

## Production runtime and persisted-chain acceptance

The production-equivalent NIS replay harness used disposable database copies
converted from the accepted H2 2.5.250 donor copies to H2 2.5.252 using H2
SCRIPT/RUNSCRIPT. Accepted originals were never opened, copied into a runtime,
mounted, or modified. Logical database fingerprints matched across the
conversion; runtime probes passed independently on Java 17 and Java 25.

| Network | Marker | Genesis | Height / blocks | Tip | Replay score | Account / state cache |
| --- | --- | --- | ---: | --- | ---: | ---: |
| Mainnet | `0x68` | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | 2,001 | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `24964532368849513` | 1,377 / 1,377; namespace 1 |
| Testnet | `0x98` | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | 1,601 | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `79553937490635759` | 21 / 21; namespace 1 |

Both networks passed full adjacent-block linkage validation, representative
DAO traversal, and Flyway checks: version `1.0.7`, 8 applied, 0 pending, no
migration executed. Spring context close and H2 shutdown completed; no
unexpected non-daemon runtime threads remained. Converted/runtime disposable
file hashes changed as expected for physical engine representations; logical
row/state fingerprints and replay outputs matched.

The funded disposable Testnet controller fixture and focused H2/DAO paths
were run through the repository setup/teardown and normal Failsafe verification
path. Java 17 and Java 25 each passed 19/19 tests (16 controller tests, one
BlockDao, one TransferDao, one H2StorageSpeed); failures/errors/skips were
0/0/0. `failsafe:verify` passed on both JDKs.

## Full 79-test Failsafe surface

The complete 79-test `nis` Failsafe suite was also executed with
`failsafe:integration-test` and `failsafe:verify`; no tests were excluded.
This wider suite did **not** pass completely, and its failures are not hidden
or attributed to H2/Flyway without evidence:

| Runtime | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Java 17 | 79 | 5 | 2 | 2 | `failsafe:verify` BUILD FAILURE |
| Java 25 | 79 | 7 | 2 | 2 | `failsafe:verify` BUILD FAILURE |

The failure categories were:

- `HttpConnectorITCase`: public NEM peer timeout/inactive (`1` failure and
  `1` error on each JDK).
- `MissingTransactionITCase`: required operator-supplied, runtime-derived
  Mijin database was absent (`1` error on each JDK); accepted Mainnet/Testnet
  databases were not substituted.
- `BlockScorerITCase`: random/statistical in-memory assertions (`2` Java 17,
  `4` Java 25 failures in these runs; prior observations also varied).
- `DefaultHashCachePerformanceITCase`: environment-sensitive performance
  thresholds (`2` Java 17, `1` Java 25 failures).
- `TimeSynchronizationITCase`: wall-clock/scheduling behavior (`1` Java 25
  failure; none Java 17 in these final runs).
- Two pre-existing ignored tests remained skipped on each JDK.

All controller, DAO, and H2 cases passed in the full runs. The varying
database-independent statistical, performance, and wall-clock failures match
the already recorded Phase 2M-B environment-sensitive suite boundary; the
public peer and Mijin data require external resources. Failsafe remained
fail-closed. No test was disabled, excluded, retried into success, or had its
assertion weakened.

## Original artifact integrity

Before and after all validation, only filesystem metadata/hash operations were
performed on the accepted original files. Values remained identical:

| Artifact | Size | SHA-256 | mtime epoch |
| --- | ---: | --- | ---: |
| Mainnet `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| Testnet `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

No DB artifact was staged or committed.

## Phase status

- Phase 2F: **COMPLETE / CLOSED**
- Phase 2K: **COMPLETE / CLOSED**
- Phase 2L-G: **COMPLETE / CLOSED**
- Phase 2L-H: **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED**
- Phase 2L overall: **PARTIAL**
- Phase 2M: **COMPLETE / CLOSED**
- Phase 2N: **COMPLETE / CLOSED**
- Phase 2O: **COMPLETE / CLOSED**
- Phase 2P: **COMPLETE / CLOSED**

The hosted Jenkins Java 25 image publication, shared-library mapping, and
hosted execution remain an external infrastructure follow-up. This is not
claimed as hosted evidence and is not a repository-controlled Phase 2 gate.
The broader external/statistical/timing Failsafe limitations remain explicitly
recorded; the database/framework/controller gates and full root test/package
gates passed. No repository-controlled Java 25 dependency or runtime blocker
was found.

**Final decision: COMPLETE / CLOSED — PHASE 2 JAVA 25 MODERNIZATION.** The
accepted repository-controlled build, test, package, runtime, database,
persisted-chain, controller/DAO, and dependency graph gates pass on Java 25,
with Java 17 compatibility retained. `_symbol` is preserved; compiler release
17 is preserved. Phase 2L-H remains independently blocked on external hosted
Jenkins ownership.
