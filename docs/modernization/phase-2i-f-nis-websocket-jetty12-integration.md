# Phase 2I-F — NIS WebSocket integration on Jetty 12 EE8

Phase status: **PARTIAL**

Repository: `nemnesia/nem`

Branch: `agent/nis-phase0-baseline`

Requested / actual starting HEAD: `412e45bcf1c0a21074f9c48d21c65c7faaf10e60`

This phase kept the production Jetty 9 runtime, Spring version, NIS source, database, schema, and Jenkins configuration unchanged. It built a standalone test-only integration harness under [`phase-2i-f-poc`](phase-2i-f-poc/pom.xml), using the installed NIS module artifact and Jetty 12.1.13 EE8. It is not a production Jetty migration.

## Decision

The compatibility shim works through the real NIS broker configuration, converter, scanned WebSocket controller, and message service for a WebSocket STOMP session. The HTTP SockJS fallback opened and accepted a STOMP send, but its subsequent poll returned only the SockJS heartbeat frame `h\n`, not STOMP `CONNECTED`. The harness also has to override endpoint registration in a test subclass to inject the shim because production `NisWebAppWebsocketInitializer` does not set a custom handshake handler.

This establishes useful compatibility evidence, but it does not meet the completion bar: the XHR fallback exchange is incomplete, the exact production endpoint registration method is not run unchanged, and a Java 17 local run was unavailable. Keep the result **PARTIAL** and do not start the production Jetty migration yet.

## Runtime and current NIS architecture

| Component | Version / behavior |
| --- | --- |
| Java available in this workspace | OpenJDK `25.0.4.1`; `javac 25.0.4.1`; Maven `3.8.7` |
| Required Java 17 | Not installed. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` points to a nonexistent directory. |
| Spring Framework | `5.3.39` |
| Test-only Jetty | `12.1.13`, EE8 / `javax.servlet` / `javax.websocket` |
| Production Jetty | Unchanged: `deploy` `9.4.56.v20240826`; `nis` `9.4.58.v20250814` |

Repository implementation inventory:

- `NisWebAppWebsocketInitializer` is `@EnableWebSocketMessageBroker` and scans `org.nem.nis.websocket`.
- It registers `/messages`, allows origin patterns `*`, enables SockJS, and installs its custom `AbstractSockJsMessageCodec`. `decode(String)` parses a JSON array and returns its first string; `decodeInputStream(InputStream)` returns an empty array; outgoing SockJS quoting uses `JSONValue.escape`.
- It enables the in-memory broker for `/blocks`, `/unconfirmed`, `/errors`, `/account`, `/transactions`, `/recenttransactions`, `/node`; application destinations use `/w/api`.
- Its custom inbound `MessageConverter` expects byte-array JSON for a `JSONObject`, creates target payloads using a public `Deserializer` constructor, and rejects unexpected shapes. Outbound conversion casts to `SerializableEntity` and serializes with `JsonSerializationPolicy` to bytes.
- `WebsocketInitController` handles `/block/last`, `/node/info`, and account subscriptions/transfers/mosaic/namespace destinations. Its `@MessageExceptionHandler` publishes an `ErrorResponse` to `/errors`.
- `MessagingService` publishes node/block/account/transaction updates using `SimpMessagingTemplate`; its constructor registers it as a listener on the chain and unconfirmed state.
- The WebSocket package has no explicit NIS handshake interceptor or channel interceptor. The endpoint has no explicit NIS-specific STOMP subprotocol, heartbeat, or transport-size configuration. SockJS/Spring defaults apply.
- There is no NIS contract for raw binary WebSocket frames identified in this configuration. Application payloads are serialized to bytes within the Spring STOMP conversion path; SockJS WebSocket transport itself carries text envelopes. This probe therefore tested text frames, not a direct binary frame.

## Test-only integration architecture

`phase-2i-f-poc` depends on the real `nem-infrastructure-server:0.6.102` artifact, excludes its transitive Jetty 9 artifacts, and adds the Jetty 12.1.13 EE8 server/servlet/WebSocket modules. It starts an embedded Jetty 12 EE8 servlet context, initializes the standard `javax.websocket` container, and loads a test subclass of `NisWebAppWebsocketInitializer` with the real `org.nem.nis.websocket` component scan.

The superclass's production `configureMessageBroker` and `configureMessageConverters` methods run unchanged. The scanned `WebsocketInitController` and `MessagingService` are real NIS classes. Database/network-facing constructor dependencies are Mockito mocks, and a generated valid NEM account is used for the account handler.

The test subclass overrides only `registerStompEndpoints` to reproduce the production `/messages` path, wildcard origin, and codec while injecting `DefaultHandshakeHandler(new Jetty12Ee8UpgradeStrategy())`. The adapter extends Spring 5's `AbstractStandardUpgradeStrategy`, checks the standard `ServerContainer` attribute for Jetty 12 EE8's `JavaxWebSocketServerContainer`, and calls its public `upgradeHttpToWebSocket` method. It uses no reflection or Jetty internal API.

This override is an explicit limitation: it validates the rest of the real NIS WebSocket configuration and handlers, but does not prove that the current production initializer can select the shim without a future narrowly scoped production registration change. No such change was made here.

Reproduction after installing the current reactor artifacts into a writable local Maven repository:

```bash
cd <repository-root>
mvn -B -Dmaven.repo.local=/tmp/nem-phase2if-m2 -pl nis -am -DskipTests install
cd docs/modernization/phase-2i-f-poc
mvn -B -Dmaven.repo.local=/tmp/nem-phase2if-m2 clean package exec:java \
  -Dexec.mainClass=org.nem.nis.websocket.NisIntegrationProbe
