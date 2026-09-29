# Phase 2J-B / 2K-A — coordinated integration readiness

## Result

**PARTIAL — a coherent target dependency graph resolves, but the NIS persistence and web source ports do not compile yet.** The attempt confirms that Spring Framework 7.0.9 has a Hibernate 7 native integration boundary, so the transition is coordinated at the production-release boundary rather than blocked by an impossible dependency graph. No production migration is ready to merge.

## Repository state

- Repository: `nemnesia/nem`
- Base branch: `agent/nis-phase0-baseline`
- Requested and actual starting HEAD: `03ba8ed2ea20859c384cf52744e80e0ea461f984`
- Starting `origin/agent/nis-phase0-baseline`: same SHA
- Disposable branch: `agent/nis-phase2j-jakarta-integration`
- Integration branch base: `03ba8ed2ea20859c384cf52744e80e0ea461f984`
- The base worktree had a pre-existing `.gitignore` change adding `legacy/`. It was not copied into this worktree, changed, or committed.
- This record describes an exploratory POM/source patch used to measure compatibility. That unbuildable patch was not retained; this branch contains evidence only. The production base branch was untouched.
- Final HEAD is the tip containing this record; the exact commit is also reported in the accompanying completion report.

## Target versions selected for the probe

| Component | Probe target | Reason |
|---|---:|---|
| Java | 17 minimum; 25 secondary | Spring 7 minimum and existing project baseline |
| Spring Framework | 7.0.9 | Current stable 7.0 patch during this work |
| Jetty | 12.1.13 EE11 | Matches the already deployed Jetty 12.1 line; EE11 modules provide Servlet 6.1 |
| Jakarta Servlet | 6.1.0 | EE11 API resolved by the Jetty EE11 graph |
| Jakarta WebSocket | 2.2.0 | EE11 WebSocket API resolved by Jetty |
| Hibernate ORM | 7.2.25.Final | Spring 7.0 release notes explicitly embrace Hibernate 7.1/7.2; selected latest available patch in that documented line for the probe |
| Jakarta Persistence | 3.2.0 | Hibernate 7.2 API requirement |
| Hibernate Validator | 9.1.4.Final | Spring 7.0 / Jakarta Validation 3.1 generation |
| Jakarta Validation | 3.1.1 | Resolved by Validator 9.1.4 |
| EL provider | Expressly 6.0.0 | Jakarta EL implementation for the Jakarta Validation runtime |
| H2 / Flyway | 2.2.220 / 9.22.3 | Held at established versions; graph resolution only, no DB compatibility claim |

Hibernate 7.2 is in a limited-support release mode according to Hibernate's release page. It was chosen over the newer Hibernate 7.4 line because Spring Framework 7.0's official release notes specifically call out Hibernate 7.1/7.2 as the provider generation it embraces. Hibernate 7.4 should be evaluated only with an explicit Spring compatibility confirmation; “newer” alone was not treated as proof. This version choice is a probe selection, not a production recommendation.

## Official upstream references

Sources checked on 2026-09-29:

