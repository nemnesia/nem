# Phase 2I-G — Jetty 9 / Jetty 12 WebSocket contract comparison

Phase status: **PARTIAL**

Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested / actual starting HEAD: `f1043b05da160aa27b975b4c766e914f51267c75`
Final HEAD: recorded after commit

## Scope and environment

This phase did not change production Jetty, Spring, application source, namespace, persistence, or Jenkins configuration. It added isolated Java 25 control launchers under `phase-2i-g-poc`, for Jetty 9.4.58 and Jetty 12.1.13 EE8. Both use the actual NIS initializer/broker/converter/component scan except that Jetty 12 test wiring overrides endpoint registration to inject the investigated shim. The launchers exercise the same XHR request sequence and instrument Spring `clientInboundChannel` for STOMP CONNECT.

Available local runtime: OpenJDK / `javac` `25.0.4.1`; Maven `3.8.7`. `mvn -version` without an explicit `JAVA_HOME` fails because its configured Java 17 path is absent. Java 11, 21, and 25 are present; Java 17 is not. No global JDK setting was changed. The probes and root tests therefore ran on Java 25; this is not Java 17 runtime verification.

## Current production registration path

`NisConfigurationPolicy.getWebAppWebsockInitializerClass()` selects `NisWebAppWebsocketInitializer.class`. `NemWebsockServerBootstrapper.WebsocketContextListener` passes that class to `AbstractNemServletContextListener`, which creates the Spring web application context in the servlet lifecycle; the initializer registers `/messages`, SockJS and the NIS codec. Jetty 9 server/servlet context construction is in `AbstractServerBootstrapper` / `NemWebsockServerBootstrapper`.

There is no production configuration property or bean injection point for a `RequestUpgradeStrategy`/`HandshakeHandler`. The Jetty 12 probe subclasses `NisWebAppWebsocketInitializer` and overrides `registerStompEndpoints` to attach the shim. It invokes the actual superclass broker/converter setup and scans real NIS websocket components, but does **not** prove shim selection through the unchanged production endpoint-registration method. No production seam was added in this phase.

## Matched SockJS XHR sequence

Both probes sent to one SockJS session:

1. `GET /messages/info` with `Origin`.
2. `POST /messages/000/nis-g-control/xhr` with `Origin` and `application/javascript`.
3. `POST /messages/000/nis-g-control/xhr_send` with `application/json;charset=UTF-8` and a JSON-array-wrapped STOMP `CONNECT` frame.
4. A second `POST .../xhr` to poll for `CONNECTED`.

The receive request is synchronous and completes before `xhr_send`; this is not yet compared to a standards-conforming SockJS client maintaining a receive stream while sending.

| Observation | Jetty 9.4.58 | Jetty 12.1.13 EE8 |
| --- | --- | --- |
| `/messages/info` | HTTP 200 | HTTP 200; `websocket:true`; wildcard origin advertised |
| XHR session open | HTTP 200; body `o\n` | HTTP 200; body `o\n` |
| `xhr_send` CONNECT | HTTP 204 | HTTP 204 |
| CONNECT observed at Spring `clientInboundChannel` | **0** | **0** |
| Subsequent XHR poll | HTTP 200; body `h\n` only | HTTP 200; body `h\n` only |
| CONNECTED queued/serialized to SockJS session | No; CONNECT did not reach inbound channel | No; CONNECT did not reach inbound channel |

Both runtimes produce the same result. The missing CONNECTED is not isolated to Jetty 12. Instrumentation shows Spring does not receive STOMP CONNECT on either runtime; this is not a failure in the NIS STOMP handler after CONNECT delivery.

### Source-level explanation

The NIS production codec in `NisWebAppWebsocketInitializer` overrides `AbstractSockJsMessageCodec.decodeInputStream(InputStream)` to return `new String[0]`. Spring 5.3.39 `XhrReceivingTransportHandler.readMessages` passes the XHR send request body to `SockJsMessageCodec.decodeInputStream`; `AbstractHttpReceivingTransportHandler.handleRequestInternal` delegates only the resulting array to the SockJS session. Thus `/xhr_send` returns 204 but drops XHR-sent STOMP frames. This source path is common to Jetty 9 and Jetty 12. The direct cause is existing NIS/Spring SockJS behavior, not the EE8 shim.

