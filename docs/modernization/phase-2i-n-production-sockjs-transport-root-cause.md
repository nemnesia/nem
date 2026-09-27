# Phase 2I-N — production SockJS transport root-cause isolation

## 判定

**PARTIAL**。production Jetty 12 migration は再実施していない。Jetty 9 production bootstrap の XHR 500 は runtime stack trace で特定できた。Jetty 12 production-trial の WebSocket 404 は、保存された記録から要求 URL と handler/upgrade callback の trace を復元できず、直接原因の確定には至っていない。

## Git / 実行環境

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `b97981493f5679bea68c0b6e2c9b5d8099d52dc3`
- 開始時 worktree: clean
- 開始時 `origin/agent/nis-phase0-baseline`: local HEAD と一致
- Java: OpenJDK / javac `17.0.20.1`
- Maven: `3.8.7`
- SockJS client: `sockjs-client@1.6.1`
- 現行 production: Jetty 9.4.58.v20250814 (NIS)、deploy 側 Jetty 9.4.56.v20240826
- 参考 test runtime: Jetty 12.1.13 EE8、Spring 5.3.39

Phase 2I-M の BLOCKED 文書は変更していない。診断時に一時挿入した例外 stack print、POC の一時 mapping edits、生成物、`node_modules` はすべて戻し/削除した。commit 対象は本記録のみ。

## 現 production mapping / lifecycle

コード上の NIS WebSocket server は `NemWebsockServerBootstrapper` が `/` の `ServletContextHandler` に構成され、`WebsocketContextListener` が DispatcherServlet を `/w/*` に登録する。Spring 内の STOMP/SockJS endpoint は `/messages` なので、production URL は `/w/messages/...` である。

`AbstractNemServletContextListener.contextInitialized` は web application context を作り、initializer class を登録し、NIS listener の `initialize` で DispatcherServlet を登録した後に `contextClass` と filter registrations を設定する。`ContextLoaderListener` は `AbstractServerBootstrapper.createHandlers` で NIS listener の後に追加されている。NIS endpoint は Spring `NisWebAppWebsocketInitializer.registerStompEndpoints` に登録され、`allowedOriginPatterns("*")` と production SockJS codec を使う。Jetty 9 では Spring の既存 Jetty strategy が使用される。

Production filter chain は `AbstractNemServletContextListener` が登録する DoSFilter (設定時)、Jetty `GzipFilter` (JSON MIME type)、custom CORS filter (`/*`) からなる。これらの filter registrations は `setAsyncSupported(true)` を呼び出しておらず、DispatcherServlet registration も `setAsyncSupported(true)` を呼ばない。

## Jetty 9 production control: WebSocket と XHR

`CommonStarter` による Jetty 9 production bootstrap を使い、standard SockJS client を `/w/messages` に接続した。server 起動 PID と listen socket PID が一致する run を採用し、古い process が応答する測定混同は除外した。

| transport | 観測 | 結果 |
| --- | --- | --- |
| WebSocket | SockJS transport 選択、STOMP CONNECT / CONNECTED | 成功。Phase 2I-N control probe は CONNECTED 後、期待した NIS MESSAGE を得られず終了したため、ここでは全 NIS message contract の pass とはしない |
| XHR polling | `/w/messages/info` 成功後に transport を試行 | `All transports failed`。Spring CONNECT は観測されず |
| direct XHR receiving request | `POST /w/messages/194/kzpano4y/xhr` | HTTP 500。Jetty log に完全な servlet exception chain を取得 |

### XHR 500 の直接原因

Jetty 9 runtime log は次の chain を記録した。

1. Jetty `HttpChannel` が `/w/messages/194/kzpano4y/xhr` を処理。
2. `DispatcherServlet` → Spring `SockJsHttpRequestHandler` → `TransportHandlingSockJsService` → `AbstractHttpSendingTransportHandler.handleRequestInternal` → `AbstractHttpSockJsSession.handleInitialRequest` に到達。
3. Spring が `ServletServerHttpAsyncRequestControl` を作る際、`IllegalArgumentException` を投げた。
4. root message: `Async support must be enabled on a servlet and for all filters involved in async request processing...`。
5. Jetty stack には `org.eclipse.jetty.servlet.ServletHolder$NotAsync.service` が明示されている。
6. 例外は `SockJsTransportFailureException` / `SockJsException` / `NestedServletException` に包まれ、Jetty が HTTP 500 を返した。

