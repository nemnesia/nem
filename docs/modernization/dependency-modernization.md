# NIS dependency modernization assessment

Status: Phase 2A audit and planning only<br>
Audit date: 2026-09-07<br>
Repository: `nemnesia/nem`<br>
Branch: `agent/nis-phase0-baseline`<br>
Starting HEAD: `c2f8f82cb9cddf3af0c55ae047fccd78a611f36a`

## Scope and conclusion

This document records the dependency baseline and proposes independently
verifiable modernization waves. It does not upgrade a dependency, change a
POM, rewrite a migration, or change production behavior. The Java 11 compiler
release and Java 11 support policy remain unchanged.

The recommended gate for this audit is:

> **PHASE 2A READY WITH CONDITIONS**

There is enough repository evidence to begin a small, test/build-only
implementation wave. Runtime-framework and database waves must remain gated on
the decisions and evidence listed below, especially a disposable copy of a
representative NIS database. Full-chain Mainnet/Testnet compatibility is not
verified in this phase.

## Evidence collected

The inventory was assembled from the four module POMs, the NIS effective POM,
and verbose dependency trees. The primary inspection commands were:

```text
mvn -B -f nis/pom.xml help:effective-pom -Doutput=/tmp/nis-phase2a-nis-effective-pom.xml
mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree -Dscope=test -Dverbose
mvn -B -f <module>/pom.xml org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates
```

The generated effective POM and dependency trees were kept outside the
repository. The versions report is an update hint, not an approval list. In
the inspected report it identified, among other entries, `commons-codec`
`1.22.0 -> 1.22.1`, `commons-collections4` `4.5.0 -> 4.6.0`, and
`hibernate-validator` `6.2.0.Final -> 6.2.5.Final`; it did not establish a
safe upgrade target for Spring, H2, Flyway, or the complete transitive graph.

## Current dependency baseline

### Build shape

- The root `pom.xml` is a packaging `pom` for version `0.6.102` and lists
  `core`, `deploy`, `peer`, and `nis` as modules.
- The modules are independent POMs. There is no shared parent,
  `<dependencyManagement>`, or `<pluginManagement>` in the repository.
- Each module compiles with Maven compiler `release 11`, UTF-8, `-Xlint:all`,
  and `failOnWarning=true`. The Java 25 profile only handles Java 25 compiler
  diagnostics and test JVM access; it does not change dependency versions.
- The root has no Maven wrapper or Maven version pin. The inspected effective
  POM inherits old Maven super-POM defaults for some unpinned plugins,
  including clean `2.5`, install `2.4`, deploy `2.7`, release `2.5.3`, site
  `3.3`, antrun `1.3`, assembly `2.2-beta-5`, and dependency `2.8`.

### Direct dependencies by module

The following is a risk-focused inventory of all direct external dependency
families. Repeated Jetty and Spring module names are grouped where they have
the same version and role.

| Module | Compile/runtime direct dependencies | Test direct dependencies |
| --- | --- | --- |
| `core` | JavaEWAH `1.2.3`; json-smart `2.6.0`; Bouncy Castle `bcprov-jdk15on 1.70`; commons-codec `1.22.0`; commons-io `2.22.0`; commons-math3 `3.6.1`; Apache HttpAsyncClient `4.1.5` | JUnit `4.13.2`; `mockito-all 1.10.19`; MTJ `1.0.4`; WireMock `1.58` standalone with explicit legacy dependency exclusions |
| `deploy` | local `nem-core`; Jetty `9.4.56.v20240826` annotations/client/plus/server/servlet/servlets; Spring `5.3.39` context/jdbc/orm/tx/webmvc | JUnit `4.13.2`; `mockito-all 1.10.19` |
| `peer` | local `nem-core` | JUnit `4.13.2`; `mockito-all 1.10.19`; commons-lang3 `3.20.0` |
| `nis` | local `nem-core`, `nem-deploy`, `nem-peer`; `javax.servlet-api 4.0.1`; json-smart `2.6.0`; commons-collections4 `4.5.0`; commons-io `2.22.0`; commons-lang3 `3.20.0`; Jetty `9.4.58.v20250814` annotations/client/plus/server/servlet/servlets and websocket-server; Spring `4.3.30.RELEASE` context/jdbc/orm/tx/webmvc/websocket/messaging; Hibernate EntityManager `4.3.11.Final`; Hibernate Validator `6.2.0.Final`; H2 `1.4.200`; Flyway `3.2.1` | JUnit `4.13.2`; `mockito-all 1.10.19`; Spring Test `4.3.30.RELEASE` |

The remaining direct entries are not presently identified as Java 25 or
consensus blockers: JavaEWAH, json-smart, commons-codec/io/lang3/math3,
JUnit, MTJ, and the local NEM test-jar dependencies. They still require
normal version-specific compatibility and security review when touched.

### Plugin baseline

Explicitly versioned plugins are repeated in each module rather than managed
centrally. The common set is:

| Plugin/function | Current explicit version | Observation |
| --- | --- | --- |
| compiler | `3.15.0` | Java release is still 11 |
| surefire / failsafe | `3.5.6` | Java 25 tests currently run with test-only `--add-opens` |
| versions | `2.21.0` | Used for inspection and update reporting |
| build-helper | `3.6.1` | Adds `src/it/java` as test sources in modules that use it |
| JaCoCo | `0.8.15` | Test agent/report/check |
| Spotless | `2.46.1` | Formatting/checking |
| javadoc | `3.12.0` | Core/deploy/peer/nis |
| jar | `3.5.0` or peer `3.5.1` | Packaging |

