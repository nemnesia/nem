# NEM modernization — Phase 2I-D Jetty 12.1 EE8 assessment

Status: **BLOCKED — Spring 5.3 WebSocket integration is incompatible with the Jetty 12 EE8 container without a separate adapter/framework change**

Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested / actual starting HEAD: `7ce671fdf5b069fcb70d285b931059e41499dd22`
Final code/evidence HEAD: same as starting HEAD; this phase records the compatibility finding in this document only.
Jetty migration: **not started**.

## Decision

Jetty 12.1.13 is the current stable 12.1 patch identified for this assessment. It requires Java 17 and provides the EE8 environment for Servlet 4.0 and `javax.servlet`. Its EE8 WebSocket implementation is also available, but Spring Framework 5.3.39 does not recognize the Jetty 12 EE8 WebSocket container class when selecting its default WebSocket upgrade strategy. NIS enables Spring STOMP/SockJS at `/messages`, so upgrading the Jetty runtime while leaving Spring and the application contract unchanged does not provide a verified working WebSocket path.

This is an explicit stop condition in the Phase 2I-D scope. No Jetty dependency, production source, test, framework, servlet namespace, endpoint, persistence, database, or runtime behavior was changed. A separate Spring/WebSocket compatibility design and validation phase is required before attempting the Jetty runtime change.

## Jetty target and upstream compatibility

The Eclipse Jetty 12.1 documentation states Java 17 as the minimum and documents EE8 support for Java EE 8 / Servlet 4.0 (`javax.servlet`). Jetty's download page identifies `12.1.13` as the current stable 12.1 release during this investigation. Maven Central metadata for `org.eclipse.jetty:jetty-server` also reports `12.1.13` as latest/release at investigation time.

Jetty 12 separates environment-specific servlet implementation artifacts into the `org.eclipse.jetty.ee8` group. The relevant artifact families include `jetty-ee8-servlet`, `jetty-ee8-webapp`, `jetty-ee8-plus`, `jetty-ee8-annotations`, and `jetty-ee8-servlets`. The EE8 WebSocket implementation uses `org.eclipse.jetty.ee8.websocket:jetty-ee8-websocket-javax-server` and exposes `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`.

Upstream references:

- [Jetty 12.1 documentation](https://jetty.org/docs/jetty/12.1/index.html)
- [Jetty downloads and release lines](https://jetty.org/download.html)
- [Jetty 11 to 12 migration guide](https://jetty.org/docs/jetty/12.1/programming-guide/migration/11-to-12.html)
- [Jetty HTTP server programming guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/http.html)
- [Jetty WebSocket programming guide](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)
- [Jetty 12.1 WebSocket modules](https://jetty.org/docs/jetty/12.1/operations-guide/protocols/index.html)
- [Spring Framework 5.3.39 `AbstractHandshakeHandler`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/support/AbstractHandshakeHandler.java)
- [Spring Framework 5.3.39 `JettyRequestUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/jetty/JettyRequestUpgradeStrategy.java)
- [Spring Framework 5.3.39 `Jetty10RequestUpgradeStrategy`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/server/jetty/Jetty10RequestUpgradeStrategy.java)

## Existing dependency and source inventory

The repository's direct Jetty versions are currently inconsistent:

| Module | Jetty version | Direct Jetty artifacts |
| --- | --- | --- |
| `deploy` | `9.4.56.v20240826` | `jetty-annotations`, `jetty-client`, `jetty-plus`, `jetty-server`, `jetty-servlet`, `jetty-servlets` |
| `nis` | `9.4.58.v20250814` | same six artifacts plus `org.eclipse.jetty.websocket:websocket-server` |

`nis` also directly declares `javax.servlet:javax.servlet-api:4.0.1`. The servlet/application boundary is `javax.*`; it was not changed. The resolved `nis` dependency tree confirms Jetty WebSocket 9.4.58 (`websocket-server`, `websocket-common`, `websocket-api`, `websocket-client`, `websocket-servlet`) and Spring WebSocket / Messaging 5.3.39. In `deploy`, Jetty's annotation and webapp dependencies bring `jetty-webapp` and `jetty-xml`; plus brings JNDI; servlet brings security and utility artifacts; servlet filters bring continuation and utility artifacts.

Jetty production APIs are concentrated in `deploy`:

- `AbstractServerBootstrapper`: `Server`, `HandlerCollection`, `ServletContextHandler`, webapp `Configuration.ClassList`, `QueuedThreadPool`, `ScheduledExecutorScheduler`; configures plus/annotation/webapp support, listeners, error handling, startup, and shutdown.
- `NemServerBootstrapper` and `NemWebsockServerBootstrapper`: `HttpConfiguration`, `HttpConnectionFactory`, and `ServerConnector`; preserve configured connector ports, bind behavior, and HTTP limits.
- `AbstractNemServletContextListener`: Jetty `MimeTypes` and DoS/Gzip/CORS filter registration through the `javax.servlet` context.
- `JsonErrorHandler`: Jetty `ErrorHandler`, old `handle(...)` override shape, request/response types, and `ByteArrayISO8859Writer`.
- `CommonStarter`: Jetty `HttpClient`, `ContentResponse`, `Server`, and `MultiException` lifecycle/client APIs.
- `deploy/src/test/java/org/nem/deploy/JsonErrorHandlerTest.java`: directly mocks Jetty `Request` and `Response`.

`nis` has no direct Jetty API imports in production source. Its `NisWebAppWebsocketInitializer` enables Spring's STOMP message broker and registers `/messages` with SockJS. Spring MVC tests and application servlet contracts use `javax.servlet` types.

`mvn -B -pl deploy,nis dependency:tree` confirmed these resolved module graphs. The narrower query for `javax.servlet`, Jetty WebSocket, and Spring WebSocket/Messaging in `nis` completed successfully and showed the versions above. No Jetty Maven plugin was found in the root, `deploy`, or `nis` POMs; Jetty is embedded through runtime dependencies.

## Specific compatibility blocker

Spring Framework 5.3.39 `AbstractHandshakeHandler` chooses a default upgrade strategy by probing server-specific classes. The Jetty paths it recognizes are:

- Jetty 9: `org.eclipse.jetty.websocket.server.WebSocketServerFactory`
- Jetty 10: `org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer`

Spring 5.3.39's `JettyRequestUpgradeStrategy` is tied to the Jetty 9 `WebSocketServerFactory`; `Jetty10RequestUpgradeStrategy` reflects the Jetty 10 container class. There is no Jetty 12 EE8 class check in this Spring version. Jetty 12 EE8 provides `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`, which does not match either detector. If neither recognized container is present, Spring's default strategy selection throws `IllegalStateException("No suitable default RequestUpgradeStrategy found")`.

The NIS `/messages` SockJS/STOMP endpoint depends on this Spring WebSocket integration. Jetty 12's `javax.websocket` support alone therefore does not demonstrate that the existing Spring 5.3 integration can perform the upgrade. Establishing a custom Spring `RequestUpgradeStrategy`, changing Spring, or changing the WebSocket integration would require an independently designed and tested compatibility change, beyond this Jetty-only phase and its behavior-preservation constraints.

Other Jetty 12 API adaptation would also be required after that blocker is addressed. The migration guide replaces `HandlerCollection` / `HandlerList` with `org.eclipse.jetty.server.Handler.Sequence`; EE-specific servlet, webapp, plus, annotation, and servlet-filter implementation classes move under `org.eclipse.jetty.ee8`; and Jetty client API types such as `ContentResponse` move from `org.eclipse.jetty.client.api` to `org.eclipse.jetty.client`. These compile-time changes are feasible in principle but are not implemented here because they do not resolve the WebSocket runtime incompatibility.

## Validation and gate status

The environment used for this investigation was Java `17.0.20.1`, `javac 17.0.20.1`, and Maven `3.8.7`. No implementation change was made, so this documentation-only blocker phase did not rerun `mvn clean test`, package, or runtime smoke tests. The latest prior local Java 17 baseline recorded in Phase 2I-A is 624 test classes / 6,218 tests with zero failures, errors, or skips, plus successful package. Those results do not validate Jetty 12.

On the starting HEAD, the prior Phase 2I-C record reports successful Java 17 Baseline run [36235747400](https://github.com/nemnesia/nem/actions/runs/36235747400) and Java 25 Compatibility run [36235747397](https://github.com/nemnesia/nem/actions/runs/36235747397). These are pre-migration results and cannot be treated as Jetty 12 verification. Current-commit workflow runs should be checked after this documentation commit; regardless, Java 17 remains the primary runtime verification target.

Jenkins Java 17 execution remains externally blocked and was not validated in this phase. The existing Phase 2I-A/B/C BLOCKED records are retained without alteration. No Jenkins shared-library, image, Jenkinsfile, controller, or agent configuration was changed.

The Phase 2F database gates remain open and unchanged:

- Existing H2 1.4 production database files require the documented offline export/import conversion to H2 2.x.
- The V1.0.0–V1.0.7 migration history and checksum constraints remain in force.
- Representative Mainnet/Testnet database verification and matching-genesis full chain-state comparison remain unavailable.

No schema, migration SQL, Flyway history, Hibernate/JPA behavior, or persisted state was touched.

## Next gate

Before retrying Jetty 12 EE8, a separate, reviewable compatibility design must demonstrate the Spring 5.3.39 STOMP/SockJS upgrade path against the Jetty 12 EE8 container, or establish an approved Spring/WebSocket change phase. The validation must cover WebSocket handshake and SockJS behavior alongside current HTTP routes, connector binding, filters/listeners, error handling, and graceful shutdown on Java 17. Only after this is resolved should the Jetty artifact/API adaptations be implemented and tested. Jetty 9 must remain in place until that runtime compatibility is demonstrated.

Phase 2I-D result: **BLOCKED**. Rollback point: `7ce671fdf5b069fcb70d285b931059e41499dd22`; no production or dependency changes were made.
