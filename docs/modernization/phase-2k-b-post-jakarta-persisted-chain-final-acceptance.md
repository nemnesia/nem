# Phase 2K-B — post-Jakarta persisted-chain final acceptance

## Decision

**COMPLETE — POST-JAKARTA PERSISTED-CHAIN FINAL ACCEPTANCE VALIDATED.** On disposable H2 2.2.220 copies of both operator-supplied, real NIS runtime-derived databases, the production Spring 7 / Hibernate ORM 7 bootstrap reopened and replayed the persisted chain across independent Java processes. A production `DbAccount` persistence marker committed through Spring's Hibernate transaction manager became visible to a separate transaction and survived process/context/H2 shutdown and reopen. A flushed marker rolled back and remained absent after restart. After the committed marker was removed, the complete logical database fingerprint returned to baseline. Mainnet and Testnet chain replay, Flyway history and chain invariants remained unchanged through the lifecycle on Java 17 and Java 25.

The supplied Mainnet and Testnet databases are real NIS runtime-derived artifacts produced by actual NIS operation. Their software/persistence lifecycle compatibility is tested here. Phase 2F remains blocked only on externally verifiable trusted provenance and custody evidence; that separate provenance gap does not block this Phase 2K acceptance.

## Repository and Git baseline

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `d825fba8fd9349713c08dbd16ffd0ad9e3fb7b19`
- Actual starting HEAD: `5055531721159e3386346f324f86a79f0ae4e38c` (differs from request)
- Local HEAD matched `origin/agent/nis-phase0-baseline` at start.
- The actual starting HEAD is the rewritten Phase 2K-A commit chain produced before this phase at the user's explicit request to correct the prefix-free commit subject. Its commit trees match the prior chain; only commit IDs/messages and their parent link differ.
- Starting worktree had the existing `.gitignore` edit adding `legacy/`; it was preserved and not staged.
- Phase 2K-B validation implementation commit: `f159ecdddd376fc697305b973f545c59465c49a0`. The evidence-record commit follows; final repository HEAD is reported in the completion response.
- Final tested source HEAD (before the evidence-only commit): `f159ecdddd376fc697305b973f545c59465c49a0`.

## Original artifacts and baseline

Per the operator's supplied artifact description, these are real database files produced/updated by actual NIS operation, not synthetic chain fixtures. H2 1.4.200 accessed each original only for read-only fingerprint/export; Flyway, Hibernate and NIS runtime/write probes used disposable copies under `/tmp/phase2kb-lifecycle`. Converted H2 2.2.220 copies were made separately for Java 17 and Java 25.

Original size, SHA-256 and mtime were recorded at both phase start and completion:

| Network / file | Start / end size | Start / end SHA-256 | Start / end mtime |
|---|---:|---|---|
| Mainnet `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `2026-09-28 21:40:07.899431491 +0900` |
| Testnet `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `2026-09-28 21:38:22.663025304 +0900` |

The read-only H2 1.4.200 baseline fingerprints observed:

| Network | Marker | Genesis | Height / blocks | Tip | Flyway |
|---|---|---|---:|---|---|
| Mainnet | `0x68` | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | 2,001 / 2,001 | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `1.0.7`, applied 8, pending 0 |
| Testnet | `0x98` | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | 1,601 / 1,601 | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `1.0.7`, applied 8, pending 0 |

The deterministic inventory also observed Mainnet 1,377 accounts, 1,377 transactions, 1,349 transfers, 22 importance transfers, 30 multisig modifications and 6 multisig signer modifications; Testnet had 21 accounts, 20 transactions and 20 transfers. Both legacy Flyway histories contain eight successful rows and have no pending migrations.

## Runtime and lifecycle method

The runtime used the current production stack: Spring Framework `7.0.9`, Hibernate ORM `7.2.25.Final`, Hibernate Validator `9.1.4.Final`, Jakarta Persistence `3.2.0`, Jetty `12.1.13` EE11, H2 `2.2.220`, Flyway `9.22.3`, and Hibernate's packaged Byte Buddy runtime `1.17.8`. Java runtimes were OpenJDK `17.0.20.1` and `25.0.4.1`.

The opt-in tool `tools/Phase2kPersistedChainLifecycleProbe.java` starts production `AnnotationConfigApplicationContext(NisAppConfig.class)` in a fresh process for each lifecycle phase. This initializes the production DataSource, Flyway migration bean, Spring ORM `LocalSessionFactoryBuilder`, NIS entity mappings, Hibernate `SessionFactory`, DAOs, caches, `NisMain` and full `BlockAnalyzer` replay. Peer auto-boot and harvesting are disabled. Each process closes the network resources, Spring context/SessionFactory and H2 database, then reports no surviving non-daemon threads.

For each network and JDK, these separate JVM phases were run in order:

1. `write`: full chain recovery; commit one isolated `DbAccount` marker using production `HibernateTransactionManager`/`TransactionTemplate`; verify it is visible within its writing transaction but invisible from an independent session before commit; verify it through production `AccountDao` after commit. Persist and flush a second marker, verify it remains invisible to another transaction, then mark the Spring transaction rollback-only and verify the row is absent.
2. `verify`: shut down and reopen the app and H2 in a new JVM; full chain recovery; verify the committed marker exists and the rolled-back marker does not.
3. `cleanup`: another new JVM and full recovery; remove the committed marker in a committed transaction; verify it is absent.
4. `verify-clean`: another new JVM and full recovery; verify both markers remain absent.

