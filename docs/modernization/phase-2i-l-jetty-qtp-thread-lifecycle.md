# Phase 2I-L — Jetty QTP thread lifecycle / shrinkage

## 判定

**COMPLETE — READY FOR PRODUCTION JETTY 12 MIGRATION**。このPhaseではreadiness調査だけを行い、production Jetty dependency/bootstrapは変更していない。QTP workerは負荷直後にminThreadsまで戻らないが、QTP自身のpool-wide staged evictionで説明でき、session/requestの残留やbatchごとの無制限な増加は観測されなかった。Java 17/25のローカル検証とhosted CIも通過した。

## 対象と変更境界

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `a97d4fe4917b880962ce19a6846b4473279ffe6c`
- Jetty 9: `9.4.58.v20250814`
- Jetty 12 test runtime: `12.1.13` EE8
- Spring: `5.3.39`; Java runtime: OpenJDK 17 `17.0.20.1`; Maven `3.8.7`; Linux amd64
- 本Phaseでは production Java、Jetty/Spring dependency、bootstrap/runtime configurationを変更していない。追加した `/phase2i-l-ping`、QTP計測、thread ID追跡は `phase-2i-g-poc` の test-only harness。
- 既存 Phase 2I-A〜K の履歴を置き換えず、本記録で追加の原因分離を行う。

## 計測方法

QTPの公開値をserver起動中に記録した: total / busy / idle / queue、min/max、idleTimeout、reserved設定、reserved capacity/current、ready、leased、utilized、low-thread threshold、Jetty 12 `maxEvictCount`。各時点で `QueuedThreadPool.toString()` の eviction countdown と、pool名をprefixに持つthreadのID/name/state/stack分類も取得した。分類はselector、acceptor、reserved runner、通常worker。JVM全thread数とscheduler等のgroup別集計も併記した。

HTTP負荷のclientは専用executorを使い、終了後にshutdown/awaitした。以前のJDK `HttpClient` はJava 17で明示closeできず、probe中にthreadを保持する可能性があるため、HTTP確認をclose可能な `HttpURLConnection` に置き換えた。XHR/WebSocket probeは標準 `sockjs-client@1.6.1` の子processを使い、process終了後にserver-side metricsを採取した。QTP peakは10ms周期のtest-only samplerで取得し、sampler自身はdaemonで停止・joinした。

## upstream QTP実装

