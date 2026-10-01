# NIS public Testnet node acceptance

**Status: EXTERNAL ACTION REQUIRED** — repository readiness and the full Java 25 regression gate now pass, and an authenticated peer checker is available. This host still has no active NIS runtime or verified public inbound route, so synchronization, inbound acceptance, reconnect, restart-at-tip, and stability remain unproven.

## Revalidation on 2026-10-01

- Requested baseline: `114365b5f0551cd2c5df787e30c2e86b7af0994d`.
- Actual starting local and origin HEAD: `0efdcc0537bf327db82b4fc459d2518fb0f10e6b`; branch `agent/nis-public-testnet-node`; divergence `0/0`; working tree clean.
- The branch already contained the two later commits `6be35333f` and `0efdcc053` beyond the requested baseline. They were preserved; no branch switch or history rewrite was made.
- Recent commit messages still follow `[nis] <type>: <lowercase summary>`.
- Runtime environment: OpenJDK `25.0.4.1`, Maven `3.8.7`, Linux x86_64. No NIS process, Testnet DB, or NIS systemd unit was present at revalidation time.
- This machine is `eolia.harvestasya.com` with private `br0` address `192.168.35.1/23`, default gateway `192.168.34.1`, and observed outbound IPv4 `203.135.231.85`. `sudo -n true` requires a password; host firewall and upstream NAT could not be inspected or changed. The outbound address does not prove an inbound route.

### Repository changes and regression evidence

- `BlockChainLastBlockLayer.isLoading` is now `volatile` because the asynchronous block loader updates it while controller and service threads read it.
- The order-sensitive `NisMainTest` fixture now clears the shared in-memory database/cache before each test and waits for its asynchronous-load case to finish before teardown. The prior failure report showed a nonempty DB without the nemesis block; the fixture cleanup addresses that cross-test contamination.
- Added `PublicPeerHandshakeCheck`, which obtains the peer's public identity from `GET /node/info`, sends a fresh authenticated NIS `POST /node/info`, verifies the challenge signature with the advertised identity, and checks the returned Testnet ID and endpoint. `check-public.sh` invokes this checker after its health/TCP checks. Unit coverage accepts a valid signed response and rejects a response signed by a different identity.
- Final `mvn -B -pl nis -am clean package`: **BUILD SUCCESS**, 3,500 tests, 0 failures, 0 errors, 0 skipped.
- `deploy/public-testnet/build-bundle.sh /tmp/nis-public-testnet-final-bundle`: **BUILD SUCCESS**, Java 25 bundle created with the authenticated checker.
- All `deploy/public-testnet/*.sh` scripts pass `bash -n` and ShellCheck. `systemd-analyze verify` passes on a temporary copy of the supplied unit with its paths and service user mapped to the generated bundle/current user; the unit was not installed or started.
- The bundle checker was run against `tortuga.nemtest.net`: remote `/node/info` reported network ID `-104`, height `813,658`, remote `/status` code `6`, and 5 active peers; the fresh signed peer handshake was verified. This validates an outbound handshake from this machine to that public peer. It is not evidence of an inbound handshake to this machine.
- The generated bundle and temporary runtime/configuration artifacts are outside the repository. No Testnet service was started on this NATed host.

These results supersede the earlier note below about the one full-suite failure: the fixture issue was investigated, corrected, and the clean reactor gate passed afterward. Historical runtime samples remain unchanged and still describe the previous short-lived node session.

## Goal and repository

- Goal: run the Java 25 optimized NIS as an Internet-reachable public NEM Testnet peer.
- Repository: `nemnesia/nem` (`https://github.com/nemnesia/nem`)
- Base branch: `agent/nis-java25-optimization`
- Base HEAD: `9db190f1136ebf7e42e4409bcafc12836591f7aa`
- Working branch: `agent/nis-public-testnet-node`
- Starting HEAD: `9db190f1136ebf7e42e4409bcafc12836591f7aa`
- Implementation commit: `80964791a1691d6fbade94c66588fd192227a383` — `[nis] build: add isolated public Testnet deployment bundle`.
- Final local HEAD: acceptance-record commit; see the final Git status reported with this record.
- Final origin HEAD and divergence: see the final Git status reported with this record.
- Commit subjects follow the recent NIS convention: `[nis] <imperative verb>: <lowercase summary>`. The convention audit covered the 100 commits ending at Base HEAD; recent modernization records use `[nis] docs: record/close …`.

## Runtime and isolation

- Runtime: OpenJDK `25.0.4.1` (Ubuntu), Maven `3.8.7`, Linux x86_64 (`6.8.0-142-generic`), NIS `0.6.102`.
- Historical note: the first clean package run at the earlier source revision had one failure among 3,498 NIS tests: `NisMainTest.initLoadsDbSynchronouslyIfDelayBlockLoadingIsDisabled` (expected `isLoading=false`, got `true`). The later revalidation above records the fixture correction and passing full gate.
- Runtime configuration used `nem.network=testnet`, network ID `-104` (`0x98`), Testnet peer seeds, explicit current Testnet fork heights, and an isolated H2 file at `/tmp/nis-public-testnet-data/nis/data/nis5_testnet.mv.db`.
- The first runtime used a stale generated config that lacked the live fork overrides and stalled at height `1601` with `FAILURE_INSUFFICIENT_FEE`. Regenerating the config from the updated template, then restarting with the same DB/key, resolved that validation failure and synchronization advanced.
- A persistent, randomly generated Testnet-only boot key was kept outside the repository in a mode-0600 file. The same config and identity were reused after restart. Auto-harvesting was disabled. No secret material is recorded here.
- The deployment template keeps `/opt`, `/etc`, `/var/lib` paths separate; supplies a Java 25 startup wrapper; and includes a dedicated unprivileged systemd unit with write access limited to Testnet data. The systemd unit was not installed on this host.
- Deployment scripts passed `bash -n` and ShellCheck. The unit passed `systemd-analyze verify` with its installed `ExecStart` path mapped to the checked-in executable for validation; it was not installed or started by systemd here.

