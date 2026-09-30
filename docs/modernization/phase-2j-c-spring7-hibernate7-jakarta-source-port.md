# Phase 2J-C — Spring 7 / Hibernate 7 / Jakarta persistence port

## Result

**COMPLETE — JAKARTA / HIBERNATE 7 PERSISTENCE PORT VALIDATED (synthetic schema only).**

The NIS production source now compiles against a coherent Spring 7 / Hibernate 7 / Jakarta EE 11 graph. A Spring persistence context and Hibernate 7 `SessionFactory` were created against the repository's synthetic Flyway schema. Commit/read, rollback, JPA cascade-persist, lazy collection initialization, query/DAO behavior, and resource shutdown were exercised. This does not validate a trusted Mainnet or Testnet database, nor does it constitute a full REST/WebSocket runtime migration.

## Repository and branch

- Repository: `nemnesia/nem`
- Base branch: `agent/nis-phase0-baseline`
- Requested and actual integration starting HEAD: `cb999043c33cee13120133496d97098271ff3503`
- Integration branch: `agent/nis-phase2j-jakarta-integration`
- Starting `origin/agent/nis-phase2j-jakarta-integration`: same SHA
- The base branch was not changed or merged. Its pre-existing `.gitignore` edit was not brought into this worktree.
- Implementation commit: `107a5b78c` (`[nis] port persistence to Spring 7 and Hibernate 7`), pushed normally to the integration branch.
- Documentation commit and final HEAD are recorded after this file is committed.

## Target dependency set

| Component | Version / family |
|---|---|
| Production compile baseline | Java 17 (`--release 17`) |
| Spring Framework | 7.0.9 |
| Hibernate ORM | 7.2.25.Final (`org.hibernate.orm:hibernate-core`) |
| Jakarta Persistence | 3.2.0 |
| Jetty | 12.1.13 EE11 |
| Jakarta Servlet | 6.1.0 |
| Jakarta WebSocket | 2.2.0 |
| Hibernate Validator | 9.1.4.Final |
| Jakarta Validation | 3.1.1 |
| Jakarta EL | Expressly 6.0.0 / EL API 6.0.1 |
| H2 / Flyway | 2.2.220 / 9.22.3, unchanged |

Phase 2J-B already recorded the upstream compatibility basis and exact release selection: Spring 7.0 release notes document Hibernate 7.1/7.2 integration; Jetty 12.1 EE11 supplies the Servlet 6.1/WebSocket 2.2 family; Hibernate ORM 7.2 uses Jakarta Persistence 3.2. See [Phase 2J-B](phase-2j-b-spring7-hibernate7-jakarta-integration.md) for upstream URLs and retrieval date. The current runtime dependency tree was generated with:

```bash
mvn -B -pl nis -am -DskipTests dependency:tree -Dscope=runtime -DoutputType=text
```

It resolves Spring WebMVC/WebSocket/ORM 7.0.9, Hibernate ORM 7.2.25.Final, Jakarta Persistence 3.2.0, Servlet 6.1.0, WebSocket 2.2.0, Jetty EE11 12.1.13, and Validator 9.1.4.Final. The NIS runtime tree contains no Hibernate 5/Spring 5 ORM artifact, Jetty EE8 module, or `javax.persistence`, `javax.servlet`, `javax.websocket`, or `javax.validation` API artifact. The production-source scan likewise has no references to those four legacy namespaces. `javax.sql.DataSource` remains in two source files because `javax.sql` is a Java SE package.

## Port scope and compatibility boundary

Phase 2J-B's failed compile experiment reported about 200 diagnostic locations across 28 NIS production files. The categories were Jakarta namespace changes, removed Hibernate Criteria/query APIs and persistence methods, Hibernate mapping annotations, Spring ORM/transaction and interceptor APIs, and Jetty/Spring WebSocket bootstrap APIs. The port changes 91 tracked files plus one new test: 2 module POMs, 6 deploy main sources, 1 deploy test, 55 NIS main sources, 24 NIS tests, and 3 NIS integration-test sources. No source outside deploy/NIS and no unrelated dependency line was changed.

