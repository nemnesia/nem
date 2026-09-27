# Phase 2I-O — production listener/filter chain parity

## 判定

**PARTIAL**。production-equivalent Jetty 12 EE8 bootstrap で XHR 500 の直接原因を再現し、test-only の async 設定で解消する因果関係を確認した。正しい `/w/*` mapping では WebSocket 404 は再現せず、Spring SockJS handler と Jetty 12 shim まで到達して protocol probe が成功した。一方、Phase 2I-M の404は正確な request URL・Servlet/handler trace・upgrade callback 記録が保存されていないため、当時どの layer が返したかは確定できない。

Production Jetty 9 dependency/bootstrap/servlet/filter/Spring behavior は変更していない。Jetty 12 migration は実施していない。

## Repository / environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `1ac7ace3971aa63b57982d8080e97afafa750ebe`
- 開始時 worktree: clean
- 開始時 local branch と `origin/agent/nis-phase0-baseline`: 同一 HEAD
- Java / javac: OpenJDK `17.0.20.1` / `17.0.20.1`
- Maven: `3.8.7`
- Jetty 9 production source baseline: deploy `9.4.56.v20240826`, NIS `9.4.58.v20250814`
- Jetty 12 test runtime: `12.1.13` EE8
- Spring: `5.3.39`; servlet/WebSocket namespace: `javax.*`
- SockJS standard client: `sockjs-client@1.6.1`

## Production topology from source

`AbstractServerBootstrapper.boot()` constructs a Jetty `Server`, a `ServerConnector` (30,000 ms idle timeout), a scheduled executor, and a root `ServletContextHandler` with context path `/`. `createHandlers()` explicitly registers the NIS custom `ServletContextListener` first, then Spring `ContextLoaderListener`, sets the production `JsonErrorHandler`, and puts the context in a `HandlerCollection`.

`NemWebsockServerBootstrapper.WebsocketContextListener` extends `AbstractNemServletContextListener`. During its `contextInitialized`, that superclass creates a child `AnnotationConfigWebApplicationContext`, sets the already-created application context as its parent, and calls the concrete initializer. The NIS initializer registers `DispatcherServlet` as `Spring Websocket Dispatcher Servlet` at `/w/*`, `loadOnStartup=1`; it does not call `setAsyncSupported(true)`. The superclass then sets `contextClass` and registers filters in this order:

1. `DoSFilter`, only when `CommonConfiguration.useDosFilter()` is true (default configuration used here: true), `/*`, `REQUEST`; parameters include `maxRequestMs=120000`.
2. `GzipFilter`, `/*`, `REQUEST`, configured for `application/json`.
3. inline CORS filter (`AbstractNemServletContextListener$1`), `/*`, `REQUEST`.

None of these registrations calls `setAsyncSupported(true)`. Servlet/filter Servlet API defaults therefore leave them async-disabled. The Jetty EE8 WebSocket SCI also installs its own `WebSocketUpgradeFilter` on `/*`; it is async-enabled and precedes dynamically added production filters in the observed Jetty 12 filter chain.

The child web context is not refreshed inside the custom listener. `DispatcherServlet` is initialized at server start (`loadOnStartup=1`), after listener callbacks; its Spring refresh invokes `NisWebAppWebsocketInitializer`. The initializer registers STOMP/SockJS endpoint `/messages`, and during endpoint registration reads ServletContext attribute `javax.websocket.server.ServerContainer`, then uses `ServiceLoader` to choose an optional strategy provider. External HTTP paths are `/w/messages/...`: context path is empty, `servletPath=/w`, and `pathInfo=/messages/...`.

## Test-only parity bootstrap and limits

`docs/modernization/phase-2i-o-poc/` adds a standalone Java 17 / Jetty 12.1.13 EE8 diagnostic launcher. It executes the actual production `AbstractNemServletContextListener` and actual `NisWebAppWebsocketInitializer`, with the real `/w/*` servlet registration, `ContextLoaderListener`, production filter registrations/configuration, and test-only trace instrumentation. The parent NIS dependency context is the existing POC `TestDependencies` context (mocks), not the complete `CommonStarter` application dependency graph. It adds a post-chain request trace filter and a Spring `MappedInterceptor` to record route handling. A test-only provider logs Spring's strategy choice and a test-only strategy delegates to Jetty 12's public `JavaxWebSocketServerContainer` API.

