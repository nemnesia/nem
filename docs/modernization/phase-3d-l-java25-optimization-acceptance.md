# Phase 3D–3L — Java 25 Native Optimization Acceptance

## Decision

**Repository-local Phase 3 implementation and acceptance: COMPLETE.**
Live public-peer synchronization, the separately provisioned Mijin fixture,
production-duration resource observation, and hosted CI remain external
acceptance items. No test was disabled, skipped, weakened, or rewritten to
change an outcome.

Repository: `nemnesia/nem`

Branch: `agent/nis-java25-optimization`

Starting HEAD: `d70faa4a3753853193601613dd5dddcd7041902e`

Java runtime used for closeout: OpenJDK `25.0.4.1`

Java 17 support: ended; all production modules compile with `--release 25`.

## Phase 3D–3I decisions

| Phase | Result | Decision and security check |
|---|---|---|
| 3D Virtual Threads | **NO-CHANGE ACCEPTED** | Peer HTTP is already asynchronous and connection-bounded; DB work has no demonstrated pool bottleneck. The bounded five-worker/five-queued-task scheduler is simpler and avoids unbounded peer-triggered task creation. Validation/crypto, ordered block application, and harvesting stay on their existing paths. **Security: PASS** — resource bounds and shutdown behavior retained. |
| 3E HTTP client | **NO-CHANGE ACCEPTED** | Retain Apache HttpAsyncClient 4.1.5. JDK `HttpClient` or a newer Apache stack would replace working timeout, abort, pool, TLS, redirect, and peer-error behavior without a demonstrated simplification. The existing client has a 100-connection total / 20 per-route bound, connect and pool-acquisition timeouts, socket timeout, 3-minute overall abort, and redirects disabled. **Security: PASS** — TLS and hostname verification remain library defaults; no HTTP trust, timeout, cancellation, connection, or redirect policy was relaxed. |
| 3F JVM/runtime | **NO-CHANGE ACCEPTED** | Java 25.0.4.1 selects G1 ergonomically (`UseG1GC=true`; ZGC is off). Keep G1 and current heap settings (Docker `-Xms6G -Xmx6G`; package scripts retain their documented 4–6G range). ZGC has no demonstrated pause problem to address. Compact Object Headers are supported but disabled by default. One small Mainnet-equivalent replay pair measured default vs compact: max RSS `417,892` vs `413,272` KiB; elapsed `8.85` vs `9.48` s; user CPU `23.88` vs `23.99` s. The small RSS difference and slower elapsed time are not clear evidence of a production benefit, so no flag is adopted. No security property, TLS restriction, trust store, or crypto policy changed. |
| 3G allocation/cache | **NO-CHANGE ACCEPTED** | No clear Phase 3 allocation or cache hotspot was established. No speculative cache, serialization, transaction, or consensus-state rewrite was made. **Security: PASS** — validation and persisted/wire representations remain unchanged. |
| 3H source modernization | **NO-CHANGE ACCEPTED** | No broad syntax migration was justified. Phase 3A–3C already removed compiler-deprecated API use and obsolete timer/executor shapes where the change had a concrete benefit. No Preview API was introduced. **Security: PASS** — no new untrusted-input path. |
| 3I AOT | **NO-CHANGE ACCEPTED** | No AOT cache/training or deployment workflow added: this is a long-running service and no repeatable startup improvement justified deployment complexity. |

| Closeout phase | Repository-local status | External acceptance |
|---|---|---|
| 3J Production compatibility | **COMPLETE** for supplied Mainnet/Testnet persisted-chain loading/restart and local API/simulator coverage | Live public-peer synchronization and Mainnet/Testnet web service operation were unavailable here. |
| 3K Lightweight performance review | **COMPLETE**; no obvious CPU or memory regression in the available short replay observation; no optimization flag justified | Long-running production CPU/heap observation remains outstanding. |
| 3L Cleanup/security/closure | **COMPLETE** locally; no remaining compatibility migration or security regression identified | Hosted Jenkins/CodeQL result is pending. |

Per-phase security summary for 3D–3I: validation semantics **NO change**;
crypto/consensus semantics **NO change**; new untrusted-input path **NO**;
concurrency/resource bounds weakened **NO**; TLS/security properties weakened
**NO**; dependency/security downgrade **NO**; secrets committed or logged
**NO**. Relevant Java 25 unit tests pass; the integration exceptions below
are classified separately and were not concealed.