- [Jetty 9.4.58 `QueuedThreadPool`](https://github.com/jetty/jetty.project/blob/jetty-9.4.58.v20250814/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java): `Runner.run()` はjob queueに即時jobがない場合、pool totalがminを超え、共有 `_lastShrink` から `idleTimeout` が経過していればCASで共有時刻を更新し、そのworkerを終了する。`startThread()` は新worker開始のたびに `_lastShrink` を現在時刻へ戻す。evictionは個々のworkerの独立した60秒timerではなく、pool共有timerに対して一度に最大1 workerずつ進む。
- [Jetty 12.1.13 `QueuedThreadPool`](https://github.com/jetty/jetty.project/blob/jetty-12.1.13/jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java): `maxEvictCount`既定値は1。eviction periodは`idleTimeout / maxEvictCount`で、idle runnerが1 periodごとに上限数まで終了する。`startThread()` はshared `_evictThreshold` を`now + idleTimeout`に更新し、新規worker作成直後のthrashingを防ぐ。runnerはidle queue pollの後にevictを試みるので、timeout境界直前にpollが満了すると次pollまでeviction観測が遅れる。
- [Jetty 12 Threading Architecture](https://jetty.org/docs/jetty/12/programming-guide/arch/threads.html): connectorのselector/acceptor、および`TryExecutor`/`ReservedThreadExecutor`はQTP workerをleaseする。QTPのtotal/busyだけをapplication request worker数と同一視できず、leased/idle/reservedを合わせて見る必要がある。公式説明もidleTimeoutに加えmaxEvictCountを縮退速度の制御としている。

本環境の両poolは`minThreads=8`, `maxThreads=200`, `idleTimeout=60000ms`, `reservedThreads=-1`, `lowThreadsThreshold=1`。Jetty 9のreserved capacityは12、Jetty 12は16。runtime検出したconnector workerはselector 6、acceptor 1。Jetty 12 `getBusyThreads()`はleasedと利用中threadを含み、`getCurrentReservedThreads()`はidleとは別に数える。Jetty 9 APIではcurrent-reserved getterがなく、`toString()`の`ReservedThreadExecutor reserved=x/capacity`を使った。

## 無負荷baseline

QTP計測前にHTTP client requestを送らず、Spring/Jetty起動後に計測を開始した。各checkpointでSockJS sessionは0、queueは0。JVM thread数はMaven/Probe JVM全体の値、QTP値はJetty poolからの直接取得。

| Runtime | start / +0s | +10s | +30s | +60s | +90s | +120s | +180s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Jetty 9 total/busy/idle/queue | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 |
| Jetty 12 total/busy/idle/queue | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 | 8/7/1/0 |

起動時点で両方min total8まで到達し、その後も8で安定した。7 busyはselector/acceptor等のleased internal thread。残る1 threadがready/idleであり、無負荷だけで余剰QTP workerが残る挙動は再現しなかった。

## 単純HTTP負荷

test-only `/phase2i-l-ping?delay=100` servletを使い、逐次20件、同時10件、同時100件×3 batchを投入した。各batchでbefore / 10ms sampler peak / request完了直後を記録し、最後のbatch後は同一serverで30/60/90/120/180秒を観測した。queueはいずれも0、SockJS session/HttpPollは0。

| Runtime / checkpoint | QTP total/busy/idle/queue | identity summary |
| --- | ---: | --- |
| Jetty 9 起動・idle | 8/7/1/0 | selector 6, acceptor 1, ordinary worker 1 |
| Jetty 9 sequential 20 peak / after | 9 / 9/7/0/0 | pool grows by one |
| Jetty 9 concurrent 10 peak / after | 18 / 18/7/5/0 | queue 0 |
| Jetty 9 concurrent 100 batch 1 peak / after | 109 / 109/7/90/0 | queue 0 |
| Jetty 9 batch 2 peak / after | 110 / 110/7/91/0 | queue 0 |
| Jetty 9 batch 3 peak / after | 110 / 110/7/91/0 | plateau; no session |
| Jetty 9 post +30/+60/+90/+120/+180s | 110/7/91 → 109/7/97 → 109/7/102 → 108/7/101 → 107/7/100 (busy/idle/queue) | one thread ID removed at +60s, another at +120s |
| Jetty 12 sequential 20 peak / after | 9 / 9/7/0/0 | pool grows by one |
| Jetty 12 concurrent 10 peak / after | 18 / 18/7/9/0 | queue 0 |
| Jetty 12 concurrent 100 batch 1 peak / after | 108 / 108/7/85/0 | queue 0 |
| Jetty 12 batch 2 peak / after | 108 / 108/7/85/0 | plateau |
| Jetty 12 batch 3 peak / after | 110 / 110/7/87/0 | +2 threads total vs batch 2 |
| Jetty 12 post +30/+60/+90/+120/+180s | 110/7/87 → 110/7/98 → 110/7/102 → 109/7/101 → 108/7/100 | `toString()` countdown was +29,926ms at +30s, -73ms at +60s; first removal by +120s |

HTTP requests each took 100ms; concurrent 100 batches completed with queue 0. QTP pools warmed and retained idle workers, but both runtimes were bounded at 110 in this same-server three-batch run. In both cases worker IDs remained stable and stacks were parked in `Unsafe.park`; selector/acceptor counts stayed 6/1. No SockJS sessions or HTTP polls existed during these measurements.

## SockJS正常・abandoned XHR

Phase 2I-Kで、正常なWebSocket/XHR pollingを各100回、abandoned XHRを100 session×3 batchで比較済み。正常終了後のsession map/HttpPollは0。abandoned XHRもJetty 9/12共通で、Spring heartbeat 25秒がactive pollにclient断を通知した後、disconnect delay 5秒とcleanup sweepで各batch約35秒にsession mapが0になった。実測したServlet AsyncContext timeoutは`-1`、Spring `disconnectDelay=5000ms`、`heartbeatTime=25000ms`。

このPhaseでは同じQTP samplerをSockJS XHR abnormal batchへ追加した。各batchは100 XHR polling sessionsで、Spring inbound channelにCONNECTが届き、clientがpollingを放棄した。以下の`peak QTP`は10ms samplingの最大値。sessionはSpringのheartbeat/disconnect cleanupにより各batch約35秒で0へ戻った。

| Runtime / batch | peak QTP / busy / queue | QTP worker IDs: prior retained/added/removed | Spring current HttpPoll at batch peak → cleanup | cleanup ms | QTP at cleanup |
| --- | ---: | ---: | ---: | ---: | ---: |
| Jetty 9 batch 1 | 42 / 37 / 0 | 11/31/0 | 100 → 0 | 35,368 | 42 |
| Jetty 9 batch 2 | 44 / 15 / 0 | 42/2/0 | 100 → 0 | 34,897 | 44 |
| Jetty 9 batch 3 | 44 / 17 / 0 | 44/12/0 | 100 → 0 | 34,968 | 56 |
| Jetty 12 batch 1 | 52 / 43 / 0 | 13/72/0 | 100 → 0 | 35,306 | 85 |
| Jetty 12 batch 2 | 85 / 38 / 0 | 85/0/0 | 100 → 0 | 34,911 | 85 |
| Jetty 12 batch 3 | 85 / 64 / 0 | 85/0/0 | 100 → 0 | 34,923 | 85 |

Jetty 9では最後のbatch後に65秒待つと56→55で1 workerが消え、Spring session mapは既に0だった。Jetty 12では85→84で1 workerが消え、Spring mapも0、reserved currentは16→1だった。前段のJetty 12 batch growthは二回目に85まで増えた後、次の二batchでworker ID追加なしにplateauした。session/request数がゼロの後にparked QTP workerが残ることはpool retentionであり、session leakではない。

## thread identity / harness確認

QTP workerはpool name prefixのthread name/idで追跡した。stackの代表状態は`TIMED_WAITING` / `Unsafe.park`で、稼働中requestを保持するstackではない。QTPのselector/acceptor/reservedと通常workerを分け、checkpoint間でretained/added/removed ID数を比較した。通常HTTP負荷のclient executorとsamplerは停止・joinされる。SockJS clientは各node子process終了後に残留しない。JVM thread summaryではQTP外のscheduler及びMaven/probe threadを別groupとして表示する。

## 縮退タイミングの説明

`idleTimeout=60000`は「最後のrequestから60秒で全workerが一斉に消えてminThreadsへ戻る」という指定ではない。両実装とも新worker生成が共有縮退timerを更新し、縮退時はpool全体から原則1 workerずつ進む。60秒周期で最大1件なら、100件規模のburstからmin付近まで戻るには多数の周期が必要になる。J12はpoll後にevict判定するため、共有threshold期限とpoll timeout境界が数十msずれると次のpollまで遅れる。

したがってPhase 2I-KのJetty 12 `34 → 54 → 58` は、各abandoned XHR batchが約35秒でsession cleanupされる一方、その35秒は60秒idle eviction periodより短く、batch中にQTP workerを追加して共有eviction thresholdをリセットすることで説明できる。負荷終了後に残るparked workerはQTP adaptive pool retentionであり、SockJS/HttpPoll reference leakではない。単純HTTP 100×3のsame-server probeでpeakがplateauし、post-idleではthread IDが段階的に消えることを交差確認する。

## test count / Java 25 transient

Phase 2H discoveryは624 classes / 6,218 tests。Phase 2I-Kのreportは625 / 6,220で、本Phaseでもproduction test class/methodを追加していない。正確な過去count差の出所は未特定のままとする。Java 25 `AsyncTimerTest.visitorIsNotifiedOfSuccessfulCompletions` の一回限りのtiming failureはPhase 2I-Kに記録済みで、本Phaseでは同一failureを再現・変更していない。

## build / CI / 既存gate

Java 17 local `mvn -B clean test`: **BUILD SUCCESS**, 6,220 tests, 0 failures, 0 errors, 0 skipped (`/tmp/phase2i-l-root-j17-test.log`; 2m18s)。Java 17 `mvn -B clean package`: **BUILD SUCCESS**, 6,220 tests, 0 failures, 0 errors, 0 skipped (`/tmp/phase2i-l-root-j17-package-retry2.log`; 2m23s)。最初の通常sandboxでのpackage試行はWireMock loopback bind拒否で16件errorとなったが、loopback許可で同一commandを再実行し成功した。これはtest環境権限による差で、repository failureではない。

Java 25 `mvn -B clean test`: 初回は `AsyncTimerTest.visitorIsNotifiedOfStops` が1件失敗（2,361実行時点）。同じclean testを再実行し**BUILD SUCCESS**, 6,220 tests, 0 failures/errors/skips (`/tmp/phase2i-l-root-j25-test-retry.log`; 2m04s)。失敗時のmock履歴はstart/completeと次のdelay通知までで`notifyStop()`が未到達。testは6ms待機後にtimer closeし、さらに9ms待つ短い時間ベースassertionで、実装変更やskipは行わず、再試行結果を採用し初回failureはflaky/transientとして残す。

Java 25 `mvn -B clean package`: **BUILD SUCCESS**, 6,220 tests, 0 failures, 0 errors, 0 skipped (`/tmp/phase2i-l-root-j25-package.log`; 2m05s)。Java versionsはOpenJDK 17.0.20.1、25.0.4.1、Maven 3.8.7。

Hosted CIはこのPhaseの計測コードcommit `8c22a37becdf432f63f9ff54181066313d3ff44b`で両方成功。

| Workflow | Run | Result |
| --- | --- | --- |
| Java 17 Baseline | [36287686205](https://github.com/nemnesia/nem/actions/runs/36287686205) | clean test・package成功。Temurin 17.0.20.1 / Maven 3.9.16 |
| Java 25 Compatibility | [36287686219](https://github.com/nemnesia/nem/actions/runs/36287686219) | clean test・package成功。Temurin 25.0.4.1 / Maven 3.9.16 |

Jetty 9/12 test-only QTP idle baseline, simple HTTP load, short-idleTimeout diagnosis, SockJS 100×3 abnormal XHR probesはすべて実行した。既知のJava 25 `AsyncTimerTest.visitorIsNotifiedOfSuccessfulCompletions` transient failureはPhase 2I-Kで一度記録済み。本Phaseでは再現しておらず、修正していない。

Jenkins Java 17 image/shared-library blockerは別の外部制約として維持し、本Phaseで変更していない。Phase 2F H2 1.4 offline export/import requirement、Flyway history/checksum、representative Mainnet/Testnet DB validation、matching-genesis full chain-state comparisonも未解決のまま維持する。

## readiness判定と残事項

### 原因分類

- Jetty 9 steady `66→65→65` / `95→94`: **A. 正常なQTP staged shrink**。shared shrink timestampに対しidle runnerが縮退を進め、worker IDが段階的に消える。
- Jetty 12 `34→54→58`: **A. 正常なadaptive growthとbatch間のeviction threshold reset**。abandoned XHRは約35秒でcleanupし、60秒idle eviction periodより短いので次batchのworker開始が共有thresholdを先送りする。100×3の追加計測ではworker数は二回目batchで85に達し、以降batchごとに増えず、最後のidle後に84へ減った。
- +60秒で必ずminThreadsまで縮退しないのは、`idleTimeout`が個別workerのexpiry時刻ではなくpool共有のshrink/eviction cadenceだからである。Jetty 12では+60s時点でcountdownが`-73ms`だったが直ちにevictせず、後続pollで+120sまでに1件を除去。各cadence最大1 workerのため、100 workerのburst解消は複数分を要しうる。
- J9/J12 post-loadの~100 workerは`TIMED_WAITING` / `Unsafe.park`で、stable identityのidle QTP workers。QTP totalはmax200以内、queue 0、busy7（connector leases等）、QTP/SockJS/request数は反復で単調増加しない。J12のReservedThreadExecutor capacity16とJetty 9の12という差、QTPのeviction実装差を考慮しても明確なresource leakは観測していない。
- XHR反復後、最終cleanup時点の比較ではJetty 9 total56（selector6 + acceptor1 + pool worker49、うちbusy/leased7）、Jetty 12 total85（selector6 + acceptor1 + ReservedThreadExecutorにleasedされた16 + 他worker62）。Jetty 12が29多い内訳の大半は16のreserved roleと13のparked ordinary worker差で、session mapは両方0。さらにJetty 12 +65sではtotal84、reserved current 1、ordinary idle 76となり、reserved roleが16から1へ縮んだ一方、pool全体の純減は1だった。reservedを含むthread roleの貸出/返却とslow evictionが総数だけの差を生む。単純HTTP 100 concurrentでは両方ともpeak110に達し、同一負荷でJ12だけが増え続ける挙動はなかった。
- Phase 2I-Kの個別snapshot `J9 74 / J12 134` は同一環境で再現していないため、その絶対差60の各thread IDへの完全な遡及帰属はできない。本PhaseのQTP direct counters/thread stack probeでは、差が出うるreserved capacity/worker retentionとeviction policyを特定し、再反復でunboundedな増加を否定した。historical snapshot単体からleakを主張しない。
- harness artifact: 既存probeはclient側HTTPを`HttpURLConnection.disconnect()`とshutdown可能なexecutorに置換し、QTP専用10ms samplerはdaemonで停止/join。QTP thread identityからclient/Maven/schedulerを分離。計測されたworker保持をprobe executorでは説明できず、idle Jetty QTP自身だった。

### shrink policy の補助確認

診断用にtest-only QTP `idleTimeout=1000ms`を設定した並行HTTP100後、Jetty 9は101→99(+2s)→97(+4s)→93(+8s)→85(+16s)→71(+30s)、Jetty 12は108→107→105→101→93→79と縮退した。構成に対する縮退がtimeout経過後に実際に起きることを確認した。これはtest harness限定で、production timeoutを変更していない。

### 既存記録・別gate

- test discoveryは本Phaseでproduction test methodを追加していない。2Hの6,218件対2I-Kの6,220件の由来はこのPhaseでも特定できず、未解決記録を維持する。
- Java 25 transient timing failureは再現せず、原因断定や修正は行っていない。
- Jenkins Java17 image/shared-library blockerは未解決。本PhaseでJenkinsを実行・変更していない。
- Phase 2FのH2 1.4 offline export/import、Flyway history/checksum、Mainnet/Testnet DB validation、matching-genesis full chain-state comparisonは未解決のまま。

### 変更file / readiness

変更は次のtest-only harness 3 fileと本documentのみ。production Java/dependencies/bootstrap/configurationの変更はない。

- `docs/modernization/phase-2i-g-poc/src/main/java/org/nem/nis/websocket/Jetty9SockJsControl.java`
- `docs/modernization/phase-2i-g-poc/src/main/java/org/nem/nis/websocket/Jetty12SockJsControl.java`
- `docs/modernization/phase-2i-g-poc/src/main/java/org/nem/nis/websocket/ReadinessProbe.java`
- `docs/modernization/phase-2i-l-jetty-qtp-thread-lifecycle.md`

**結論: QTP観測ではJetty 12固有のunbounded thread/resource growthは見つからず、既存のJetty 9/12 WebSocket/SockJS parity evidenceと合わせ、次の独立Phaseでproduction Jetty 12 migrationを開始できるreadinessと判定する。** 本Phaseではmigrationを行っていない。

計測・ローカル検証結果を含む本記録は、GitHub-hosted Java 17 / Java 25 workflow成功を追記したfollow-up documentation commitで確定する。計測/実装結果そのものは上記runのhead commit `8c22a37becdf432f63f9ff54181066313d3ff44b`で検証済み。