従って、現 Jetty 9 production chain でも XHR receiving transport は DispatcherServlet が async-disabled のため失敗する。少なくとも servlet async 非対応は runtime で証明された直接原因であり、Jetty 12 固有 incompatibility ではない。加えて全ての関係 filter も async-enabled でなければならないが、現登録コードは各 registration に async flag を設定していない。stack の `NotAsync` は servlet の状態を直接特定する。個々の filter が同一の試行でどれだけ寄与するかは isolation 未実施。

Phase 2I-M の Jetty 12 trial で dispatcher/CORS async を変更し、compression path も試験されたが解決しなかったという記録は維持する。DoS/Gzip/その他の filter が残った場合、async 非対応 filter がなお request の `isAsyncSupported()` を false にする可能性がある。その trial の完全な stack trace は保存記録にないため、Jetty 12 trial の 500 の具体的例外を同一と断定しない。次の診断では対象 filter chain 全ての async flag を runtime で計測し、filter を個別に切り分ける。

## Jetty 12 EE8 harness と production bootstrap の差

既存 Phase 2I-G harness は production `NisWebAppWebsocketInitializer` とその endpoint configuration / codec を使うが、Jetty と Spring web context を直接組み立てる。比較のため test-only POC source を一時的に変更し、Jetty 12.1.13 EE8 `ServletContextHandler` 上で DispatcherServlet mapping を production と同じ `/w/*` にした。Jetty 12 `JavaxWebSocketServletContainerInitializer.configure(...)` は server start 前に呼び、ServiceLoader provider による `JavaxWebSocketServerContainer` → shim 選択を assertion している。

この `/w/*` harness で Java 17 runtime 上、標準 client の `websocket` と `xhr-polling` を個別に実行した。両方で transport が実際に選択され、STOMP `CONNECTED`、`/node/info` の NIS handler による `MESSAGE`、receipt 付き DISCONNECT が成功した。約10秒後に SockJS session map は0になった。これは `/w/*` prefix と production initializer/shim の組み合わせだけで404/transport failureになるわけではない証拠である。

ただしこの harness は production `NemWebsockServerBootstrapper` / `AbstractNemServletContextListener` の listener order、実際の filter registrations、production `JsonErrorHandler`、production `CommonStarter` の二 server lifecycle を完全には通していない。従って harness 成功は production startup の 404 を否定するものではない。

### Spring/Jetty initialization facts

| 項目 | Jetty 12 POC harness | production bootstrap/trial |
| --- | --- | --- |
| Servlet context | EE8 `ServletContextHandler`, context path `/` | production source は Jetty `ServletContextHandler`, context root `/` |
| Dispatcher mapping | Phase 2I-N 一時診断時 `/w/*`; 変更は戻した | source 上 `/w/*` |
| `/messages/info` external path | `/w/messages/info` が200 | M doc に `/messages/info` と `/w/messages/info` の表記揺れあり。保存されている 404 artifact は path を持たない |
| Spring endpoint | 実 NIS `NisWebAppWebsocketInitializer` | production initializer |
| upgrade container | `JavaxWebSocketServerContainer` を start 後に確認。provider/shim selection assertion | M doc は Jetty EE8 initializer/provider を試したと記録。ただし startup-time ServletContext attribute dump / selected provider event は保存されていない |
| servlet/filter lifecycle | direct setup、production listener/filter chain 不完全 | NIS listener → root `ContextLoaderListener`; dispatcher + optional DoS + gzip + CORS registrations |
| request result | `/w/messages`: WS/XHR STOMP flow pass | Jetty 12 trial WebSocket 404 / XHR 500 と記録 |

## WebSocket 404: evidence と未解決点

Phase 2I-M の記録は、Jetty `Server` header を持つ HTTP 404 と JSON error payload `{"error":"Not Found","status":404,...}` を示す。しかし保存された probe result は完全な request URL/session path を含まず、ServletContext handler、DispatcherServlet、Spring SockJS handler、Jetty upgrade component のどこが404を設定したか分かる access log / trace / callback event も残っていない。`JsonErrorHandler` は Jetty context で返された status を JSON 化するため、この JSON body だけでは発生 handler を特定できない。