NIS additionally pins exec `3.6.3`, dependency `3.11.0`, resources `3.5.0`,
and velocity `1.1.0`. The commented license plugin is `1.7`. The unpinned
effective super-POM plugins are a reproducibility concern, but they are not a
reason to change runtime dependencies in this phase.

## Classification

`REQUIRED` below means required for a later modernization objective or for
removing a demonstrated compatibility/support debt. It does not authorize an
upgrade in Phase 2A.

| Classification | Candidate | Repository evidence and handling |
| --- | --- | --- |
| `REQUIRED` | `org.mockito:mockito-all 1.10.19` | Test-only dependency. Phase 1 required reflective-access workarounds on the supported JDKs. Replace the bundled 1.x artifact with a maintained test dependency in an isolated test wave; preserve test assertions and run the Java 11/25 matrix. |
| `REQUIRED` | Spring Framework `4.3.30.RELEASE` | At the Phase 2A audit, NIS runtime used Spring Core/ORM/WebMVC/WebSocket/Messaging and Java 25 startup needed `--add-opens=java.base/java.lang=ALL-UNNAMED` for the old reflection path. Phase 2E replaces that runtime line; the 4.3 line is EOL. |
| `REQUIRED` | Jetty `9.4.x` runtime/websocket line | NIS exposes HTTP/WebSocket functionality and the repository uses an EOL Jetty line. The NIS and deploy POMs also select different patch versions. Security/support remediation must be tested as a web-stack wave, not as a blind version bump. |
| `RECOMMENDED` | H2 `1.4.200` | Embedded file H2 is the production database and test database. The repository does not start the H2 Console, but this version is below the upstream-patched version for the H2 Console RCE advisory. A future change should first prove the actual deployment exposure and replay/migration compatibility. |
| `RECOMMENDED` | Flyway `3.2.1` | Flyway is constructed directly by `NisAppConfig`, runs `db/h2` migrations during startup, and is a dependency of both production and database tests. It is old and tightly coupled to H2 migration parsing/order; upgrade only with migration replay evidence. |
| `RECOMMENDED` | Hibernate EntityManager `4.3.11.Final` and Spring ORM integration | SessionFactory loading uses `LocalSessionFactoryBuilder`, `hibernate4` transaction management, annotated entity mappings, and HQL/native SQL. This is a persistence compatibility wave, not an independent patch. |
| `RECOMMENDED` | WireMock `1.58` standalone | Test-only and excluded legacy transitive stack. It is a likely source of old HTTP/Jetty/Jackson coupling in tests; modernize only after the Mockito/test harness wave or isolate it explicitly. |
| `RECOMMENDED` | Explicit Maven plugin baseline and inherited super-POM plugins | Pinning/centralizing build plugins would improve reproducibility, but the current Java 11 and Java 25 builds pass. Handle in a build-only wave and preserve test-source, JaCoCo, compiler warning, and packaging behavior. |
| `RECOMMENDED` | Bouncy Castle `bcprov-jdk15on 1.70` | Direct crypto dependency in `core`; signatures and hashing are consensus-sensitive. Perform an advisory/version/API review and byte-for-byte signature regression test before any change. No version change is authorized here. |
| `OPTIONAL` | Hibernate Validator `6.2.0.Final` patch update | The update report identified `6.2.5.Final`. It is not a Java 25 blocker and should not be coupled to H2 or schema work unless validation behavior is specifically covered. |
| `OPTIONAL` | Commons minor/patch candidates | The versions report identified small candidates such as commons-codec and commons-collections4. They are not reasons for broad churn; handle only when a concrete advisory or isolated compatibility need exists. |
| `DEFER` | `javax.*` to `jakarta.*`, Spring 6+/Jetty 12, JUnit 4 to JUnit 5, HTTP-stack replacement, and broad transitive cleanup | These imply API, servlet, test, or deployment decisions beyond a low-risk dependency update. They must not be mixed into the first wave, and Java 11 support must remain an explicit constraint. |
| `DEFER` | H2 schema rewrite, Flyway migration rewrite, persistent state, cache redesign, and memory work | Explicitly outside Phase 2A and outside the dependency modernization sequence until a separate approved phase. |

## Coupling analysis

### Spring, CGLIB, reflection, and the web stack

The NIS runtime selects Spring `4.3.30.RELEASE` even though `deploy` declares
Spring `5.3.39`; Maven dependency mediation reports the deploy Spring path as
omitted in the NIS graph. `NisAppConfig` uses Spring JDBC, ORM, transactions,
MVC, WebSocket, and messaging. `SessionFactoryLoader` uses Spring's
`hibernate4` integration. This makes a Spring change a coherent family change,
not a single-artifact update.

At the Phase 2A audit, the Java 25 profile kept the production runtime
workaround out of the normal test/build configuration. A bounded startup smoke
test with Spring 4.3 required `--add-opens=java.base/java.lang=ALL-UNNAMED`
for legacy Spring/CGLIB class definition behavior. Phase 2E's Java 25 smoke
starts with and without this open, as recorded below. The old Mockito artifact
separately required test JVM opens. Therefore:

- Mockito can be modernized as a test-only wave, subject to test API changes
  and Java 21+ agent/instrumentation behavior.
- The production `java.lang` open should remain until the selected Spring/web
  stack has been tested without it. Phase 2E provides that smoke evidence, but
  did not remove any configured JVM workaround.
- Jetty, servlet API, Spring WebMVC/WebSocket, and the deploy module must be
  tested as one web/runtime compatibility set. The current `javax.servlet`
  API makes a Jakarta namespace migration a separate decision.

### H2, Flyway, Hibernate, and migrations

