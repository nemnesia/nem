# Phase 2I-P — Jetty 12 WebSocket routing / upgrade trace

## 結果

**COMPLETE — ORIGINAL 404 NOT REPRODUCIBLE**。Phase 2I-M の404自体は履歴として維持する。Phase 2I-Mで保存されたのは `/w/messages/{server}/{session}/websocket` というpath patternとHTTP 404の結果で、実際の要求行・session token・routing/upgrade traceは残っていない。今回、同一path patternを標準SockJS clientで生成し、production NIS listener/initializer/filter chainを通すJetty 12.1.13 EE8 test bootstrapを独立に2回起動して比較したところ、両方ともHTTP 101、STOMP CONNECTED、NIS MESSAGE、receipt付きDISCONNECTに成功し、404を再現しなかった。したがって元の404を特定のlayerに帰属させることはできず、現在は再現可能なJetty 12 routing incompatibility evidenceではない。

これはJetty 12 production migrationの承認ではない。Servlet/filter async-support問題および独立gateが残るため、次Phaseでの統合candidate検証が必要。

## Repository / environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `1f6d45799de39075c328b23d94aedfa850aecc27`
- 開始時worktree: clean。開始時local HEADと`origin/agent/nis-phase0-baseline`は一致。
- Java / javac: OpenJDK `17.0.20.1` / `17.0.20.1`
- Maven: `3.8.7`
- Candidate: Jetty `12.1.13` EE8、`javax.servlet`; Spring `5.3.39`
- Standard client: Node.js `sockjs-client@1.6.1`
- Production source/dependency/bootstrap: 変更なし

## Bootstrap and instrumentation

Test-only launcher: `docs/modernization/phase-2i-o-poc/ProductionParityControl` (source path under `src/main/java`). It calls the real production `AbstractNemServletContextListener`, `NisWebAppWebsocketInitializer`, and production filter registration code; DispatcherServlet is mapped `/w/*`; `ContextLoaderListener` follows the NIS listener; Jetty 12 EE8's `JavaxWebSocketServletContainerInitializer` is configured before server start. The parent NIS application context is the existing test fixture/mocks, not the complete `CommonStarter` production dependency graph. A diagnostic `MappedInterceptor` and request-trace filter observe routing. Jetty 9's old Gzip filter name is represented by a test-only pass-through filter; production `JsonErrorHandler` cannot be reused unchanged because its Jetty 9 base type is incompatible with Jetty 12 EE8. These harness differences are not on the successful WebSocket route, but this is not a full production `CommonStarter` boot.

At startup the WebSocket SCI callback precedes application listeners and sets `javax.websocket.server.ServerContainer` to `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`. The same attribute is present at entry to the NIS listener, after `ContextLoaderListener`, and at child Spring context refresh. The `NisWebAppWebsocketInitializer` ServiceLoader provider detects that EE8 container and selects `ProductionParityControl$LoggedUpgradeStrategy`; selection is logged twice during initialization. There is no fallback to Spring's Jetty 9 strategy in the measured run. The selected strategy logs its entry and calls Jetty's public `JavaxWebSocketServerContainer.upgradeHttpToWebSocket` API.

Runtime servlet mapping:

| Servlet | URL pattern | asyncSupported |
| --- | --- | --- |
| Spring `DispatcherServlet` (`Spring Websocket Dispatcher Servlet`) | `/w/*` | false |
| Jetty `Default404Servlet` | `/` | true |

Filters on `/*`, `REQUEST`, in observed runtime chain order:

| Filter | asyncSupported | Ownership |
| --- | --- | --- |
| Jetty `WebSocketUpgradeFilter` | true | EE8 SCI |
| `DoSFilter` | false | production listener registration; test alias to Jetty EE8 implementation |
| `GzipFilter` | false | production registration; test-only pass-through compatibility alias |
| inline CORS filter | false | production listener |
| `Phase2IORequestTrace` | true | test only |

Listener order is NIS production-derived listener, Spring `ContextLoaderListener`, test observer. Spring child context refresh and endpoint registration occur during `DispatcherServlet` initialization after listener callbacks, before requests. At client request entry the trace records `DispatcherType.REQUEST`, URI, query, context/servlet/path-info, async flags, and the live Servlet API mapping name/class/pattern.

## Exact client / request trace

Initial SockJS URL: `http://localhost:40867/w/messages` (second run used its own ephemeral port). SockJS info URL: `http://localhost:40867/w/messages/info`; server trace recorded `GET /w/messages/info`, `contextPath=""`, `servletPath=/w`, `pathInfo=/messages/info`, servlet `Spring Websocket Dispatcher Servlet`, Spring handler `SockJsHttpRequestHandler`, HTTP 200.

First full trace's WebSocket URL as recorded from `sockjs-client`'s selected transport object:

```text
ws://localhost:40867/w/messages/211/rj1zh0ks/websocket
```

The SockJS client reported WebSocket handshake status `101`. Matching server-side request trace for that same path:

```text
GET /w/messages/211/rj1zh0ks/websocket
contextPath="", servletPath=/w, pathInfo=/messages/211/rj1zh0ks/websocket
DispatcherType=REQUEST
servlet=Spring Websocket Dispatcher Servlet
servlet mapping=/w/*, match=w, type=PATH
asyncSupported=false, asyncStarted=false
Spring handler=org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler
```

