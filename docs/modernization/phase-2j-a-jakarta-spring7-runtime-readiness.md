# Phase 2J-A — Jakarta Servlet / Spring Framework 7 runtime readiness

## Decision

**COMPLETE — PHASE 2J / 2K COORDINATED TRANSITION REQUIRED.** The web/container subset can be prototyped independently, and an isolated Spring 7 + Jetty 12.1 EE11 probe started successfully. The current NIS application cannot move its Spring runtime independently while preserving its production persistence integration: it compiles against Spring ORM 5's `org.springframework.orm.hibernate5` package, Hibernate ORM 5.4, and `javax.persistence`. Spring Framework 7's native Hibernate integration moved to `org.springframework.orm.jpa.hibernate` and is documented for Hibernate ORM 7.x. Do not deploy an intermediate Spring 7 / Hibernate 5.4 mixed graph.

This is a readiness result, not a production migration. No production source, dependency, bootstrap, protocol, schema, or runtime configuration was changed.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `c86041c78f97937b14609a6ffc90b5c01d18785b`
- Actual starting HEAD: `c86041c78f97937b14609a6ffc90b5c01d18785b`
- Starting remote tracking HEAD: `c86041c78f97937b14609a6ffc90b5c01d18785b`
- Final HEAD: the commit that adds this record (reported exactly in the final closeout).
- Changed files: this record and the standalone POC `docs/modernization/phase-2j-a-poc/pom.xml`, `docs/modernization/phase-2j-a-poc/src/main/java/poc/EmbeddedSpring7Ee11.java`.
- A pre-existing `.gitignore` change adds `legacy/`. It is unrelated, was not altered, and is not part of this record's commit.
- This record's final commit is identified by the repository HEAD after commit; no production runtime changes were made.

## Current production runtime graph

The production POMs still declare Jetty `12.1.13` EE8, Spring Framework `5.3.39`, Hibernate ORM `5.4.33.Final`, Hibernate Validator `6.2.5.Final`, and the Java EE 8 `javax` APIs. The production dependency tree from `mvn -B -pl deploy,nis -DskipTests dependency:tree -Dscope=compile` resolved Spring 5.3.39 modules (`spring-context`, `spring-jdbc`, `spring-orm`, `spring-tx`, `spring-webmvc`, `spring-websocket`, `spring-messaging`), Hibernate 5.4.33.Final, `javax.persistence-api:2.2`, and Jetty EE8 servlet/WebSocket artifacts. The NIS runtime graph was not changed.

| Boundary | Current production | Investigated target family |
|---|---|---|
| Container | Jetty 12.1.13 EE8 (`jetty-ee8-*`) | Jetty 12.1.13 EE11 (`jetty-ee11-*`) |
| Servlet | Servlet 4.0 / `javax.servlet` | Servlet 6.1 / `jakarta.servlet` |
| Spring | Framework 5.3.39 | Framework 7.0.9 |
| Persistence | Hibernate 5.4.33.Final / JPA 2.2 | Hibernate ORM 7.4.11.Final / Jakarta Persistence 3.2 |
| Validation | Hibernate Validator 6.2.5 / Bean Validation 2 / `javax.validation` | Jakarta Validation 3.1 family; Spring 7 release notes list Hibernate Validator 9.0/9.1 |

The versions in the target column are investigation targets as of 2026-09-29, not a production bill of materials. Spring's official release page identifies 7.0.9 as the current stable release; its support matrix gives Spring 7 a JDK 17–25+ and Jakarta EE 11 baseline, while Spring 5.3 is Java EE 8 / `javax` and its open-source support ended in August 2024. [Spring Framework versions](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions), [Spring Framework 7.0.9 release](https://github.com/spring-projects/spring-framework/releases)