The marker is a single temporary row in the existing production `accounts` entity/table on disposable copies only. It is not referenced by any block or transaction. No block, transaction, balance, consensus state, migration SQL, schema or production chain data was fabricated or modified. The committed account row is removed before final baseline comparison.

## Results

Every process reported the same production replay baseline:

| Network | Genesis | Height | Tip | Replay chain score | Account / account-state cache | Namespace cache | Block links |
|---|---|---:|---|---:|---:|---:|---|
| Mainnet | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | 2,001 | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | `24964532368849513` | 1,377 / 1,377 | 1 | full height/link scan valid |
| Testnet | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | 1,601 | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | `79553937490635759` | 21 / 21 | 1 | full height/link scan valid |

For each network/JDK pair, the complete post-commit logical fingerprint was identical before and after the separate-process `verify` reopen. The transient `accounts` count was 1,378 on Mainnet and 22 on Testnet while the marker existed; block count, transaction count, other table row counts/digests, Flyway rows and chain identity remained unchanged. After cleanup and again after `verify-clean`, all logical table schemas, row counts, row digests, chain values and Flyway history matched the original baseline. H2 product/driver version fields were excluded from logical-equivalence comparison because the comparison is from H2 1.4.200 source format to the H2 2.2.220 runtime.

The copy's physical bytes did change during H2 2.2.220 opens, transactions and clean shutdowns; these hashes are **disposable-copy only**:

| Network | Converted baseline copy size / SHA-256 | Java 17 final copy size / SHA-256 | Java 25 final copy size / SHA-256 |
|---|---|---|---|
| Mainnet | 942,080 / `47dd52f59eeb8a7be97cec2511c5e88d10554a41472d1f7e956aa275cd1c31eb` | 1,028,096 / `46c62ab5158c588dcc1c32485ed9ba167e685778987a0986dc2169469f228b20` | 1,028,096 / `6a421083d767acf8cb1463521ef498a68906b22564108f3eeb4bb9838945aaa7` |
| Testnet | 454,656 / `4f5ddbdceb4e4a91b63b4f7a286c99aec49736f1c1124c13b0f27c04f2b2bb9e` | 528,384 / `cbbf5efdebbfdd35d1fabb36e4ccadc4a93bf999c39700858c6265362d0a9496` | 528,384 / `3142eed9e9a788a3b5bac506446f2c2473a7004fa0c5d3913a405ed3e1e65569` |

The physical file hashes differ across runs/JDKs, while the logical fingerprints are equivalent. Original hashes/sizes/mtimes remained unchanged.

### Flyway and schema

All lifecycle process starts across two complete run cycles (32 process starts: four phases × two networks × two JDKs × two cycles) reported Flyway `1.0.7`, 8 applied migrations and 0 pending migrations. Flyway executed no migration. No repair, baseline or history rewrite was used. The known Flyway 3.2.1 / 9.22.3 strict checksum-generation difference remains unchanged. `git diff -- nis/src/main/resources/db` is empty; Hibernate schema auto-update is not enabled, and post-clean fingerprints show no DDL/schema or history changes.

## Full regression and runtime graph

| JDK | Command | Result |
|---|---|---|
| Java 17.0.20.1 | `mvn -B clean test` | PASS: 6,227; failures 0, errors 0, skipped 0 |
| Java 17.0.20.1 | `mvn -B clean package` | PASS on final rerun |
| Java 25.0.4.1 | `mvn -B clean test` | PASS: 6,227; failures 0, errors 0, skipped 0 on final rerun |
| Java 25.0.4.1 | `mvn -B clean package` | PASS |

Two timing-sensitive failures occurred on earlier attempts: Java 17 `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance` failed once and passed isolated before the full package rerun passed; Java 25 `AsyncTimerTest.visitorIsNotifiedOfStops` failed once, passed isolated, then the full clean test and package passed. Neither involved persistence code. The Java 25 Maven launcher emitted the known Guava `sun.misc.Unsafe` terminal-deprecation warning.

The reactor runtime tree and packaged `nis/target/libs` resolve Spring `7.0.9`, Hibernate `7.2.25.Final`, Jakarta Persistence `3.2.0`, Servlet `6.1.0`, WebSocket `2.2.0`, Jetty EE11 `12.1.13`, and Byte Buddy `1.17.8`. No migrated `javax.persistence`, `javax.servlet`, `javax.websocket` or legacy Bean Validation API; Spring 5/6; Hibernate 5/6; Jetty EE8; legacy Hibernate integration package; or duplicate Persistence/Servlet/WebSocket API was found. Byte Buddy is present in the packaged runtime and the four-JVM lifecycle probes exercised it successfully.

Runtime-only warnings were the configured H2Dialect advisory, classpath metadata/certificate warnings because the opt-in probe runs from reactor classes rather than a signed distribution JAR, and NIS's existing startup clock log. No persistence exception or new classpath warning occurred in successful probes.

## Verdicts and remaining boundary

- Phase 2K persisted-chain software/runtime acceptance: **COMPLETE** for these exact real NIS runtime-derived Mainnet and Testnet artifacts. Open, replay, isolated commit/rollback, clean close, independent-process reopen, marker cleanup, and final logical baseline recovery all passed on Java 17 and Java 25.
- Phase 2F trusted provenance/custody gate: **BLOCKED** pending independently verifiable trusted source, custody, quiescence and snapshot/checkpoint evidence. This is independent of the Phase 2K result.
- The artifacts are real runtime-derived data; this validation does not authenticate their external provenance. No DB artifact was staged or committed.
- The pre-existing `.gitignore` `legacy/` edit was left uncommitted and untouched.
