# Phase 2I-S — Production Jetty 12 EE8 migration

**Status: COMPLETE — production Jetty 12 EE8 migration verified.**

## Scope and starting state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested and actual starting HEAD: `903e7829b8191977f2850e99846dd60dd4bdacaa`
- Final production implementation HEAD: `b95d890172e7cd847f896be6d5e2698d83980d95` (the subsequent commit only adds diagnostic instrumentation and final evidence).
- Target: Jetty `12.1.13` EE8 / Servlet 4.0 while retaining Spring `5.3.39` and `javax.*`.
- Production migration is limited to NIS/Deploy Jetty dependencies and bootstrap compatibility. No Spring, Hibernate, database, schema, Flyway, or protocol upgrade is included.

## Dependency and bootstrap changes

Changed files:

- Production/build: `deploy/pom.xml`, `nis/pom.xml`, `deploy/src/main/java/org/nem/deploy/CommonStarter.java`, `deploy/src/main/java/org/nem/deploy/JsonErrorHandler.java`, `deploy/src/main/java/org/nem/deploy/server/AbstractNemServletContextListener.java`, `deploy/src/main/java/org/nem/deploy/server/AbstractServerBootstrapper.java`, `deploy/src/main/java/org/nem/deploy/server/NemWebsockServerBootstrapper.java`, `nis/src/main/java/org/nem/specific/deploy/Jetty12WebSocketUpgradeStrategy.java`, `nis/src/main/java/org/nem/specific/deploy/Jetty12WebSocketUpgradeStrategyProvider.java`, and its `META-INF/services` entry.
- Test: `deploy/src/test/java/org/nem/deploy/JsonErrorHandlerTest.java`.
- Verification/documentation: `docs/modernization/phase-2i-s-poc/` and this phase record.

Deploy's Jetty dependencies moved from Jetty `9.4.56.v20240826` to the Jetty `12.1.13` EE8 artifact family. NIS's Jetty `9.4.58.v20250814` dependencies were removed; NIS now receives the EE8 Servlet/runtime implementation through Deploy and has the Jetty 12 EE8 WebSocket server artifact required by its compatibility provider. The direct `javax.servlet-api:4.0.1` dependency was removed after runtime-tree inspection found duplicate `javax.servlet` classes alongside Jetty EE8's `jetty-servlet-api:4.0.9`. The packaged runtime uses the Servlet API supplied with the Jetty EE8 family, as confirmed by dependency and package inspection below.

`AbstractServerBootstrapper` now constructs `org.eclipse.jetty.ee8.servlet.ServletContextHandler`; NCC's annotation/plus configuration list uses EE8 configuration classes. `NemWebsockServerBootstrapper` installs `JavaxWebSocketServletContainerInitializer` before Spring context listeners so the `javax.websocket.server.ServerContainer` is available during Spring initialization. The existing production NIS initializer discovers the provider through its SPI and chooses the Jetty 12 strategy for `JavaxWebSocketServerContainer`; Spring remains on 5.3.39. The strategy uses Jetty 12's public EE8 WebSocket container upgrade API.

The production `DispatcherServlet` and applicable DoS, CORS, and WebSocket upgrade registrations are async-enabled. Jetty 12 relocated `DoSFilter` to `org.eclipse.jetty.ee8.servlets.DoSFilter`. The former JSON-only `GzipFilter` was replaced with a Jetty core `GzipHandler` configured for `application/json`; this preserves the existing JSON compression intent without mixing EE9/Jakarta filters into the EE8 application. The error handler and relocated Jetty response/content APIs were adapted to Jetty 12. The existing Java 17 Docker build/runtime images and launch classpath remain unchanged; they consume the newly packaged dependency set.

## Runtime verification

The committed diagnostic harness in `phase-2i-s-poc` invokes the production `NemServerBootstrapper` and `NemWebsockServerBootstrapper`, production servlet-context listeners/filters, the NIS WebSocket initializer, and the production SPI provider. It uses mocked chain/network collaborators and a narrow test MVC configuration importing the real heartbeat controller; it does not boot a database-backed node or connect to public peers.

The standard `sockjs-client@1.6.1` probe explicitly selected each transport. Across Jetty 12 EE8 production bootstrap smoke runs on Java 17 and Java 25:

| Path | Observed result |
| --- | --- |
| REST heartbeat | `/heartbeat` returned HTTP 200 and the existing JSON `ok` response. An undefined route returned the JSON 404 response. |
| SockJS info | `/w/messages/info` returned HTTP 200. Servlet mapping was the production `/w/*` DispatcherServlet, async-supported. |
| WebSocket | Standard SockJS client selected `websocket`; generated `/w/messages/{server}/{session}/websocket` request upgraded with HTTP 101. STOMP CONNECT/CONNECTED succeeded, `/node/info` reached the NIS handler and returned MESSAGE, and receipt-bearing DISCONNECT received its receipt. |
| XHR polling | Client was forced to `xhr-polling`; session open, CONNECT/CONNECTED, `/node/info` handler MESSAGE, receipt-bearing DISCONNECT, and normal close succeeded. This is not a WebSocket fallback result. |
| Cleanup/reconnect | Normal WebSocket and XHR session maps returned to zero. Abandoned XHR cleanup was observed at about 9.8 seconds; abrupt WebSocket cleanup at about 10.1 seconds. Reconnect after abrupt WebSocket close succeeded. |