Two scope differences remain: the Jetty 9 `org.eclipse.jetty.servlets.GzipFilter` is represented by a test-only pass-through filter with the same name/mapping/default async behavior (Jetty 12 moved/changed that artifact); and production `JsonErrorHandler` cannot be attached unchanged because it extends Jetty 9 `org.eclipse.jetty.server.handler.ErrorHandler`, while Jetty 12 EE8 `ServletContextHandler.setErrorHandler` requires the EE8 nested error-handler type. The test harness therefore does not reproduce production JSON error-body formatting. These differences do not affect the measured Spring route or async exception but mean this is not the complete `CommonStarter` runtime.

## Initialization order and ServletContext evidence

Runtime events from the production-equivalent Jetty 12 launcher, in order:

1. Root servlet context created (`contextPath=/`).
2. `JavaxWebSocketServletContainerInitializer` callback runs before application listeners; it sets `javax.websocket.server.ServerContainer` to `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`. At this point Jetty attributes include `org.eclipse.jetty.websocket.core.WebSocketComponents`, `org.eclipse.jetty.websocket.core.server.WebSocketMappings`, and `org.eclipse.jetty.server.Executor`.
3. Listener order: production-derived NIS listener, `ContextLoaderListener`, observer. On entry to the NIS listener, the same EE8 `ServerContainer` attribute is already present.
4. NIS listener adds `/w/*` DispatcherServlet; the servlet async flag is false; it registers DoS, Gzip, CORS filters without enabling async support.
5. `ContextLoaderListener` establishes root Spring context.
6. Server startup initializes the load-on-startup DispatcherServlet; child Spring context refresh sees the same `ServerContainer`. `NisWebAppWebsocketInitializer` selects the Jetty 12 provider (logged twice in the observed initialization; at least one selected strategy is installed; the repeat is not treated as evidence of duplicate endpoint registration).
7. First client requests follow.

The startup snapshots do not indicate that Spring missed the WebSocket container: the EE8 attribute exists before either application listener and remains present when the child Spring context refreshes and the provider is selected.

Runtime registration matrix after Jetty 12 server start:

| Registration | Mapping | Dispatcher | asyncSupported |
| --- | --- | --- | --- |
| Jetty default 404 servlet | `/` | n/a | true |
| `Spring Websocket Dispatcher Servlet` | `/w/*` | n/a | **false** |
| Jetty `WebSocketUpgradeFilter` | `/*` | REQUEST | true |
| `DoSFilter` | `/*` | REQUEST | **false** |
| `GzipFilter` | `/*` | REQUEST | **false** |
| inline CORS filter | `/*` | REQUEST | **false** |
| test-only request trace filter | `/*` | REQUEST | true |

Source filter-registration order is DoS, Gzip, CORS; all are added with `isMatchAfter=true`. Observed handler filter list is WebSocketUpgradeFilter, DoS, Gzip, CORS, trace. `ServletContext.getFilterRegistrations()` has no portable API for retrieving URL/servlet mappings or dispatcher types; these were taken from the live Jetty `ServletHandler` mappings. No production source instrumentation was added.

## Request path and routing evidence

The standard client requests the base URL `http://localhost:<port>/w/messages` and selects the requested transport itself. The trace filter records the incoming URI/path decomposition; the Spring interceptor records whether a `HandlerMapping` resolved a handler.

| Request | Runtime path | Route evidence | Result |
| --- | --- | --- | --- |
| SockJS info | `GET /w/messages/info` | `contextPath=""`, `servletPath=/w`, `pathInfo=/messages/info`; Spring resolves `SockJsHttpRequestHandler` | 200, JSON info, `websocket:true` |
| WebSocket transport | `GET /w/messages/<server>/<session>/websocket` | Same `/w` decomposition; Spring resolves `SockJsHttpRequestHandler`; `LoggedUpgradeStrategy.upgradeInternal` is invoked with EE8 `JavaxWebSocketServerContainer` | client opens; STOMP CONNECTED, NIS MESSAGE, receipt DISCONNECT; client exits 0 |
| XHR polling receive | `POST /w/messages/<server>/<session>/xhr` | Spring resolves `SockJsHttpRequestHandler`; trace sees `isAsyncSupported=false`, `isAsyncStarted=false` | 500 through Spring exception chain; no STOMP CONNECT reaches inbound channel |
| Diagnostic wrong-prefix info | `GET /messages/info` | servletPath is the entire URI, `pathInfo=null`; no Spring handler trace; Jetty default `Default404Servlet` formats HTML 404 | 404 |
| Diagnostic wrong-prefix WebSocket | `GET /messages/000/phase2i-o-wrong-prefix/websocket` | no Spring handler trace; request exits from Jetty default 404 servlet | Jetty 12 HTTP 404 |