## Phase 3J — Mainnet / Testnet persisted-chain acceptance

The authoritative files were never opened by NIS/H2. Each was copied to a new
`/tmp` runtime directory before the production Spring/Hibernate `NisAppConfig`
startup and replay probe. Auto-boot, harvesting-on-boot, and network time were
disabled for deterministic offline chain loading. The probe ran in a fresh
Java 25 JVM twice per network against the same disposable runtime copy,
including a second open after normal Spring/H2 shutdown. It traversed every
block height and checked adjacent previous-hash links, initialized tip/height,
cache counts, Flyway state, shutdown, and surviving non-daemon threads.

| Network | Height / DB count | Genesis | Tip | Score | Account / state cache | Flyway / restart |
|---|---:|---|---|---:|---:|---|
| Mainnet | 2,001 / 2,001 | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `24964532368849513` | 1,377 / 1,377 | `1.0.7`, 8 applied, 0 pending; two startup/replay/shutdown passes |
| Testnet | 1,601 / 1,601 | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `79553937490635759` | 21 / 21 | `1.0.7`, 8 applied, 0 pending; two startup/replay/shutdown passes |

All heights and links passed on both passes. Spring context close, H2 shutdown,
and no-live-non-daemon-thread checks passed. These runs exercised production
NIS chain loading/state reconstruction against the supplied persisted data;
they did not start Jetty or auto-boot peers against these DBs. Existing local
controller/API integration tests and the two Peer network-simulator integration
tests passed. WebSocket SockJS/STOMP local handshake/message/shutdown evidence
is recorded in Phase 2I-H/P; Phase 3 did not change the web stack.

The original files' SHA-256 before and after the Phase 3 work and replay runs:

| Authoritative artifact | Before | After |
|---|---|---|
| `legacy/nis5_mainnet.mv.db` | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` |
| `legacy/nis5_testnet.mv.db` | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` |

Only disposable copies were mutated/opened. These checks establish compatibility
with the supplied Mainnet/Testnet persisted data and do not make a separate
claim about its independent external provenance.

### Network, API, and harvesting limits

- Local peer simulation passed both Peer integration tests. Direct synchronization
  with a public peer was not established: outbound access is unavailable here,
  and the integration peer `alice2.nem.ninja:7890` timed out. No network acceptance
  is claimed from that timeout.
- Controller/API acceptance tests on the repository's disposable loopback NIS
  passed. Public API or WebSocket behavior against live external peers was not
  required for the Phase 3 Java-only changes and was not independently exercised
  on the Mainnet/Testnet replay process.
- Harvesting-on-boot was disabled for the authoritative-data copies. The existing
  randomized BlockScorer simulation failures reproduced on the pre-Phase-3 Java 25
  baseline (see below); no harvesting, consensus, or block-scoring production code
  changed in Phase 3. Live production harvesting remains external operational
  validation.

## Integration failure classification

The current Phase 3 Java 25 NIS Failsafe report contains 79 tests, 8 failures,
2 errors, and 2 skips. Core Failsafe also has the existing parallel-verification
performance assertion. To distinguish Phase 3 regressions from baseline behavior,
selected failing tests were rerun on the Phase 3 parent Java 25 checkout
`7cadd7d86c3d00bc5282f9006773504b9e4563a0` with JDK 25.

