# Phase 2I-H — SockJS XHR fallback / NIS codec contract

## 判定

**PARTIAL** — 標準 client 比較、production codec 修正、回帰テスト、Jetty 9 / Jetty 12 EE8 POC は成立しました。Java 25 hosted CI は成功しましたが、Java 17 hosted clean test は失敗し、GitHub の公開 job log 制限により failing test を特定できませんでした。Java 17 の最終検証が未解決です。

## Repository と範囲

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `21f3adb590758d9396d456bac96268f88e34cd2c`
- Final code-validation commit: `6a7c2c51651540a09207958e0743d104e0a26f71`; documentation-only CI result update は final report に記載する。
- Jetty 9: `9.4.58.v20250814`。実 NIS `NisWebAppWebsocketInitializer` を使用。
- Jetty 12 harness: `12.1.13` EE8、Spring upgrade shim は test-only。
- Spring Framework: `5.3.39`
- SockJS client: `sockjs-client@1.6.1`（upstream release version を固定）
- Local runtime: OpenJDK / `javac` `25.0.4.1`, Maven `3.8.7`, Node.js `v26.5.0`, npm `11.17.0`。
- ローカル Java 17 runtime は存在しない。Java 17 判定は hosted workflow で確認する。

本 Phase は production Jetty version、bootstrap、dependency graph、Spring、database、protocol、Jenkins を変更していません。production application の変更は SockJS request-body decoder とその unit test に限られます。Jetty 12 は引き続き isolated test harness のみです。

## 標準 SockJS client の試験方法

`docs/modernization/phase-2i-h-poc/` に独立した npm test harness を置き、`package.json` / `package-lock.json` で `sockjs-client@1.6.1` を固定しました。実行時に `npm ci` してから Java harness を実行します。Node client は SockJS `transports` option を一つに制限し、実際の `sock._transport.transportName` を open 時に記録します。`_transport` はクライアントの内部フィールドであり、transport 強制 option と合わせたテスト観測専用です。

各 transport のシナリオは次のとおりです。

1. `GET /messages/info`
2. SockJS client が `/messages` のセッションを開く。
3. STOMP `CONNECT`。成功時に `CONNECTED` を待つ。
4. `SUBSCRIBE /node/info`。
5. NIS application destination `/w/api/node/info` に `SEND`。実 `WebsocketInitController.nodeInfo()` が `MessagingService.pushNodeInfo()` を呼ぶ。
6. `/node/info` の outbound STOMP `MESSAGE` を待つ。
7. `UNSUBSCRIBE` と receipt 付き `DISCONNECT` を送り、`RECEIPT` を待つ。

Jetty 9 は production initializer を直接 register しました。Jetty 12 は Phase 2I-G の test-only `HandshakeHandler` / `RequestUpgradeStrategy` shim を使い、同じ NIS broker/controller 構成をロードしています。Jetty 12 の endpoint 登録と codec はテスト fixture で明示します。production registration path の切り替え可能性や production Jetty migration を証明するものではありません。

## 変更前の標準クライアント結果

Jetty 9 の production initializer および Jetty 12 EE8 harness の双方で、標準 client を使った変更前の基準を取得しました。

| 項目 | Jetty 9.4.58 | Jetty 12.1.13 EE8 |
| --- | --- | --- |
| `/messages/info` | 200 | 200 |
| WebSocket transport 選択 | `websocket` | `websocket` |
| WebSocket STOMP CONNECT / CONNECTED | 成功 | 成功 |
| XHR transport 強制選択 | `xhr-polling` | `xhr-polling` |
| XHR SockJS open | 成功 | 成功 |
| XHR STOMP CONNECT が Spring inbound に到達 | いいえ（delta 0） | いいえ（delta 0） |
| XHR CONNECTED | なし。heartbeat / timeout のみ | なし。heartbeat / timeout のみ |

この比較により Phase 2I-G の手製 POST probe の client sequencing が原因ではなく、両 Jetty 共通の NIS codec 挙動であることを確認しました。

## Spring と NIS codec の call path

Spring Framework 5.3.39 の `XhrReceivingTransportHandler.readMessages(ServerHttpRequest)` は `SockJsServiceConfig.getMessageCodec().decodeInputStream(request.getBody())` を呼びます。その戻り値は `AbstractHttpReceivingTransportHandler.handleRequestInternal` から SockJS session に delegate されます。従って decoder が空配列を返すと、HTTP `xhr_send` が成功応答になっても STOMP frame は `clientInboundChannel` に届きません。

NIS `NisWebAppWebsocketInitializer` の既存 `decode(String)` は SockJS の JSON array envelope を parse し、その先頭 string を STOMP frame として返します。対して既存 `decodeInputStream(InputStream)` は明示的に `new String[0]` を返していました。Spring の受信 handler bytecode でもこの stream decoder 呼び出しを確認しました。`XhrPollingTransportHandler` / `XhrStreamingTransportHandler` は送信側の HTTP handler であり、この decoder 修正が影響するのは受信側の XHR send path です。WebSocket は従来の `decode(String)` を通ります。

## 既存実装の意図

`git blame` では空配列 return は2021年の formatter commit に帰属しますが、その前の2015年時点のファイルにも同じ実装がありました。コードコメント、関連テスト、commit message にこの動作を必要とする根拠は見つかりませんでした。従って、歴史的に長く存在した挙動ではありますが、意図された compatibility workaround / formal unsupported contract と断定できません。

一方、NIS は `/messages` を `.withSockJS()` で登録し、fallback transport を無効化する allowlist を設定していません。標準 SockJS client が XHR fallback を選択できる public endpoint で、STOMP frame を黙って破棄することは宣言された SockJS registration と矛盾します。この Phase では XHR fallback を supported contract と判断し、現行 Jetty 9 上の欠陥も修正対象としました。これは Jetty 12 migration 用の workaround ではありません。

