# Phase 3A–3B — Java 25 compile baseline and concurrency inventory

## Scope

The production compiler release is now Java 25 in Core, Deploy, Peer, and NIS.
This ends Java 17 bytecode compatibility as a supported build target. The
existing compiler plugin is Maven Compiler Plugin 3.15.0 in each module, and
the project already runs against the Java 25 runtime baseline established in
Phase 2. No dependency, compiler plugin, security property, or protocol setting
was changed for this step.

## Concurrency inventory

| Area | Classification | Existing shape | Java 25 direction |
|---|---|---|---|
| Peer HTTP requests | I/O-bound | Apache HttpAsyncClient 4.x in `HttpMethodClient`, surfaced as `CompletableFuture` by `DefaultAsyncNemConnector` | Candidate for focused simplification in Phase 3E; preserve pool bounds, timeout, cancellation, TLS, and peer error mapping. |
| Peer discovery and refresh | I/O-bound | `PeerNetwork.refresh`, node refresher and connector futures perform peer requests | Candidate for virtual threads only if it simplifies the existing bounded HTTP path; do not add per-peer unbounded task creation. |
| Network synchronization | Mixed, predominantly I/O-bound with ordered state changes | `PeerNetwork.synchronize` invokes `NodeSynchronizer`; block acquisition is remote, while accepted block processing mutates chain state | Keep block processing and state mutation ordered. Any virtual-thread use must be confined to blocking acquisition and retain existing peer/resource limits. |
| Peer broadcasts / time and endpoint requests | I/O-bound | CompletableFuture fan-out through peer connector services | Existing asynchronous HTTP path is already non-blocking; virtual threads are not an automatic improvement. Preserve fan-out limits and cancellation behavior. |
| Database access | Blocking I/O | Persistence is accessed through Hibernate/JPA DAO layers | Virtual-thread candidate only after identifying concrete blocking call paths and checking connection-pool capacity. No broad parallelization. |
| NIS recurring timers | Scheduling | `AsyncTimer` chains `SleepFuture`; `SleepFuture` previously used one daemon `java.util.Timer` | Phase 3C change: use `CompletableFuture.delayedExecutor` with direct execution on the JDK delay thread to preserve continuation thread behavior. Negative delays still fail immediately. |
| Scheduler-launched NIS tasks | Mixed | `PeerNetworkScheduler` previously used an unbounded cached thread pool for selected recurring work | Phase 3C change: cap workers at five (the five scheduler task suppliers that dispatch through this executor), cap the pending queue at five, reject overload, and shut the executor down with the scheduler. No virtual threads are introduced. |
| Harvesting | CPU-bound | `HarvestingTask.harvest` is launched from scheduler work | Do not virtual-thread or increase parallelism as an optimization. Retain ordering and existing scheduling cadence. |
| Validation and cryptography | CPU-bound / consensus-critical | Block and transaction validators, signature checks, hashing and chain-state application | Excluded from concurrency modernization. Validation, cryptographic, serialization, and consensus semantics remain unchanged. |
| REST / WebSocket request handling | Mixed | Spring MVC and Jetty request handling | Inventory only; no server execution model change planned without measured resource evidence and request-boundary review. |

## Current phase checks

- Security semantics: unchanged; no validation, crypto, consensus, TLS, or
  resource-limit behavior was changed.
- Dependency and build integrity: no dependency or plugin change.
- Java 25 `clean compile`: PASS for all four modules with no compiler warnings.
- Java 25 `test-compile`: PASS for all test sources with no compiler warnings.
- Java 25 full unit suite: PASS, 6,228 tests, 0 failures/errors/skips across
  Core (2,364), Deploy (61), Peer (306), and NIS (3,497).
- Focused Core regression tests: PASS, 42 tests. The final
  `PeerNetworkSchedulerTest` suite passes 6 tests after adding a close-state
  guard.

## Java 25 persisted-chain replay

The production Spring/Hibernate replay probe was recompiled against the Phase 3
Java 25 module classes and run in separate JVMs on disposable copies of the
converted Mainnet and Testnet H2 databases. Auto-boot, harvesting, and network
time were disabled. Both copies loaded all blocks, matched prior accepted
heights/tips/state, passed full adjacent-block linkage checks, and reported
Flyway 1.0.7 with 8 migrations applied and none pending. Spring context close,
H2 shutdown, and the no-live-non-daemon-thread check all passed.