The wrong-prefix requests prove what `/messages/...` without production `/w` mapping does in this test context. They do **not** prove that Phase 2I-M sent that URL. Phase 2I-M's retained result table labels the WebSocket endpoint `/w/messages/.../websocket`, but no raw request log, request URI, DispatcherServlet trace, handler trace, or upgrade callback was preserved. Its XHR error body was also not accompanied by a stack. Its JSON 404 is not enough to identify the layer because production's `JsonErrorHandler` transforms a status into JSON. Thus the historical Phase 2I-M WebSocket 404 direct source remains unknown.

## XHR failure and corrected diagnostic experiment

In the faithful listener/filter setup, the XHR `POST /w/messages/739/faqndvlr/xhr` reaches the real Spring `SockJsHttpRequestHandler`. Runtime state at filter entry is `request.isAsyncSupported=false`, `request.isAsyncStarted=false`. The recorded exception chain is:

```text
NestedServletException: Request processing failed
  <- SockJsException: Uncaught failure in SockJS request
  <- SockJsTransportFailureException: Failed to open session
  <- IllegalArgumentException: Async support must be enabled on a servlet and for all filters involved in async request processing...
```

This is the same root exception and message documented from the Jetty 9 production runtime in Phase 2I-N, where Jetty's `ServletHolder$NotAsync.service` also appeared in the stack. The request's `DispatcherServlet`, DoSFilter, GzipFilter, and CORS filter are all async-disabled. `isAsyncStarted=false` is expected because Spring fails before async processing can start.

A separate `corrected` test-only mode enables async support on the DispatcherServlet and **every** filter registration before server start. Under that configuration, the same standard `sockjs-client@1.6.1` XHR-polling client receives CONNECTED, an outbound NIS `/node/info` MESSAGE, and a receipt to DISCONNECT; the receive request is async-started, send requests return normally, and Spring SockJS session stats return to zero after the observation interval. WebSocket continues to pass. This isolates async-enabled servlet/filter registrations as the direct fix for the observed XHR failure; it does not alter production settings.

## Protocol result

| Configuration | WebSocket | XHR polling | NIS handler / cleanup |
| --- | --- | --- | --- |
| Jetty 12.1.13, production-equivalent listener/filter chain, current default async flags | CONNECTED and selected `websocket` | HTTP 500 before CONNECT; client reports `All transports failed` | WS reaches `/node/info`, emits MESSAGE and receipt; sessions return to zero after ~10 s. XHR failure session also expires to zero after ~10 s |
| Same test-only bootstrap, all servlet/filter registrations async-enabled | CONNECTED | Selected `xhr-polling`, CONNECTED | XHR reaches `/node/info`, MESSAGE and DISCONNECT receipt; session stats return to zero after ~10 s |
| Jetty 12 direct POC harness from prior phases | CONNECTED | CONNECTED | Prior test fixture completed the NIS message/receipt path; this direct setup's DispatcherServlet was async-enabled and did not reproduce the production filter registrations |
| Jetty 9 production | WebSocket CONNECTED in Phase 2I-N control; earlier parity probes pass | HTTP 500 | Phase 2I-N recorded the same async exception; its one control did not reach the expected MESSAGE |

The Phase 2I-O production-equivalent Jetty 12 WebSocket request was `GET /w/messages/550/5q40nv1d/websocket`; Spring handler mapping and the explicit shim callback were both observed. The standard SockJS client completed CONNECT / CONNECTED, a NIS `/node/info` handler MESSAGE, receipt-bearing DISCONNECT, and normal close. This rules out the `/w/*` mapping and the reproduced listener/filter order as causes of a 404 when this exact URL and initialized container are used.

## Hypothesis results

