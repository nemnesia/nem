# Phase 2I-K — Jetty 12 abnormal lifecycle readiness

## 判定

**PARTIAL — production Jetty 12 migration は開始しない。**

SockJS XHR の残留は Jetty 9 / Jetty 12 共通で、恒久 session leak ではなく、HTTP async poll が SockJS heartbeat で切断を検知するまで保持され、その後 Spring の disconnect cleanup が回収する挙動と特定できた。両 Jetty で100件×3 batchを実行し、各回約35秒でsession mapが0になった。QTP workerは実行ごとのhost負荷で変動し、idle状態でも60秒後にベースラインまで縮退しなかったため、resource-retentionの説明をreadiness条件として残す。Malformed STOMPの代表caseでは両 transport / runtimeで20秒間ERROR/closeがなく接続維持だった。Java 17/25 のローカル clean test/package および同一commitの hosted CI は成功した。Jetty QTP の余剰 workerがidle timeout後も残る理由、batch間のthread増加の意味が確定していないため判定はPARTIAL。

## 対象と開始状態

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `d860c8ee2c6bdc56ec96dbb8d1e8c1d86b795b98`
- Jetty 9: `9.4.58.v20250814`
- Jetty 12 test runtime: `12.1.13` EE8
- Spring Framework: `5.3.39`
- SockJS client: `sockjs-client@1.6.1`
- JDK for local runtime probes: OpenJDK `17.0.20.1`; Maven `3.8.7`; Linux amd64
- Servlet contract: `javax.servlet` / Servlet 4.0
- Production Jetty dependency/bootstrap, Spring, Jakarta namespace, persistence, DB schema, Flyway, and migration SQL were not changed.

2I-J report and older 2I-A–2I-J records remain historical records; this document supplements them.

## Spring SockJS cleanup の実装確認

NIS production initializer の `/messages` SockJS registration has no explicit heartbeat or disconnect-delay override. Runtime reflection shows Spring `heartbeatTimeMs=25000`, `disconnectDelayMs=5000`. Each created SockJS session schedules Spring cleanup at a fixed rate of `disconnectDelay` (5 seconds). The callback removes and closes sessions only when `getTimeSinceLastActive() > getDisconnectDelay()`.

`AbstractHttpSockJsSession.isActive()` is true while its `ServerHttpAsyncRequestControl` exists and has not completed. The abandoned XHR samples showed `AsyncContext(started=true, completed=false, timeoutMs=-1)`, `idleMs=0`; the Servlet API defines a timeout of zero or less as no timeout. The probe therefore confirms why the 5-second session cleanup does not remove a still-active poll immediately. The default Spring heartbeat later writes to that poll. When the client socket has been abandoned, the heartbeat write completes/resets the request; the session becomes inactive, its activity time is refreshed, then the normal disconnect-delay cleanup removes it.

Upstream source references:

