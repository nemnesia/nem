# Phase 2J-E — Jakarta / EE11 integration final acceptance

## Decision

**COMPLETE — READY TO MERGE.** The integration branch is internally consistent for the audited Spring 7 / Hibernate 7 / Jakarta EE 11 scope. This is a merge-readiness decision for Phase 2J; it is not a production rollout approval and does not close Phase 2F.

## Repository and branch state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase2j-jakarta-integration`
- Requested starting HEAD: `79bc7d20d000b59ffb33c2e17b34dabd807e2331`
- Actual starting HEAD: `79bc7d20d000b59ffb33c2e17b34dabd807e2331`
- At start, `origin/agent/nis-phase2j-jakarta-integration` pointed to the same SHA. A final fetch also succeeded and confirmed it still pointed to `79bc7d20d000b59ffb33c2e17b34dabd807e2331` before this record was added.
- Base / merge-base: `agent/nis-phase0-baseline` / `03ba8ed2ea20859c384cf52744e80e0ea461f984`.
- No merge, rebase, cherry-pick, or base-branch change was performed.
- The only initial worktree item was the pre-existing untracked `docs/modernization/phase-2i-h-poc/node_modules/`; it was left untouched and uncommitted.
- Implementation and runtime evidence commits already on the branch: `107a5b78c` (persistence port), `fa8e4a876` (EE11 runtime probe), `de9314d1f` (Phase 2J-D record), and `79bc7d20d` (probe-isolation documentation clarification). This acceptance record is a separate documentation-only change.
- Audited implementation HEAD before this record: `79bc7d20d000b59ffb33c2e17b34dabd807e2331`. The final Git HEAD carrying this record is reported by the completion commit and final report.

## Scope and diff audit

The branch contains 96 changed paths relative to the base: the two `deploy` / `nis` POMs; deploy bootstrap/error-handler code; NIS Jakarta web bootstrap, Hibernate/JPA mappings and DAO query/persistence adaptations; related tests; and Phase 2J-B/C/D evidence. The scope is the coordinated EE11/Spring 7/Hibernate 7 port and its validation.

The production source changes are confined to `deploy` and `nis`. No `core` consensus/cryptography/serialization code, `peer` protocol code, network constants, genesis resources, migration SQL, or database schema resources changed. No unrelated dependency modernization is present. `git diff --check agent/nis-phase0-baseline...HEAD` passed.

The persistence changes are the API/mapping/query adaptations documented in Phase 2J-C; the web bootstrap change is the REST DispatcherServlet async-support setting documented in Phase 2J-D. Phase 2J-C's earlier “REST/WebSocket not yet run” statement described that phase's checkpoint; Phase 2J-D subsequently supplied production-equivalent live transport evidence. Phase 2J-B's failed exploratory compile is likewise historical and was resolved by the later C/D implementation; none of those records were rewritten.

No Flyway SQL or schema DDL change was found. No REST or application-level WebSocket/STOMP contract change was identified. Phase 2J-C records the persistence-specific API changes and tests, including Hibernate 7's explicit `persist` and query adaptations.

## Target stack and dependency graph

The packaged NIS runtime resolves:

| Component | Resolved version |
|---|---:|
| Java compile baseline | 17 (`--release 17`) |
| Spring Framework | 7.0.9 |
| Hibernate ORM | 7.2.25.Final |
| Hibernate Validator | 9.1.4.Final |
| Jakarta Persistence | 3.2.0 |
| Jakarta Servlet | 6.1.0 |
| Jakarta WebSocket | 2.2.0 |
| Jakarta Validation | 3.1.1 |
| Jetty | 12.1.13 EE11 |
| H2 / Flyway | 2.2.220 / 9.22.3 (unchanged) |

The reactor-aware command `mvn -B -pl nis -am -Dverbose dependency:tree -Dscope=runtime` was used so `nis` sees the current reactor `deploy` artifact rather than an older locally installed module. Its NIS runtime graph resolves Spring 7.0.9, Hibernate ORM 7.2.25.Final, and the Jakarta API versions above; Jetty EE11 modules are the only EE-specific Jetty server modules in that NIS graph. Maven resolves one version per coordinate. The tree shows ordinary transitive version mediation for unrelated libraries (for example ASM, Commons Logging, and JBoss Logging), but no mixed Spring, Hibernate, or Jetty generation.

The packaged `nis/target/libs` contains 96 JARs. Filename inventory and the reactor graph show Spring 7.0.9, Hibernate ORM 7.2.25.Final, Jakarta Persistence 3.2.0, Servlet 6.1.0, WebSocket 2.2.0, Validation 3.1.1, and Jetty EE11 12.1.13. No Spring 5/6, Hibernate 5/6, Jetty EE8/9/10, `javax.persistence`, `javax.servlet`, `javax.websocket`, or `javax.validation` API artifact is present. Jetty's shared `org.eclipse.jetty.ee:jetty-ee-webapp` module is part of the EE11 dependency graph and is not the EE8 runtime family.

Production Java source scan found no imports/references to `javax.persistence`, `javax.servlet`, `javax.websocket`, or `javax.validation`, and no `org.springframework.orm.hibernate5` / Hibernate 5 Criteria bootstrap APIs. Historical Phase 2I EE8 POCs and the Phase 2J-A standalone POC still contain their original `javax.*` examples under documentation-only directories; these are historical/test-only artifacts and are not reactor modules, production source, or packaged libraries. Java SE `javax.sql` is not treated as Jakarta residue.

All reactor/module POMs have no custom `<repositories>` or `<pluginRepositories>` declaration. A clean temporary local repository was used:

```bash
mvn -B -Dmaven.repo.local=/tmp/nem-phase2j-final-m2 clean test
```

The cold-resolution log records artifact downloads from `https://repo.maven.apache.org/maven2/`; the full reactor compiled and ran without relying on a stale locally installed NEM module. This fresh-cache Java 17 clean test passed.

## Validation evidence

### Java 17

| Command | Result |
|---|---|
| `mvn -B clean test` | Final full rerun PASS: 6,227 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -B clean package` | Final full rerun PASS; full test/package lifecycle completed |
| Fresh local repository `mvn -B -Dmaven.repo.local=/tmp/nem-phase2j-final-m2 clean test` | PASS: 6,227 tests, 0 failures, 0 errors, 0 skipped; dependencies resolved from Maven Central |
| `mvn -B -pl nis -am -Dtest=Ee11ProductionWebRuntimeProbe,Hibernate7PersistenceRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS: web probe 1/1; Hibernate persistence probe 3/3 |

### Java 25

`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and its `bin` prepended to `PATH`:

| Command | Result |
|---|---|
| `mvn -B clean test` | PASS: 6,227 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -B clean package` | PASS: full test/package lifecycle completed |
| Focused EE11 + Hibernate persistence command above | PASS: web probe 1/1; Hibernate persistence probe 3/3 |

Java 25's Maven launcher prints its Guava `sun.misc.Unsafe` terminal-deprecation warning. No new JVM opens, warning suppression, or compatibility workaround was added.

### Timing-sensitive failures

Transient failures were retained in the evidence rather than hidden. In Java 17:

- The first sandboxed clean test could not bind WireMock's loopback socket (`SocketException: Operation not permitted`) and produced 17 cascading Core errors. Re-running with local loopback permitted removed that environment restriction.
- One Java 17 clean suite then had `AsyncTimerTest.timerContinuesIfFutureSupplierThrows` fail (`expected 3`, observed 2); a full 16-test `AsyncTimerTest` rerun passed, and the following full clean test passed.
- A Java 17 clean test suite had `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance` fail (`expected true`, observed false). Its isolated class rerun passed 13/13 and the following full clean test passed.
- Another package attempt had `AsyncTimerTest.visitorIsNotifiedOfDelays` fail (2 calls vs 4); the class rerun passed 16/16 and the following full clean package passed.

These are timing/statistical test sensitivities outside the Jakarta/ORM/web changes. They were not suppressed or edited. The successful final full runs satisfy the test/package gate, with those intermittent baseline risks retained as limitations.

## Runtime acceptance

The reused opt-in `Ee11ProductionWebRuntimeProbe` starts the production Jetty/Spring bootstrap, initializes production REST and WebSocket DispatcherServlets and filters, and uses production controllers, WebSocket/SockJS/STOMP configuration, and NIS message handlers. Only the database and external node startup are substituted: the probe uses synthetic in-memory H2 with repository migrations, ephemeral ports, disabled peer auto-boot, and a mocked `CommonStarter` to avoid network side effects. It is not a real-network database test.

Phase 2J-D evidence plus the focused reruns on both JDKs confirms:

- live `/heartbeat` REST request returned HTTP 200 and expected JSON contract;
- native Jetty EE11 WebSocket upgrade, STOMP CONNECT/CONNECTED, NIS MESSAGE delivery, and receipt-bearing DISCONNECT succeeded;
- SockJS `/info` and XHR polling/send transport completed CONNECTED, NIS MESSAGE, and receipt-bearing DISCONNECT;
- async support was enabled on REST/WebSocket DispatcherServlets and registered filters; no async-support exception occurred;
- abrupt close produced the expected transport-error callback, session cleanup completed, and a reconnect succeeded;
- WebSocket/SockJS active sessions returned to zero; Jetty stop/join, thread-pool shutdown, and Spring context close completed.

`Hibernate7PersistenceRuntimeTest` starts the Spring persistence context and Hibernate 7 `SessionFactory` on synthetic H2 schema, exercises entity commit/read, rollback, cascade/lazy behavior and resource shutdown. The NIS unit/integration suite also exercises DAO, retriever, multi-block persistence and analyzer paths. This validates the synthetic persistence contract only.

## Documentation and remaining gates

Phase 2J-A records the readiness boundary; Phase 2J-B records the initial coherent graph and failed exploratory source compile; Phase 2J-C records the completed persistence source port and synthetic runtime tests; Phase 2J-D records production-equivalent EE11 web runtime behavior. Their dates/checkpoints and differing scope remain explicit and are not contradictory when read as sequential phase evidence.

Phase 2F remains **BLOCKED — external trusted artifact/evidence owner required**. The candidate database files and public-node checkpoint agreement do not establish authenticated provenance, custody, trusted key binding, quiesced snapshot evidence, or independent authenticated checkpoint. The synthetic probes here are not evidence of real Mainnet/Testnet database compatibility. Trusted snapshots remain required before claiming persisted real-chain equivalence or production DB acceptance.

## Hosted CI and merge readiness

The Java 17 Baseline and Java 25 Compatibility workflows automatically trigger for `dev`, `main`, and `agent/nis-phase0-baseline`, not `agent/nis-phase2j-jakarta-integration`; both define `workflow_dispatch`. `gh auth status` reports the existing GitHub token invalid, so dispatch/run lookup was unavailable. No hosted CI success is claimed for this branch. This is separate from successful local clean test/package validation.

**Merge readiness:** COMPLETE for the Phase 2J EE11 integration branch. No merge was performed. Phase 2F remains a separate release/production data acceptance gate and must be closed with trusted external evidence before real Mainnet/Testnet data compatibility may be claimed.