Changing the codec would alter application protocol behavior and is outside this phase's allowed Jetty compatibility work. It needs a separately reviewed behavior decision and regression test. The blocking request order also means a real SockJS-client timing comparison remains useful, but does not negate the empty decoder result observed in source and the zero inbound CONNECT counter.

## Jetty 12 WebSocket/STOMP control

The existing Phase 2I-F harness was rerun on Java 25 with loopback socket permission:

- Spring context and the test endpoint initialized; Jetty EE8 `JavaxWebSocketServerContainer` was present.
- `/messages/info`: HTTP 200.
- WebSocket STOMP `CONNECT / CONNECTED`, `SUBSCRIBE`, real NIS account subscription `SEND`, and a real `MessagingService.pushNodeInfo()` outbound `MESSAGE` succeeded.
- Normal `UNSUBSCRIBE` / `DISCONNECT` frames were sent; receipts and server-side cleanup were not asserted.
- XHR control: open 200, send 204, poll 200 with heartbeat only; inbound CONNECT counter 0.
- Jetty and Spring context normal shutdown completed.

Both Jetty 9 and Jetty 12 control POMs/probes reported `BUILD SUCCESS`. Jetty 9 used the unchanged production initializer. Jetty 12 used a test subclass override, not the unchanged production endpoint-registration method.

## Lifecycle, origin, error and handshake comparison

| Contract | Result |
| --- | --- |
| Raw 101 headers (`Upgrade`, `Connection`, `Sec-WebSocket-Accept`, `Sec-WebSocket-Protocol`) | Not captured; only the prior Jetty 12 JDK-client-selected `v12.stomp` is known. |
| Normal disconnect receipt and server cleanup | Not verified. |
| Abnormal disconnect / forced socket close and NIS subscription cleanup | Not verified. |
| Allowed and rejected origin behavior | Not compared. Production configuration allows origin patterns `*`; explicit rejected origin was not tested. |
| Malformed STOMP / invalid destination and error mapping | Not compared. |
| Repeated create/destroy, session counts, lingering threads | Not measured. Normal shutdown is not leak evidence. |

## Java/build and CI

Java 17 local runtime validation was unavailable. The Java 25 root `mvn -B clean test` succeeded with **624 classes / 6,218 tests, 0 failures, 0 errors, 0 skipped** after being run with loopback socket permission. The first sandboxed attempt produced 16 Core test errors because socket operations were denied; that run was environmental, not a code failure. Aggregated Surefire reports confirm the Phase 2H test discovery baseline remains unchanged. Root `mvn -B -DskipTests package` succeeded.

The Jetty control launchers compile with Maven compiler `release=17` but ran on Java 25. This does not establish Java 17 runtime compatibility. Hosted Java 17 / Java 25 workflow results for the final commit are added after push. Jenkins Java 17 execution remains externally blocked and was not validated in this phase.

The Phase 2H Failsafe conditions remain unchanged; Failsafe was not run here. Phase 2F also remains unchanged: H2 1.4 database files require offline export/import; Flyway history/checksum constraints remain; representative Mainnet/Testnet database validation and matching-genesis full chain-state comparison remain outstanding.

## Verdict and next step

**PARTIAL.** Matched HTTP probes yield identical Jetty 9 and Jetty 12 EE8 outcomes. The current NIS SockJS XHR send path drops payloads before Spring's inbound STOMP channel because its codec's `decodeInputStream` returns no messages. This points to a pre-existing application behavior rather than a Jetty 12 difference. However, no compliant SockJS client comparison or CONNECTED delivery is proven, the production Jetty 12 registration path cannot currently select the shim through the unchanged initializer, and lifecycle/origin/error comparisons remain incomplete. The READY criteria are not met.

Do not start production Jetty migration. First make an explicit product decision and separately validate the existing SockJS XHR codec behavior; do not silently modify it as a Jetty-only shim. Then rerun a standards-conforming SockJS client on both runtimes with Spring inbound/outbound/STOMP and SockJS session instrumentation. Verify normal/abnormal cleanup, origin rejection, error mapping, and raw handshake headers. Review a narrow explicit production shim-registration seam separately. If EE8 cannot satisfy these contract checks without broad intervention, design a coherent Spring 6 + Jakarta Servlet/WebSocket + Jetty 12 EE10 route.
