# Phase 2I-I — Jetty 12 production readiness gate

## 判定

**PARTIAL — production Jetty migration は開始しない。**

本番 `NisWebAppWebsocketInitializer` から Jetty 12 EE8 provider を実行時選択できる narrow SPI を追加し、Jetty 9/Jetty 12 の標準 `sockjs-client` による WebSocket/XHR polling、STOMP、Origin、異常終了の比較を行った。Java 25 の root test/package は成功した。一方で、Java 17 hosted run は既知の外部 public peer timeout で失敗し、SockJS polling session cleanup が10秒後も残る観測、raw WebSocket handshake header 未取得などが残る。

Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested / actual starting HEAD: `1d7177c199d68ebbbefbaac7e88e744d9efb02be`
Jetty 9: `9.4.58.v20250814`
Jetty 12 test runtime: `12.1.13` EE8
Spring: `5.3.39`
Java runtime available locally: OpenJDK `25.0.4.1`; Java 17 is not installed locally.

## 変更範囲

- Production Jetty version/bootstrap/dependency は変更していない。
- Spring、namespace、application protocol、DB、schema、migration、Jenkins は変更していない。
- `NisWebAppWebsocketInitializer` に optional `NisWebSocketUpgradeStrategyProvider` の発見を追加した。ServletContext に標準 JSR-356 attribute が存在し、provider が container を受け入れたときだけ `DefaultHandshakeHandler` に strategy を渡す。providerがないJetty 9では従来のSpring自動選択を使う。
- SPI は `Object` container 引数を受ける。production compile classpath に `javax.websocket`/Jetty 12 API を追加せず、Jetty 12実装は隔離 harness のみで提供するためである。Jetty 12 provider は `JavaxWebSocketServerContainer` を通常の `instanceof` で確認し、Jetty public upgrade API adapter を返す。reflection / class-name spoofing は使っていない。
- `phase-2i-g-poc` harness は実 production initializer を両 runtime で登録する。Jetty 12 provider のみ test-only ServiceLoader resource から提供する。
- `sockjs-client` test probe は close event を待ってから終了するよう調整した。

## Runtime selection / startup

| Runtime | initializer | selection | endpoint/startup |
| --- | --- | --- | --- |
| Jetty 9.4.58 | `NisWebAppWebsocketInitializer` 本体 | test providerなし。Spring 5.3.39既定Jetty strategy | Spring context、`/messages`、SockJS `/info` 起動成功 |
| Jetty 12.1.13 EE8 | 同じ initializer 本体。test subclass overrideなし | ServletContext の `javax.websocket.server.ServerContainer` attribute に対し、`Jetty12Provider` が `JavaxWebSocketServerContainer` を検出。provider が `RequestUpgradeStrategy` を返し、initializer が `DefaultHandshakeHandler(strategy)` を設定 | provider selection assertion成功。`/messages`登録、Spring context、`/info` 起動成功 |

Jetty 12 provider class と ServiceLoader registration は test-only artifact にあり、本番 Jetty dependency は Jetty 9のまま。本番Jetty12配備には、互換providerのproduction artifact/dependency wiringが別途必要で、このSPIだけではproduction runtimeをJetty12化しない。

## Transport / normal lifecycle

標準 Node `sockjs-client@1.6.1` を用い、同じ NIS initializer、real NIS websocket component scan、broker、message converter、`/w/api/node/info` handler、test dependency fixtures を通した。Jetty 12ではSPI経由のshimを使用した。

1セッションのWebSocketとXHR pollingは両方で以下が成功した。

- `/messages/info`: HTTP 200
- SockJS transport: 指定した `websocket` / `xhr-polling` を実際に選択
- STOMP CONNECT / CONNECTED
- SUBSCRIBE `/node/info`
- SEND `/w/api/node/info`、実NIS handlerが `MessagingService.pushNodeInfo()` を呼びoutbound MESSAGEを配送
- receipt付きUNSUBSCRIBE / DISCONNECT
- SockJS close event: code `1000`, `Normal closure`, `wasClean=true`

同一 Node process を使った再接続probeは両runtimeで各transport 100回を実施し、各セッションでNIS MESSAGEとDISCONNECT receiptまで検証した。Spring `WebSocketMessageBrokerStats` では Jetty 12 の通常サイクル後に CONNECT/CONNECTED/DISCONNECT 各200件、Jetty 9も同じ数を記録した。追加の異常終了probeを含むrunでは最終的に各204 CONNECT/CONNECTEDを確認した。

| 反復probe | Jetty 9 | Jetty 12 EE8 |
| --- | --- | --- |
| WebSocket | 100/100 CONNECT、NIS MESSAGE、DISCONNECT receipt | 100/100 同左 |
| XHR polling | 100/100 同左 | 100/100 同左 |
| JVM thread count, WebSocket `before/peak/after` | `36/96/82` | `34/94/80` |
| JVM thread count, XHR `before/peak/after` | `82/84/84` | `80/82/82` |

thread count はJVM全体の `ThreadMXBean` 値。Jetty専用thread / timer / native memory / open descriptor は別に直接計測していない。Spring statsでは通常200セッションのDISCONNECT receipt処理後に current `HttpPoll` が一時3、その後10秒settleで1（Jetty 9 / Jetty 12とも同じ）となった。これは両runtimeで同じでJetty 12固有差ではないが、application/SockJS session cleanupがゼロへ戻ることを実証できていない。