- [Spring Framework versions](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions) — 7.0.9 stable line.
- [Spring Framework 7.0 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/d2df491db0e5856e31b1694e5c6efdf727bcb517) — JDK 17 baseline; Servlet 6.1, JPA 3.2, Bean Validation 3.1; Hibernate 7.1/7.2; native Hibernate package move and continued classes.
- [Spring Hibernate integration reference](https://docs.spring.io/spring/reference/7.0-SNAPSHOT/data-access/orm/hibernate.html) — `org.springframework.orm.jpa.hibernate` is the Hibernate 7 integration package; native SessionFactory/current-session integration remains available.
- [Jetty 12.1 migration guide](https://jetty.org/docs/jetty/12.1/programming-guide/migration/12.0-to-12.1.html) — EE11 module family and 12.1 specification baseline.
- [Jetty 12.1 WebSocket guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html) — Jakarta EE11 WebSocket setup.
- [Hibernate ORM releases](https://hibernate.org/orm/releases/) and [Hibernate ORM 7.2](https://hibernate.org/orm/releases/7.2/) — release/support status and Java/JPA compatibility.
- [Hibernate Validator releases](https://hibernate.org/validator/releases/) — Validator 9.1 release generation.

## Dependency graph experiment

On the disposable branch, the reactor POMs were temporarily changed to the target versions above, with Jetty EE8 artifacts replaced by the corresponding `org.eclipse.jetty.ee11` and `org.eclipse.jetty.ee11.websocket` artifacts. H2, Flyway, crypto, protocol, and unrelated libraries were not changed. The exploratory POM change was removed after the compile boundary had been measured.

Command:

```bash
mvn -B -pl nis -am dependency:tree -Dscope=runtime
```

The target runtime tree resolved Spring 7.0.9, Hibernate ORM 7.2.25.Final, Jakarta Persistence 3.2.0, Servlet 6.1.0, WebSocket 2.2.0, Validator 9.1.4.Final, and Jetty EE11 12.1.13. It contained no Jetty EE8 module and no `javax.persistence`, old servlet/websocket API, or Bean Validation 2 API artifact. No `javax.*` artifact collision appeared in that target tree. `javax.sql` is a Java SE package and remains outside the migration inventory.

Spring ORM 7.0.9 included and resolved these Hibernate 7 integration classes used by the current bootstrap: `LocalSessionFactoryBuilder` and `HibernateTransactionManager` under `org.springframework.orm.jpa.hibernate`. The current `SessionFactoryLoader` and `NisAppConfig` integration shape therefore has a plausible Spring integration replacement. That is compile-level API availability only; Hibernate bootstrap and transaction behavior were not runtime-tested.

## Source compatibility experiment

The production inventory established in Phase 2J-A was used: 17 production files reference `javax.servlet`, 21 `javax.persistence`, 1 `javax.validation`, 1 `javax.annotation`, 2 `javax.websocket`, and none `javax.transaction`, JAXB, or activation. The probe mechanically moved those application references to Jakarta namespaces while retaining Java SE namespaces.

The first attempt exposed Jetty API changes as well: EE11 no longer provides the old `ee8.nested` package. The custom error handler must move to the EE11 servlet handler API and Jetty 12's `Request` / `Response` / callback model; the old test mocks also need a behavior-preserving rewrite. Spring 7 removed `HandlerInterceptorAdapter`, and its servlet interceptors must implement `HandlerInterceptor`. Spring 7's previous `AbstractStandardUpgradeStrategy` is absent; its standard strategy API needs a focused replacement for the existing Jetty upgrade shim.

The NIS production source compile command was:

```bash
mvn -B -pl nis -am -DskipTests compile
```

Result: failed in NIS compilation with 200 compiler diagnostic locations across 28 production source files. The significant Hibernate 7 incompatibilities found were:

- Legacy Criteria is removed: 16 `createCriteria(...)` calls and related Criteria/Restrictions/Projections/Order/transformer usage across 9 DAO/retriever files.
- 21 `createSQLQuery(...)` calls across 7 DAO/retriever files must move to typed `createNativeQuery(...)`/query APIs, including scalar result mapping.
- `Session.saveOrUpdate(...)` (3 calls) is removed; choosing `persist`, `merge`, or another operation requires checking detached/new entity behavior and cascade semantics.
- `org.hibernate.type.LongType` and the old `org.hibernate.Query` API are not source-compatible; current type/result declarations need adaptation.
- Hibernate-specific `CascadeType.SAVE_UPDATE` and `@LazyCollection` are unavailable. There are 11 `SAVE_UPDATE` annotations across 9 mapping files and 22 lazy-collection annotation references across 5 files. Replacing these with JPA cascades or fetch settings without mapping tests could change write and lazy-loading semantics.
- Spring `HandlerInterceptorAdapter` occurs in 3 production files; Spring's removed WebSocket upgrade superclass occurs in the Jetty shim.
- Hibernate 7 emitted removal warnings for existing Hibernate-specific cascade annotations before the compile stopped. The project mapping annotations therefore need a deliberate persistence review, not only import changes.

A preliminary deploy main-source compile succeeded after adapting its custom Jetty error handler to the new EE11 request/response model. Deploy test compilation then stopped at test code coupled to the old Jetty nested classes; it was not represented as a passing test. No test assertions were weakened.

These counts describe API occurrences requiring a port, not completed changes. No Hibernate query rewrite was retained because that could silently alter NIS query results or persistence behavior without targeted tests.

## Persistence and schema boundary

The current bootstrap uses native Hibernate `SessionFactory`, `LocalSessionFactoryBuilder`, Spring-managed `HibernateTransactionManager`, and `getCurrentSession()`-style DAO transactions. The Spring 7 native integration classes required for that model exist in the selected framework generation. No `HibernateTemplate` / `HibernateDaoSupport` dependency was identified in the initial production inventory.

The compile probe did not reach Hibernate `SessionFactory` startup. No synthetic database was opened, no read/write transaction or rollback was tested, and no mapping/schema equivalence claim is made. Migration SQL, schema resources, mappings in the base branch, H2, and Flyway were not changed. Hibernate auto-DDL was not run. Runtime differences in identifier generation, legacy cascades, collection loading, HQL/native SQL behavior, and H2 type mapping remain unverified.

## Web / transport boundary

The dependency graph provides a Jakarta EE11 container family and Spring 7 MVC/WebSocket modules, but the NIS application compile did not complete. Consequently none of these were validated on the target branch:

- production NIS Spring context startup or REST endpoints;
- Jetty startup with the production bootstrap;
- `/w/*` SockJS `/info` or XHR polling;
- WebSocket HTTP 101, STOMP CONNECT/CONNECTED/MESSAGE/receipt DISCONNECT;
- session cleanup, async support, QTP lifecycle, or shutdown.

The Phase 2J-A standalone MVC/SockJS bootstrap remains useful groundwork, but does not substitute for those production-equivalent NIS paths.

## Validation summary

| Check | Result |
|---|---|
| Target runtime dependency tree | PASS for resolution; no EE8 or javax persistence/web/validation API artifact in the target tree |
| `mvn -B -pl nis -am -DskipTests compile` | FAIL: Hibernate/Spring/Jetty source API incompatibilities described above |
| Deploy target main source | PASS in the temporary source experiment after Jetty handler adaptation |
| Deploy test compile | FAIL: old `ee8.nested` request/response mocks |
| Java 17 tests/package | Not run: compile blocker prevents meaningful suite execution |
| Java 25 tests/package | Not run: compile blocker prevents meaningful suite execution |
| Synthetic persistence/runtime | Not run |
| Hosted CI | Not run; no buildable target patch was retained or pushed for CI |
| `git diff --check` | PASS for the documentation-only final branch change |

No project test count is reported because the target compile failed before tests were discovered or run. The initial cold-cache Maven attempt also encountered an environment-level inability to write the default local repository; retrying with the existing Maven cache resolved dependencies and exposed the source compile errors, so the final blocker is code/API compatibility, not repository access.

## Boundary decision and next implementation order

**Coordinated Spring 7 / Jakarta EE11 / Hibernate 7 transition is required.** Spring 7 does not preserve Hibernate 5.4's old Spring ORM integration package, but provides a Hibernate 7 integration package. The selected graph resolves coherently; the current NIS persistence and servlet code does not compile against it without a substantive source port.

Recommended next implementation phase on an isolated integration branch:

1. Port Spring/Jetty servlet and WebSocket adapters to Jakarta EE11 and Spring 7 while preserving endpoint and registration contracts.
2. Port Hibernate persistence as a coordinated sub-boundary: typed HQL/native queries, CriteriaBuilder queries, save/merge semantics, cascade mappings, and collection-fetch behavior. Keep every query's ordering, joins, distinctness, pagination, and transaction boundaries explicit and add focused DAO/mapping tests.
3. Require a clean Java 17 compile/test before attempting full startup. Then exercise SessionFactory on a disposable synthetic schema with schema generation disabled, compare read results, and verify write/rollback only on copies.
4. Only after both web and persistence paths compile together, run REST and SockJS/WebSocket/XHR production-equivalent probes, then Java 25 and hosted CI.
5. Treat production rollout as a single coordinated release/rollback unit for Spring 7 + Jakarta + Hibernate 7. Do not deploy a Spring 7 / Hibernate 5.4 mixed graph. Preserve the current EE8 runtime as rollback point until the combined candidate passes.

No production merge is recommended from this readiness result. The testable minimum migration patch set is broader than mechanical namespace replacement: servlet/Jetty and Spring interceptor/upgrade adapters, plus DAO query API migration and ORM mapping semantics/tests. No schema or protocol changes are justified by current evidence.

## Independent Phase 2F gate

Phase 2F trusted Mainnet/Testnet snapshot evidence remains **BLOCKED — external trusted artifact/evidence owner required**. No candidate DB was opened in this phase, and synthetic compatibility or web readiness does not close the trusted persisted-chain equivalence gate. Full-chain data equivalence for Phase 2K still requires trusted Mainnet and Testnet snapshot evidence.