| Failure | Current observation | Pre-Phase-3 Java 25 comparison | Classification |
|---|---|---|---|
| `ParallelVerifyPerfITCase.parallelVerifyIsFasterThanSequentialVerify` | Fails the assertion that parallel is >2x faster; Phase 3 run measured 229,104 vs 250,275 ns/transaction (about 1.09x). | Same assertion failed on parent `7cadd7d86`; crypto/verification source is untouched. | **5 — existing environment-sensitive performance assumption**, not a Phase 3 regression. |
| `DefaultHashCachePerformanceITCase` | Two of six 500 ms thresholds fail in the full NIS run. | On the Java 25 parent, three of six cases failed: `put` 596 ms, `putAll` 537 ms, and one copy-size assertion. The implementation was unchanged. | **5 — existing timing-sensitive performance checks** (plus an unchanged copy test failure), not a Phase 3 regression. |
| `TimeSynchronizationITCase.highPercentageOfNewNodesDoesNotHaveBigInfluenceOnNetworkTime` | One simulated-network mean-shift assertion failed. | The same class passed its selected parent run (20 tests, 1 pre-existing skip). | **5 — nondeterministic statistical simulation outcome**; results vary across runs on unchanged code. |
| Four `BlockScorerITCase` assertions | Failures involve one-vs-many harvesting and attacker/random-harvester chain wins. | The same four assertions failed on the Java 25 parent. | **2 / 5 — pre-existing randomized statistical harvesting assertions**, unrelated to Phase 3 code. |
| `HttpConnectorITCase` (one error, one failure) | Remote `alice2.nem.ninja:7890` timed out; the follow-up expected-address assertion received `InactivePeerException`. | Depends on an external public peer and outbound network availability. | **3 — external network dependency**, not a local protocol regression. |
| `MissingTransactionITCase` (one error) | Explicitly requires a separate NIS runtime-derived Mijin DB at `NIS_IT_MIJINNET_DB`. | No Mijin DB fixture was supplied; the Mainnet/Testnet artifacts are not valid substitutes. | **4 — missing external fixture**, not a Phase 3 regression. |

The parent comparison used unchanged Java 25 baseline source and no test
modifications. The timing and statistical assertions were not relaxed. The
remaining correctness-oriented local DAO/storage/controller/POI tests passed,
as did all Java 25 unit tests. No failure was identified as a Phase 3 bug.

## Phase 3K — lightweight runtime observation

One same-input observation compared Java 25.0.4.1 default G1 with the supported
but opt-in Compact Object Headers flag while loading and replaying the small
2,001-block disposable Mainnet-equivalent DB:

| Mode | Elapsed | User CPU | System CPU | Peak RSS |
|---|---:|---:|---:|---:|
| Java 25 defaults (G1) | 8.85 s | 23.88 s | 1.04 s | 417,892 KiB |
| G1 + Compact Object Headers | 9.48 s | 23.99 s | 1.05 s | 413,272 KiB |

This is a single lightweight startup/replay sample, not a representative
long-running production profile. RSS was slightly lower with compact headers,
while elapsed replay was slightly slower and CPU was effectively unchanged.
The difference does not justify a production option. No ZGC, heap, GC, or
security-property setting was changed. The project already uses fixed heap
settings in deployment examples; no new memory setting was introduced.

## Phase 3L — security and cleanup acceptance

Security checks for this closeout:

- **Validation semantics changed:** NO. Block, transaction, signature, and peer
  input validation implementations were not changed.
- **Crypto/consensus semantics changed:** NO. No crypto, block scoring, consensus,
  serialized bytes, chain mutation order, or persisted representation was changed.
- **New untrusted-input path:** NO. Peer data remains untrusted.
- **Concurrency/resource limits weakened:** NO. Scheduler remains bounded at five
  workers and five queued tasks; HTTP pool and timeout limits remain in place.
- **TLS/security properties weakened:** NO. No TLS, certificate, trust-store, or
  Java security property was changed. Redirects remain disabled.
- **New dependency/security issue:** NO dependency was added or downgraded. Maven
  repositories and compiler/annotation-processing plugins were not changed.
- **Secrets committed/logged:** NO. Temporary replay output stayed under `/tmp`;
  no keys, credentials, tokens, or authorization headers were written to the repo.
- **Container/CI:** the Java 25 builder/JRE and non-root `nem` runtime user remain;
  Java 25 CI and the CodeQL workflow remain enabled.
- **Static analysis:** CodeQL and standalone SpotBugs binaries are unavailable in
  this environment. The repository CodeQL workflow is present, but no hosted scan
  result was available. `git diff --check`, changed-file review, and the Java 25
  full unit suite passed.
- **Relevant tests:** Java 25 unit suite PASS — 6,229 tests, 0 failures, 0 errors,
  0 skipped (Core 2,364; Deploy 61; Peer 306; NIS 3,498). The Failsafe failures
  above remain visible and classified; none was suppressed.

The Java 25 runtime baseline, JDK delayed execution, bounded/shutdown-aware
scheduler, dependency set, Docker user, trust boundaries, and security scan
workflow were reviewed. No additional cleanup or optimization had a justified
benefit. Repository-local correctness, data compatibility, and security
acceptance are complete; hosted Jenkins/CodeQL and live external-network / Mijin
fixture checks remain pending external validation.