## 修正と test-only experiment

production codec の `decodeInputStream` は、`JSONValue.parse(InputStream)` で stream 内の既存 SockJS JSON array envelope を読み、先頭の STOMP string を返すよう変更しました。既存の JSON parser と array/string semantics を維持し、追加の UTF-8 byte array / `String` コピーを作らない形です。WebSocket から使う `decode(String)`、encode、STOMP message converter は変えていません。

変更前の標準 client failure を記録した後に、Jetty 9 / Jetty 12 test fixture 上で同じ decoder semantics を試す test-only experiment を行いました。両方で XHR CONNECTED が回復したことを確認してから production codec に同じ処理を反映しました。

`NisWebAppWebsocketInitializerTest` は次を検証します。

- UTF-8 request stream から STOMP frame を復元する。
- `decodeInputStream` と `decode(String)` が同じ SockJS envelope semantics を返す。

標準-client runtime harness はさらに CONNECT / CONNECTED、SUBSCRIBE、NIS `/w/api/node/info` SEND、NIS handler からの outbound MESSAGE、UNSUBSCRIBE、DISCONNECT receipt を両 Jetty harness 上で検証します。

## 変更後の Jetty 比較

| 項目 | Jetty 9.4.58 production initializer | Jetty 12.1.13 EE8 test harness |
| --- | --- | --- |
| `/messages/info` | HTTP 200 | HTTP 200 |
| forced WebSocket transport | `websocket` 選択 | `websocket` 選択 |
| WebSocket STOMP | CONNECTED / NIS MESSAGE / DISCONNECT RECEIPT | CONNECTED / NIS MESSAGE / DISCONNECT RECEIPT |
| forced XHR transport | `xhr-polling` 選択 | `xhr-polling` 選択 |
| XHR STOMP | CONNECTED / NIS MESSAGE / DISCONNECT RECEIPT | CONNECTED / NIS MESSAGE / DISCONNECT RECEIPT |
| Spring `clientInboundChannel` CONNECT delta | 1 per scenario | 1 per scenario |
| NIS handler | `/w/api/node/info` が `MessagingService.pushNodeInfo()` を呼び、`/node/info` MESSAGE を返した | 同左 |
| server/context shutdown | 成功 | 成功 |

受信 `MESSAGE` は NIS handler と既存 outbound messaging/converter path が通った証拠です。test fixture の local Node は mock なので、payload は `{}` です。Mainnet/Testnet node payload の全 serialization は本 Phase の対象外です。Java harness の normal shutdown は成功しましたが、server-side session 数、Jetty thread leak、abnormal disconnect stress は測定していません。

## Java、root build と CI

- Java 17: ローカル runtime がないため未実行。GitHub-hosted Java 17 Baseline を使う。
- Java 25 root `mvn -B clean test`: 最終 parser 実装で2回実行。どちらも Core / Deploy / Peer は成功したが、NIS `NisPeerNetworkHostTest` が public-peer 接続中に `network boot failed` となり全体は失敗（1回目3 errors、retry 1 error）。他の NIS tests 3,488件は成功。これは Phase 2H / 2I で記録済みの public-peer network-dependent failure 群と同種で、今回の codec 変更とは別経路。
- 直前の decoder variant での root clean test は Core `AsyncTimerTest.timerContinuesIfFutureSupplierThrows` が1回だけ timing failure、変更なしの再実行は成功し、625 classes / 6,220 tests / 0 failures / 0 errors / 0 skipped でした。最終 parser 実装での full root clean test success とは数えず、上記 public-peer failure と分けて記録します。
- 最終 parser 実装の targeted `NisWebAppWebsocketInitializerTest`: 2 tests / 0 failures / 0 errors / 0 skipped。
- Java 25 root `mvn -B -DskipTests package`: 成功。
- Java 25 Jetty 9 / Jetty 12 POC: 各 `clean compile exec:java` 成功。
- `git diff --check`: commit 前に確認。
- Java 17 Baseline run `36274421188`, commit `6a7c2c51651540a09207958e0743d104e0a26f71`: **FAIL**。`Set up Java 17` と version report は成功、`Run clean unit tests` は失敗、`Package modules` は skip。公開 check annotation は `Process completed with exit code 1.` のみ。job logs API は HTTP 403 `Must have admin rights to Repository.` を返したため failing test / root cause を取得できなかった。原因を推測しない。
- Java 25 Compatibility run `36274421190`, 同一 commit: **SUCCESS**。`Run clean unit tests` と `Package modules` の両方が成功。
- Jenkins Java 17 execution は従来の external infrastructure blocker のままで、今回は解消も検証もしていない。
- Phase 2H Failsafe 制約は今回実行・変更していない。

## 維持する未解決制約

この変更は Phase 2F DB gate に影響しません。H2 1.4 database の offline export/import requirement、Flyway V1.0.0–V1.0.7 history/checksum 制約、代表 Mainnet/Testnet DB validation、matching-genesis full chain-state comparison は未解決のままです。

## 次の判断

この Phase の結果は SockJS codec contract を直したもので、Jetty 12 production migration gate を解除しません。production initializer が Jetty 12 shim を明示的に選択する path、raw handshake headers、Origin rejection、STOMP error mapping、abnormal disconnect、session cleanup、leak behavior は別途確認が必要です。Java 17 hosted clean-test failure の診断と成功 validation が得られるまで Phase 2I-H は `PARTIAL` とし、production Jetty 12 migration は開始しません。
