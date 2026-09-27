# Phase 2I-Q — async transport readiness

## 判定

**PARTIAL — PRODUCTION JETTY 12 MIGRATION NOT STARTED**

async-support の servlet/filter 登録原因を修正し、Jetty 9 と Jetty 12 EE8 の production-equivalent runtime で WebSocket と SockJS XHR polling の正常な NIS/STOMP 経路を確認した。session は正常終了・abandoned XHR 後に回収され、反復負荷で thread/session が batch ごとに増え続ける様子は観測されなかった。一方、Jetty 12 の abrupt WebSocket close では Spring の transport-error callback が Jetty 9 より増え、QTP worker が120秒後も高い idle 数で残った。async 有効化後のこの callback/lifecycle 差を十分に説明し切れていないため、production Jetty 12 readiness はまだ宣言しない。

## Repository / Git

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `ac085f7ee383abf9dc9061c9748d3d32eb037759`
- Final HEAD: commit 後に記録する
- Production Jetty migration: 未実施。Jetty 9 production dependency と bootstrap を維持

## 実装差分

production に加えた変更は `/w/*` SockJS request に必要な Servlet async support の宣言のみ。

| Registration | 変更前 | 変更後 | 決定箇所 |
|---|---:|---:|---|
| `Spring Websocket Dispatcher Servlet` (`DispatcherServlet`, `/w/*`) | false | true | `NemWebsockServerBootstrapper.WebsocketContextListener.initialize` |
| `DoSFilter` (`/*`, `REQUEST`) | false | true | `AbstractNemServletContextListener.addDosFilter` |
| `GzipFilter` (`/*`, `REQUEST`) | false | true | `AbstractNemServletContextListener.addGzipFilter` |
| `cors filter` (`/*`, `REQUEST`) | false | true | `AbstractNemServletContextListener.addCorsFilter` |
| Jetty `WebSocketUpgradeFilter` | container-managed | true (runtime) | Jetty ServletContainerInitializer |

Servlet API の async 処理は chain 上の servlet と全 filter の async-enabled 登録を要する。Phase 2I-O では servlet と production filters が無効のまま Spring が `AsyncContext` を開始しようとして失敗した。修正後、Jetty 9 / 12 の登録実値はすべて true、XHR request は `request.isAsyncSupported() == true` かつ poll 後 `isAsyncStarted() == true` となった。`web.xml`/annotation の別登録や Spring Security filter はこの production bootstrap path にはなかった。Jetty WebSocket filter は SCI が container 初期化時に加える。

## 検証環境

- Java 17: OpenJDK `17.0.20.1`
- Java 25: OpenJDK `25.0.4.1`
- Maven: `3.8.7`
- Spring Framework: `5.3.39`
- SockJS client: `sockjs-client@1.6.1`
- Jetty 9 control: `9.4.58.v20250814`
- Jetty 12 candidate: `12.1.13` EE8 (`javax.servlet`)
- Jetty 9/12 harness は NIS production listener・`DispatcherServlet`・DoS/Gzip/CORS filters・NIS WebSocket initializer と production NIS handler/converter を使う test-only bootstrap。Jetty 12 は既存 SPI/shim selection を使う。どちらも production dependency/bootstrap は切り替えていない。
- loopback server と WireMock を必要とする試験は sandbox 外の通常実行で検証した。sandbox 内の Java 25 初回 full test は socket bind `Operation not permitted` により失敗したため、同じ suite を制約のない通常実行で再実行した。

## XHR / WebSocket protocol

標準 client に各 runtime / transport 2回ずつ接続させた。両方とも指定した transport が実際に選ばれ、`CONNECT → CONNECTED → SUBSCRIBE / NIS SEND → /node/info MESSAGE → receipt付き UNSUBSCRIBE / DISCONNECT → close` を完了した。

| Runtime | Transport | 代表的 request | 結果 |
|---|---|---|---|
| Jetty 9 | WebSocket | `/w/messages/{server}/{session}/websocket` | HTTP 101、production `SockJsHttpRequestHandler`、NIS `MESSAGE` と disconnect receipt 成功 |
| Jetty 9 | XHR polling | `/w/messages/{server}/{session}/xhr`, `xhr_send` | open/poll 200、send 204、async poll開始、CONNECTED/MESSAGE/receipt成功 |
| Jetty 12 EE8 | WebSocket | `/w/messages/{server}/{session}/websocket` | HTTP 101、shim/provider選択、production handler、CONNECTED/MESSAGE/receipt成功 |
| Jetty 12 EE8 | XHR polling | `/w/messages/{server}/{session}/xhr`, `xhr_send` | open/poll 200、send 204、async poll開始、CONNECTED/MESSAGE/receipt成功 |

XHR は各 runtime で通常 smoke 2回に加えて通常終了10 session、abandoned 100 sessionを3 batch実行した。正常終了後の session map は Jetty 9 約`6.3s`、Jetty 12 約`6.3s`で0。abandoned 100件の各 batch は Jetty 9 `34.626s`, `34.480s`, `34.665s`、Jetty 12 `34.701s`, `34.446s`, `34.718s`で0に戻った。Spring heartbeat 25秒、disconnect delay 5秒、scheduler sweepを待つ既知の挙動と整合し、HttpPoll/session の恒久残留は見られなかった。