`db.properties` selects an embedded file URL of the form
`jdbc:h2:${nem.folder}/nis/data/nis5_${nem.network};DB_CLOSE_DELAY=-1`,
`org.h2.Driver`, `org.hibernate.dialect.H2Dialect`, and `flyway.locations=db/h2`.
`NisAppConfig` creates the DataSource, runs Flyway's `migrate` init method,
then creates the Hibernate SessionFactory after the Flyway bean. Database
tests use an in-memory H2 database with the same migration location.

The migration history is V1.0.0 through V1.0.7. It contains the initial
account/block/transaction/multisig schema, later namespace and mosaic tables,
indexes, foreign keys, a `transaction_id_seq` sequence, `AUTO_INCREMENT`,
backtick-quoted identifiers, `IF NOT EXISTS`, public-schema ALTER statements,
and repeated `VARBINARY` width changes for transfer messages. Existing model
classes map these tables using JPA annotations, with lazy/eager relationships
and cascading/orphan removal.

The DAO layer also uses HQL plus native SQL for block and account operations,
including explicit deletion order during rollback. A database wave must
therefore test more than migration success:

1. Flyway validation and exact V1.0.0–V1.0.7 replay on a clean database.
2. Opening and using a copy of an existing database without rewriting it.
3. Schema metadata and JDBC type behavior for `BIGINT`, `VARBINARY`, sequence
   defaults, indexes, foreign keys, and nullable fields.
4. HQL/native query behavior, transaction boundaries, flush behavior, and
   rollback/fork deletion ordering.
5. Block-to-model mapping and deterministic state/consensus snapshots after
   replay.

No H2 compatibility mode is configured in the repository. Migrations are not
   idempotent as a general property merely because some creates use
   `IF NOT EXISTS`; later ALTERs, indexes, and foreign keys must be treated as
   ordered history. Do not rewrite or re-baseline them as part of a dependency
   update.

### Mockito and test infrastructure

`mockito-all 1.10.19` is duplicated in `core`, `deploy`, `peer`, and `nis`.
It is test scope and has no production classpath role. Replacing it can be
isolated from consensus behavior, but the test suite uses Mockito broadly and
some tests exercise Spring/Hibernate objects. The replacement must be tested
module by module, then with the full unit suite. Test JVM opens and any agent
configuration must remain test-only.

### Maven plugins and test wiring

The compiler, Surefire, Failsafe, build-helper, JaCoCo, Spotless, and packaging
plugins are coupled through test-source registration, `argLine`, coverage
instrumentation, and package-time dependency copying. The first build wave
should not change all of them together. In particular, Failsafe report
interpretation and the existing integration-test goal wiring must be preserved
while plugin versions are evaluated.

## Java 25 workaround and removal outlook

Current Java 25 support is additive: the compiler release remains 11 and the
Java 11 path remains supported. The JDK 25 test profile currently supplies
test-only opens for `java.lang`, `java.net`, `java.security.cert`, and
`java.util`. Production startup testing identified the narrower
`java.base/java.lang` open for legacy Spring/CGLIB behavior.

The intended removal order is:

1. Modernize the test mocking stack and verify all unit/Failsafe forks on Java
   11 and 25. Remove only the test opens proven unnecessary.
2. Select and test the supported Spring/web stack while retaining the existing
   Java 11 compiler release. Remove the production `java.lang` open only after
   a real startup and HTTP/WebSocket smoke test succeeds without it.
3. Treat any new Mockito agent requirement on newer JDKs as an explicit,
   test-only configuration item; do not add it to production startup.

No workaround is removed in Phase 2A.

## Security observations

These observations distinguish upstream information from repository evidence.
They are triage findings, not automatic upgrade approvals.

| Component | Upstream/repository observation | NIS exposure and proposed handling |
| --- | --- | --- |
| Spring 4.3 | Spring's maintenance roadmap records the 4.3 line EOL after 2020. A historical Spring advisory fixed a range-request issue in 4.3.20; the repository's 4.3.30 is not below that particular fix. | Runtime Spring WebMVC is directly used. EOL status is a support/security maintenance concern; audit actual controller/resource exposure and plan a coherent Spring wave. |
| H2 1.4.200 | The H2 advisory for CVE-2022-23221/GHSA-45hx-wfhj-473x is rated Critical by the GitHub advisory database, affects H2 versions below 2.1.210, and specifically concerns the H2 Console. | Repository search found embedded JDBC usage and no H2 Console server/endpoint. Do not claim the console RCE is exploitable in the default NIS path; confirm deployment, network exposure, and whether operators ever enable a console before prioritizing a security-only patch. |
| Jetty 9.4 | Eclipse Jetty lists `9.4.58.v20250814` as EOL/unsupported and recommends Jetty 12 for community support. | NIS uses Jetty HTTP/WebSocket components and deploy uses a different 9.4 patch. Treat supported patch/line selection as a runtime security wave with web compatibility tests. |
| Bouncy Castle 1.70 | The upstream BC CVE tracking page lists later issues across the Java line, but the page alone does not establish that every issue affects 1.70 or that NIS exercises the affected API. | `core` uses BC in a consensus-sensitive crypto boundary. Run version-specific advisory matching and signature/hash regression tests before selecting a target. |
| Mockito 1.10.19 | Mockito's maintained line is 5.x and requires Java 11; its release material specifically discusses newer-JDK compatibility and inline instrumentation. | Test-only exposure. Modernize in an isolated wave; assess agent/opens behavior and do not treat test-only changes as production security remediation. |
| Other direct libraries | No repository-specific advisory applicability was established in this audit for the remaining direct libraries. | Run a version-pinned dependency/advisory scan in the implementation wave and review each finding against the exercised code path. |

External references:

