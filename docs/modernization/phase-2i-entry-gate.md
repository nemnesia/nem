# NIS modernization — Phase 2I Jetty entry gate

Status: **BLOCKED — Phase 2I implementation not started**
Date: 2026-09-26 (Asia/Tokyo)
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested starting HEAD: `a8cf554d9a3baf08b8a9cf3808d98eea79b3cff5`
Actual starting HEAD: `b65754d0d128e5bcda441cf6e4c710808bdf6c99`

The actual HEAD differs because the Phase 2H commit subject was subsequently
reworded at the user's request and pushed. The Phase 2H source/content commit
is `750d8c48b7f88695606a42165d3e0d89e804f971`; `b65754d` is its formatted
follow-up. The starting worktree was clean and the local branch matched
`origin/agent/nis-phase0-baseline`.

Phase 2I Jetty dependencies and source were **not changed**. The entry gate
identified a configured Jenkins build image that still uses Java 11, while
Java 17 is now the required compiler baseline. No Phase 2H Java 17/25 hosted
CI runs were present for this branch. Without a verified CI path that can
build and test Java 17 changes, Jetty implementation would start without the
repository's configured Jenkins build being able to compile the project.
The pinned `_symbol` Java utility images were not modified.

## Entry gate results

| Gate | Result | Evidence |
| --- | --- | --- |
| Java 17 and Java 25 CI status | **NOT VERIFIED — no runs for this branch** | Public GitHub Actions API returned `total_count: 0` for `agent/nis-phase0-baseline`. The workflows currently trigger on pushes to `dev`/`main` and pull requests targeting `dev`; this branch has no matching run. The previous Phase 2H local runs recorded 6,218/6,218 passing tests on both Java 17 and Java 25, but they do not establish hosted CI status. |
| Java 17 production Docker build | **PASS** | `docker build -f nis/Dockerfile -t nem-phase2h-java17-entry-gate .` succeeded. It built all Maven modules and packaged the NIS image with Ubuntu 22.04 and OpenJDK 17/JRE 17. |
| Current NIS Docker command/startup | **PASS with isolated smoke settings** | The unchanged image command launched `org.nem.deploy.CommonStarter`; logs show Spring contexts and both Jetty 9.4.58 listeners started on 7778 and 7890. With ordinary public-peer boot, the node later failed network boot and exited because peers were unreachable. A second run used temporary `config-user.properties` with `nis.ipDetectionMode=Disabled` to isolate server startup from public-peer availability. |
| Persistence initialization | **PASS for an empty throwaway database only** | On the isolated smoke run, Flyway 9.22.3 created a new H2 2.2.220 database and applied V1.0.0–V1.0.7; Hibernate initialized. The database was inside the disposable container's `/home/nem/nem` directory. No existing database or migration file was altered. This does not satisfy Phase 2F Mainnet/Testnet or full-chain verification gates. |
| HTTP / heartbeat / error / filter and listener startup | **PASS for smoke scope** | `/heartbeat` returned HTTP 200 JSON `{"code":1,"type":2,"message":"ok"}`. An unknown route returned HTTP 404 JSON with CORS response headers. Logs show Spring root and dispatcher contexts initialized and servlet contexts started. |
| SockJS / WebSocket / STOMP | **PASS for smoke scope** | `GET /w/messages/info` on port 7778 returned HTTP 200 with `"websocket":true`. A SockJS WebSocket connection to `/w/messages/000/phase2i-stomp/websocket` returned `o`; a framed STOMP `CONNECT` returned `CONNECTED` version 1.2. |
| Graceful shutdown | **PASS** | `docker stop --timeout 20` delivered SIGTERM; Jetty stopped both connectors and destroyed both Spring servlet contexts. Container exit 143 is the expected signal exit status. |
| Jenkins executor JDK | **BLOCKER — configured build image is Java 11; live executor unverified** | `nis/Jenkinsfile` selects `java.Dockerfile`. The pinned `_symbol/jenkins/docker/ubuntu/java.Dockerfile` defaults `JAVA_VERSION=11`; `_symbol/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml` also records `ubuntu-lts` Java 11. The NIS Jenkins pipeline resolves its default image as `java-ubuntu-lts`. The externally managed live Jenkins agent cannot be queried here, so its actual JDK is not claimed as verified. |
| Phase 2H Failsafe comparison | **NO NEW RUN; prior recorded baseline reviewed** | Phase 2H records Core 7 classes with one timing failure, Peer 2 cases passing, and NIS 79 cases with 9 failures / 21 errors / 2 skipped. Phase 2F previously recorded 9 / 19 / 2; the two additional errors were H2 1.4 hard-disk context-load failures. They remain under the offline H2 conversion gate. The known public-peer timeout remains environment-dependent. |

