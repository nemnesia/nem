# Phase 2J-F — Jakarta EE 11 merge and post-merge validation

## Result

**COMPLETE — PHASE 2J MERGED AND POST-MERGE VALIDATED.** The Phase 2J integration branch was merged into `agent/nis-phase0-baseline` with a non-fast-forward merge. The merged software/runtime stack passed post-merge Java 17 and Java 25 validation. This is not acceptance of production Mainnet/Testnet data.

## Git baseline and merge

- Repository: `nemnesia/nem`
- Target: `agent/nis-phase0-baseline`
- Source: `agent/nis-phase2j-jakarta-integration`
- Requested and actual target start: `03ba8ed2ea20859c384cf52744e80e0ea461f984`
- Requested and actual source tip: `1bd0f71103b094fe2b7e3eae86f82a86834ee114`
- Merge base: `03ba8ed2ea20859c384cf52744e80e0ea461f984`
- Merge commit: `d68997426f0d1fc7bc2fa0046657b453b4b20dcf` (`[nis] merge Jakarta EE11 integration`)
- Final validation-record commit: a documentation-only commit follows the merge; see target branch history for its final SHA.
- Merge conflicts: none
- Merge diff: 97 files changed, 1,530 insertions, 674 deletions
- The target had no new commit beyond the expected base. The integration source was the accepted Phase 2J-E tip.
- The pre-existing `.gitignore` edit adding `legacy/` was not staged or included. The integration worktree's pre-existing `docs/modernization/phase-2i-h-poc/node_modules/` was not touched or included.

The merge diff contains the accepted Jakarta / EE11 implementation and its phase records. No generated or large binary files, database snapshots, or `node_modules` paths were added. No Flyway SQL/schema path changed. `git diff --check 03ba8ed2ea20859c384cf52744e80e0ea461f984..d68997426f0d1fc7bc2fa0046657b453b4b20dcf` passed.

## Runtime dependency and source audit

The production runtime versions in the merged tree are:

- Spring Framework `7.0.9`
- Hibernate ORM `7.2.25.Final`
- Jetty `12.1.13` EE11
- Jakarta Persistence `3.2.0`
- Jakarta Servlet `6.1.0`
- Jakarta WebSocket `2.2.0`
- Jakarta Validation `3.1.1`

The reactor-aware runtime dependency tree was generated with:

```bash
mvn -B -pl nis -am -Dverbose dependency:tree -Dscope=runtime
```

It resolves the target Spring, Hibernate, Jakarta API, and Jetty EE11 coordinates. The audit found no Spring 5/6, Hibernate ORM 5/6, Jetty EE8 runtime, or legacy `javax.persistence`, `javax.servlet`, `javax.websocket`, or Bean Validation API coordinates in the NIS runtime tree. No duplicate Jakarta API generations were found. Production Java source under `deploy/src/main` and `nis/src/main` has no references to those migrated `javax.*` packages or `org.springframework.orm.hibernate5`.

The packaged NIS runtime distribution contains 96 JARs. It includes Spring 7.0.9, Hibernate ORM 7.2.25.Final, and Jetty 12.1.13 EE11 JARs. The package scan found no Spring 5/6, Hibernate 5/6, Jetty EE8, or migrated legacy `javax.*` API artifact coordinates.

The fresh-cache build below resolved artifacts through Maven Central (`https://repo.maven.apache.org/maven2/`). No custom artifact repository was needed. `http://nem.io` strings in module POMs are organization metadata, not repository declarations.

## Post-merge validation

The complete test suite has 6,227 discovered tests: Core 2,364; Deploy 61; Peer 306; NIS 3,496.

| Runtime | Command | Result |
| --- | --- | --- |
| Java 17 | `mvn -B clean test` | 6,227 tests; 0 failures, 0 errors, 0 skipped; success |
| Java 17 | `mvn -B clean package` | success |
| Java 17, fresh Maven local repository | `mvn -B -Dmaven.repo.local=/tmp/phase2j-f-central-m2 clean test` | success; 6,227 tests; Central downloads observed |
| Java 25 | `mvn -B clean test` | 6,227 tests; 0 failures, 0 errors, 0 skipped; success |
| Java 25 | `mvn -B clean package` | success |

Runtime probes were also run on both Java 17 and Java 25 from clean reactor outputs:

```bash
mvn -B clean -pl nis -am -Dtest=Ee11ProductionWebRuntimeProbe,Hibernate7PersistenceRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Each run passed 4 tests (one production-equivalent EE11 web probe and three Hibernate 7 persistence tests), with no failures/errors/skips. The web probe starts the real Jetty EE11 / Spring application path and exercises live REST, WebSocket upgrade, STOMP connect/message/disconnect, SockJS XHR, cleanup, and shutdown. The persistence probe uses synthetic H2 and checks bootstrap, mapping, write/read, transaction behavior, and shutdown. These probes do not validate trusted real-network database provenance.

An initial non-clean targeted invocation ran those same four tests successfully, then JaCoCo report generation failed with `malformed input around byte 22`. The clean targeted runs on Java 17 and Java 25 both completed successfully, including JaCoCo reporting. The one-off report failure is recorded; its exact cause was not established. It did not recur on clean execution. Java 25 Maven launcher output included the known Guava `sun.misc.Unsafe` terminal-deprecation warning.

The clean test/package runs use compiler release 17 while executing under the respective JDK. The subsequent artifact-only `mvn -B -DskipTests package` completed successfully to recreate `nis/target/libs` for the JAR audit; it was not counted as a test run.

## Schema, migration, and behavior boundary

No Flyway migration SQL, database schema resource, or migration-history expectation changed in the merge. No protocol, serialization, consensus, or network-constant changes were identified in the accepted Phase 2J diff. Jakarta-side entity/API changes remain Java-side framework porting and are not a database migration. Runtime persistence validation used synthetic/in-memory H2 only.

## Hosted CI and independent Phase 2F gate

Hosted CI was **not verified** for the final target commit. `gh auth status` succeeded, but `gh run list --branch agent/nis-phase0-baseline --limit 10` failed with `error connecting to api.github.com`; no run result could be retrieved. Local validation is not represented as hosted CI success.

Phase 2F remains **BLOCKED — external trusted artifact/evidence owner required**. Trusted Mainnet/Testnet snapshot provenance, custody, quiescence, and independently trusted checkpoint evidence remain outstanding. The Phase 2J merge and synthetic runtime probes do not close that gate and do not establish real Mainnet/Testnet production-data compatibility.

## Final disposition

Phase 2J Jakarta EE11 software/runtime integration is merged and post-merge validated. The target branch is suitable for the Phase 2J integration acceptance scope. Production rollout and real persisted-chain data acceptance remain separate decisions, with Phase 2F still blocked.