```

The integration run used Java 25 because Java 17 is unavailable in this environment. Maven compiled the harness with `--release 17`; this is not a Java 17 runtime verification.

## Test matrix and results

| Scenario | Result | Observation |
| --- | --- | --- |
| Spring context / endpoint infrastructure startup | PASS in test subclass | Real superclass broker/converter configuration, scanned NIS controller/service, and Jetty EE8 `JavaxWebSocketServerContainer` were present. |
| SockJS `/messages/info` | PASS | HTTP 200; body advertised `websocket:true`; origin list was `*:*`. |
| WebSocket handshake | PASS in test subclass | JDK WebSocket client completed the upgrade with `Origin: http://nis-test.invalid`; selected STOMP subprotocol was `v12.stomp`. Raw 101 response headers were not captured. |
| STOMP CONNECT / CONNECTED | PASS | `v12.stomp`, STOMP 1.2. |
| SUBSCRIBE | PASS | Subscribed to existing `/node/info` broker destination. |
| SEND / real NIS inbound handler | PASS | Sent a serialized `AccountId` payload to `/w/api/account/subscribe`; the actual `WebsocketInitController` and production converter ran, and `MessagingService.observedAddresses` reflected the valid account. |
| MESSAGE / outbound conversion | PASS | Called the actual `MessagingService.pushNodeInfo()` with an isolated mock node after subscription; received a STOMP `MESSAGE` through the configured outbound converter. |
| UNSUBSCRIBE / DISCONNECT | SENT | Frames were sent before a normal WebSocket close. Receipt acknowledgement, server-side session cleanup, and abnormal disconnect were not asserted. |
| SockJS HTTP XHR polling open | PASS | `POST /messages/000/xhr-probe/xhr` returned 200 with SockJS open frame `o\n`. |
| SockJS XHR send | PASS at HTTP transport layer | `POST .../xhr_send` containing STOMP CONNECT returned 204. |
| SockJS XHR STOMP response | **INCOMPLETE** | The next polling request returned HTTP 200 with only `h\n` (heartbeat), not `CONNECTED`, after the configured idle wait. The same request/send exchange was not run against production Jetty 9, so this does not isolate Jetty as the cause. Preserve this failed experiment. |
| XHR streaming / other polling modes | NOT RUN | Only the XHR polling receive/send path above was attempted. |
| Binary / fragmented / oversized frames | NOT RUN | No raw binary WebSocket contract is configured; fragmentation and configured-size limits were not compared. |
| Error / abnormal-close behavior | NOT RUN | Invalid frames, denied origins, STOMP errors, close codes, and abnormal disconnect were not exercised. |
| Server / context shutdown | PASS, limited | The harness stopped and joined the Jetty server and closed the Spring context after the normal session path. No thread/session leak audit was performed. |

The executable printed `NIS_CONTEXT_PASS`, `NIS_SOCKJS_INFO_PASS`, `NIS_STOMP_PASS`, `NIS_SOCKJS_XHR_LIMITATION`, and `NIS_INTEGRATION_PROBE_PASS`; Maven completed successfully. The XHR limitation is deliberately reported as a limitation, not as a passing STOMP fallback.

