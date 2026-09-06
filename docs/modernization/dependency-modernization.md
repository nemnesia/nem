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
| `REQUIRED` | Spring Framework `4.3.30.RELEASE` | NIS runtime uses Spring Core/ORM/WebMVC/WebSocket/Messaging and Java 25 startup still needs `--add-opens=java.base/java.lang=ALL-UNNAMED` because of the old reflection path. The 4.3 line is EOL, so a supported replacement/upgrade must be planned, but it is a coupled runtime wave. |
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

The Java 25 profile currently keeps the production runtime workaround out of
the normal test/build configuration. A bounded startup smoke test required
`--add-opens=java.base/java.lang=ALL-UNNAMED` for legacy Spring/CGLIB class
definition behavior. The old Mockito artifact separately requires test JVM
opens. Therefore:

- Mockito can be modernized as a test-only wave, subject to test API changes
  and Java 21+ agent/instrumentation behavior.
- The production `java.lang` open must remain until the selected Spring/web
  stack has been tested without it.
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

### Phase 2D — web/runtime framework alignment

Choose one compatible Spring/Jetty/servlet family for NIS and deploy. The
candidate must preserve Java 11 runtime support, existing `javax` interfaces
unless separately approved, HTTP/WebSocket behavior, startup, and Spring ORM
integration. This wave should include the CGLIB/reflection workaround test.

Required verification: startup without the production open where possible,
HTTP/WebSocket smoke tests, controller serialization, peer/deploy startup,
and consensus/state regression snapshots.

### Phase 2E — ORM and validation compatibility

Only after the web/runtime family is stable, evaluate Hibernate EntityManager,
Hibernate Validator, and their bytecode/JPA dependencies as a controlled
persistence wave. Keep schema and migrations unchanged.

Required verification: SessionFactory bootstrap, all DAO tests, HQL/native SQL,
transaction and rollback tests, entity mapping, and deterministic block/state
snapshots.

### Phase 2F — H2/Flyway database compatibility

Treat H2 and Flyway as one database wave unless evidence proves an independent
move safe. The first target should be selected only after a disposable copy of
representative data is available. Do not introduce a new schema or state table
in this wave.

Required verification: migration replay, existing-database open, schema
metadata, all database tests, rollback/fork behavior, block replay, and
full-chain comparison. A failed migration or changed persisted meaning stops
the wave.

### Phase 2G and later — security-only patches and deferred modernization

Concrete advisory-driven patches for crypto, HTTP, or small libraries may be
done as isolated waves when affected versions and exercised paths are proven.
Jakarta migration, Spring major modernization beyond the selected compatibility
line, cache/persistent state, and memory optimization remain later decisions.

## Explicitly deferred

- Any production dependency version change in Phase 2A.
- Spring, H2, Flyway, Mockito, schema, migration, cache, and state changes in
  this phase.
- Java 11 support removal or changing compiler release from 11.
- Production Docker migration to Java 25. `nis/Dockerfile` still builds and
  runs on Ubuntu 22.04 with OpenJDK 11 and keeps `MEMORY_MS/MEMORY_MX` at 6G;
  a Java 25-compatible base image is a deployment decision separate from CI
  validation.
- A broad Maven/dependency cleanup, JUnit 5 migration, and Java package
  namespace migration.

## NEEDS USER DECISION

1. **Spring/web target family:** retain the `javax`/Java 11-compatible web
   contract while moving to a supported Spring/Jetty combination, or approve
   a larger `jakarta`/servlet migration with deployment and API consequences.
2. **Database upgrade policy:** approve testing H2/Flyway against production
   database copies and define whether opening existing Mainnet/Testnet files
   in-place is required. No database target can be selected safely without
   this evidence.
3. **Production container:** decide separately whether the production image
   may move from Java 11 to a Java 25-compatible base after CI validation.
4. **Security priority:** if an external advisory is shown to apply to an
   exercised NIS path, decide whether an isolated emergency patch takes
   priority over the ordered modernization waves.

## Phase 2A gate

**PHASE 2A READY WITH CONDITIONS**

The first low-coupling build/test wave can be planned from repository evidence.
Runtime framework and database implementation waves remain conditional on the
Java 11/web target decision, representative database copies, and full
consensus/state regression verification. Phase 2B has not been implemented.