- [Spring security advisories](https://spring.io/security/)
- [Spring Framework 4.3 end-of-life notice](https://spring.io/blog/2019/12/03/spring-framework-maintenance-roadmap-in-2020-including-4-3-eol/)
- [H2 Console RCE advisory](https://github.com/advisories/GHSA-45hx-wfhj-473x)
- [Eclipse Jetty downloads and support status](https://jetty.org/download.html)
- [Bouncy Castle CVE tracking](https://github.com/bcgit/bc-java/wiki)
- [Mockito maintained line and Java requirement](https://github.com/mockito/mockito/blob/main/README.md)

## Proposed implementation waves

Each wave is a separate implementation/change set. No wave below is started by
this document.

### Phase 2B — build and test infrastructure

First pin or otherwise make explicit only the build plugins needed for
reproducible Java 11/25 builds, preserving the current compiler release,
test-source registration, warning policy, JaCoCo behavior, package layout,
and Failsafe report semantics. Avoid runtime dependency changes in this wave.

Required verification: clean package on Java 11 and 25; Core/Deploy/Peer/NIS
unit counts; Failsafe XML report counts; package contents; `git diff --check`.

### Phase 2C — test mocking and legacy test infrastructure

Replace `mockito-all` with a maintained test-only arrangement, then address
WireMock only if its legacy embedded stack prevents a clean test matrix. Keep
the change separate from Spring/H2 changes.

Required verification: all unit tests on Java 11 and 25, integration report
classification against the Phase 0 baseline, test-only JVM arguments, and no
production dependency tree change.

Phase 2C was implemented and completed with Mockito Core 5.22.0. Java 11
post-change clean package and unit tests passed with 6,218 tests and no
failures/errors/skips. Java 25 discovery matched the pre-change baseline;
environmental network/socket failures were unchanged. Details are recorded in
`build-test-modernization.md`.

### Phase 2D — web/runtime framework alignment

Choose one compatible Spring/Jetty/servlet family for NIS and deploy. The
candidate must preserve Java 11 runtime support, existing `javax` interfaces
unless separately approved, HTTP/WebSocket behavior, startup, and Spring ORM
integration. This wave should include the CGLIB/reflection workaround test.

**Phase 2D status: BLOCKED.** The audit found NIS resolves Spring
`4.3.30.RELEASE` while Deploy is compiled against `5.3.39`. Aligning NIS to
the Java 11-compatible Spring 5.3 line removes Spring's Hibernate 4 ORM
integration, which NIS uses in production configuration and tests. The
required Hibernate 4→5 compatibility and database/transaction verification
belongs to the planned ORM wave; applying it here would combine web and ORM
migrations without the required persistence checks. No dependency or source
change was made. The full dependency/API/security audit is recorded in
`build-test-modernization.md`.

This was the status at the Phase 2D audit boundary. The history is retained;
the coupled compatibility change in Phase 2E below resolves its ORM blocker.

Required verification: startup without the production open where possible,
HTTP/WebSocket smoke tests, controller serialization, peer/deploy startup,
and consensus/state regression snapshots.

### Phase 2E — ORM and validation compatibility

**Phase 2D remains recorded as BLOCKED.** Its blocker was the Hibernate 4
integration required to move NIS from Spring 4.3 to Spring 5.3. That history is
retained; the compatibility work below resolves the blocker as a coupled
Spring/ORM change rather than treating Hibernate migration as a Spring 4-only
intermediate state.

The selected combination is Spring Framework `5.3.39` with Hibernate ORM
`5.4.33.Final`. Spring 5.3 requires Hibernate 5.2 or later for native ORM
integration and its 5.3.39 reference recommends Hibernate 5.4 for a new
SessionFactory setup. Hibernate's official compatibility information lists
Java 11 from Hibernate 5.4.32 onward and JPA 2.2, preserving the existing
`javax.persistence` namespace. Hibernate 5.2/5.3 do not meet the same explicit
Java 11 line. Spring 4.3's Hibernate 5 integration would therefore create an
intermediate, older ORM pairing followed by a second Spring migration; it is
not the chosen path. Spring 5.3.39 is the same version already used by Deploy.
References: [Spring 5.3.39 data-access reference](https://docs.spring.io/spring-framework/docs/5.3.39/reference/pdf/data-access.pdf),
[Spring 5.3.39 LocalSessionFactoryBuilder Javadoc](https://docs.spring.io/spring-framework/docs/5.3.39/javadoc-api/org/springframework/orm/hibernate5/LocalSessionFactoryBuilder.html),
and [Hibernate ORM 5.4 compatibility and artifacts](https://hibernate.org/orm/releases/5.4/).

Hibernate 5.4 merges EntityManager into `hibernate-core`, so NIS replaces
`hibernate-entitymanager:4.3.11.Final` with `hibernate-core:5.4.33.Final`.
`hibernate-core` supplies `javax.persistence-api:2.2`, Javassist, JBoss
Logging, the transaction API, JAXB 2.3, and activation. Its default Byte Buddy
proxy provider would add Byte Buddy to production runtime, which is disallowed
by this phase's runtime-graph constraint. NIS selects Hibernate 5.4's
supported Javassist provider with `hibernate.bytecode.provider=javassist` and
excludes Byte Buddy from `hibernate-core`. Mockito supplies Byte Buddy
`1.17.7` and its agent only in test scope. Hibernate 5.4 documents both
provider names in its [Environment Javadoc](https://docs.hibernate.org/orm/5.4/javadocs/org/hibernate/cfg/Environment.html)
and describes the [bytecode provider boundary](https://docs.hibernate.org/orm/5.4/javadocs/org/hibernate/bytecode/package-summary.html).
The full DAO/lazy-loading suite and runtime smoke below verify the provider
choice. H2 `1.4.200` and Flyway `3.2.1` stay unchanged.

Spring 5.3 and Hibernate 5.4 both officially list Java 11 compatibility. Java
25 is outside the published Spring 5.3 and Hibernate 5.4 support matrices and
is treated as an empirical test target, not as an upstream-supported runtime.
The existing `javax` Servlet/JPA boundary remains intact; this phase does not
include Spring 6, Hibernate 6, Jakarta, H2, or Flyway migration.

The source migration is limited to Spring's `hibernate4` imports becoming
`hibernate5`, Hibernate 5's `NativeQuery` return type in two test doubles,
focused test-fixture transaction handling, and the SockJS CORS declaration
using Spring 5.3's origin-pattern API so the existing wildcard-origin policy
still works with credentials. The `/messages` endpoint and STOMP destinations
are unchanged. See the [Spring 5.3.39 STOMP endpoint Javadoc](https://docs.spring.io/spring-framework/docs/5.3.39/javadoc-api/org/springframework/web/socket/config/annotation/WebMvcStompWebSocketEndpointRegistration.html)
for the framework's `allowedOrigins` / `allowedOriginPatterns` constraint.
Legacy Criteria, `Query`, and
`createSQLQuery` use remains because Hibernate 5.4 still exposes those
compatibility APIs; HQL, native SQL, mappings, and production transaction or
rollback ordering are unchanged. The stricter Hibernate 5 requirement for
explicit transactions applies to bulk test-fixture DML, so the test helpers
now commit their setup/cleanup DML explicitly.

Required verification: SessionFactory bootstrap, all DAO tests, HQL/native SQL,
transaction and rollback tests, entity mapping, and deterministic block/state
snapshots. See the Phase 2E results below for build/test counts, dependency
trees, artifact comparison, database checks, and remaining risks.

#### Phase 2E implementation and verification results

**Phase 2E status: COMPLETE WITH A RECORDED EXISTING-DATABASE LIMITATION.**
The Spring 5.3 / Hibernate 5.4 coupled change was implemented. The Phase 2D
`BLOCKED` audit history above remains unchanged.

The runtime dependency tree resolves all Spring Framework artifacts to
`5.3.39`; no Spring 4 artifact remains. NIS resolves one Hibernate line,
`hibernate-core:5.4.33.Final`, and one `javax.persistence-api:2.2`; no
Hibernate 4 core or `hibernate-entitymanager` remains. Javassist is
`3.27.0-GA` and is the selected proxy provider. Byte Buddy and its agent,
Mockito, Objenesis, JUnit, WireMock, and `spring-test` are absent from the
production runtime tree and copied `nis/target/libs`; the test tree resolves
Mockito's Byte Buddy `1.17.7` and agent as test-only dependencies. JAXB 2.3
and activation 1.2 resolve as runtime libraries;
the transaction API resolves to `jboss-transaction-api_1.2_spec:1.1.1.Final`.
H2 and Flyway versions are unchanged.

Verification results:

| Check | Result |
| --- | --- |
| Java 11 `mvn -B clean package` | PASS with OpenJDK 11.0.32.1. A later repeat hit two public-peer network errors in `NisPeerNetworkHostTest`; the successful full run's Surefire XML totals are listed below. |
| Java 11 Surefire XML | 624 classes, 6,218 tests, 0 failures, 0 errors, 0 skipped; module totals match Phase 2C |
| Java 25 clean package and unit suite | All modules compiled; 624 classes and 6,218 tests discovered, 0 failures, 1 error, 0 skipped. `NisPeerNetworkHostTest.isNetworkBootedReturnsTrueIfNetworkIsBooted` failed while contacting public peers (`NoRouteToHost` / connection refused), matching the existing network-dependent baseline category. The lifecycle stopped before packaging. |
| Java 25 package | `mvn -B -DskipTests package` PASS; this separates packaging from the peer-network test error without suppressing discovery in the reported test result |
| SessionFactory, DAO, transaction, flush, rollback/fork tests | PASS in the full Java 11 suite and Java 25 suite apart from the peer-network test error; Hibernate 5.4 bootstrapped under both JDKs |
| Clean H2/Flyway replay | PASS on fresh in-memory H2 1.4.200; unchanged V1.0.0–V1.0.7 all applied (8 migrations), then Spring context and SessionFactory initialized |
| Existing Testnet DB copy | Read-only Hibernate open and representative HQL reads PASS: schema 1.0.7, 5,000 blocks, max height 5,000, 100 accounts. Full NIS startup stopped at the genesis-hash check. The starting-commit Hibernate 4 application stops at the same check against this copy and reports the same expected genesis hash, so this mismatch predates Phase 2E. Full chain-state comparison against this snapshot is **not verified**. Original and copy SHA-256 hashes were identical before/after. |
| Java 25 startup without `--add-opens` | PASS on the fresh migrated database: NIS/Deploy started; `/heartbeat`, `/chain/height`, and `/w/messages/info` returned HTTP 200 with JSON responses |
| Java 25 startup with `--add-opens=java.base/java.lang=ALL-UNNAMED` | PASS on the same smoke setup and endpoints. The existing JVM workaround was not removed from configuration. |
| Artifact comparison | Same-JDK Java 11 baseline/final JAR names match. Core main/test JAR entries: 352/485 in both; Deploy 31/25; Peer 80/88; NIS main: 622→623 (one added `hibernate.properties`). No production class additions/removals. Other compared module JAR entry counts are unchanged. The retained baseline snapshot did not contain an NIS test JAR. NIS copied runtime libraries change from 71 to 76, replacing Spring 4/Hibernate 4 and their JPA/JAXB/transaction dependencies with the selected versions. Timestamp/raw hash differences are not treated as behavior changes. |

All entity mapping source, migration SQL, H2/Flyway configuration, HQL/native
SQL text, persistent state format, and production transaction/rollback
ordering remain unchanged. Hibernate 5's deprecated Criteria, Query, and
`createSQLQuery` compatibility APIs remain in use to avoid a broad query
rewrite. Tests now wrap setup/cleanup bulk DML in explicit transactions,
matching Hibernate 5's required transaction behavior. The Spring 5.3 SockJS
endpoint uses wildcard origin patterns because wildcard `allowedOrigins` with
credentials is rejected by Spring 5; smoke tests confirm the endpoint remains
available. `HandlerInterceptorAdapter` remains as a deprecated but working
Spring 5.3 API.

Remaining risks: Java 25 is an empirical target outside the published support
matrices of Spring 5.3 and Hibernate 5.4; both selected lines are end-of-life.
Hibernate 5.4 emits a deprecation warning for its Javassist provider and says
it may be removed in a later ORM line, so the provider choice must be revisited
before that upgrade. The Java 25 suite retains one environment-dependent
peer-network error. The available Testnet database copy can be mapped and
read, but its genesis identity prevents a full application chain-state
comparison. A matching Mainnet/Testnet database snapshot is still needed for
complete persisted-state compatibility evidence.

### Phase 2F — H2/Flyway database compatibility

**Current Phase 2F status: COMPLETE / CLOSED.** Real legacy DB runtime and
persisted-chain compatibility is accepted; external third-party cryptographic
provenance is not a Phase 2F technical acceptance requirement. See the [Phase
2F final closure](phase-2f-real-db-final-closure.md). The earlier sub-phase
records below preserve the acceptance criteria and limitations that applied
when they were written.

**Historical Phase 2F implementation status: COMPLETE WITH RECORDED COMPATIBILITY LIMITATIONS.** Starting
HEAD was `5868cb184fa29f35a6dc36655f1753f4f5984301`; the final commit is this
Phase 2F verification commit (the exact final SHA is recorded in the final report).
The Phase 2D `BLOCKED` history and Phase 2E result above are unchanged.

#### Candidate selection

H2 and Flyway were moved together to H2 `2.2.220` and Flyway `9.22.3`. Both
published artifacts are available from Maven Central and have class-file
major version 52; the selected ORM remains Hibernate `5.4.33.Final`, the JPA
namespace remains `javax.persistence` 2.2, and compiler release remains 11.
H2 `2.3.232` is an upstream Java 11-capable alternative, and Flyway `10.21.0`
lists H2 `2.3.232` as supported. However, the Flyway 10.21.0 API artifact has
class-file major version 61 and cannot run on Java 11. Flyway 9.22.3 is the
last Flyway 9 release and its official release notes cap tested H2 support at
`2.2.220`; Flyway 10.19/10.21 add newer H2 lines but require Java 17 bytecode.
The Java 11 baseline therefore takes priority over those newer pairings.
Both selected lines are old and no longer current upstream maintenance
targets; this is an explicit support and security risk, not a claim that the
selected versions are current. References: [H2 migration guide](https://h2database.com/html/migration-to-v2.html),
[H2 2.2.220 release](https://github.com/h2database/h2database/releases/tag/version-2.2.220),
[Flyway 9/10 engine release notes](https://documentation.red-gate.com/fd/release-notes-for-flyway-engine-179732572.html),
[H2 2.2.220 Maven artifact](https://central.sonatype.com/artifact/com.h2database/h2/2.2.220),
[Flyway 9.22.3 Maven artifact](https://central.sonatype.com/artifact/org.flywaydb/flyway-core/9.22.3).

#### Implementation and SQL compatibility

NIS replaces only the H2/Flyway versions, adds H2 `MODE=LEGACY` and
`NON_KEYWORDS=VALUE` to both the in-JAR and package-overlay `db.properties`,
and moves Flyway construction to its fluent configuration API. Flyway is
explicitly configured to keep using the existing `schema_version` table; its
production `validateOnMigrate` behavior remains false as before (the existing
`flyway.validate` property is absent and `Boolean.valueOf(null)` is false).
The mode settings are required by unchanged V1.0.0–V1.0.7 SQL: normal H2 2.2
rejects the legacy `transaction_id_seq.nextval` syntax, and `VALUE` is now a
reserved word while it remains an unquoted entity column. With the two
settings, all eight migrations replay without editing their SQL. Three H2 2.2
foreign-key support indexes are generated for `transfers` (`blockId`,
`senderId`, `recipientId`) in addition to migration-defined indexes. These
are index-only engine metadata differences; the migration-defined keys and
data constraints are unchanged. The H2 2.2 JDBC metadata also reports
`BINARY VARYING` where 1.4 reported `VARBINARY`, and wider metadata precision
values for `BIGINT`/`INTEGER`; JDBC type codes, declared column lengths,
nullability, PK/FK columns and referential rules compare equal after
normalizing those representation differences.

Three test-only raw SQL fixtures now use explicit `X'…'` binary literals for
hex-encoded public keys. That preserves the old 32-byte value when inserting
into `VARBINARY(34)`; no production DAO, entity mapping, HQL/native SQL,
transaction ordering, consensus logic, or block/fork rollback code changed.
The only production Java change is the Flyway builder/API update. No migration
SQL, schema definition, H2 version, URL path, or persisted-state format was
rewritten.

#### Existing database conversion and state comparison

H2's official 1.4→2.0 guide says direct database-file upgrade is not
supported. The documented migration path is to export with the old H2, create
a new database with the new H2, and import with `FROM_1X`. That exact procedure
was run only against a disposable Testnet database copy. The original
`/home/harvestasya/nem/nis/data/test.mv.db` and Phase 2E copy remained at
SHA-256 `6e68d1a1c604e2bed0af9c0feb6ac17a6d60991f30d5ac8fc2bc408cf710184e`.
The converted H2 2.2 file was 173,010,944 bytes; its hash changed from
`cdde81131c44125655450d786b8aba9fe5a03a1d5eace0e22d8ae88b9b518e64` before
Flyway's read/no-op migrate to
`25670fbc87a614a4598ef09cc470643c0943e7b091a92926f6aa868f92e15292` after
opening it. The file set remained one `.mv.db`; the old copy was 509,284,352
bytes. The new application does not auto-convert old H2 files: operators must
use a verified offline export/import on backups before opening them with H2 2.

Old and converted databases have identical per-table row counts and
SHA-256 digests across every application table and the eight existing
`schema_version` rows. Representative block hashes/fees/difficulty,
account keys, transfer hashes/amounts, and the `transaction_id_seq` next value
also match. The latter is represented as old `CURRENT_VALUE=724979` versus new
`BASE_VALUE=724980` with increment 1 (same next value). Counts include 5,000
blocks (max height 5,000), 100 accounts, 475,018 transfers, 24,982 importance
transfers, 74,993 multisig sends/receives/transactions, and 149,986 multisig
signatures; namespace and mosaic tables are empty in this snapshot. Hibernate
5.4 SessionFactory and HQL reads on Java 25 returned height 5,000, 5,000
blocks, 100 accounts, and 475,018 transfers.

Flyway 9 reads the eight old rows as `SUCCESS`, and with the preserved
production setting (`validateOnMigrate=false`) its `migrate()` is a no-op at
1.0.7; all schema-history rows and checksum values remained identical in a logical
row comparison. A direct Flyway 9 `validate()` does report checksum
mismatches against Flyway 3's stored checksums. No `repair`, baseline, or
history rewrite was run. Since production already disabled this validation
before Phase 2F, startup behavior is preserved, but checksum validation across
this Flyway generation gap remains unavailable and is a documented risk.

The Testnet copy is still not the current Testnet genesis database. This
proves file/schema/migration/HQL and stored-row compatibility only; it does
**not** prove matching-genesis full chain-state, reconstructed balance or
importance equality. A matching Mainnet/Testnet snapshot is still required
for that claim.

#### Phase 2F verification results

| Check | Result |
| --- | --- |
| Java 11 `mvn -B clean package` | All code compiled; the lifecycle ended with one public-peer timeout in `NisPeerNetworkHostTest`. Full Surefire XML: 624 classes, 6,218 tests, 0 failures, 1 error, 0 skipped. An isolated retry hit the same external peer-network timeout. No H2/Flyway, DAO, transaction or rollback errors occurred. |
| Java 11 packaging fallback | `mvn -B -DskipTests clean package` PASS. |
| Java 25 `mvn -B clean package` | All code compiled; one `NisPeerNetworkHostTest` public-peer boot timeout stopped the lifecycle. Surefire XML: 624 classes, 6,218 tests, 0 failures, 1 error, 0 skipped. This matches the known Java 25 network-dependent error category from Phase 2E; no database-stack errors occurred. |
| Java 25 packaging fallback | `mvn -B -DskipTests clean package` PASS as required after the network-dependent lifecycle error. |
| Fresh migration replay / SessionFactory | PASS on fresh H2 2.2.220 with Flyway 9.22.3: 8/8 migrations to 1.0.7; NIS/Deploy Spring context, Hibernate SessionFactory, and transaction manager bootstrapped. |
| DAO/HQL/native SQL/transactions/rollback/fork | All database tests passed in both full-suite runs; all 6,218 tests were discovered. Commit/rollback, flush, read, lazy/eager, cascade, block rollback and fork rollback tests reported no database errors. |
| Schema metadata | Application columns, types, declared widths, nullability, PK/FK columns, FK rules, sequences, and migration-defined indexes matched after normalizing H2 metadata naming/precision. H2 adds the three generated FK support indexes described above. |
| Existing Testnet DB copy | Old H2 read/export and H2 2 `FROM_1X` import PASS on disposable copies. Per-table logical row hashes and representative stored values match; Flyway reads 8/8 old history entries and no-op migrate stays at 1.0.7 with production validation disabled. The original and old source copy hashes are unchanged. Full-chain state remains unverified because genesis does not match. |
| Java 11 startup smoke | PASS on a fresh migrated in-memory database: NIS and Deploy started; `/heartbeat`, `/chain/height`, `/w/messages/info` returned HTTP 200 JSON on their configured listeners. |
| Java 25 startup smoke | PASS on the same fresh-database path using the existing `--add-opens=java.base/java.lang=ALL-UNNAMED` workaround; all three endpoints returned HTTP 200 JSON. The workaround was not changed. |
| Dependency graph | NIS runtime resolves one H2 `2.2.220`, one Flyway `9.22.3`, one `javax.persistence-api:2.2`, Hibernate `5.4.33.Final`, Javassist `3.27.0-GA`, JAXB 2.3, activation 1.2 and transaction API `1.1.1.Final`. No H2 1.4/Flyway 3 duplicate, duplicate JPA, or test-only library is in the copied runtime graph. Spring remains uniformly `5.3.39`; no Spring 4 artifact is present. |
| Artifact comparison | Same-JDK Java 11 build at the Phase 2E start HEAD versus Phase 2F: module JAR names and every JAR entry name/count are unchanged (Core main/test 352/485, Deploy 31/25, Peer 80/88, NIS main 623). No production class additions/removals. NIS copied runtime libraries: 76→81; H2/Flyway old JARs are replaced, with five net new runtime entries from Flyway's transitive Jackson/Gson dependencies. No Mockito, Byte Buddy/agent, Objenesis, JUnit, WireMock or spring-test leaks. Raw JAR hashes are not used as behavior evidence. |
| Memory observation | Not measured; no profiling or memory tuning was performed in this database compatibility phase. |

Remaining risks are the EOL/maintenance status of the Java 11-compatible
Flyway line, the unavailable checksum validation across Flyway 3→9, the
offline H2 file conversion required for existing production databases, the
three generated FK indexes, the public-peer test error, and the missing
matching-genesis snapshot. H2/Flyway changes beyond this compatibility wave,
schema/migration redesign, and all Phase 2G+ work remain out of scope.

### Phase 2G and later — security-only patches and deferred modernization

Concrete advisory-driven patches for crypto, HTTP, or small libraries may be
done as isolated waves when affected versions and exercised paths are proven.
Jakarta migration, Spring major modernization beyond the selected compatibility
line, cache/persistent state, and memory optimization remain later decisions.

### Current modernization status — Phase 2M

Phase 2M H2/Flyway modernization is **COMPLETE / CLOSED** on H2 `2.5.250` and
Flyway `12.11.0`. Phase 2M-A records clean migration replay, persisted
Mainnet/Testnet logical equivalence, and chain reconstruction. Phase 2M-B
records Java 17/25 root regression and the complete relevant
repository-controlled H2/DAO/controller Failsafe group. The full 79-test NIS
Failsafe suite was run on both JDKs; its external peer/Mijin prerequisites and
database-independent random/timing/performance failures are classified in the
[Phase 2M-B acceptance record](phase-2m-b-h2-flyway-integration-final-acceptance.md),
and are not represented as passing.

Phase 2L-H remains **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED**;
Phase 2L overall remains **PARTIAL**. Phase 2M does not claim hosted Jenkins
Java 25 acceptance.

### Phase 2N — NIS / Symbol Java 25 boundary

**COMPLETE / CLOSED.** `_symbol` remains a submodule, but the Java 25
repository-controlled NIS build, tests, production container, and runtime do
not depend on Symbol Java implementation. No source port or dependency change
was necessary. The Java 25 shared-library/image mapping and hosted Jenkins run
remain the separately tracked Phase 2L-H external infrastructure follow-up.
See the [Phase 2N boundary closure record](phase-2n-nis-symbol-java25-boundary-closure.md).

The next modernization audit is **Phase 2O — Final Dependency Modernization
Audit**.

### Current modernization status — Phase 2O / 2P

Phase 2O and Phase 2P are **COMPLETE / CLOSED — PHASE 2 JAVA 25
MODERNIZATION**. Phase 2O updated the justified dependency lines to Hibernate
ORM `7.4.11.Final` and H2 `2.5.252`; Spring `7.0.9`, Flyway `12.11.0`, Jetty
`12.1.13` EE11, Jakarta Persistence `3.2.0`, and compiler release `17` remain
in the accepted configuration. Its final audit classifies other direct
dependency/plugin candidates as KEEP or DEFER rather than adding unrelated
updates. Phase 2P records Java 17/25 root tests and package checks, current
runtime graph, fresh Flyway migration behavior, Mainnet/Testnet disposable-copy
replay, and the focused controller/DAO/H2 Failsafe group. The wider 79-test
Failsafe suite was also run and did not pass completely due the recorded public
peer, Mijin dataset, statistical, timing, and performance cases; no failure was
suppressed and the Phase 2 database/controller acceptance group passed.

Phase 2L-H remains **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED**
and Phase 2L remains **PARTIAL**. No hosted Jenkins Java 25 execution is
claimed. Under the Phase 2O/2P scope, hosted Jenkins image publication and
mapping are an external infrastructure follow-up, not a repository-controlled
Java 25 completion gate. See the [Phase 2O dependency audit](phase-2o-final-dependency-modernization-audit.md)
and [Phase 2P final acceptance record](phase-2p-java25-final-acceptance.md).

Phase 2 overall is **COMPLETE / CLOSED for repository-controlled Java 25
modernization**. Phase 2L-H's hosted operational follow-up remains separately
tracked and is not represented as completed.

## Explicitly deferred

- Any production dependency version change in Phase 2A.
- Further H2/Flyway upgrades, schema or migration changes, Mockito changes,
  cache/state redesign, and Phase 2G+ work after this compatibility wave.
- Java 11 support removal or changing compiler release from 11.
- Production Docker migration to Java 25. `nis/Dockerfile` still builds and
  runs on Ubuntu 22.04 with OpenJDK 11 and keeps `MEMORY_MS/MEMORY_MX` at 6G;
  a Java 25-compatible base image is a deployment decision separate from CI
  validation.
- A broad Maven/dependency cleanup, JUnit 5 migration, and Java package
  namespace migration.

## NEEDS USER DECISION

1. **Database rollout procedure:** Phase 2F later validated the supplied
   Mainnet and Testnet legacy databases on disposable copies, including
   matching-genesis chain reconstruction and post-Jakarta persisted-chain
   lifecycle. This closes the Phase 2F compatibility acceptance. Any future
   production database conversion or migration still needs an operational
   backup/maintenance procedure; direct in-place H2 file opening is not
   supported.
2. **Production container:** decide separately whether the production image
   may move from Java 11 to a Java 25-compatible base after CI validation.
3. **Security priority:** if an external advisory is shown to apply to an
   exercised NIS path, decide whether an isolated emergency patch takes
   priority over the ordered modernization waves.

## Phase 2A gate

**PHASE 2A READY WITH CONDITIONS**

The first low-coupling build/test wave was implemented separately and is
documented in `build-test-modernization.md`. Runtime framework and database
implementation waves remain conditional on the Java 11/web target decision,
representative database copies, and full consensus/state regression
verification. Phase 2C's Mockito/test-harness implementation and verification
are recorded in `build-test-modernization.md`.