The temporary container was stopped and removed. Its generated database was
not retained or used as a baseline. The local Docker image is a disposable
test artifact and is not part of the repository.

## Jetty inventory (unchanged)

The dependency graph was inspected with Maven on Java 17 before any Jetty
changes:

| Module | Current direct Jetty dependencies | Current version | Notes |
| --- | --- | --- | --- |
| `deploy` | `jetty-annotations`, `jetty-client`, `jetty-plus`, `jetty-server`, `jetty-servlet`, `jetty-servlets` | `9.4.56.v20240826` | Server transitively supplies `javax.servlet-api:3.1.0`. |
| `nis` | Same six Jetty artifacts plus `org.eclipse.jetty.websocket:websocket-server` | `9.4.58.v20250814` | Direct `javax.servlet:javax.servlet-api:4.0.1`; WebSocket server transitively includes Jetty WebSocket API/client/common/servlet artifacts. |

The Deploy and NIS Jetty graphs are inconsistent: their direct Jetty patch
versions differ. No Jetty 12 or Jetty 9 cleanup was applied.

Jetty-specific production APIs are concentrated in `deploy`:

- `AbstractServerBootstrapper`: `Server`, `ServerConnector`,
  `HandlerCollection`, `ServletContextHandler`, `Configuration.ClassList`,
  `WebAppContext`, `QueuedThreadPool`, and `ScheduledExecutorScheduler`.
- `NemServerBootstrapper` and `NemWebsockServerBootstrapper`: HTTP and
  WebSocket listener construction, servlet dispatcher mapping, listener
  registration, HTTP configuration, and port binding.
- `AbstractNemServletContextListener`: Spring context/listener setup and
  registration of DOS, Gzip, and CORS filters.
- `JsonErrorHandler`: Jetty `ErrorHandler` extension.
- `CommonStarter`: lifecycle, shutdown, and Jetty `HttpClient` use.
- NIS WebSocket endpoints are Spring STOMP/SockJS at `/w/messages`; no NIS
  production source directly imports a Jetty WebSocket endpoint API.

## Candidate boundary and unresolved work

Upstream Jetty's stable 12.1 line requires Java 17 and offers the EE8,
EE9, EE10, and EE11 environments. Its EE8 environment retains the
`javax.servlet` namespace; the Servlet module is relocated to the
`org.eclipse.jetty.ee8` artifact family, and Jetty 12 changes embedded
handler APIs. Jetty 12 documents a separate EE8 `javax.websocket`
implementation, so Spring STOMP/SockJS compatibility still needs direct
validation. This supports EE8 as a candidate transitional boundary with
Spring 5.3 and the existing `javax` application contract, but no EE8 or EE11
selection is made while the entry gate is blocked.

Relevant upstream references:

- [Jetty 12.1 compatibility and support status](https://jetty.org/docs/jetty/12.1/index.html)
- [Jetty 12.1 server and Servlet artifact layout](https://jetty.org/docs/jetty/12.1/programming-guide/server/http.html)
- [Jetty 12.1 WebSocket environments](https://jetty.org/docs/jetty/12.1/programming-guide/server/websocket.html)
- [Jetty 11 to 12 API migration guide](https://jetty.org/docs/jetty/12.1/programming-guide/migration/11-to-12.html)

No production dependency, source, route, filter, persistence, schema,
migration, or database compatibility behavior changed. The Phase 2F gates
remain intact, including offline H2 1.4 to 2.x export/import, V1.0.0–V1.0.7
history/checksum preservation, representative Mainnet/Testnet verification,
and matching-genesis full-chain comparison.

## Unblock and Phase 2J entry conditions

Phase 2I may start after the repository-owned Jenkins configuration selects
a Java 17-capable build image (without editing externally owned `_symbol`
utility images), and the Java 17/25 workflows have verified results for the
branch or an explicitly accepted equivalent. The actual Jenkins agent JDK
must also be confirmed with its operators. Do not report the pinned Java 11
default image as Java 17.

After Jetty 12.1 EE8 implementation, Phase 2J may start only when both
Java 17/25 test and package matrices pass without test-discovery reduction;
HTTP, error/filter, listener, shutdown, WebSocket handshake, STOMP, and
SockJS contracts are verified; no Jetty 9 runtime artifacts or accidental
`javax`/`jakarta` mixing remain; and production-like NIS startup and
persistence initialization pass. Phase 2F database gates remain separate
and unresolved regardless of the Jetty smoke result.

Rollback point for this entry-gate-only commit is
`b65754d0d128e5bcda441cf6e4c710808bdf6c99`. No Phase 2I implementation
commit exists.