The namespace port is mechanical only where the API contract is unchanged: Servlet, Persistence, Validation, Annotation, and WebSocket references now use `jakarta.*`. Spring interceptors implement `HandlerInterceptor`; Jetty's error handler uses the Jetty 12 request/response API; WebSocket setup uses the Spring 7 `WebSocketMessageBrokerConfigurer` and `StandardWebSocketUpgradeStrategy` integration surface.

### Hibernate query and persistence APIs

- Native SQL calls use Hibernate 7 `createNativeQuery` / `createNativeMutationQuery` APIs. Scalar `Long` mapping uses `Long.class`; `RawMapperUtils` accepts numeric JDBC values through `Number` conversion, with a focused regression test.
- Removed legacy Criteria queries were ported to typed HQL. The block-height projection retains `height >= :height`, ascending height order, and `setMaxResults(limit)`. Retriever ports preserve their filters, joins/fetches, sort order, and result cardinality. Existing DAO/retriever test suites exercise boundaries, ordering, pagination, missing/empty results, and multiple results.
- JPA `FetchType.LAZY`/`EAGER` now expresses the prior `@LazyCollection(TRUE/FALSE)` intent. `SAVE_UPDATE` mappings were reviewed and represented as JPA `PERSIST` plus `MERGE` where those operations were the required legacy cascade behavior. No orphan-removal or relationship ownership change was introduced.
- Removed `saveOrUpdate` calls in block persistence now use explicit `persist`, with account-reference normalization before persisting a block. This addresses Hibernate 7's transient/detached entity rules and historical account identity. The helper resolves an existing account by ID and printable key, keeps account identity stable, and fills a missing persisted public key when a replayed block supplies it. `NisMainTest.initUpdatesNisCacheWhenMultipleBlocksArePresentUsingBlockAnalyzer` initially exposed a missing public-key synchronization case; that case was fixed and the test now passes. This helper is a residual maintenance risk because it traverses package-local model fields reflectively; it is confined to `org.nem.nis.dbmodel` objects and covered by block persistence/replay tests.

Spring's Hibernate 7 integration is wired through `org.springframework.orm.jpa.hibernate.LocalSessionFactoryBuilder` and `HibernateTransactionManager`. No HibernateTemplate/HibernateDaoSupport use was found. `SessionFactoryLoader` retains the current annotated-class registry and data-source contract.

## Synthetic persistence runtime evidence

`Hibernate7PersistenceRuntimeTest` uses Spring `TestConf`, an in-memory H2 2.2.220 database, and the repository Flyway scripts to create the synthetic schema through version 1.0.7 (8 migrations). It does not use or represent a real network database. Hibernate's automatic schema generation is not enabled.

The test confirms:

- Spring persistence context startup builds an open Hibernate 7 `SessionFactory` and initializes the configured transaction manager.
- A new `DbAccount` is persisted and committed; a separate session/query reads it back with the same printable key.
- A persisted account in a rolled-back transaction is absent in a new session.
- Persisting a `DbBlock` cascades persistence to its harvester account. The block transaction collection is initially unloaded according to `PersistenceUnitUtil`, remains empty after initialization, and then reports loaded. (Calling the model getter itself iterates through a filter, so the initial state is checked before invoking that getter.)
- Closing a Spring application context closes the context-owned `SessionFactory`.
- Existing DAO tests execute Spring-managed current-session transactions. Block save/reload/replay and query/retriever tests pass; the block analyzer test reconstructs the expected cache balance after multi-block persistence.

This is synthetic persistence compatibility, not consensus/signature re-audit, persisted-chain equivalence, or a full replay of a trusted real chain.

## Schema and protocol invariants

