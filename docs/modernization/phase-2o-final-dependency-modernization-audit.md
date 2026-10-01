# Phase 2O — Final Dependency Modernization Audit

**Decision: COMPLETE / CLOSED — no repository-controlled dependency update required for the accepted Java 25 baseline remains.**

This audit classifies remaining direct dependencies and explicitly configured
Maven plugins as UPDATE, KEEP, or DEFER. The only updates justified by the
current support/security evidence and verified against the NIS database/runtime
contract were Hibernate ORM and H2. Other available updates are not required
for Java 25 correctness and are deferred to focused future work.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `0315ff8056c52386aaec2f4c72a3eba6b17c7264`
- Actual starting HEAD: `a8790c7823172d2eb8f6045dd6e88f4eb0463440`
- The actual HEAD is the requested Phase 2M-B line plus its accepted
  implementation and documentation commits; local and origin both started at
  the actual HEAD, divergence `0/0`.
- Pre-existing `.gitignore` change: its `legacy/` entry was preserved byte for
  byte, unstaged, and uncommitted.
- Implementation change: Hibernate ORM `7.2.25.Final` → `7.4.11.Final` and H2
  `2.5.250` → `2.5.252` in `nis/pom.xml`.
- No migration SQL, application schema, protocol, consensus, serialization, or
  Java compiler release was changed.

## Final disposition

| Disposition | Dependencies / plugins | Reason |
| --- | --- | --- |
| **UPDATE** | Hibernate ORM `7.2.25.Final` → `7.4.11.Final` | Hibernate's 7.4 line is the current stable ORM line; 7.2 is limited-support. 7.4.11 supports Java 17/21/25, Jakarta Persistence 3.2, and EE11. NIS runtime, persisted-chain, DAO, controller, and regression probes passed with this version. |
| **UPDATE** | H2 `2.5.250` → `2.5.252` | H2 2.5.252 is the current patch release observed on 2026-10-01 and includes fixes for database reopening/corruption, rollback notifications, deserialization filtering, URL injection, and SecureFileStore reads. Fresh migration and converted persisted-copy probes passed. |
| **KEEP** | Spring Framework `7.0.9`; Jakarta Persistence `3.2.0`; Jetty `12.1.13` EE11; Jakarta EE 11 APIs; Hibernate Validator `9.1.4.Final`; Expressly `6.0.0`; Flyway `12.11.0`; Byte Buddy as resolved by Hibernate `7.4.11`; Bouncy Castle `1.86` | Coherent accepted runtime stack; no newer GA update is required to satisfy Java 25 or compatibility evidence. Spring 7.1 was only a milestone candidate at audit time, so the production 7.0 GA line remains selected. Hibernate 7.4 resolves Byte Buddy `1.18.8` transitively; runtime/package inspection found one version and Hibernate startup passed. |
| **KEEP** | JavaEWAH `1.2.3`; json-smart `2.6.0`; commons-io `2.22.0`; commons-math `3.6.1`; HttpAsyncClient `4.1.5`; WireMock standalone `3.13.2`; JUnit `4.13.2`; MTJ `1.0.4` (test scope) | No Java 25 compatibility defect or acceptance failure tied to these versions. HTTP architecture replacement and JUnit 5 migration are explicitly out of scope. |
| **DEFER** | commons-codec `1.22.0` → `1.22.1`; commons-collections4 `4.5.0` → `4.6.0`; commons-lang3 `3.20.0` → `3.21.0`; Mockito `5.22.0` → `5.24.0` | Versions plugin reports newer versions, but there is no demonstrated Java 25/persisted-state blocker. Codec's listed changes do not address the NIS `Base32`/`Base64` constructors exercised here; collections is not compatibility-required; Lang 3.21's newer-JDK symbols are irrelevant to Java 25; Mockito is test-only and current tests pass. Handle in isolated audits with direct regression coverage. |
| **DEFER** | `core/pom.xml` stale unused `spring.version=4.3.30.RELEASE`; Maven Enforcer/minimum-Maven policy; inherited Super-POM auxiliary plugins; NIS Velocity plugin with no executions | Cleanup/build reproducibility policy rather than a Java 25 compatibility requirement. Maven 3.8.7 and the current explicitly pinned plugins completed the gates. |