- [Spring 5.3.39 `TransportHandlingSockJsService`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/transport/TransportHandlingSockJsService.java) — fixed-rate cleanup and `getTimeSinceLastActive() > getDisconnectDelay()` predicate.
- [Spring 5.3.39 `AbstractHttpSockJsSession`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/transport/session/AbstractHttpSockJsSession.java) — async request control and active-poll state.
- [Spring 5.3.39 `AbstractSockJsSession`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/transport/session/AbstractSockJsSession.java) — heartbeat scheduling and last-active tracking.
- [Spring 5.3.39 `PollingSockJsSession`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/transport/session/PollingSockJsSession.java) — polling response / heartbeat write lifecycle.
- [Servlet `AsyncContext` API](https://docs.oracle.com/javaee/7/api/javax/servlet/AsyncContext.html) — zero or less is no timeout; probe measured Jetty's current async timeout as `-1`.

### XHR retention 時系列

Measurements use the same test-only runner, production NIS initializer path, real NIS STOMP handlers/converter, and standard SockJS client. `t=0` is immediately after the abrupt client batch has exited. One session count is transiently removed before the first sample in the 10/100 cases; remaining sessions have an open receiving AsyncContext until heartbeat. The cleanup task is not canceled and is observed scheduled at approximately 5-second intervals.

| Count / time | Jetty 9 session map / HttpPoll | Jetty 12 EE8 session map / HttpPoll | State / interpretation |
| --- | ---: | ---: | --- |
| 1 / t=0 | 1 / 1, inactive poll in sampled run | 1 / 1, inactive poll | Single-session receiving request had already completed; cleanup is scheduled. |
| 1 / t=10 | 0 / 0 | 0 / 0 | Both single-session probes completed cleanup. |
| 10 / t=0 | 10 / 10, 9 active AsyncContexts | 10 / 10, 9 active AsyncContexts | Async timeout reports `-1`; Spring heartbeat 25s, disconnect cleanup 5s. |
| 10 / t=10 | 9 / 9, all remaining active | 9 / 9, all remaining active | Active poll prevents idle cleanup. |
| 10 / t=30 | 9 / 9, 0 active; sample idle 5.0s | 9 / 9, 0 active; sample idle 5.1s | Heartbeat ended the abandoned poll; cleanup scheduler has not yet crossed the next predicate/sweep. |
| 10 / t=45 | 0 / 0 | 0 / 0 | Both runtimes reclaimed all sessions. |
| 100 / t=0 | 100 / 100, 99 active AsyncContexts | 100 / 100, 99 active AsyncContexts | Same state at scale. |
| 100 / t=10 | 99 / 99, 99 active AsyncContexts | 99 / 99, 99 active AsyncContexts | One inactive entry was swept; remaining polls stay active. |
| 100 / t=30 | 99 / 99, 0 active; idle 5.0s | 99 / 99, 0 active; idle 5.1s | Both have received the heartbeat disconnect detection and await session sweep. |
| 100 / t=45 | 0 / 0 | 0 / 0 | No persistent session or HttpPoll remains. |

Jetty 9 same-instance repeated 100-session XHR batches: cleanup times from batch start were `35,282 ms`, `34,896 ms`, `35,020 ms`; session map remaining after each was `0`. Jetty 12 batches were `35,369 ms`, `35,049 ms`, `34,924 ms`, each with 0 sessions/HttpPoll after cleanup. Values include client startup/delivery and match the ~25-second heartbeat plus 5-second disconnect delay and scheduler phase. Separate Jetty 12 one-session sampling was not run; the 10/100 series and earlier Jetty 9 one-session probe are the available scale evidence.

## Jetty thread pool

Both runtime probes used the default `QueuedThreadPool`: `minThreads=8`, `maxThreads=200`, `idleTimeout=60000 ms`, `reservedThreads=-1`; `maxReservedThreads` was 12 on Jetty 9 and 16 on Jetty 12. The repeated Jetty 12 same-server series reported 9 threads before load, then 34 / 54 / 58 after cleanup of batches 1 / 2 / 3 (7 busy each time). Top stacks were predominantly `TIMED_WAITING` at `Unsafe.park`; six were selector `EPoll.wait` and one acceptor `Net.accept`. A separate single-batch idle observation was 66 QTP threads immediately after cleanup and still 66 after an additional 65 seconds; the comparable Jetty 9 run was 95 then 94. Both had 0 sessions/HttpPoll and 7 busy; counts are noisy across isolated invocations but the held workers are idle QTP workers, not blocked async requests. The repeated Jetty 12 counts rise across short inter-batch cleanup windows, but they do not monotonically grow sessions; the sample does not prove eventual worker eviction. Treat QTP retention/eviction behavior as an unresolved resource-readiness item, not as a proven leak or as a harmless default without more observation.

JVM thread categories include Jetty QTP worker threads, selector/acceptor, one SockJS scheduler, Node/HTTP client threads. Profiler/FD/native memory profiling was not added. Session cleanup is proven; worker shrink after idle timeout is not.

## WebSocket abrupt-close callback

Test-only `WebSocketHandlerDecoratorFactory` instrumentation was installed through a subclass of the production initializer. It records session ID, exception/root cause, callback order and close code without changing production classes.

| 100 abruptly exited WebSocket clients | Spring `handleTransportError` callbacks | close callbacks | Sequence / outcome |
| --- | ---: | ---: | --- |
| Jetty 9.4.58 | 0 | 100 | each observed session closed; reconnect succeeded |
| Jetty 12.1.13 EE8 | 100 | 100 | one `java.nio.channels.ClosedChannelException` (message `null`) then one close callback `1006: Session Closed` per session; reconnect succeeded |

An isolated Jetty 12 100-session batch produced exactly 100 transport errors and one `ClosedChannelException` callback before a close callback per session, no duplicate callback within a session, 100 CONNECT/CONNECTED, and successful cleanup/reconnect. A controlled run with one preliminary abrupt connection plus a nominal 100 batch produced exactly 101 transport error callbacks. It also recorded 101 total sessions (preliminary +100); by the batch snapshot 95 close callbacks had arrived, then Spring stats reached 0 active WS and all 101 transport callbacks were accounted for. This explains 101 as the extra preliminary session, not a duplicated callback. Jetty 9 reports 0 Spring transport errors for the 100 abrupt closes while close callbacks occur. The Jetty 12 counter is observability of its `ClosedChannelException` mapping; no NIS handler/reconnect/session regression was observed. Keep the counter difference visible; do not suppress it.

## STOMP malformed/error probe

The client harness uses a 20-second observation window and reports selected transport, `ERROR`/message, close/error events, and socket state separately. Sending `INVALID_STOMP_FRAME` after CONNECTED on both WebSocket and XHR polling yielded no client ERROR or close and left the client socket open for the observation window. Jetty 9 and Jetty 12 matched this silent/connected outcome. On WebSocket, Jetty 12 counted one `ClosedChannelException` only after the probe process exited without a STOMP DISCONNECT; Jetty 9 counted 0. On XHR both had one active HttpPoll, no transport error, and no ERROR/close at the end of the window. Other invalid destinations/subscriptions were not exercised in this phase.

## Public peer dependency in Java 17 unit suite

`NisPeerNetworkHostTest.createNetwork()` used a real `HttpConnectorPool`. Boot triggered `PeerNetwork.boot()` and `LocalNodeEndpointUpdater.updateAny()`, which sent `getLocalNodeInfo` to seed peers from `peers-config_mainnet.json`. This made a unit test and its neighboring host boot tests depend on DNS/public peer availability.

The test-only default host helper now uses mocked peer/time-sync connectors. `getLocalNodeInfo` asynchronously returns the same supplied local endpoint after a 25 ms delay, preserving successful auto-IP discovery and the pending-future observation in `defaultHostCanBeBootedAsync`; no public network request is made. Tests supplying explicit connector pools retain their own behavior. The complete `NisPeerNetworkHostTest` class targeted run passed. Java 17 `mvn -B clean test` and `mvn -B clean package` passed. Java 25 first clean test had one timing failure in `AsyncTimerTest.visitorIsNotifiedOfSuccessfulCompletions` (2361 tests, 1 failure); an immediate full clean test retry passed, and a subsequent `mvn -B clean package` passed. The first failure occurred while the probe suite was concurrently loading the host, so scheduling contention is plausible but not proven. The final Java 17 Baseline and Java 25 Compatibility workflows both passed clean test and package on commit `c3027972c712ecdaaa811a46df92d88415561990`. Local successful package reports 625 classes / 6,220 tests (0 failures/errors/skips), two more tests than the recorded 2H discovery baseline (624 / 6,218); no source test class/method was added in this phase, and exact source of that historical count difference was not established.

Hosted workflow evidence:

- Java 17 Baseline run [`36284615185`](https://github.com/nemnesia/nem/actions/runs/36284615185), commit `c3027972c712ecdaaa811a46df92d88415561990`: `Run clean unit tests` and `Package modules` succeeded.
- Java 25 Compatibility run [`36284615182`](https://github.com/nemnesia/nem/actions/runs/36284615182), same commit: `Run clean unit tests` and `Package modules` succeeded. This is compatibility evidence only, not a Java 17 substitute.
- Documentation follow-up HEAD `e9f256a3fdf0478c2b54a09823a11495371d7d8f` was also verified: Java 17 Baseline run [`36284822528`](https://github.com/nemnesia/nem/actions/runs/36284822528) and Java 25 Compatibility run [`36284822511`](https://github.com/nemnesia/nem/actions/runs/36284822511) both completed `success`, including clean test and package.

## Existing independent gates

- Jenkins Java 17 execution remains blocked by external shared-library/image ownership and was not modified.
- Phase 2F H2 1.4 offline export/import, Flyway history/checksum constraints, Mainnet/Testnet DB validation, and matching-genesis state comparison remain unresolved.
- Known Phase 2H Failsafe / public-peer history is retained; this phase did not disable or skip any tests.

## Files and implementation boundary

Production code changes: none. The NIS unit-test fixture is deterministic; the Jetty lifecycle probes, Spring callback decorator, session/thread inspection, and extended Node observation window are under `docs/modernization/phase-2i-g-poc` and `phase-2i-h-poc` only. This phase does not change production Jetty 9 dependencies or start Jetty 12 production migration.

## Remaining evidence before readiness

1. Resolve or further characterize QTP worker retention after 60 seconds idle; thread counts are idle-dominated and noisy, but did not shrink to baseline during the observation window.
2. Run the Jetty 12 one-session retention sample and, if readiness claims depend on it, extend post-idle/repeated-batch observation beyond the measured window.
3. Clarify why Jetty 9/12 QTP retains excess idle workers beyond the configured `idleTimeout`, and determine whether the 34→54→58 Jetty 12 batch counts reflect normal pool growth or a lifecycle retention issue. Hosted Java 17/25 validation passed on the documented follow-up HEAD; Java 25 cannot substitute for Java 17.

Until these are complete and all parity results are reviewed, Jetty 12 production migration must remain gated.
