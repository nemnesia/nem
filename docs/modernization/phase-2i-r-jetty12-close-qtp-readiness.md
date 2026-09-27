# Phase 2I-R — Jetty 12 close callback / QTP readiness

## Result

**COMPLETE — READY FOR PRODUCTION JETTY 12 MIGRATION**

The two Phase 2I-Q uncertainties are characterized on test-only production-equivalent Jetty 9 and Jetty 12 EE8 runtimes. Abrupt EOF has a Jetty 12 callback-count difference, but callback ordering, Spring implementation, and cleanup evidence indicate a transport-layer EOF notification rather than an NIS application failure. QTP worker retention is bounded and shrinks slowly according to each pinned Jetty line's pool-wide eviction policy. Java 17/25 local test/package and hosted Java 17/25 workflows succeeded for the evidence commit. **This phase is ready to hand off to a separate production migration phase.**

No production Jetty dependency, bootstrap, Spring configuration, or application behavior was changed. The production Jetty 12 migration was not started.

## Repository and environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `56447225c3357c28613548c5b410a00ebdc876dc`
- Evidence commit: `a0802c66ed6cd754bdd2b8dbaf8a6e4b01efe92b`; the final documentation result is a follow-up commit.
- Jetty 9: `9.4.58.v20250814`
- Jetty 12: `12.1.13` EE8
- Spring Framework: `5.3.39`
- SockJS client: `sockjs-client@1.6.1`
- Java: OpenJDK `17.0.20.1` and `25.0.4.1`
- Maven: `3.8.7`
- Harnesses: `phase-2i-g-poc` Jetty 9/Jetty 12 controls, using the real NIS WebSocket configuration and test-only Jetty 12 EE8 shim. Loopback runtime probes use the standard Node SockJS client.

## WebSocket close callback comparison

The instrumentation records monotonic callback timestamps, thread names, exception/root-cause class and message, the top eight exception stack frames, close status, and callbacks by SockJS session ID.

| Scenario | Jetty 9 | Jetty 12 EE8 | Result |
|---|---|---|---|
| STOMP `DISCONNECT` + receipt, then transport close | Prior Phase 2I-Q controls delivered the receipt and cleaned the Spring/SockJS session; no abnormal error was observed. | Same contract in the controls; no abnormal close error for the normal client path. | No observed difference in application protocol behavior. |
| WebSocket close frame (1000), without STOMP `DISCONNECT` | `handleTransportError=0`; `afterConnectionClosed=1`, status 1000; SockJS session map returned to 0. | `handleTransportError=0`; `afterConnectionClosed=1`, status 1000; SockJS session map returned to 0. | Same close-frame mapping and cleanup. |
| Abrupt client process/socket EOF, 100 clients | 100 `afterConnectionClosed` callbacks (status `1006:Disconnected`); 0 `handleTransportError`; Spring map returned to 0; subsequent reconnect succeeded. | 100 transport-error callbacks and 100 close callbacks, one of each per session; each error is `java.nio.channels.ClosedChannelException`; Spring map returned to 0; subsequent reconnect succeeded. | J12 reports the EOF through an additional error callback before close. No duplicate callback per session and no observed NIS/STOMP cleanup or reconnect failure. |
| Server shutdown with one active SockJS WebSocket | 0 transport errors, one close callback; client observed code 1000 (unclean transport); Spring closed status `1006:Disconnected`. | 1 transport error (`ClosedChannelException`) followed by one close callback; client observed `1006`, Spring close status `1001` (going away). | Teardown details differ, but both paths close the Spring session. This is a runtime shutdown observability difference, not a normal-session protocol result. |

For the 100 abrupt Jetty 12 sessions, callback order was `handleTransportError` then `afterConnectionClosed`; the error callback occurred exactly once per session. Representative exception stack begins at `org.eclipse.jetty.websocket.core.internal.WebSocketSessionState.onEof`, then `WebSocketCoreSession.onEof`, `WebSocketConnection.fillAndParse`, `onFillable`, and Jetty's `QueuedThreadPool.runJob`. The server-shutdown sample also entered through `WebSocketSessionState.onEof` / `WebSocketConnection` close handling. There was no 101st probe/shutdown callback in the isolated 100-client run.

