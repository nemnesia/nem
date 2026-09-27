# Phase 2I-M — Production Jetty 12 migration

## 判定

**BLOCKED — PRODUCTION JETTY 12 MIGRATION BLOCKED**

この記録では production runtime を Jetty 9 のまま維持する。Jetty 12.1.13 に依存関係を置き換えた作業ツリーで production bootstrap を起動したところ、HTTP startup は成立したが、SockJS WebSocket/XHR polling transport が実 NIS の `/messages` contract を通過しなかった。未完成の runtime 変更はコミットせず、Jetty 12 dependency/bootstrap/provider の変更をすべて開始 HEAD に戻した。

## Repository / starting point

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Starting HEAD: `927a3da5f42a2d6697d11dcb3466ea0f853cf53d`
- 開始時 working tree: clean
- 作業開始時 local/origin branch: 同じ HEAD
- Java: OpenJDK / javac `17.0.20.1`; Maven `3.8.7`
- Jetty before: deploy `9.4.56.v20240826`; NIS `9.4.58.v20250814`
- 試験した Jetty 12: `12.1.13`, EE8 / `javax.servlet`
- Spring: `5.3.39`

## 試した production migration

Jetty 12 dependency graph と EE8 artifact boundary に合わせて deploy/NIS の Jetty modules、server/servlet bootstrap、EE8 `ErrorHandler` imports、Jetty 12 `ContentResponse` import、および NIS upgrade strategy provider を一時的に切り替えた。Jetty 9/12 dependency を production に二重保持しない構成でコンパイルと `mvn -B -pl nis -am -DskipTests package` は成功した。`javax.servlet` API の二重実装を避けるため旧 `javax.servlet-api:4.0.1` も試験 tree から外した。

この試行では Jetty 12 `JavaxWebSocketServletContainerInitializer` を設定し、production `NisWebAppWebsocketInitializer` が ServiceLoader provider から Jetty EE8 `JavaxWebSocketServerContainer` 用 Spring `RequestUpgradeStrategy` を選ぶようにした。`ServletContextHandler`、filter/servlet async support、Jetty 12 compression handler も調査した。これらの変更では SockJS の失敗は解消しなかったため、production code/dependency の変更はすべて取り消した。

## Production bootstrap runtime smoke

実際の `org.nem.deploy.CommonStarter`、一時 Mainnet データディレクトリ、および production bootstrap を使った local smoke。JVM は Jetty `12.1.13` を起動し、両 connector が bind して shutdown できた。

| 観測 | 結果 |
| --- | --- |
| REST `/heartbeat` (`17890`) | HTTP 200, `{"code":1,"type":2,"message":"ok"}` |
| `/w/messages/info` on WebSocket listener (`17778`) | HTTP 200, `websocket:true`, origins `*:*` |
| `/w/messages/{server}/{session}/websocket` (standard SockJS client) | Jetty HTTP 404; client reports unexpected response code 404; SockJS close 2000 `All transports failed` |
| SockJS XHR polling (standard client) | transport open 前に失敗し、transport 選択なし、close 2000 `All transports failed`; STOMP CONNECT/CONNECTED は観測されず |
| Direct `xhr` poll request | HTTP 500 JSON (`Internal Server Error`) |
| Direct valid-envelope `xhr_send` | HTTP 204。ただし receiver/poll が成立せず、これは STOMP CONNECT 成功の証明ではない |
| REST undefined route | HTTP 404 |

標準 client は `sockjs-client@1.6.1`。XHR は `/w/messages/info` を取得できたが、transport sequence は完了しなかった。WebSocket response には `Server: Jetty(12.1.13)` があり、要求は production WebSocket listener に到達していた。

調査時に servlet async support を DispatcherServlet と CORS filter に付けても XHR は失敗した。HTTP compression wrapper を一時的に外した別試験でも WebSocket/XHR が回復しなかった。このため、これらを修正済み原因として採用せず、該当する temporary code edits は取り消した。正確な Spring/Servlet failure callback の原因は未確定。

以前の isolated harness が Jetty 12 shim で STOMP lifecycle を通した結果は、本番 `CommonStarter` と同一の bootstrap/handler/filter chain を通した証明にはならない。本 Phase はその差を実 runtime で露呈させたため、Phase 2I-L の readiness を覆す。

## Dependency / bootstrap の最終状態

production migration 試行中の runtime dependency tree は Jetty `12.1.13` EE8 に収束し、NIS EE8 WebSocket server artifact を含めてコンパイルした。しかし本番 SockJS contract が失敗したため、その tree は採用しなかった。最終 committed source/POM は開始 HEAD のまま:

- Jetty 9 production dependency line が維持される
- production Jetty 12 dependency は残らない
- production servlet namespace は `javax.*` のまま
- Spring / Hibernate / H2 / Flyway / schema / migration SQL は変更しない
- Jenkins shared-library / Java image は変更しない

## Build / test

- Jetty 12 trial tree: `mvn -B -pl nis -am -DskipTests package` 成功。
- Jetty 12 trial tree: Java 17 root `mvn -B clean test` は一度成功した（既存約6,220 tests、failures/errors/skips 0）。その後の必須 runtime smoke が失敗したため、test green は production migration 合格を意味しない。
- reverted baseline の Java 17 `mvn -B clean test`: sandbox および再試行で Core 2,361 tests 中16 errors。すべて WireMock の loopback listener 起動が `java.net.SocketException: Operation not permitted` となったもの。Jetty migration regression ではなく、この実行環境の socket 制約で root test は完走できなかった。
- reverted baseline の Java 17 `mvn -B clean package`: Core の同じ 16 WireMock errors で test phase が停止し、package phase まで到達しなかった。
- Java 25 clean test/package: 今回未実行。Java 25 が Java 17 の代用にはならない。
- Docker build/smoke: Docker daemon API が利用不可のため未実施。
- Hosted Java 17/25 CI: この BLOCKED docs-only change に対する run は push 後確認する。Jetty 12 runtime CI の成功は未確認。

## 既知の独立 gate

以下は未解決のまま維持する。

- Jenkins Java 17 image / pinned shared-library / external image owner blocker
- Phase 2F H2 1.4 offline export/import requirement
- Flyway schema history / checksum constraints
- representative Mainnet/Testnet DB validation
- matching-genesis full chain-state comparison
- 過去の 6,218 → 6,220 test discovery 差
- Java 25 `AsyncTimerTest` transient timing failure history

## 次工程

Production Jetty 12 migration は開始しない。次はこの production bootstrap の実 `/messages` 経路における 404/500 の発生層を、Jetty 9 baseline と同じ listener/filter/DispatcherServlet construction を比較して特定する。production dependency を切り替える前に、Jetty 12 EE8 上で SockJS WebSocket と XHR polling の双方が標準 client により STOMP `CONNECTED` まで到達することを再度実証する必要がある。