## Origin / HTTP error

Production config は `.setAllowedOriginPatterns("*")` で、拒否対象 Origin は定義されていない。従って「disallowed Origin」の比較点が存在しない。Jetty 9 / 12 で `/messages/info` はOriginなし、`https://allowed.example`、`https://otherwise-unmatched.invalid` のすべてHTTP 200となった。Originありの場合は要求Originが `Access-Control-Allow-Origin` に反映された。policyを緩和・変更していない。明示的な拒否origin契約は未検証。

`GET /messages/not-sockjs` は両方HTTP 404。raw malformed HTTP upgrade handshakeの応答headersは取得していない。

## STOMP error / abnormal disconnect / cleanup

- CONNECTED後に不正な`INVALID_STOMP_FRAME`を送り、1.5秒観測したが、Jetty 9/12のWebSocket/XHR pollingいずれもSTOMP ERROR frameは届かなかった。test clientを閉じるとSockJS close code 1000だった。Spring stats上、CONNECT/CONNECTEDは加算されてもDISCONNECTは増えない場合があり、Spring error mappingとserver session cleanupの詳細は未確定。
- STOMP DISCONNECT / SockJS closeを送らずCONNECTED後にNode client processを終了し、TCP/socketを閉じた。両runtimeでWebSocket、XHR pollingのCONNECT後に同transportで正常再接続できた。STOMP DISCONNECT counterは異常終了では増えなかった。Spring statsのtransport errorはJetty 9で0、Jetty 12ではWebSocket abrupt-close後1（XHR abrupt-closeでは追加なし）となった。この内部counter差の原因は未特定で、外部clientに見えるERROR frame/close responseとの差はこのprobeでは得ていない。
- 100回ずつのnormal reconnectでCONNECT/CONNECTED/DISCONNECT各200、NIS MESSAGE/receiptが成功し、JVM thread数はJetty 9/12とも横ばい域だった。一方、Spring statsでは通常cycle完了時に一時current `HttpPoll` 3、10秒settle後にも1が両runtimeに残った。異常終了後のstatsでもcurrent数が1残る。これはJetty 12だけの差と判断できないが、session cleanup完了は証明できない。Jetty 12のmonotonic JVM thread growthは観測していない。
- server、Spring context、Jetty lifecycleのshutdownは正常に完了した。これはsession cleanup/leak不存在の証拠ではない。

## Java / build / hosted CI

- ローカル `java -version`: OpenJDK `25.0.4.1`; `javac`: `25.0.4.1`; Maven `3.8.7`。Java 17 runtimeはローカルになく、system/global JDK設定は変更していない。
- Java 25 `mvn -B clean test`: 成功。Surefire report集計 `625 classes / 6,220 tests; failures=0, errors=0, skipped=0`。従来 baselineの `624 / 6,218` より1 class / 2 tests多い。test source自体は変更していない。増加理由は test discovery / report差分として記録し、減少はない。
- Java 25 `mvn -B -DskipTests package`: 成功。
- Starting HEADでの hosted Java 17 Baseline `36274704082` はfailure。認証済み `gh run view --log-failed` で失敗を取得した。`NisPeerNetworkHostTest.getNetworkBroadcastBufferDoesNotThrowIfNetworkIsBooted` 1 error、`network boot failed`。ログでは `hachi.nem.ninja/62.146.225.82:7890` 接続timeoutが原因として表示される。外部public peer依存の既知カテゴリと一致するが、runは成功ではない。
- Starting HEAD hosted Java 25 Compatibility `36274704103`: success。これはJava 17 hosted gateの代替ではない。
- 変更後commitに対するhosted Java17/25結果をpush後に追記する。
- Jenkins Java 17 build pathは過去Phaseのexternal image/shared-library blockerのまま。今回調査・変更・検証していない。

## 維持する未解決条件

Phase 2F の H2 1.4 offline export/import requirement、Flyway schema history/checksum制約、representative Mainnet/Testnet DB validation、matching-genesis full chain-state comparison は未解決のまま。Jenkins Java 17 blockerも未解決のまま。過去PhaseのBLOCKED/PARTIAL履歴は上書きしていない。

## 残る gate / 次の判断

この結果では `COMPLETE — READY FOR JETTY 12 PRODUCTION MIGRATION PHASE` にしない。Jetty production依存/bootstrapを変えていない。

次の再検証では少なくとも以下を完了する。

1. Java 17 hosted CIで全テスト・package成功。現在の既知 public-peer timeoutが成功runを阻んだ場合、そのrunも記録し安定成功を確認。
2. 生の101 handshake headers / subprotocol比較と Origin contract（拒否originがない現行wildcard仕様を含む）を検証。
3. normal/abnormal disconnect後にSpring SockJS sessionとNIS subscription/account stateが解放されることを両Jettyで直接確認。100回後のsession current countがsettle後ゼロへ戻るか検証。
4. malformed STOMP / invalid destination / server handler exceptionのERROR mappingとtransport closeを比較。
5. production Jetty 12 deliverableにSPI providerをどう同梱するかを dependency/packaging試験で確認する。

以上が完了するまで production Jetty 12 migrationは開始しない。Phase 2F DB gateおよびJenkins blockerは独立して維持する。