The Jetty 12 EE8 bridge upgrades the request into Jetty's standard `javax.websocket` container; it does not implement the endpoint's error/close callbacks. Spring 5.3.39 `StandardWebSocketHandlerAdapter.onError` forwards the JSR-356 error to `WebSocketHandler.handleTransportError`; `onClose` separately forwards `afterConnectionClosed`. `SubProtocolWebSocketHandler.handleTransportError` increments the transport-error statistic, while `afterConnectionClosed` clears the session and invokes the protocol handler's session-ended path. Thus the metric difference is produced by Jetty 12 Core EOF reporting propagated through the standard EE8/Spring adapter, not by the NIS application handler or the upgrade shim. The application handler is not invoked for the `ClosedChannelException`; cleanup is performed on the subsequent close callback. No exception was swallowed or suppressed.

Upstream source references:

- [Spring 5.3.39 `StandardWebSocketHandlerAdapter`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/adapter/standard/StandardWebSocketHandlerAdapter.java)
- [Spring 5.3.39 `SubProtocolWebSocketHandler`](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/messaging/SubProtocolWebSocketHandler.java)
- [Jetty 12.1.13 `QueuedThreadPool`](https://github.com/jetty/jetty.project/blob/jetty-12.1.13/jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java)
- [Jetty 9.4.58 `QueuedThreadPool`](https://github.com/jetty/jetty.project/blob/jetty-9.4.58.v20250814/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java)
- [Jetty WebSocket Core `FrameHandler` lifecycle contract](https://javadoc.jetty.org/jetty-12/org/eclipse/jetty/websocket/core/FrameHandler.html)

The exact pinned runtime callback stack above is the primary evidence for this run; the `FrameHandler` API documents the related error-then-close callback order.

## QTP runtime configuration and observations

Values were queried from the running `QueuedThreadPool`, not inferred from defaults.

| Runtime | min | max | idle timeout | reserved setting / capacity | max evict count | LowThreadsThreshold |
|---|---:|---:|---:|---:|---:|---:|
| Jetty 9 | 8 | 200 | 60,000 ms | `reservedThreads=-1`, max reserved capacity 12 | 1 | 1 |
| Jetty 12 | 8 | 200 | 60,000 ms | `reservedThreads=-1`, max reserved capacity 16 | 1 | 1 |

Both runtimes had queue size 0 at the recorded post-cleanup checkpoints and 7 leased/busy threads for the live server/control harness. Selector and acceptor threads were included in QTP totals: 6 selectors and 1 acceptor. The number of reserved workers changes at runtime; J12's post-load sample showed 16 reserved parked workers, then 1 reserved worker by the +30 second checkpoint. QTP `idle` is not equivalent to only ordinary worker count when reserved execution is enabled.

### Five batches of 100 abandoned XHR polling sessions

Each batch launched 100 standard SockJS XHR-polling clients, established STOMP CONNECT, then exited without DISCONNECT. Both runtimes recovered all sessions in about 35 seconds per batch. Queue remained 0.

| Runtime | QTP total after batches 1→5 (busy / idle / queue) | cleanup duration, batches 1→5 | Session map after each batch |
|---|---|---|---|
| Jetty 9 | `83, 86, 85, 85, 84` (busy 7; queue 0) | `35.234, 34.914, 35.163, 34.780, 34.882` sec | `0, 0, 0, 0, 0` |
| Jetty 12 | `56, 71, 71, 71, 70` (busy 7; queue 0) | `35.324, 35.019, 34.755, 34.919, 35.063` sec | `0, 0, 0, 0, 0` |

Jetty 12 rose after the first batch while the pool grew to meet concurrent demand, then plateaued at 71 and fell to 70; the data does not show batch-over-batch positive growth after pool warm-up. The lower J12 totals than J9 are from different startup/runtime pool histories and should not be read as a benchmark comparison.

### Post-batch idle series

Format is QTP `total / busy / idle / queue`; Spring SockJS session count was zero at each checkpoint.

| Idle time after batch 5 | Jetty 9 | Jetty 12 |
|---:|---:|---:|
| cleanup checkpoint | `84 / 7 / 66 / 0` | `70 / 7 / 47 / 0` |
| +30 sec | `84 / 7 / 77 / 0` | `70 / 7 / 62 / 0` |
| +60 sec | `83 / 7 / 76 / 0` | `69 / 7 / 61 / 0` |
| +120 sec | `82 / 7 / 75 / 0` | `68 / 7 / 60 / 0` |
| +300 sec | `79 / 7 / 72 / 0` | `65 / 7 / 57 / 0` |

Jetty worker identity tracking showed the ordinary retained workers in `TIMED_WAITING` at `jdk.internal.misc.Unsafe.park`; Jetty 12 also had parked reserved workers. Selector threads were in `sun.nio.ch.EPoll.wait`; the acceptor was in `sun.nio.ch.Net.accept`. No worker was executing a request or blocked on an application lock at the final checkpoint. Live session maps, active transports, and queues were zero. JVM thread totals included Maven/exec, Node-client-related, scheduler, and unrelated worker threads, so QTP's own live pool metrics are used for the comparison.

The observed slow shrink is consistent with both pinned QTP implementations. In Jetty 9, a pool-wide `_lastShrink` gate allows one worker to be removed after a shared idle interval; starting a worker resets the gate. Jetty 12's `maxEvictCount=1` limits idle-worker eviction to one per idle-timeout period, through its pool-level eviction policy. Therefore `idleTimeout=60000` does **not** mean all workers independently disappear exactly 60 seconds after their last task. One worker per interval is consistent with the +60/+120/+300 series. The pool remains bounded by `maxThreads=200`; these probes show no request/session retention and no monotonic batch growth, while also showing that returning to `minThreads=8` can take many minutes after a burst.

## Validation

- Java 17 `mvn -B clean test`: **success**, 6,220 tests across 625 classes; 0 failures, 0 errors, 0 skipped.
- Java 17 `mvn -B clean package`: **success**, same test totals and no failures/errors/skips.
- Java 25 `mvn -B clean test`: **success**, same test totals and no failures/errors/skips.
- Java 25 `mvn -B clean package`: **success**, 6,220 tests; 0 failures, 0 errors, 0 skipped.
- Jetty 9/12 standard client probes on Java 17: two normal WebSocket and two XHR polling sessions per runtime, abrupt 100-connection WebSocket close, graceful close frame, active-session server shutdown, and five batches of 100 abandoned XHR sessions. All normal sessions reached STOMP CONNECTED and receipt-bearing DISCONNECT; SockJS maps returned to zero. The five-batch XHR probe produced zero session maps after every batch.
- Jetty 12 EE8 probes on Java 25: two normal WebSocket and two XHR polling sessions; 100 abrupt WebSocket closes (100 error callbacks then 100 close callbacks, cleanup to zero and reconnect); one normal close frame (0 error, 1 close callback); and 100 abandoned XHR sessions (0 by 45 seconds). All POC Maven executions succeeded.
- Hosted GitHub Actions for evidence commit `a0802c66ed6cd754bdd2b8dbaf8a6e4b01efe92b`:
  - [Java 17 Baseline run 36300800961](https://github.com/nemnesia/nem/actions/runs/36300800961): **success**; clean unit tests and package modules steps succeeded.
  - [Java 25 Compatibility run 36300800958](https://github.com/nemnesia/nem/actions/runs/36300800958): **success**; clean unit tests and package modules steps succeeded.

Test-only files changed in this phase are the Jetty 9/12 readiness controls, shared QTP/callback instrumentation, and the standard SockJS client probe. Production dependencies and production source are unchanged.

## Remaining independent gates and risks

- Hosted Java 17/25 runs for this revision remain to be confirmed.
- Jenkins Java 17 image/shared-library execution remains an external blocker.
- Phase 2F database rollout gates remain unresolved: H2 1.4 offline export/import, Flyway history/checksum validation, representative Mainnet/Testnet DB validation, and matching-genesis chain-state comparison.
- The Phase 2I-M historical production trial WebSocket 404 remains part of the record; Phase 2I-P found it unreproducible on the current correct `/w/*` production-equivalent route and did not retroactively change that history.
- QTP behavior was measured on this test harness and workload. The reserved-thread capacity differs by Jetty line; these observations do not substitute for production capacity/operations testing.

Production Jetty 12 migration remains out of scope for Phase 2I-R. This verdict only opens the entry gate for a separately reviewed production migration phase; it does not perform that migration. Jenkins Java 17 infrastructure and Phase 2F DB/Flyway gates remain independent unresolved rollout gates.