The harness does not create a production `NemWebsockServerBootstrapper`, run the real NIS persistence/application context, or capture all handshake headers. It also uses mocks for peer/database services. No byte-for-byte Jetty 9 versus Jetty 12 comparison was performed.

## Shim quality and maintenance risk

- The harness adapter uses Spring's public `AbstractStandardUpgradeStrategy` contract and the Jetty 12.1 public EE8 container API. It has no reflection or internal-package references.
- It checks/casts the implementation to `JavaxWebSocketServerContainer`; that vendor-specific coupling is narrow but means Jetty API changes require explicit review on each Jetty update.
- The adapter delegates endpoint/session adaptation to Spring's `AbstractStandardUpgradeStrategy` and uses Jetty's synchronous upgrade API. This probe did not validate asynchronous sends, negotiated extensions, principal propagation, close/error mapping, or session cleanup.
- No production source has yet supplied a stable injection point for this adapter. Adding one must preserve current SockJS defaults and registration order; the test override alone is not sufficient evidence for production.

## Repository regression and CI

The workspace has Java 25 but no Java 17 installation, so the required local `mvn -B clean test` under Java 17 could not be run. The available Java 25 `mvn -B clean test` discovered 624 classes / 6,218 tests and ended with **0 failures, 2 errors, 0 skipped**. Both errors were `NisPeerNetworkHostTest` public-peer boot tests (`isNetworkBootingReturnsFalseIfNetworkIsBooted` and `isNetworkBootedReturnsTrueIfNetworkIsBooted`) failing with `IllegalStateException: network boot failed` while reaching public peers. This is the known environment-dependent network failure category; there were no other Surefire failures/errors. It is not counted as a clean suite pass.

`JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -B -DskipTests package` succeeded. The test-only integration harness compiled/package-built and its runtime probe completed on Java 25. These results do not replace Java 17 verification.

Hosted Actions for implementation commit `8c319b5d2869ba5f6d8fe06220ce3c20990125bc` both completed successfully, including clean tests and package:

- [Java 17 Baseline run 36242299887](https://github.com/nemnesia/nem/actions/runs/36242299887) — Temurin 17; `mvn -B clean test` and `mvn -B -DskipTests package` succeeded.
- [Java 25 Compatibility run 36242299847](https://github.com/nemnesia/nem/actions/runs/36242299847) — Temurin 25; `mvn -B clean test` and `mvn -B -DskipTests package` succeeded.

Failsafe was not run. Phase 2H's recorded Failsafe baseline (Core timing case; NIS 9 failures / 21 errors / 2 skipped, including external-peer and legacy H2-file issues) remains unchanged and was not treated as solved here.

## Existing blockers retained

- Jenkins Java 17 execution remains externally blocked and was not validated in this phase.
- Phase 2F remains unchanged: H2 1.4 database files require offline export/import; Flyway migration history/checksum constraints remain; representative existing Mainnet/Testnet DB validation remains outstanding; matching-genesis full chain-state comparison remains unavailable.
- No schema, migration SQL, H2/Flyway behavior, persistence state, production dependency, application source, or Jetty production runtime was changed.

## Recommendation for Phase 2I-G

**Do not start production Jetty migration in the current state.** First complete a focused test phase that:

1. Diagnoses why the real NIS XHR send followed by poll yields only `h\n`; run the identical transport sequence on the current Jetty 9 runtime to separate an existing NIS/SockJS contract issue from a Jetty 12 difference.
2. Tests an actual `/messages` registration path with the shim selected through the exact production configuration mechanism, or makes the smallest separately reviewed production seam necessary to inject the `HandshakeHandler`. Re-run handshake, STOMP, origin, disconnect, and SockJS tests through that path.
3. Verifies XHR streaming/polling behavior, STOMP error mapping, close/session cleanup, and any message/frame size assumptions required by clients.
4. Runs the regression suite on Java 17 and hosted Java 17 CI. Keep Java 25 results separate and retain the Jenkins external limitation.

If the production `javax.*` stack cannot be integrated using this small explicit strategy without changing Spring lifecycle or SockJS behavior, stop the EE8 shim route and design a coherent Spring 6 + Jakarta Servlet/WebSocket migration with Jetty 12 EE10. Include coordinated changes to NIS/Deploy servlet bootstrap and tests, STOMP/SockJS/controller bindings, Hibernate/JPA namespace, validation/lifecycle APIs, and all exposed `javax.*` third-party boundaries. Do not mix this design work with a Jetty production switch.