Phase 2I-N の `/w/*` Jetty 12 harness では同じ standard client が WebSocket 101/CONNECTED を得た。よって servlet mapping prefix 自体が必ず失敗する原因ではない。残る差は production listener/filter/initializer lifecycle または当時の実際の request URL/provider/container timing だが、いずれも今回の保持 evidence からは直接原因として区別できない。推測で「SCI ordering」や「shim」を根因と断定しない。

| WebSocket evidence | 結果 |
| --- | --- |
| Jetty 9 production `/w/messages` standard client | WS transport 選択、`CONNECTED` 成功 |
| Jetty 12 test harness `/w/messages` standard client | WS transport 選択、`CONNECTED`、NIS `MESSAGE`、receipt 成功 |
| Jetty 12 production-trial `/w/messages/.../websocket` | M 記録上 404。ただし exact request/handler trace は不足 |
| ServletContext `javax.websocket.server.ServerContainer` の値と Spring 初期化時刻 | POC は Jetty EE8 container を確認。production trial の保存 runtime dump なし |

WebSocket 404 の直接原因は未確定。M の再現そのものを、production-like Jetty 12 harness で完全に同じ URL/initializer sequence にして再採取する必要がある。

## 変更と検証

- Production source / dependencies / bootstrap: 変更なし。diagnostic print は stack capture 後に戻した。
- Jetty 12 harness: `/w/*` mapping のみ一時変更して実行し、元に戻した。commit しない。
- Java 17 / Maven: `17.0.20.1` / `3.8.7`。
- Jetty 12 POC: `mvn -B -f docs/modernization/phase-2i-g-poc/pom-jetty12.xml -DskipTests package` 成功。runtime probe は loopback socket が必要なため同じ harness を通常 sandbox では `Operation not permitted` で開始できず、許可された loopback execution では成功した。
- Jetty 12 `/w/*` runtime probe: WebSocket 1/1; XHR polling 1/1; 両方 CONNECT / CONNECTED / NIS MESSAGE / receipt DISCONNECT。10秒後 `sockJsSessions=0`, `HttpPoll=0`。
- Java 17 root test: 本 Phase は production codeを変更せず、full suite は実行していない。Java 17 `mvn -B -pl nis -am -DskipTests package` は診断 print を戻した後に成功。
- Jetty 12 POC package は一時 mapping source の compileで成功。
- Java 25: 未実施。
- Hosted CI: source change を含まない調査であり再実行していない。

## 結論と次の作業

XHR 500 は servlet async が無効な実 production listener/DispatcherServlet path で Spring SockJS async receiving request を開始したために起きる。Jetty 12 trial が dispatcher と CORS だけを async 化してなお失敗した記録は、filter chain 全体も async-compatible にする必要性と整合する。ただし Jetty12 trial の exact exception は未採取なので、次回診断時に同じ chain を使って個々の async flags を記録する。

WebSocket 404 は M の artifact が URL/handler provenance を含まないため直接原因未確定。`/w/*` production mapping + production initializer + Jetty 12 EE8 shim の test harness は成功するが、production listener/filter chain と initialization order が完全一致しない。次工程 Phase 2I-O では production bootstrap を Jetty 9 control と Jetty 12 test-only bootstrap に分け、production listener order、ServletContext attributes (SCI 前後と Spring refresh 時点)、servlet/filter async flag、registration/mapping、Spring handler mapping、upgrade strategy invocation、response handler を instrument する。標準 client の正確な URL は `/w/messages` とし、WebSocket/XHR の各 request trace を相関 ID 付きで保存してから migration remediation を決める。XHR は `DispatcherServlet` と request chain 上すべての filter の async support を確認・個別化する。production Jetty 12 dependency/bootstrap はその後も gate が閉じたまま変更しない。

以下の独立 gate は未解決のまま維持する。

- Jenkins Java 17 image / pinned shared-library blocker
- Phase 2F H2 offline export/import validation
- Flyway history/checksum validation
- representative Mainnet/Testnet DB validation
- matching-genesis full chain-state comparison

## Phase 2I-N verdict

**PARTIAL**。Jetty 9 production XHR 500 の原因は直接 stack evidence で確認した。Jetty 12 EE8 `/w/*` harness が成功することも確認した。一方、Jetty 12 production-trial WebSocket 404 の直接発生箇所、および Jetty 12 production-trial XHR 500 の実例外は未確定である。Jetty 12 production migration は行っていない。