- No Flyway migration SQL or other `.sql` file changed.
- No DDL, table/column, identifier, enum, numeric, binary, or timestamp contract was intentionally changed. Hibernate does not auto-create/update the schema.
- No REST response contract, WebSocket protocol, serialization, consensus, or blockchain validation logic was intentionally changed.
- Existing H2 2.2.220 and Flyway 9.22.3 remain in use; Flyway validation reports all 8 synthetic migrations at 1.0.7 and no pending migration on an already initialized test database.

## Validation results

All Maven commands ran in the integration worktree. Java 25 commands set `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`; Maven continues compiling with `--release 17`.

| Runtime | Command | Result |
|---|---|---|
| Java 17 | `mvn -B clean test` | PASS: 6,227 tests, 0 failures, 0 errors, 0 skipped |
| Java 17 | `mvn -B clean package` | PASS |
| Java 25.0.4.1 | `mvn -B clean test` | First run had one timing failure described below; a single full rerun passed: 6,227 tests, 0 failures, 0 errors, 0 skipped |
| Java 25.0.4.1 | `mvn -B clean package` | PASS |
| Java 17 | `mvn -B -pl nis -am -Dtest=Hibernate7PersistenceRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS: 3/3 |
| Java 25 | same focused runtime command | PASS: 3/3 |
| Runtime dependency tree | command above | PASS: no accidental EE8 / listed legacy EE API artifacts |
| `git diff --check` |  | PASS |

The first final-source Java 25 clean-test attempt failed in unchanged `core/src/test/java/org/nem/core/async/AsyncTimerTest.java`, method `refreshIntervalIsRespected`: expected 3 timer invocations after a fixed sleep, observed 1. A single-test rerun passed 1/1, then one full clean-test retry passed. This is a timing-sensitive test failure outside changed files; it remains reported rather than hidden. Java 25 also prints the JDK's terminal-deprecation warning for Guava's `sun.misc.Unsafe` access; no additional JVM opens or suppressions were added.

The final clean-test count consists of Core 2,364, Deploy 61, Peer 306, and NIS 3,496 tests. The +1 NIS test is the lazy/cascade runtime regression test. Java 17 and Java 25 package invocations run the full test lifecycle and completed successfully.

## REST / WebSocket status

Production sources and existing configuration/bootstrap tests compile/pass on the EE11 graph. No live NIS REST request was run in this phase. No live WebSocket HTTP 101, STOMP, SockJS XHR, or transport matrix was run; those are explicitly deferred until after this persistence port stabilizes. Phase 2J-A standalone EE11 evidence does not substitute for production-equivalent transport validation.

## Remaining risks and gates

- This branch has not been merged to the base branch and is not a production rollout approval. Full REST/WebSocket/SockJS EE11 validation remains outstanding.
- No provenance-verified Mainnet/Testnet snapshot was used. Phase 2F remains **BLOCKED — external trusted artifact/evidence owner required**. Synthetic tests do not close that gate; persisted-chain equivalence for the target ORM remains unproven.
- Hibernate ORM 7.2 and Spring 7 behavior is proven against the synthetic H2 schema and tests only. Real persisted DB mapping/data compatibility still depends on Phase 2F trusted snapshots.
- The reflective account-reference normalization in block persistence is the main code-level follow-up risk.
- Hosted CI was not available for the integration branch. Both Java workflows include `workflow_dispatch`, but their automatic push filters omit `agent/nis-phase2j-jakarta-integration`; `gh auth status` reports that the configured GitHub token is invalid, so dispatch and authenticated run lookup could not be performed. The implementation commit was pushed successfully, but hosted CI is **not verified**.

## Reproduction commands

```bash
mvn -B -pl nis -am -Dtest=Hibernate7PersistenceRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B clean test
mvn -B clean package

export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B clean test
mvn -B clean package

mvn -B -pl nis -am -DskipTests dependency:tree -Dscope=runtime -DoutputType=text
```
