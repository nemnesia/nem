# NEM modernization — Phase 2I-E Spring WebSocket / Jetty 12 compatibility

Phase status: **PARTIAL**

Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested starting HEAD: `08f9bf8280f83f94fd3378f19105b0975f33c998`
Actual starting HEAD: `08f9bf8280f83f94fd3378f19105b0975f33c998`

No production dependency, production Java source, NIS protocol, database, or runtime configuration was changed. A reproducible, standalone proof-of-concept (PoC) is kept under [`phase-2i-e-poc`](phase-2i-e-poc/pom.xml); it is not part of the NEM Maven reactor or production artifact.

## Summary decision

The Spring 5.3.39 automatic selection path cannot use Jetty 12 EE8's `JavaxWebSocketServerContainer`: it probes known server implementation class names and has no generic `javax.websocket` fallback. Spring 5.3.39 does, however, expose a supported injection point for a `RequestUpgradeStrategy` on the STOMP endpoint registration. Jetty 12.1.13 EE8's public `JavaxWebSocketServerContainer.upgradeHttpToWebSocket(...)` method can be called directly through a small, explicit adapter without reflection or class-name spoofing.

The standalone Java 17 PoC exercised this adapter with Spring 5.3.39, Jetty 12.1.13 EE8, a Spring STOMP endpoint at `/messages` with SockJS enabled, and the same SockJS message codec logic used by NIS. It verified SockJS `/info`, strategy entry with the actual Jetty 12 EE8 container instance, STOMP CONNECT, subscribe, send, and echo delivery. This establishes a technically viable compatibility mechanism for the protocol path.

The PoC is not the NIS application: it does not load `NisWebAppWebsocketInitializer`, NIS message controllers, NIS's custom STOMP `MessageConverter`, real destinations, any interceptors, or the production embedded-server bootstrap. It also does not cover SockJS XHR fallback, non-WebSocket fallback transports, binary frame handling, or the full existing event lifecycle. Therefore the existing NIS behavior is not yet established on Jetty 12, and the overall result is **PARTIAL**. Do not begin the full Jetty migration on this evidence alone.

## Current NIS contract and namespace inventory

At the starting HEAD:

- Java baseline is 17; Spring Framework is `5.3.39`.
- `deploy` uses Jetty `9.4.56.v20240826`; `nis` uses Jetty `9.4.58.v20250814`.
- Servlet and WebSocket APIs in this stack use `javax.*`.
- `NisWebAppWebsocketInitializer` registers `/messages`, enables SockJS, allows origin patterns `*`, and supplies a custom SockJS codec. It does not supply a custom `HandshakeHandler`, so Spring's default strategy detection applies.
- Spring STOMP configuration uses broker destinations `/blocks`, `/unconfirmed`, `/errors`, `/account`, `/transactions`, `/recenttransactions`, `/node` and application prefix `/w/api`.
- The application configures a custom message converter for serialized NEM payloads; those payloads are byte arrays and are distinct from the SockJS envelope codec.

Namespace grep across `core`, `deploy`, `nis`, and `peer` found `javax.servlet` in deploy bootstrap/filters/error handling, NIS servlet-facing controllers/configuration and servlet-based tests; `javax.persistence` in NIS database model entities; `javax.validation.Valid` in `AccountController`; and `javax.annotation.PostConstruct` in `NisMain`. The source tree does not import `javax.transaction` or JAXB APIs directly. Third-party boundaries include Spring Framework 5.3, Jetty EE8/Servlet APIs, Hibernate ORM 5.4.33.Final, and Hibernate Validator 6.2.0.Final. The persistence classes and schema were not modified.

## Spring 5.3.39 source-level selection and extension points

### Automatic strategy selection

`AbstractHandshakeHandler` statically checks for known container classes and selects the corresponding implementation. In 5.3.39 the relevant Jetty probes are `org.eclipse.jetty.websocket.server.WebSocketServerContainer` (Jetty 9) and `org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer` (Jetty 10). It then instantiates `JettyRequestUpgradeStrategy` or `Jetty10RequestUpgradeStrategy`. If no recognized server is present, it throws `IllegalStateException("No suitable default RequestUpgradeStrategy found")`; the 5.3.39 artifact contains no generic `StandardWebSocketUpgradeStrategy` class.

Jetty 12 EE8 exposes `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`, so its class name is not one of Spring 5.3.39's probes. The fact that this class implements `javax.websocket.server.ServerContainer` does not alter Spring's detector, which is based on named implementation probes rather than discovery of a ServletContext container attribute.

