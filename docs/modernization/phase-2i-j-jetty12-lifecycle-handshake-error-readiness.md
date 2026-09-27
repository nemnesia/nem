# Phase 2I-J — Jetty 12 production readiness final verification

## 判定

**PARTIAL — production Jetty 12 migration はまだ開始しない。**

Jetty 9.4.58 と Jetty 12.1.13 EE8 は、raw handshake、Origin、SockJS WebSocket/XHR polling、正常な STOMP lifecycle の主要な観測契約で同等だった。一方で、異常終了した XHR polling の receiving request/session は両 runtime で残り、45 秒後にも Spring の session map に99件残った。同じ残留poll数でJetty12のthread poolは134、Jetty9は74 threadとなり、Jetty12側が60 thread多かった。単一batchのためmonotonic leakとまでは証明していない。Jetty 12 の異常 WebSocket close は101件に対してSpring transport-error counterが101増え（Jetty 9は0）、callback ThrowableとJetty差を特定できていない。これらが残るため production readiness は証明されていない。

| 項目 | Jetty 9 | Jetty 12 EE8 | 評価 |
| --- | --- | --- | --- |
| version | `9.4.58.v20250814` | `12.1.13` | test-only runtime |
| Spring | `5.3.39` | `5.3.39` | 同一 |
| servlet namespace | `javax.servlet` | EE8 / `javax.servlet` | 同一 application contract |
| `/messages/info` | 200 | 200 | 同等 |
| native/SockJS WebSocket と STOMP | CONNECT/CONNECTED、NIS SEND/MESSAGE、receipt DISCONNECT 成功 | 同左 | 同等 |
| XHR polling | CONNECT/CONNECTED、NIS handler/MESSAGE、receipt 成功 | 同左 | 同等 |
| normal/abnormal cleanup | WS は消える。放棄 XHR poll は残る | WS は消える。放棄 XHR poll は残る | 共通の未解決 retention |
| raw handshake | 101 / accept 成功 | 101 / accept 成功 | 意味上同等 |
| abrupt WS Spring transport error | 0 | batch で最大101 | counter 差の原因未確定 |

Repository: `nemnesia/nem`

Branch: `agent/nis-phase0-baseline`

Requested / actual starting HEAD: `f56f52339f1fcdb9c46b0de28c1b8cf1db09ef17`

JDK: OpenJDK `17.0.20.1` / `25.0.4.1`; Maven `3.8.7`; Linux amd64
Final HEAD: この記録を含む commit SHA を Git 最終報告に記載する。

## 変更範囲と実行経路

Production Jetty dependencies、production bootstrap、Spring、namespace、DB、Jenkins は変更していない。変更した Java/JS はすべて `docs/modernization/phase-2i-g-poc` / `phase-2i-h-poc` の test-only harness である。

Jetty 9 harness は production `NisWebAppWebsocketInitializer` を使用し、Spring 既定 strategy を通る。Jetty 12 EE8 harness も同 initializer を実行し、ServletContext の標準 `javax.websocket.server.ServerContainer` が `JavaxWebSocketServerContainer` である場合に ServiceLoader provider が shim を選ぶ Phase 2I-I の経路を使用した。両方で実 NIS WebSocket/STOMP configuration、handler、converter、および `sockjs-client@1.6.1` を使った。session map / Spring private stats の reflection は POC observer のみで、production code にはない。

## Cleanup semantics と反復 probe

Spring Framework 5.3.39 の `TransportHandlingSockJsService` は session map を保持し、disconnect delay を超えて inactive になった session を掃除する。`SockJsServiceRegistration` の default disconnect delay は5秒。XHR polling は server-side receiving HTTP request が active な間は session が active と見なされるため、`getTimeSinceLastActive()` が0のままとなる。今回の Node abrupt batch は STOMP DISCONNECT を送らず process/socket を終了し、server 側では poll request が待機し続ける。