All explicitly configured Maven plugins are version-pinned. The Versions
Maven Plugin audit found those declared versions current at the audit point;
no plugin update is needed for Java 25. The audit noted a plugin ecosystem
minimum Maven version of 3.6.3; the repository's Maven 3.8.7 exceeds it.
Adding an Enforcer rule or changing the Maven wrapper/toolchain policy is
deferred because it is not a demonstrated blocker.

## Effective runtime stack

The final NIS runtime graph resolves:

```text
Spring Framework          7.0.9
Hibernate ORM              7.4.11.Final
Jakarta Persistence        3.2.0
Jetty                      12.1.13 (EE11)
Hibernate Validator        9.1.4.Final
Byte Buddy                 1.18.8 (Hibernate runtime dependency)
H2                         2.5.252
Flyway                     12.11.0
Bouncy Castle              1.86
```

The compiler release remains 17 in Core, Deploy, Peer, and NIS. Java 25 is the
primary runtime and Java 17 remains the compatibility runtime.

## Candidate evidence

- Hibernate ORM [7.4 release and compatibility information](https://hibernate.org/orm/releases/7.4/)
  identifies 7.4.11.Final and its supported Java/Jakarta compatibility. The
  [ORM release matrix](https://hibernate.org/orm/releases/) describes 7.2 as
  limited support and 7.4 as the current stable series.
- The [H2 2.5.252 release](https://github.com/h2database/h2database/releases/tag/version-2.5.252)
  was published on 2026-09-23. The release notes list database/security fixes;
  the selected patch was tested on both supported JDK lanes.
- Spring's [version policy](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions)
  identifies 7.0.x as the production GA line and 7.1 milestones as preview
  releases. Spring remains at 7.0.9.
- Flyway's [H2 support reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/h2)
  and the Phase 2M-A record document the H2/Flyway support warning and the
  empirical compatibility evidence. Flyway remains at 12.11.0; no extra H2
  database module was required by the resolved runtime graph.

H2 file-format transitions are not performed by opening an older database
file directly with a newer engine. The established offline SCRIPT/RUNSCRIPT
conversion policy on disposable copies remains in effect. The accepted
Mainnet/Testnet originals were never opened by the candidate H2 engine.

## Validation evidence

The candidate H2 2.5.252 and Hibernate 7.4.11 stack was checked against
converted disposable copies of the accepted H2 2.5.250 databases. Per-table
logical fingerprints matched after normalizing engine/JDBC version fields.
The production NIS replay probe then ran on Java 17 and Java 25 for both
networks. Genesis, height, tip, replay score, account/state/namespace caches,
block adjacency, and Flyway state matched the accepted Phase 2M-A values.
Spring closed, H2 shut down, and no unexpected non-daemon threads remained.

Fresh migration replay applied the unchanged eight migrations
`V1.0.0`–`V1.0.7`, ended at `1.0.7`, and had zero pending migrations. The
focused production-equivalent controller/DAO/H2 Failsafe group passed 19/19
on both JDKs with `failsafe:verify` active. The full-reactor unit/regression
and package checks passed on both JDKs, as recorded in Phase 2P.

## Boundary and deferred work

Phase 2L-H remains independently **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER
ACTION REQUIRED**, and Phase 2L overall remains **PARTIAL**. Hosted Jenkins
Java 25 image publication, shared-library mapping, and hosted execution are
external follow-up; they do not create a repository-controlled dependency
blocker for this audit.

Further patch updates without a demonstrated requirement, Maven policy
cleanup, JUnit 5 migration, HTTP client replacement, and unrelated dependency
modernization are deferred to Phase 3 or their own focused phases.

**Phase 2O: COMPLETE / CLOSED.** The two justified dependency updates passed
the database and Java 17/25 acceptance evidence. No other repository-controlled
dependency update is required to make the accepted Java 25 baseline work.