| Network | Height | Tip | Score | Account / state cache | Result |
|---|---:|---|---:|---:|---|
| Mainnet | 2,001 | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `24964532368849513` | 1,377 / 1,377 | PASS |
| Testnet | 1,601 | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `79553937490635759` | 21 / 21 | PASS |

The ignored original `legacy/nis5_mainnet.mv.db` and
`legacy/nis5_testnet.mv.db` were not opened or copied into the runtime. Their
SHA-256 values before and after the probes remained
`8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` and
`23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87`.
This establishes Java 25 compatibility for the converted copies; it does not
resolve the Phase 2F candidate-provenance limitation.

## Integration results

The repository-owned `nis/scripts/ci/test.sh` was run under Java 25. It created
and then removed its disposable, genesis-only Testnet runtime. NIS Failsafe ran
79 tests: 8 failures, 2 errors, and 2 skipped. Controller tests, the H2 storage
test, transfer DAO test, block DAO test, and POI integration tests passed. The
remaining results were:

- 2 `DefaultHashCachePerformanceITCase` throughput assertions failed.
- 1 time-synchronization timing assertion failed.
- 4 `BlockScorerITCase` randomized harvesting assertions failed.
- `HttpConnectorITCase` could not reach `alice2.nem.ninja`; the other selected
  Testnet peers responded. Its expected-address assertion also failed because
  that peer timed out.
- `MissingTransactionITCase` needs a separate NIS-derived Mijin DB that was not
  supplied.

In the root Java 25 reactor run, Core Failsafe passed the sparse-matrix and
block-cipher stress cases, but the existing `ParallelVerifyPerfITCase`
assertion requiring a greater-than-2× parallel speedup failed. This host
measured parallel verification at 229,104 ns/transaction and sequential
verification at 250,275 ns/transaction (about 1.09×). Peer Failsafe's two
network simulator tests passed. `failsafe:verify` correctly reported the Core
failure; the NIS CI script also invokes it and correctly failed after
reporting its Failsafe results. No test was skipped or weakened.

## Follow-on evaluation

- Virtual threads: not adopted. Peer HTTP is already asynchronous through the
  bounded Apache client, and database calls need connection-pool capacity
  tracing before they can be converted safely. Chain mutation, validation,
  cryptography, hashing, and harvesting stay on their existing execution paths.
- HTTP client: retain Apache HttpAsyncClient 4.1.5. The Phase 2 dependency audit
  found no Java 25 blocker; changing to JDK `HttpClient` would replace the
  existing connection pool, cancellation, timeout, TLS, and error-mapping
  behavior without a demonstrated simplification or peer compatibility test.
- JVM: Java 25.0.4.1 selects G1 ergonomically. Compact Object Headers are
  available but disabled by default and can be enabled with
  `-XX:+UseCompactObjectHeaders`; no option change is proposed without a
  Mainnet/Testnet heap and CPU comparison. No explicit GC or security-property
  flags are added.
- AOT: no archive/training workflow is added. NIS is long-running, and no
  startup measurement demonstrates a benefit that justifies deployment
  complexity.
- Allocation/cache cleanup and broader source modernization: no concrete
  hotspot was evidenced in this baseline. The changes so far are limited to
  removal of compiler-deprecated APIs and timer/executor lifecycle cleanup.
- Security review: validation, crypto, consensus, TLS, serialization, database
  constraints, and peer trust semantics were not modified. The executor now has
  a five-thread cap and a five-task queue; overload is rejected and recorded by
  the existing async-timer failure path. GitHub Actions Java 25 and CodeQL
  workflows remain; the Java 17 compatibility workflow was removed.

## Remaining production acceptance

Mainnet/Testnet database replay, NIS boot/restart/shutdown against accepted
chain data, peer synchronization, API/WebSocket behavior, harvesting, and
lightweight production CPU/heap comparison remain necessary before calling
Phase 3 complete. They require provisioned chain databases and permitted
network access that are not part of this local compile/test run.