- **A — confirmed for the production-equivalent Jetty 12 XHR path.** The same async-support `IllegalArgumentException` occurs at the real Spring SockJS handler, with DispatcherServlet and relevant production filters async-disabled. Jetty 9 production had the same root exception.
- **B — unresolved for the historical Phase 2I-M WebSocket 404.** Current runtime snapshots show the correct container before Spring refresh and successful strategy selection. The historical trial lacks the corresponding runtime snapshots and exact request trace, so it is not possible to identify whether a different request path, startup order, ServletContext attribute, or upgrade registration caused that 404.
- **C — rejected for the faithfully mapped `/w/messages/...` route in the test-only reproduction.** Reproducing the listener/filter chain by itself does not produce 404; Spring resolves the SockJS handler and Jetty 12 shim upgrades successfully. A request missing `/w` is separately shown to hit Jetty's default 404 servlet.
- **D — confirmed as an explanation for the XHR harness discrepancy, but not as proof of the M 404.** The earlier direct harness used a root-mapped DispatcherServlet with async enabled and omitted the production filter chain; it passed XHR. The production-equivalent chain sets the dispatcher and filters async-disabled and fails. Its WebSocket still works, so this chain difference explains the XHR discrepancy but not the historical WebSocket 404.

## Root cause / next fix candidate

The XHR failure is caused by async processing being disabled on the production-style DispatcherServlet and filters. A future production fix candidate would need to enable async on the servlet **and every filter participating in the `/w/*` async request chain**, then run Jetty 9 and the eventual Jetty 12 runtime tests. This Phase intentionally made no such change.

The WebSocket 404 needs a repeatable production-trial trace before selecting a fix: record the exact client request URI and response, `ServletContext` container attributes at SCI/listener/Spring refresh, selected provider/strategy, Jetty handler/filter mappings, and Spring handler/upgrade callback. In particular, retain enough evidence to distinguish a missing `/w` prefix (which this reproduction shows reaches Jetty's default 404 servlet) from an upgrade-filter/container-registration failure. Do not infer the historical 404's layer from its status or JSON body.

## Build and validation

- Runtime probe setup: `npm ci --prefix docs/modernization/phase-2i-h-poc` installs the test-only `sockjs-client@1.6.1` used by the existing probe script. Production-equivalent run: `mvn -B -f docs/modernization/phase-2i-o-poc/pom.xml clean package exec:java -Dexec.mainClass=org.nem.nis.websocket.ProductionParityControl`. Causal corrected experiment: same command with `-Dexec.args=corrected`. The extra flags only affect the standalone test bootstrap.
- Test-only Jetty 12 POC compile/package: `mvn -B -f docs/modernization/phase-2i-o-poc/pom.xml clean package` — success; runtime probe ran under Java 17 with loopback access.
- Runtime, production-equivalent async-default mode: WebSocket full contract passed; XHR async exception reproduced; wrong-prefix requests reached default 404; post-probe session map returned to 0.
- Runtime, corrected async diagnostic mode: WebSocket and XHR contract passed; XHR received CONNECTED/MESSAGE/receipt; session map returned to zero.
- `mvn -B -pl nis -am -DskipTests package` — success (Core, Deploy, Peer, Infrastructure Server).
- `git diff --check` — success.
- Java 25: not run.
- Hosted GitHub Actions Java 17/25: not run; this phase changes only test-only diagnostics/documentation and does not claim hosted results.
- Jenkins Java 17 execution: remains externally blocked; not addressed.

The first non-escalated probe attempt failed before runtime startup because WSL sandbox restrictions prevented Mockito/Byte Buddy from self-attaching to the JVM. The same test-only run succeeded with the permitted local loopback/attach execution. This is an environment limitation, not a NIS runtime result.

## Independent gates retained

The Phase 2I-M production Jetty 12 migration remains **BLOCKED** and was not resumed. The Phase 2I-A/B/C Jenkins Java 17 image/shared-library blocker remains unresolved. Phase 2F constraints remain unresolved: H2 1.4 offline export/import; V1.0.0–V1.0.7 Flyway history/checksum validation; representative Mainnet/Testnet database validation; and matching-genesis full chain-state comparison. This phase does not change those records or gates.

## Final Phase 2I-O status

**PARTIAL**. The Jetty 12 production-equivalent XHR failure is identified and causally reproduced; async-enabled test-only configuration proves the correction path. A no-`/w` diagnostic request demonstrably hits Jetty's default 404 servlet, while the correct production URL reaches Spring and upgrades. However, the retained Phase 2I-M evidence does not identify its actual failing URL/layer, so the historical WebSocket 404 root cause is not established. No production Jetty migration or production behavior change is authorized by this result.