Spring Framework 7.0 release notes specify Servlet 6.1, JPA 3.2, Bean Validation 3.1 and Jetty 12.1 as the aligned server family. They describe Hibernate ORM 7.1/7.2 integration; current Hibernate upstream lists ORM 7.4.11.Final as its latest stable release, compatible with Java 17/21/25/26, Jakarta Persistence 3.2 and Jakarta EE 11. A real migration must select a mutually supported patch set at implementation time rather than copy this readiness snapshot blindly. [Spring 7 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/d2df491db0e5856e31b1694e5c6efdf727bcb517), [Hibernate ORM releases](https://hibernate.org/orm/releases/), [Hibernate ORM 7.4 compatibility](https://hibernate.org/orm/releases/7.4/)

## Jetty EE8 → EE11 boundary inventory

The embedded production bootstrap in `deploy` currently uses:

- `org.eclipse.jetty.server.Server`, `ServerConnector`, common `GzipHandler`, `QueuedThreadPool`, and `ScheduledExecutorScheduler`;
- EE8 `ServletContextHandler`, EE8 annotations/plus/webapp configuration, EE8 servlet/servlets, and EE8 `JavaxWebSocketServletContainerInitializer` / javax WebSocket server implementation;
- `DispatcherServlet` registrations for HTTP and `/w/*`, Spring `ContextLoaderListener`, a custom servlet-context listener, JSON error handler, DoS filter and custom CORS filter;
- async support enabled on the `/w/*` dispatcher and the DoS/CORS filter registrations;
- Gzip outside the servlet context, restricted to JSON MIME type;
- Spring WebSocket/STOMP/SockJS bridge, with a NIS Jetty 12 EE8-specific strategy/provider using `javax.websocket.server.ServerContainer`.

The EE11 container family is available in Jetty 12.1.13 and retains common Jetty server APIs while changing the EE-specific artifacts and types. Relevant target artifacts include `org.eclipse.jetty.ee11:jetty-ee11-servlet`, `jetty-ee11-servlets`, `jetty-ee11-webapp`, `jetty-ee11-annotations`, and `org.eclipse.jetty.ee11.websocket:jetty-ee11-websocket-jakarta-server`; the standard API is `jakarta.websocket:jakarta.websocket-api:2.2.0`. Jetty documents Servlet 6.1 and Jakarta WebSocket 2.2 for EE11 and requires `JakartaWebSocketServletContainerInitializer` to make a Jakarta `ServerContainer` available. [Jetty 12.1 migration guide](https://jetty.org/docs/jetty/12.1/programming-guide/migration/12.0-to-12.1.html), [Jetty WebSocket server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)

The embedded bootstrap shape (server, connector, handler, gzip, listener and servlet registrations) is separable from the persistence stack, but every servlet/filter/listener signature and the WebSocket initializer/upgrade bridge must be ported to Jakarta. The EE8 shim/provider is not reusable as a class-name compatibility bridge: its API types and ServletContext attribute are javax-based. Preserve ordering by installing the EE11 WebSocket initializer before Spring listeners/context startup, then re-prove the container attribute and upgrade strategy selection.

## Spring ORM / Hibernate coupling evidence

The current persistence configuration is not abstracted behind JPA:

- `nis/src/main/java/org/nem/nis/dao/SessionFactoryLoader.java` constructs `org.springframework.orm.hibernate5.LocalSessionFactoryBuilder` directly.
- `nis/src/main/java/org/nem/specific/deploy/appconfig/NisAppConfig.java` returns `org.springframework.orm.hibernate5.HibernateTransactionManager` directly.
- `nis/src/test/java/org/nem/nis/dao/TestConf.java` also imports the Spring ORM 5 manager.
- Entities in the NIS DB model import `javax.persistence` (21 production files / 36 matching import or reference lines in the source inventory).
- Existing DAOs use Hibernate-native legacy Criteria (`createCriteria`, `org.hibernate.criterion.*`), `createSQLQuery`, and `setResultTransformer`, in addition to HQL/native SQL and Hibernate Session APIs. The source search found these patterns across production DAOs and their tests; they are not a replaceable POM-only concern.
- `NisWebAppWebsocketInitializer` extends Spring 5's `AbstractWebSocketMessageBrokerConfigurer`. That class is absent from the Spring WebSocket 7.0.9 JAR; `WebSocketMessageBrokerConfigurer` remains. The initializer therefore needs a small source-level migration to the interface/default-method model, in addition to the namespace and container bridge changes.
- The project uses Spring's `org.springframework.transaction.annotation.Transactional` and `@EnableTransactionManagement`; no `javax.transaction` imports or explicit `PersistenceExceptionTranslationPostProcessor` registration were found. DAOs are annotated with Spring `@Repository`. Exception translation behavior and transaction semantics must therefore be regression-tested in the coordinated port rather than assumed from package compatibility.

Direct inspection of the Spring ORM 7.0.9 JAR showed `LocalSessionFactoryBuilder` and `HibernateTransactionManager` under `org.springframework.orm.jpa.hibernate`; there are no `org.springframework.orm.hibernate5` classes. Spring 7 release notes explicitly say the old package moved and the continuing native Hibernate facilities are for Hibernate ORM 7.x, integrated with JPA. Spring's ORM documentation states that Framework 7 requires Hibernate ORM 7.x for `HibernateJpaVendorAdapter`. [Spring 7 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/d2df491db0e5856e31b1694e5c6efdf727bcb517), [Spring Hibernate integration](https://docs.spring.io/spring/reference/7.0-SNAPSHOT/data-access/orm/hibernate.html), [Spring ORM Hibernate package API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/hibernate/package-summary.html)

This is a compile/API boundary, not a claim that every Spring web module intrinsically requires Hibernate 7. A servlet-only application can use Spring 7 without Hibernate. The current NIS module cannot: its persistence bootstrap, transaction manager, mapped entity namespace, and DAO APIs bind the running application to the old stack. Splitting those into independently deployable modules would itself be an architectural change and is not evidenced here.

## `javax.*` migration surface inventory

Inventory counts are source-file counts and matching import/reference line counts from `nis`, `deploy`, and relevant `core` Java source roots. They are not a count of distinct classes or required edits; wildcard imports make symbol totals differ from file totals.

| Package family | Production | Tests / IT | Treatment |
|---|---:|---:|---|
| `javax.servlet` | 17 files / 25 lines | 9 files / 10 lines | Port deploy bootstrap, filters, listeners, servlet APIs, NIS controllers/interceptors and tests to `jakarta.servlet` |
| `javax.persistence` | 21 / 36 | 0 | Port entity annotations/types with Hibernate 7 / Jakarta Persistence; schema behavior must be verified, not mass-replaced |
| `javax.validation` | 1 / 1 | 1 / 8 | Port API, constraints and compatibility tests to Jakarta Validation 3.1 / compatible provider |
| `javax.transaction` | 0 / 0 | 0 / 0 | No direct imports found; Spring transaction annotations remain a Spring API concern |
| `javax.annotation` | 1 / 1 | 0 | `javax.annotation.PostConstruct` in `NisMain` requires Jakarta annotation equivalent |
| JAXB / activation (`javax.xml.bind`, `javax.activation`) | 0 / 0 | 0 / 0 | No Java import references found |
| WebSocket (`javax.websocket`) | 2 / 4 | 0 | NIS EE8 strategy imports javax Endpoint/Extension/ServerContainer and servlet request/response; replace with Jakarta WebSocket / Servlet and EE11 Jetty integration |
| Java SE `javax.sql` | 2 / 2 | 2 / 2 | Keep `javax.sql.DataSource`; this is Java SE, not Jakarta EE |
| Other Java SE `javax.*` | `javax.security.auth.x500.X500Principal` in core test | present in test source | Keep Java SE APIs; do not globally rewrite |

No non-Java configuration references to these EE namespace families were found in the inspected module configuration. The validator test loads the EL factory by class name and will need to follow the Jakarta validation/EL family in the coordinated migration. Do not perform a global `javax`→`jakarta` replacement.

## Isolated compatibility spike

The isolated POC in `docs/modernization/phase-2j-a-poc/` is a standalone Maven project outside the root reactor; its dependencies are not declared by NIS or copied to production packaging. It uses Java compiler `--release 17`, Jetty `12.1.13` EE11 artifacts, and Spring Framework `7.0.9` web MVC, WebSocket, and messaging modules. It starts an embedded EE11 servlet context, configures `JakartaWebSocketServletContainerInitializer` before the Spring dispatcher, registers a Jakarta listener/filter and the dispatcher at `/w/*`, bootstraps Spring MVC plus Spring's STOMP/SockJS broker configuration, and exercises HTTP requests.

Reproduction commands from repository root (the isolated POC files are committed; `/tmp` is only Maven's disposable artifact cache):

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH \
  mvn -B -f docs/modernization/phase-2j-a-poc/pom.xml \
  -Dmaven.repo.local=/tmp/phase2j-a-poc-m2 clean compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=poc.EmbeddedSpring7Ee11

JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH \
  mvn -B -f docs/modernization/phase-2j-a-poc/pom.xml \
  -Dmaven.repo.local=/tmp/phase2j-a-poc-m2 clean compile \
  org.codehaus.mojo:exec-maven-plugin:3.5.1:java \
  -Dexec.mainClass=poc.EmbeddedSpring7Ee11
```

Observed result on Java 17 and Java 25 runtimes (both compile with `--release 17`):

```text
POC_MVC=200 spring7-ee11-ok
filter=registered
POC_SOCKJS_INFO=200 {"entropy":...,"origins":["*:*"],"cookie_needed":true,"websocket":true}
POC_JAKARTA_SERVER_CONTAINER=org.eclipse.jetty.ee11.websocket.jakarta.server.JakartaWebSocketServerContainer
POC_JAKARTA_LISTENER=true
BUILD SUCCESS
```

This proves EE11 servlet/filter/listener startup, Spring 7 context/DispatcherServlet routing, SockJS `/info`, and Jakarta `ServerContainer` initialization. It does **not** prove a WebSocket HTTP 101 handshake, STOMP CONNECT/CONNECTED/MESSAGE/DISCONNECT, XHR polling exchange, or NIS endpoint compatibility; those remain required in the coordinated production-candidate validation. The POC did not contain Hibernate and did not modify production POMs. Spring ORM 7.0.9 artifact inspection separately confirms the package move above; Spring WebSocket 7.0.9 artifact inspection confirms the old abstract broker configurer is absent.

## Recommended transition boundary and order

Use a disposable coordinated Phase 2J/2K integration branch; do not merge or deploy a state with Spring 7 / Jakarta web runtime and the current Hibernate 5.4 / javax persistence application graph.

1. **Persistence integration first, still off production:** select mutually compatible Hibernate ORM 7.x, Jakarta Persistence 3.2, Spring Framework 7 and Jakarta Validation versions; port entity imports, SessionFactory/transaction bootstrap and Hibernate 5-era Criteria/native query APIs. Keep schema/migration scripts unchanged unless an independently evidenced compatibility issue requires a separately reviewed database decision. Run query/DAO and transaction tests.
2. **Shared Jakarta boundary:** port servlet, validation, annotation and WebSocket APIs plus tests/configuration. Keep Java SE `javax.sql` and other Java SE namespaces unchanged.
3. **EE11 runtime:** swap Jetty EE8 artifacts to EE11, replace the WebSocket initializer and NIS upgrade strategy/provider, and preserve servlet mappings, async support, CORS, DoS, Gzip, error mapping and startup ordering.
4. **End-to-end gates:** validate REST contracts, WebSocket/STOMP and XHR SockJS transports, reconnect/session cleanup, startup/shutdown/thread lifecycle, then Java 17 and Java 25 test/package. Do not claim persisted-chain equivalence for Phase 2K until the independent Phase 2F trusted snapshot gate is satisfied.

Rollback point: retain the current production branch and artifacts unchanged until the coordinated integration branch passes the full gates; revert/abandon that integration branch as a unit if persistence or transport behavior diverges. A web-only production cutover is not a safe rollback boundary for the existing NIS module.

## Validation and gates

- Production source, POMs, dependency tree, servlet bootstrap and runtime behavior: unchanged.
- Isolated standalone POC in `docs/modernization/phase-2j-a-poc/`: Java 17 and Java 25 runtime probes passed using separate fresh temporary Maven repositories; Maven logs show the Spring/Jetty artifacts resolved from `repo.maven.apache.org`. Both used Java 17 source compatibility (`--release 17`).
- Current production dependency graph remains Jetty EE8 + Spring 5.3.39 + Hibernate 5.4.33.Final; no POC dependencies were added to production artifacts or `nis/target/libs`.
- No repository Maven tests were rerun: the committed change is readiness documentation only, and the runtime proof was executed in an isolated `/tmp` POC. Existing Jetty 12 production regression evidence remains the Phase 2I-S baseline; it is not recharacterized as a Spring 7 regression run.
- `git diff --check` is required for this documentation change.
- Phase 2F remains **BLOCKED — external trusted artifact/evidence owner required**. Existing DB candidates and their runtime compatibility evidence are not provenance evidence. Trusted Mainnet/Testnet snapshots remain required before full persisted-chain equivalence can be claimed in Phase 2K.

## Upstream sources consulted

All version/support sources below were checked on 2026-09-29:

- [Spring Framework versions and support matrix](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions)
- [Spring Framework 7.0 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes/d2df491db0e5856e31b1694e5c6efdf727bcb517)
- [Spring Framework 7 Hibernate integration docs](https://docs.spring.io/spring/reference/7.0-SNAPSHOT/data-access/orm/hibernate.html)
- [Spring ORM 7 Hibernate package API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/hibernate/package-summary.html)
- [Jetty 12.1 compatibility table](https://jetty.org/docs/jetty/12.1/index.html)
- [Jetty 12.1 migration guide](https://jetty.org/docs/jetty/12.1/programming-guide/migration/12.0-to-12.1.html)
- [Jetty EE11 WebSocket server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)
- [Jakarta Validation 3.1 specification](https://jakarta.ee/specifications/bean-validation/3.1/)
- [Hibernate ORM releases and compatibility matrix](https://hibernate.org/orm/releases/)
- [Hibernate ORM 7.4 compatibility](https://hibernate.org/orm/releases/7.4/)