WebSocket 通常終了100 sessionでは両 runtime とも100 CONNECT、100 NIS MESSAGE、100 receipt付き終了が成功し、session map は約8.8秒で0へ戻った。

## 異常 WebSocket close

abrupt close 100 session後、両 runtime で全 session が cleanup された。Jetty 9 は Spring `handleTransportError` callback 0、`afterConnectionClosed` 100（close `1006: Disconnected`）。Jetty 12 は最終的に transport error 100、close callback 100で、各該当 sessionに `java.nio.channels.ClosedChannelException`（messageなし）と `1006: Session Closed` が記録された。1 session内の transport-error callback は重複していない。client切断時のJetty 12 error mapping差と観測される。Jetty 12 session map は約`9.6s`で0になった。この Phase の abrupt close直後には明示的 reconnect probe を行っていないため、reconnect の新たな結論は加えない。NIS handler invocation / cleanup の異常は観測しなかったが、Spring transport-error差の運用上の意味はreadiness gateとして残す。

## QTP / thread lifecycle

両 pool は `minThreads=8`, `maxThreads=200`, `idleTimeout=60000ms`, queue `0`。記録値は `total/busy/idle/queue`。

| Runtime / checkpoint | 起動 | WS正常100終了後 | WS abrupt100 peak / cleanup直後 | abandoned XHR 100 batch peak | idle観測後 |
|---|---:|---:|---:|---:|---:|
| Jetty 9 | `8/7/1/0` | `27/7/9/0` | `59/7/40/0` | `59/7/40/0` | 30s `57/7/50/0`; 90s `56/7/49/0` |
| Jetty 12 EE8 | `9/8/0/0` | `24/7/7/0` | `114/7/91/0` after cleanup (peak busy 55, queue 0) | all 3 batches `114/7/91/0` | +10s `114/7/91/0`; +30/+60s `113/7/105/0`; +90/+120s `112/7/104/0` |

Jetty 9 batch 前後にも非単調な小幅縮退がある一方、Jetty 12 は100 WebSocket peak時に114 workerへ拡張し、QTP maximum 200未満・queue 0・busy 7まで戻った。abandoned XHR 各batch開始時のtotalは同じ114で、session mapは毎回0、thread数のbatchごとの増加はなかった。thread identity集計では余剰workerの大半が `TIMED_WAITING` / `Unsafe.park`、selectorは6、acceptorは1。Jetty 12では+120秒で112までしか下がらず、poolの高いidle保持を測定できた。これはworkload比例の単調増加ではないが、短い観測時間でbaselineへ縮退するとも言えない。QTPのevictionを超えた長期の収束 / 運用上のthread上限は引き続き確認が必要。

## Java / build

| JDK | Full clean test | Clean package | Runtime probe |
|---|---|---|---|
| Java 17 | `mvn -B clean test`: 6,220 tests、failure/error/skip 0。`mvn -B clean package`: BUILD SUCCESS | Jetty 9/12、WebSocket/XHR、normal/abandoned/abrupt lifecycle実施 |
| Java 25 | `mvn -B clean test`: 6,220 tests、failure/error/skip 0（sandbox内初回はWireMock bind拒否。通常実行でpass）。`mvn -B clean package`: BUILD SUCCESS | Jetty 12 production-equivalent、WebSocket/XHR各2回、CONNECTED/MESSAGE/receipt/cleanup確認 |

## Hosted CI / 独立 gate

- GitHub CLI の保存tokenは無効で API 接続も利用できなかった。通常 push 後に public Actions page を確認し、run ID と結果を追記する。確認不能なら未検証と記録する。
- Jenkins Java 17 image / pinned shared-library blocker は未解決。GitHub Actions / local successで置き換えない。
- Phase 2F の H2 offline conversion、Flyway history/checksum、代表 Mainnet/Testnet DB、matching-genesis chain-state comparison は未解決の独立 rollout gateで維持。
- Phase 2I-M の production Jetty 12 trialで `/messages` WebSocket 404が観測された履歴は維持する。Phase 2I-Pの現production-equivalent正しい `/w/*` pathではJetty 12 WebSocket handshake/ STOMPが成功し、歴史的404の原因は未確定のまま。

## Changed files

- `deploy/src/main/java/org/nem/deploy/server/AbstractNemServletContextListener.java`
- `deploy/src/main/java/org/nem/deploy/server/NemWebsockServerBootstrapper.java`
- `docs/modernization/phase-2i-g-poc/pom.xml`
- `docs/modernization/phase-2i-g-poc/src/main/java/org/nem/nis/websocket/Jetty9ProductionParityControl.java`
- `docs/modernization/phase-2i-h-poc/sockjs-normal-batch.js`
- `docs/modernization/phase-2i-o-poc/src/main/java/org/nem/nis/websocket/ProductionParityControl.java`
- `docs/modernization/phase-2i-q-async-transport-readiness.md`

Jetty/Spring/Hibernate/H2/Flyway dependency、Servlet namespace、protocol、DB、production Jetty bootstrap は変更していない。async declaration 以外の production behavior変更はない。