### Standard JSR-356 pieces

`ServletServerContainerFactoryBean` implements `ServletContextAware` and retrieves the attribute `javax.websocket.server.ServerContainer`; it configures container timeouts and message buffer limits. It does **not** provide a request-upgrade strategy and cannot make Spring's default detector select Jetty 12.

Spring 5.3.39's `AbstractStandardUpgradeStrategy` is a reusable base for `javax.websocket` containers. It adapts Spring's `WebSocketHandler` into a JSR-356 `Endpoint` and `ServerEndpointRegistration`, but leaves the vendor-specific `upgradeInternal(...)` call abstract. Spring ships vendor strategies (Tomcat, Undertow, GlassFish/Tyrus, WebLogic and WebSphere), not a generic Jetty 12 EE8 strategy.

The public `StompWebSocketEndpointRegistration.setHandshakeHandler(HandshakeHandler)` method is an explicit extension point. `DefaultHandshakeHandler(RequestUpgradeStrategy)` accepts a supplied strategy. This allows NIS to select a narrowly scoped adapter for the `/messages` endpoint without spoofing class names or changing STOMP/SockJS registration semantics. NIS does not currently use that extension point.

### Jetty 12 EE8 API

Jetty 12.1 supports EE8 on Java 17, with Servlet 4.0 and `javax.servlet`. The EE8 WebSocket server artifact supplies `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`; it implements `javax.websocket.server.ServerContainer` and has a public `upgradeHttpToWebSocket(Object, Object, ServerEndpointConfig, Map)` method. Jetty's official WebSocket guide documents a Java EE 8 (`javax.websocket`) implementation and describes container setup for embedded `ServletContextHandler` usage.

The PoC adapter extends Spring's `AbstractStandardUpgradeStrategy`, creates Spring's `ServerEndpointRegistration` for the current request URI, passes selected protocol/extensions, obtains the container through the JSR-356 ServletContext attribute, checks the concrete Jetty EE8 type, then calls the Jetty container's public upgrade method. It has no reflection, class-name substitution, global classpath detector changes, or shared-library hooks. It is intentionally compiled only in the isolated PoC using Jetty 12.1.13 dependencies; no Jetty 12 dependencies were added to NEM's production classpath.

Upstream source and documentation:

- [Spring Framework 5.3.39 `AbstractHandshakeHandler`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/support/AbstractHandshakeHandler.java)
- [Spring Framework 5.3.39 `DefaultHandshakeHandler`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/support/DefaultHandshakeHandler.java)
- [Spring Framework 5.3.39 `RequestUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/RequestUpgradeStrategy.java)
- [Spring Framework 5.3.39 `AbstractStandardUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/standard/AbstractStandardUpgradeStrategy.java)
- [Spring Framework 5.3.39 `ServerEndpointRegistration`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/standard/ServerEndpointRegistration.java)
- [Spring Framework 5.3.39 `ServletServerContainerFactoryBean`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/standard/ServletServerContainerFactoryBean.java)
- [Spring Framework 5.3.39 `StompWebSocketEndpointRegistration`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/config/annotation/StompWebSocketEndpointRegistration.java)
- [Jetty 12.1 WebSocket server guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)
- [Jetty 12.1 `JavaxWebSocketServerContainer` API](https://javadoc.jetty.org/jetty-12.1/org/eclipse/jetty/ee8/websocket/javax/server/JavaxWebSocketServerContainer.html)
- [Jetty 12.1 WebSocket modules](https://jetty.org/docs/jetty/12.1/operations-guide/protocols/index.html)

## Compatibility paths assessed

| Path | Result | Evidence / limitation |
| --- | --- | --- |
| Leave Spring 5.3.39 default detection unchanged | **Does not work** | No Jetty 12 EE8 container probe or generic `javax` fallback; fails strategy selection when no other recognized container is present. |
| Register Jetty `javax.websocket` container and rely on Spring to discover it | **Does not work by itself** | `ServletServerContainerFactoryBean` reads the standard context attribute but only configures the container; strategy detection is separate. |
| NIS-specific `HandshakeHandler` + `RequestUpgradeStrategy` adapter | **Mechanism proven in isolated fixture; NIS integration pending** | Uses documented Spring injection point and public Jetty EE8 method. Isolated SockJS/STOMP runtime probe passed; actual NIS wiring and application behavior remain untested. |
| Upgrade to a later Spring 5.x while preserving `javax.*` | **No maintained OSS candidate found** | Spring 5.3 is the final 5.x line; upstream lists OSS support ended August 2024, with commercial LTS options. 5.3.39 remains `javax` / Java EE 8 but does not contain the Jetty 12 EE8 strategy. |
| Spring Framework 6.2 | **Not compatible with `javax.*` application stack** | Java 17 supported and Spring 6.2 adds a generic `StandardWebSocketUpgradeStrategy` using Jakarta WebSocket 2.1+, but Spring 6.2 is Jakarta EE 9/10 (`jakarta.*`), and Jetty-specific detection targets EE10 packages. Jetty EE8's `javax.websocket` container is not its API. |
| Spring Framework 7.x | **Not compatible with `javax.*` application stack** | Current Spring production generation; Java 17+ and Jakarta EE 11 baseline (`jakarta.*`). This is a future coherent family candidate only after a broad namespace/application migration. |

The Spring Framework upstream version matrix says Spring 5.3 supports Java EE 7/8 (`javax`), Spring 6.2 uses Jakarta EE 9/10, and Spring 7 uses Jakarta EE 11-12. It lists 5.3 OSS support ended August 2024, 6.2 OSS support ending June 2026, and 7.0.x as the current production line. A Maven-resolvable 6.x or 7.x artifact therefore is not a drop-in maintained `javax` update.

References:

- [Spring Framework version/support matrix](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions)
- [Spring Framework 6.2.15 `AbstractHandshakeHandler`](https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-websocket/src/main/java/org/springframework/web/socket/server/support/AbstractHandshakeHandler.java)
- [Spring Framework 6.2.15 `StandardWebSocketUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-websocket/src/main/java/org/springframework/web/socket/server/standard/StandardWebSocketUpgradeStrategy.java)
- [Spring Framework 6.2.15 `JettyRequestUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v6.2.15/spring-websocket/src/main/java/org/springframework/web/socket/server/jetty/JettyRequestUpgradeStrategy.java)
- [Spring Framework 7.0 `AbstractHandshakeHandler`](https://github.com/spring-projects/spring-framework/blob/v7.0.0/spring-websocket/src/main/java/org/springframework/web/socket/server/support/AbstractHandshakeHandler.java)

## Isolated PoC and test results

The isolated harness is under `docs/modernization/phase-2i-e-poc/`. It uses Java `17.0.20.1`, Maven `3.8.7`, Spring `5.3.39`, Jetty `12.1.13`, and Java SE `HttpClient` as the client. Its minimal Spring broker fixture configures `/messages`, `.withSockJS()`, `setAllowedOriginPatterns("*")`, a simple in-memory broker, and an echo mapping. The SockJS codec implementation matches the `decode`, `decodeInputStream`, and JSON quoting behavior of `NisWebAppWebsocketInitializer`.

From the PoC directory, package and run it on Java 17 with:

```bash
mvn -B clean package
mvn -B compile exec:java -Dexec.mainClass=org.nem.experiments.PocServer
```

The Java 17 run completed with:

- `SOCKJS_INFO_PASS`: `GET /messages/info` returned HTTP 200 and advertised WebSocket availability.
- `UPGRADE_STRATEGY_ENTERED container=org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer path=/messages/000/poc-session/websocket`.
- `SOCKJS_STOMP_CONNECT_SUBSCRIBE_SEND_PASS`: SockJS WebSocket transport accepted STOMP `CONNECT`, `SUBSCRIBE`, and `SEND`; the echoed message was received.
- `POC_PASS port=... spring=5.3.39 jetty=12.1.13 namespace=javax` and Maven `BUILD SUCCESS`.

This proves context startup, endpoint registration in the minimal fixture, actual upgrade-strategy entry, STOMP connection and message delivery, and that SockJS WebSocket transport remains advertised. It does not test SockJS XHR/streaming fallback or NIS's production event handlers, converters, origin policy under real requests, interceptors, binary payloads, or server bootstrap. The fixture's text STOMP message is not a substitute for NIS's binary serialized transaction payload path.

NEM's Java 17 reactor verification completed:

- `mvn -B clean test`: success across 624 test report classes / 6,218 tests, 0 failures, 0 errors, 0 skipped.
- `mvn -B -DskipTests package`: success.
- The first sandboxed attempt could not bind WireMock loopback sockets and failed 16 Core tests with `SocketException: Operation not permitted`; rerunning the same clean test with loopback access succeeded. This was an execution-environment restriction, not an application regression.
- The isolated PoC also packaged successfully with `mvn -B -DskipTests package`; its separate runtime handshake/STOMP result is above.

No test was deleted, skipped, or weakened in the NEM reactor. The pre-existing known integration/Failsafe and public-peer limitations remain unchanged; this phase did not run Failsafe.

## Jakarta boundary and migration impact

There is no Spring 6/7 drop-in option that keeps the application's `javax.*` API surface. Spring 6 moves its web and persistence integration to Jakarta packages; Spring 7 raises that baseline to Jakarta EE 11. A coherent Jetty path on those lines would use a matching Jetty Jakarta EE environment (for example Jetty 12 EE10 with Spring 6.2 or EE11 with Spring 7), not Jetty EE8.

The namespace/application scope indicated by repository inventory is:

- `deploy`: servlet imports and `WebListener`, embedded servlet bootstrap, filter/listener registration, Spring MVC bootstrap, and servlet-based tests must move together to Jakarta Servlet APIs. Jetty bootstrap and filters also change EE-specific artifact/package family.
- `nis`: `NisWebAppWebsocketInitializer`, STOMP/SockJS registration, Spring WebSocket handler integration, controller request types, servlet interceptors/advice, and the message-converter integration/tests must move to Jakarta-aware Spring APIs while preserving `/messages`, destinations, codec behavior, origin policy, and frame/message semantics.
- Persistence: NIS database entity mappings use `javax.persistence` in 20-plus model classes. A Jakarta move requires coordinated Jakarta Persistence annotations/API and a compatible Hibernate ORM generation, followed by mapping/query/Criteria/native query and persistence behavior verification. This phase did not change mappings or database state.
- Validation and lifecycle: `javax.validation.Valid`, Hibernate Validator 6.2.0.Final integration, and `javax.annotation.PostConstruct` require Jakarta equivalents and compatible dependencies.
- External/third-party APIs: Servlet/WebSocket APIs, Jetty EE8 artifacts, Spring ORM/WebSocket modules, Hibernate, and Validator must be aligned as a coherent family. Any transitive library exposing `javax.*` types at these boundaries needs an explicit compatibility review; simply adding both namespaces would create incompatible types.

This is a future design inventory only. No Jakarta import, dependency, schema, Flyway SQL/history, or production behavior changed. Phase 2F's H2 1.4 offline export/import requirement, Flyway checksum/history constraints, representative Mainnet/Testnet verification gap, and matching-genesis full chain-state comparison limitation remain unchanged.

## Jenkins and remaining validation

Jenkins Java 17 execution remains externally blocked and was not validated in this phase. The Phase 2I-A/B/C records remain intact. GitHub Actions Java 17 and Java 25 were successful at the starting HEAD; final-commit workflow results are to be checked after push and recorded in the final report. Java 25 remains a compatibility target only.

## Recommendation for Phase 2I-F

Keep the Jetty production dependency and bootstrap unchanged until Phase 2I-F completes a test against NIS's real initializer and endpoint contract. Phase 2I-F should:

1. Put the explicit adapter behind a test-only Jetty 12 EE8 runtime profile or separate integration harness so Jetty 9 and Jetty 12 core artifacts are not mixed in the production runtime.
2. Exercise the real `NisWebAppWebsocketInitializer` and `/messages` registration, and prove the exact custom codec plus NIS byte-array `MessageConverter` paths.
3. Verify current NIS destinations/handlers with STOMP connect/subscribe/send, origin behavior, subprotocol negotiation, close/error lifecycle, and text/binary payload contract.
4. Verify SockJS `/info`, WebSocket transport, and at least one XHR/streaming fallback transport, along with the production Jetty startup and clean shutdown sequence.
5. Decide whether the small Jetty-specific adapter is acceptable as a maintained compatibility shim for the planned Jetty 12 EE8 transition. If it is not, make a separately reviewed Spring/Jakarta modernization plan before Jetty migration.
6. Run Java 17 clean test/package and hosted Java 17 CI for the exact tested revision. Do not treat Java 25 or Jenkins as a substitute; keep the Jenkins external blocker explicit.

Only after the real NIS runtime contract passes should a later phase migrate the embedded Jetty APIs/artifacts. Phase 2I-E does not start that migration.