## Public Testnet runtime evidence

The public peer endpoints below returned matching Testnet heights and peer metadata over the runtime window. The node connected to three peers and received/validated real remote blocks after applying the documented public Testnet fork overrides.

| UTC sample | Local height | `tortuga` | `ocracoke` | `libertalia` |
| --- | ---: | ---: | ---: | ---: |
| 2026-10-01 10:20:49 | 4,801 | 813,509 | 813,509 | 813,509 |
| 2026-10-01 10:21:50 | 10,801 | 813,510 | 813,510 | 813,510 |
| 2026-10-01 10:22:52 | 16,401 | 813,511 | 813,511 | 813,511 |
| 2026-10-01 10:23:40 | 21,201 | 813,511 | 813,511 | 813,511 |
| 2026-10-01 10:24:41 | 27,201 | 813,511 | 813,511 | 813,511 |
| 2026-10-01 10:25:42 | 32,801 | 813,513 | 813,513 | 813,513 |
| 2026-10-01 10:26:43 | 39,201 | 813,513 | 813,513 | 813,513 |
| 2026-10-01 10:27:44 | 44,801 | 813,514 | 813,514 | 813,514 |
| 2026-10-01 10:28:11 | 47,201 | 813,514 | 813,514 | 813,514 |
| 2026-10-01 10:29:12 | 53,201 | 813,517 | 813,517 | 813,517 |
| 2026-10-01 10:30:13 | 59,201 | 813,518 | 813,518 | 813,518 |
| 2026-10-01 10:33:07 | 73,601 | 813,525 | 813,525 | 813,525 |
| 2026-10-01 10:34:08 | 79,201 | 813,526 | 813,526 | 813,526 |
| 2026-10-01 10:36:10 | 90,801 | 813,526 | 813,526 | 813,526 |

- `/node/info` reported network ID `-104`, NIS `0.6.102`, and three active peers.
- `/status` returned code `5` (`BOOTED`), not code `6` (`SYNCHRONIZED`). Chain tip following and synchronization to the current public tip are therefore incomplete.
- Runtime listener inspection showed TCP `7890` and WebSocket TCP `7778` bound to all interfaces; TCP `7891` was not listening. The intended public peer/API port is TCP `7890`; the WebSocket port must be denied at host and edge firewalls.
- The isolated test config deliberately advertised `127.0.0.1`; it was not a public endpoint. This machine has a private `br0` address (`192.168.35.1`) behind an upstream gateway; an outbound address observation (`203.135.231.85`) does not establish an inbound route. No Internet-side inbound peer handshake was observed.
- The temporary node was started with G1, `-Xms1g`, `-Xmx6g`, and GC logs. It ran about 10 minutes before a graceful restart, remained alive with three peers, and had no current-interval fatal/error or fee-validation log entries. At the diagnostic sample it used about 1.6 GiB RSS, 270 MiB used heap, 72 threads, and 151 file descriptors. The catch-up interval is too short to establish sustained stability.

## Restart, persistence, reconnect, and hardening

- Graceful shutdown through the local trusted `/shutdown` endpoint returned HTTP 200 and exited cleanly.
- The same Testnet H2 DB and key were reused on restart. Before the last restart the node reported height `60,801`; startup logged `block loading completed; height 60801` at 10:30:48 UTC, and the endpoint reported `64,001` by 10:31 UTC while pulling/validating new blocks. It continued to height `90,801` with three peers by 10:36 UTC. The H2 file remained at the same path and was about 33 MB after graceful shutdown. No fresh database was created. This demonstrates DB reuse and resumed synchronization, but not a completed catch-up/restart at the live tip.
- A controlled connectivity interruption and reconnect cycle was not run because changing host routes/firewall requires unavailable privileged access. Scheduler reconnect behavior is present in source but was not accepted as runtime evidence.
- No system firewall or upstream NAT rule could be inspected or changed; `sudo` requires an unavailable password. The node was not advertised publicly and no public port was opened intentionally.
- The deployment artifacts restrict identity permissions, disable harvesting, separate Testnet storage, and document exposure only on TCP `7890`. Internet exposure hardening is not accepted until host/edge firewall state and an external probe are verified.

## Remaining external actions

An infrastructure owner with access to the intended Internet-facing Linux host, firewall, and upstream NAT/router must:

1. Install the bundle under `/opt/nis-public-testnet`, create the `nis-testnet` service account, configure `/etc/nis-public-testnet` and `/var/lib/nis-public-testnet`, and install the supplied systemd unit.
2. Route public TCP `7890` to that host's TCP `7890`; allow only that required inbound NIS peer/API port and deny TCP `7778`, `7891`, and unrelated services. This source host advertises no verified public endpoint.
3. From a different Internet-side host, run `check-public.sh PUBLIC_HOST` and establish a real NIS peer handshake. Confirm the public endpoint matches the listener, edge mapping, and `nem.host`.
4. Keep the Java 25 service online until it reaches the public chain tip; record `/status` code `6` and repeated local/peer tip samples.
5. With that node online, record graceful restart and DB reuse at its current height, then a controlled local outbound TCP `7890` interruption/recovery and successful peer reconnection/sync.
6. Run an extended stability interval and retain the final clean build result with the runtime evidence.
The deployment and validation procedure, including stop/rollback instructions, is in [`deploy/public-testnet/README.md`](../../deploy/public-testnet/README.md). The public Testnet fork-height overrides follow the [NEM Testnet node installation guide](https://docs.nemtest.net/en/userbook/node/install/).