The same request then emitted `upgrade-callback` from `LoggedUpgradeStrategy.upgradeInternal` with container `org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer`. The standard client opened the SockJS session, received STOMP `CONNECTED`, an NIS `/node/info` `MESSAGE`, and `RECEIPT` for `DISCONNECT`, then closed normally. Thus this request passed through **client URL → DispatcherServlet mapping → Spring SockJS handler → selected shim → Jetty EE8 upgrade callback → HTTP 101**. The Servlet API filter's post-chain status reads 200 after the container upgrade, so it is not used as wire-level handshake status; the client's actual WebSocket status is 101.

There was no redirect, host/path rewrite, or query string in the request. The request path contains SockJS server id `211` and session id `rj1zh0ks`. A second independent run also selected the standard `websocket` transport and completed the same STOMP/NIS message/receipt flow; it used a different ephemeral port and generated session id.

Second run's exact URLs were `http://localhost:42147/w/messages`, info `http://localhost:42147/w/messages/info`, and WebSocket `ws://localhost:42147/w/messages/728/0s4ug5kg/websocket`; client handshake status was also `101`.

## Control B — intentionally incorrect path

The control sent `GET /messages/info` and raw WebSocket `GET /messages/000/phase2i-o-wrong-prefix/websocket`, omitting `/w`.

For both requests, `contextPath=""`, `servletPath` was the entire URI, `pathInfo=null`, and the live servlet mapping was Jetty `Default404Servlet` with pattern `/` (`MappingMatch.DEFAULT`). No Spring HandlerMapping or SockJS handler trace occurred. The WebSocket control received Jetty 12 HTTP `404 Not Found`; the response included `Server: Jetty(12.1.13)`. This proves the producer for this deliberately wrong path is the Jetty default 404 servlet. It does not prove that Phase 2I-M used this path.

## Phase 2I-M comparison and conclusion

The Phase 2I-M record states that `/w/messages/info` returned 200 and gives `/w/messages/{server}/{session}/websocket` as the failing WebSocket endpoint pattern, with Jetty HTTP 404 and `All transports failed`. It does not preserve the concrete client-generated URI/session id, a corresponding server request trace, servlet selection, Spring handler, container attribute snapshot, provider selection, or upgrade callback. The Phase 2I-N document also notes earlier path-label inconsistency in M artifacts. Therefore Control C can reproduce the documented path *shape*, but cannot replay the exact historical request bytes or identify its 404 producer.

With that limitation, the production-equivalent `/w/*` route was exercised twice with the standard client and both times reached Spring, selected the shim, invoked the Jetty 12 upgrade API, and negotiated status 101. The intentionally wrong path produced 404 at the default servlet. Neither result matches a retained M server trace because there is none. The cause of the historical 404 remains **unknown**; it is not assigned to a URL typo, Servlet mapping, Spring handler, or Jetty upgrade layer. The recorded M failure is retained unchanged, but this phase did not reproduce it and it is not repeatable evidence of a current Jetty 12 routing incompatibility.

## XHR status and remaining gates

This run intentionally kept production-equivalent async flags unchanged. As Phase 2I-O established, the DispatcherServlet and DoS/Gzip/CORS filters are async-disabled, so XHR polling still fails at Spring SockJS async request setup with `Async support must be enabled on a servlet and for all filters involved in async request processing`. No XHR correction was made here.

Still independent and unresolved:

- production async-support fix for DispatcherServlet and all request-chain filters, followed by Jetty 9 and Jetty 12 WebSocket/XHR regression and lifecycle checks;
- Jenkins Java 17 image/shared-library execution blocker;
- Phase 2F H2 offline conversion, Flyway history/checksum, representative Mainnet/Testnet DB validation, and matching-genesis chain-state comparison.

Do not start production Jetty 12 migration until the next phase validates the async-support candidate together with WebSocket and XHR regression, cleanup, thread lifecycle, and Java 17 / Java 25 builds. This phase makes no changes to Phase 2I-M's BLOCKED result or Phase 2I-O's historical PARTIAL record.

## Validation / changed files

- Probe setup: `npm ci --prefix docs/modernization/phase-2i-h-poc`; runtime command: `mvn -B -f docs/modernization/phase-2i-o-poc/pom.xml clean package exec:java -Dexec.mainClass=org.nem.nis.websocket.ProductionParityControl`.
- `mvn -B -pl nis -am -DskipTests package` on Java 17: success.
- `mvn -B -f docs/modernization/phase-2i-o-poc/pom.xml clean package exec:java -Dexec.mainClass=org.nem.nis.websocket.ProductionParityControl`: success; runtime completed WebSocket flow. A second runtime repetition also succeeded.
- Java 25 `25.0.4.1`: `mvn -B -pl nis -am -DskipTests package` and test-only harness `clean package` both succeeded; Java 25 runtime protocol probe was not run.
- Java 17 runtime protocol: `/info` 200; WebSocket 101; CONNECTED/MESSAGE/receipt DISCONNECT; clean close.
- Java 17 Maven: `3.8.7`.
- Hosted CI: not run.
- Changed files: this report, `phase-2i-o-poc/ProductionParityControl.java`, and test-only `phase-2i-h-poc/sockjs-client-probe.js` URL/status logging.
- Production source/dependency/bootstrap: unchanged.

The ordinary sandbox invocation of the runtime probe cannot perform Mockito/Byte Buddy self-attach and loopback operations in this WSL environment. The same test-only command succeeded when run with the authorized local loopback/attach capability; this environment restriction is not interpreted as an application failure.