参照した upstream source: [TransportHandlingSockJsService 5.3.39](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/transport/TransportHandlingSockJsService.java)、[AbstractSockJsService 5.3.39](https://github.com/spring-projects/spring-framework/blob/v5.3.39/spring-websocket/src/main/java/org/springframework/web/socket/sockjs/support/AbstractSockJsService.java)、[SockJsServiceRegistration Javadoc](https://docs.spring.io/spring-framework/docs/5.3.x/javadoc-api/org/springframework/web/socket/config/annotation/SockJsServiceRegistration.html)。

cleanup probe はそれぞれ同じ test-only production-initializer harness、同じ client と iteration 数で行った。100件は並列 abrupt batch。Spring SockJS session map、open/active/closed、Spring `SubProtocolWebSocketHandler` stats を server が動いている間に5秒間隔で45秒まで採取した。

| ケース | Jetty 9 | Jetty 12 EE8 | 結果 |
| --- | --- | --- | --- |
| normal WebSocket cycle 100回 | current Java 17 run: 100/100; threads `28/88/82` | current Java 17 run: 100/100; threads `26/86/80` | CONNECT/CONNECTED、NIS MESSAGE、receipt DISCONNECT 成功 |
| normal XHR polling cycle 100回 | current Java 17 run: 100/100; threads `82/84/84` | current Java 17 run: 100/100; threads `80/82/82` | 同上 |
| 200 normal sessions後のcleanup | SockJS map / current `HttpPoll` は5秒時点2、10秒時点0 | 同左 | Java 17両runtimeで eventual cleanup 確認 |
| current cleanup harness normal 1回ずつ後の残留 | closed WS map entry は約5秒後に除去。HttpPoll 1 が表示されることあり | 同様 | XHR client の open receive がstats採取時点で残る |
| abrupt WebSocket batch | 100 CONNECT。観測時点では約75 map entries open、後続 XHR batch/cleanup中にWS entryは消失 | 100 CONNECT。観測時点では約79 open、transport error callbackを経由。後続cleanup中にWS entryは消失 | reconnect成功、45秒後にWS残留なし |
| abrupt XHR batch | 100 CONNECT。初期101 PollingSockJsSession、5秒時点100、10秒時点99 | 100 CONNECT。初期102 PollingSockJsSession、5秒時点101、10秒時点100、15秒時点99 | 両方とも残る |
| abrupt XHR 45秒後 | 99 open/active `PollingSockJsSession`、99 Spring `HttpPoll` | 99 open/active `PollingSockJsSession`、99 Spring `HttpPoll` | JVM を止めずに残留を確認。timeoutだけでは解放されない |
| Jetty QueuedThreadPool 100件放棄時 | cleanup probeで `74 threads / 7 busy / 55 idle` | `134 threads / 7 busy / 111 idle` | Jetty 12で60 thread多い。1 batchの測定でありmonotonic leakとは断定しないが、同じ99 active pollでもresource差があり説明が必要 |

normal reconnect の100回 probe は Jetty 9/12 とも本 Phase で再実行し、各transport100/100成功した。normal後の JVM thread count は Jetty9 WebSocket `28/88/82`、XHR `82/84/84`、Jetty12 WebSocket `26/86/80`、XHR `80/82/82` (`before/peak/after`)。normal後 Jetty poolは別途固定値採取を追加しておらず、Spring session map は両方とも10秒後ゼロ。Phase 2I-J abrupt batch は Jetty 9/12 それぞれ WebSocket 100 と XHR polling 100。WebSocket sessionsは cleanupされ reconnectも成功。異常WebSocket callback終了後のJVM thread数はJetty12で `74→78→78` 等、Jetty9で同様に横ばい。対して99 orphan XHR pollを保持した際のJetty poolはJetty9 74 threads、Jetty12 134 threadsであった。単独batchでは増加傾向までは証明できないが、Jetty 12で大きい差を観測したためresource差がないとは言えない。Spring active WebSocket session は eventual cleanup後ゼロ。XHR pollは99残留したため、総session/resource cleanupは成功と判定しない。メモリ/FD profilerによる測定はしていない。

この結果は明白な Jetty 12 固有 leak を示すものではない。active long-poll request が client abort により Servlet async layer まで通知されず、Spring が session を inactive と判定できない可能性がある。ただしこの因果は現在の probe だけでは確定していない。production で放棄 poll をどう終端するか、また server/proxy/client の timeout 条件で解放されるかを別途追う必要がある。

## Spring transport-error counter 差

100 abrupt WebSocket connectionsと前段の abrupt close 1件では Jetty 9 の Spring `transportErrors` は0、Jetty 12 は最終的に101だった（中間採取時は35、callback処理中はより少ない値）。 Jetty 12 counter は101切断/closeに対して最終101であり、probe上の重複加算を示していない。WS current sessions は最終的に消え、同一endpointへの再接続は成功し、STOMP DISCONNECT を受信しないケースを除き NIS application handler への追加 delivery は確認しなかった。

Spring 5.3.39 の `SubProtocolWebSocketHandler.handleTransportError` は `DefaultStats.transportError` を加算する計測点であり、この counter 自体は application STOMP ERROR frame ではない。Jetty 12 shim が Spring `Endpoint` を登録する箇所で callback の二重通知を行う設計ではないが、実 Throwable/Jetty callback 回数を採取する test wrapper は handshake/全 transportを壊したため戻した。従って「counter差のみで application semantics に影響しない」と完全には証明できず、この unexplained lifecycle observability 差は readiness limitation として残す。counter を抑制する修正はしていない。

## Handshake / Origin / handshake failure

SockJS native endpoint `/messages/000/{session}/websocket` へ raw HTTP upgrade を送信。RFC上 case-insensitive な token は小文字化して比較した。

| Request | Jetty 9 | Jetty 12 EE8 | 意味上の比較 |
| --- | --- | --- | --- |
| valid, Originなし | 101; `Upgrade: WebSocket`; `Connection: Upgrade`; expected `Sec-WebSocket-Accept` | 101; `Upgrade: websocket`; 同じ connection/accept | 同等。Upgrade token の大小文字のみ |
| same-origin | 101 | 101 | 同等 |
| unrelated Origin | 101; requested origin を ACAO に反映、credentials true | 同じ | production `.allowedOriginPatterns("*")` と整合 |
| negotiated `Sec-WebSocket-Protocol` | absent | absent | SockJS WebSocket は STOMP subprotocol header を交渉せず、STOMP は SockJS message envelope 内 |
| missing Upgrade | 400 | 400 | 同等 |
| invalid version `12` | 426; supported version 13 | 426; supported version 13 | 同等 |
| missing key | 400 | 400 | 同等 |
| non-base64 key string | 101 | 101 | 両方で受理。現行 stack 共通の validation limitation |
| malformed SockJS path | 404 | 404 | 同等 |

`Server` header と header order/date は Jetty build/runtime由来で差があるため固定比較しない。手作り invalid key が両方101となる現象は Jetty migration 差分ではないが、HTTP/WebSocket key validation の欠落可能性として記録する。拒否 Origin は現行 wildcard policy に存在しないため人工的に作らなかった。

## STOMP error probe

WebSocket / XHR polling の両方で invalid command、SEND destination 欠落、SUBSCRIBE destination 欠落を CONNECTED 後に送った。Jetty 9/12とも観測2秒間に client-visible STOMP ERROR frame や remote close はなく、missing destination は Spring STOMP handler側の error log/exception に到達した。probe は malformed frame 後に Node process/socket を強制終了するため、2秒以降の ERROR/close mapping は検証していない。従って現時点の結果は「観測窓内の差なし」であり、STOMP error contract 完全一致とはしない。invalid operationを通じた具体的 application handler delivery の差は観測していない。

## Build / test / CI

- Java 17 local: `mvn -B clean test` は sandbox 内で WireMock loopback bind 制限により Core 16 errors。loopbackを許可した再試行では Core/Deploy/Peer成功、NIS `NisPeerNetworkHostTest.getVisitorsReturnsElevenTimerVisitors` 1 error。原因は public peer `Connection refused`。残りは成功、総計 `625 classes / 6,220 tests; failures=0, errors=1, skipped=0`。Jettyコード由来ではなく、全 suite clean green とは扱わない。
- Java 17 local package: `mvn -B -DskipTests package` 成功。
- Java 25 local: `mvn -B clean test` も `NisPeerNetworkHostTest.defaultHostCanBeBootedAsync` 1 error (`Connection refused`) で終了。集計 `625 classes / 6,220 tests; failures=0, errors=1, skipped=0`。Jetty関連の失敗はなかった。`mvn -B -DskipTests package` は成功。
- Test-only Jetty 9/Jetty 12 control harness は Java 17 上で runtime probe を実行し、両方の Spring startup/transport probeを利用した。normal-only 各100-cycle rerun は双方で成功し、終了10秒後に session map と current HttpPoll がゼロになった。
- Hosted Java 17 Baseline / Java 25 Compatibility run IDs and current commit result: pending push/Actions lookup; final status will be appended after lookup. Java 25 cannot replace Java 17 gate.
- Jenkins Java 17 execution remains externally blocked by the image/shared-library limitation previously documented; this Phase does not alter it.

## 維持する独立 blocker

Phase 2F の H2 1.4 offline export/import requirement、Flyway history/checksum constraints、representative Mainnet/Testnet DB validation、matching-genesis full chain-state comparison は未解決のまま。Jenkins Java 17 image/shared-library blocker も未解決のまま。本 Phase はこれらを変更していない。

## 結論 / 次工程

主要な successful WebSocket/SockJS/STOMP path、handshake、Origin、basic handshake failure は意味上同等で、Jetty 12 固有の thread/session monotonic leak は現在の probe では観測していない。しかし異常終了 XHR polling session が45秒後に99件残る理由/運用上限が確定せず、Jetty 12 の transport-error counter 増加もcallbackレベルで説明できていない。Java 17 hosted CI もこの commitで確認する必要がある。従って判定は **PARTIAL**。Jetty 12 production migration は開始しない。

次の判断前に、(1) XHR polling client abortからServlet async requestを終端しSockJS sessionを回収する条件を両Jettyで再現、(2) Jetty 12 transport callbackとSpring `handleTransportError` の1対1対応/Throwableを非侵襲計測、(3) malformed STOMPのより長い観測窓でERROR/close mapping確認、(4) Java 17 hosted workflow successを確認する。production Jetty version/bootstrapは変更しない。