The abrupt WebSocket close produced the already documented Jetty 12/Spring transport error callback (`ClosedChannelException`) followed by one close callback. It did not reach the NIS application handler, session cleanup completed, and reconnect worked; this is consistent with Phase 2I-R and is not suppressed by this migration.

The smoke harness reported QTP queue size zero and `StatisticsHandler.activeRequests=0` at all settled checkpoints. Startup configuration was `minThreads=8`, `maxThreads=200`, `idleTimeout=60000ms`; 7 threads remained busy for server/control work. Java 17 samples were 8 threads at startup, 9 after WebSocket, 11 after XHR, 14 after abandoned-XHR cleanup, and 14 after abrupt-WebSocket cleanup. Java 25 samples were 8, 9, 12, 12, and 12 respectively. Both shutdowns reported `STOPPED`, zero pool threads, and zero queue. These short runs showed no repeated-cycle growth; thread count need not immediately return to `minThreads`, consistent with Phase 2I-R's pinned Jetty pool eviction findings.

## Build and package validation

| Runtime | `mvn -B clean test` | `mvn -B clean package` |
| --- | --- | --- |
| Java 17 (`17.0.20.1`, Maven `3.8.7`) | **Pass** — 6,220 tests, 0 failures/errors/skips | **Pass** — 6,220 tests, 0 failures/errors/skips |
| Java 25 (`25.0.4.1`, Maven `3.8.7`) | **Pass** — 6,220 tests, 0 failures/errors/skips | **Pass on retry** — first run had `AsyncTimerTest.firstFireFutureIsSetAfterFirstExceptionalCompletion` fail once (Core 2,361 tests); immediate clean retry passed all 6,220 tests with 0 failures/errors/skips. No test or timeout was changed. |

The prior full-suite baseline was 6,220 tests in 625 classes, with no failures, errors, or skips. This remains two tests and one test class above the earlier Phase 2H discovery record (6,218 tests / 624 classes); no tests were disabled or removed. The difference is retained as a historical count difference rather than treated as a Jetty regression.

## Dependency/runtime audit

The final `nis` runtime dependency tree and `infra/package.prepare.sh package` output were inspected:

- Jetty core and EE8 artifacts resolve consistently to `12.1.13` (including `jetty-ee8-servlet`, `jetty-ee8-webapp`, and `jetty-ee8-websocket-javax-server`). `spring-websocket` remains `5.3.39`.
- EE8 uses `jetty-servlet-api:4.0.9` and `jetty-javax-websocket-api:1.1.2`; the runtime smoke confirmed `javax.servlet.ServletContext` loads from `jetty-servlet-api-4.0.9.jar`.
- The packaged `infra/package/libs` contains no Jetty `9.4.*` artifact and no `javax.servlet-api` duplicate. A jar-content scan found `javax/servlet/Servlet.class` only in `jetty-servlet-api-4.0.9.jar`.
- The packaged NIS jar contains `META-INF/services/org.nem.specific.deploy.NisWebSocketUpgradeStrategyProvider`.
- The runtime package is the same `/app/libs/*` classpath assembled by the existing NIS Docker build. Docker daemon access was denied in this environment, so the Docker image itself was not built; the exact package classpath was assembled and audited directly.

## CI and independent gates

The production migration commit `b95d890172e7cd847f896be6d5e2698d83980d95` passed both hosted workflows. Each workflow completed `mvn -B clean test` and `mvn -B -DskipTests package` successfully:

- [Java 17 Baseline run 36304308477](https://github.com/nemnesia/nem/actions/runs/36304308477): success.
- [Java 25 Compatibility run 36304308480](https://github.com/nemnesia/nem/actions/runs/36304308480): success.

The subsequent documentation/diagnostic-only follow-up does not change the production implementation.

These independent historical limitations remain open:

- Jenkins Java 17 execution image/shared-library resolver is externally blocked; GitHub Actions success does not validate Jenkins.
- Phase 2F H2 1.4 offline conversion, Flyway history/checksum, representative Mainnet/Testnet DB validation, and matching-genesis chain-state comparison remain unresolved and are not changed here.
- The Phase 2I-M historical WebSocket 404 remains in its original record. Phase 2I-P and later production-equivalent traces succeeded on the correct `/w/*` path; the old event is not erased or reclassified as though it never occurred.
- The earlier test-class/test-count delta is preserved above.

## Final assessment

The production runtime has been switched to Jetty 12.1.13 EE8. Local clean test/package, production-bootstrap REST/WebSocket/XHR smoke on Java 17 and Java 25, dependency/package audit, and hosted Java 17/25 workflows pass. Java 25 package required one clean retry after `AsyncTimerTest.firstFireFutureIsSetAfterFirstExceptionalCompletion` failed once; the immediate clean retry passed without code or test changes. The Docker image itself was not built because the local Docker daemon socket was inaccessible, but the exact package classpath consumed by that image was generated and audited. This phase does not close the independent Jenkins or Phase 2F rollout gates.
