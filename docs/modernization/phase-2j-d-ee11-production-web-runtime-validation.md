# Phase 2J-D — Spring 7 / Jetty 12 EE11 production-equivalent web runtime

## Result

**COMPLETE — EE11 PRODUCTION WEB RUNTIME VALIDATED**

This phase validates the EE11 web runtime boundary on synthetic in-memory data. It does not validate trusted Mainnet/Testnet database artifacts; the separate Phase 2F trusted snapshot gate remains `BLOCKED — external trusted artifact/evidence owner required`.

## Repository and revisions

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase2j-jakarta-integration`
- Requested starting HEAD: `95600e3a8380a813d7be451f6f4b8bdd48cdc72a`
- Actual starting HEAD: `95600e3a8380a813d7be451f6f4b8bdd48cdc72a`
- Validated implementation HEAD: `fa8e4a876` (`[nis] test: validate EE11 production web runtime`)
- Starting worktree on resume: contained the in-progress Phase 2J-D source/test changes; an unrelated untracked `docs/modernization/phase-2i-h-poc/node_modules/` directory was also present and was left untouched/uncommitted.
- Base branch: not modified or merged
- This evidence record is committed separately after the validated implementation commit.

## Runtime and production parity

The probe starts the production `NemServerBootstrapper` and `NemWebsockServerBootstrapper`, production Jetty handler construction, `NisAppConfig`, NIS configuration policy, servlet context listener/filter registration, production Spring MVC initializers, WebSocket/SockJS/STOMP configuration, and the production message handler path.

Runtime checks observed Spring Framework `7.0.9`, Jetty `12.1.13`, Jakarta Servlet specification `6.1`, Jakarta WebSocket specification `2.2`, and Jetty EE11 WebSocket container classes. The production runtime dependency tree and packaged `nis/target/libs` contain Jetty EE11 and Jakarta Servlet/WebSocket APIs; no Jetty EE8 servlet/WebSocket artifact or `javax.servlet` / `javax.websocket` API was present. Java SE `javax.sql` remains unrelated to Jakarta EE migration.

The harness substitutes an in-memory H2 `2.2.220` database with the repository's schema migrations and ephemeral HTTP ports. NIS auto-boot is disabled to prevent peer discovery/synchronization; `CommonStarter` is mocked to avoid external node/network lifecycle. NIS application context, genesis/cache initialization, servlet/filter registration, controllers, WebSocket handlers, and broker are real production beans. The harness temporarily isolates `user.home` to a temporary directory. No production protocol, serialization, schema, or persistence behavior is changed.

The live test is named `Ee11ProductionWebRuntimeProbe` so the NIS startup probe is opt-in and does not leave NIS's one-shot global state in the ordinary unit-test JVM. It is run explicitly with the command below.

## Live runtime evidence

### REST

- Both production Jetty bootstrappers started and both Spring DispatcherServlet instances initialized.
- A live HTTP client request to `/heartbeat` returned HTTP `200`, JSON media type, and the expected NIS body fields (`type: 2`, `code: 1`, `ok`).
- No servlet/filter runtime exception was observed.

### Async support

The probe inspects the runtime registrations. Async support is enabled on the REST and WebSocket DispatcherServlets and on every registered DoS/CORS filter in both contexts. This exposed that the production REST DispatcherServlet had not opted into async support. `NemServerBootstrapper` now sets `dispatcher.setAsyncSupported(true)`, matching the already-enabled WebSocket servlet and the filter chain. SockJS XHR completed without an “Async support must be enabled” exception.

### Native WebSocket and STOMP

- Java `HttpClient` completed the WebSocket handshake against `/w/messages/.../websocket` and negotiated `v12.stomp` (successful client WebSocket establishment confirms the HTTP upgrade).
- STOMP `CONNECT` received `CONNECTED`.
- A `/blocks` subscription followed by a `SEND` to `/w/api/block/last` traversed the production NIS message handler and delivered a STOMP `MESSAGE`.
- `DISCONNECT` with a receipt returned the matching `RECEIPT`.
- The normal WebSocket closed and active session count returned to zero.

### Abrupt close and reconnect

The test also aborts a connected client. Spring's WebSocket transport-error session counter changed from `0` to `1`; the active session count returned to zero. The probe then opened a new WebSocket and completed the full normal STOMP/message/receipt flow. No uncaught server exception was observed. This records the transport callback as an expected abrupt-close observation while confirming cleanup and reconnect.

### SockJS XHR polling

- `GET /w/messages/info` returned `200` and advertised WebSocket support.
- SockJS XHR opened a session using `POST .../xhr`, sent STOMP frames through `.../xhr_send`, and received `CONNECTED`, the NIS `MESSAGE`, and the requested disconnect `RECEIPT` through polling responses.
- XHR send requests returned `204`; polling/open responses returned `200`.
- After graceful disconnect, broker active-session metrics returned to zero. No pending asynchronous poll was left by the completed request sequence.

### Shutdown and resources

Both Jetty servers stopped and joined; their thread pools were no longer running. Spring's WebSocket broker emitted its normal stopping/stopped lifecycle and the application context closed. WebSocket and SockJS active-session metrics were zero before shutdown. The test does not treat short-lived idle worker threads as a leak.

## Regression and build results

Commands use Java 17 by default and Java 25 by setting `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` and prepending its `bin` directory to `PATH`.

```bash
mvn -B -pl nis -am -Dtest=Ee11ProductionWebRuntimeProbe -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B clean test
mvn -B clean package
```

The runtime probe passed on both Java 17 and Java 25: `1 test, 0 failures, 0 errors, 0 skipped` each.

The first version used the ordinary Surefire `*Test` naming pattern. Starting NIS in the same test JVM exposed its process-global `NemGlobals` state and caused unrelated later fork/mosaic/remote observer tests to fail. The probe now resets the test globals and is named `...Probe`, so it is executed only by the explicit command above in its own Surefire invocation. The ordinary full suite then passed at its expected 6,227-test discovery count; no assertions or tests were weakened.

| Runtime | `clean test` | `clean package` |
|---|---:|---:|
| Java 17 | 6,227 tests; 0 failures, 0 errors, 0 skipped | 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 25 | 6,227 tests; 0 failures, 0 errors, 0 skipped | 6,227 tests; 0 failures, 0 errors, 0 skipped |

Java 17 had two isolated timing-sensitive `AsyncTimerTest` failures on intermediate package attempts (`closeStopsRefreshing` and later `visitorIsNotifiedOfDelays`). The affected test/method reruns passed (the full 16-test class and the individual method, respectively); the subsequent complete clean package passed. No timer test was disabled or changed. These failures were unrelated to the EE11 changes. No Java 25 failures occurred.

The target EE11 runtime graph was generated from the reactor (`mvn -B -pl nis -am -DskipTests dependency:tree -Dscope=runtime`) to avoid stale locally installed module artifacts. The final `nis/target/libs` package includes Jakarta Servlet `6.1.0`, Jakarta WebSocket `2.2.0`, and `jetty-ee11-*` `12.1.13`; scans found no `jetty-ee8-*`, `javax.servlet`, or `javax.websocket` artifact.

## Hosted CI

Java 17 Baseline and Java 25 Compatibility workflows support `workflow_dispatch`, but automatic push triggers name only `dev`, `main`, and `agent/nis-phase0-baseline`, not this integration branch. Manual dispatch was unavailable because the configured GitHub CLI token is invalid (`gh auth status` reported the active token invalid). No hosted run is claimed.

## Changes

- `deploy/src/main/java/org/nem/deploy/server/NemServerBootstrapper.java`: enable async support for the REST DispatcherServlet.
- `nis/src/main/java/org/nem/specific/deploy/Jetty12WebSocketUpgradeStrategyProvider.java`: update stale Spring 5 / EE8 comment to describe the Spring Jakarta WebSocket bridge on EE11.
- `nis/src/test/java/org/nem/specific/deploy/Ee11ProductionWebRuntimeProbe.java`: opt-in live REST/WebSocket/STOMP/SockJS XHR runtime probe, async/session/lifecycle assertions, and abrupt-close callback metric.
- This document records the runtime evidence.

No Flyway SQL, schema, Hibernate persistence semantics, protocol, serialization, consensus, or REST/WebSocket application contract changed.

## Phase 2F separation

This work used only a synthetic in-memory H2 database. It does not authenticate or validate the supplied Mainnet/Testnet DB candidates. The Phase 2F trusted snapshot gate remains BLOCKED pending an external trusted artifact/evidence owner and authenticated provenance, quiescence, and independent checkpoint evidence.

## Reproduction

Run the opt-in probe on either supported JDK:

```bash
mvn -B -pl nis -am -Dtest=Ee11ProductionWebRuntimeProbe -Dsurefire.failIfNoSpecifiedTests=false test
```
