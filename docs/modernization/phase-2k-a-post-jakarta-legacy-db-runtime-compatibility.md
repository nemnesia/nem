# Phase 2K-A — post-Jakarta legacy DB runtime compatibility

## Decision

**COMPLETE — POST-JAKARTA LEGACY DB RUNTIME COMPATIBILITY VALIDATED.** Both supplied candidates opened through the current production Spring `NisAppConfig` / Hibernate ORM 7 runtime on Java 17 and Java 25. Flyway found schema version `1.0.7`, eight applied migrations and zero pending migrations. The production `BlockAnalyzer` replay and complete `BlockDao` height traversal produced the same chain identities and replay state recorded by the pre-Jakarta Phase 2F runtime validation. Read-only logical fingerprints of both disposable databases were identical before and after the current runtime probes. The original candidate files retained their starting size, SHA-256 and modification time.

**Phase 2F provenance gate remains BLOCKED. This result proves software/runtime compatibility with the supplied database candidates only. It does not establish trusted Mainnet/Testnet snapshot provenance or final Phase 2K persisted-chain acceptance.**

## Repository and starting state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `8c5de3fec6972bb66ef10c81fafd6628b437bbc5`
- Actual starting HEAD: `8c5de3fec6972bb66ef10c81fafd6628b437bbc5` (matches request)
- Starting local HEAD == `origin/agent/nis-phase0-baseline`
- Starting worktree already had `.gitignore` adding `legacy/` and `nis/pom.xml` removing Hibernate's Byte Buddy exclusion. The `.gitignore` edit remains an unrelated uncommitted change. The POM edit was included in the follow-up build commit after runtime packaging confirmed that Hibernate 7's Byte Buddy provider must be present.
- No reset, rebase, stash, force push, or database artifact commit was performed.

## Candidate protection and identity

At start and completion, the original files had the following values. Their recorded modification times also matched at both checks; neither file was opened by H2, Flyway, Hibernate or NIS. H2 1.4.200 exported read-only SQL, which was imported into separate H2 2.2.220 disposable runtime directories outside the repository.

| Network | Candidate | Start / end size | Start / end SHA-256 | Start / end mtime |
|---|---|---:|---|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `2026-09-28 21:40:07.899431491 +0900` |
| Testnet | `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `2026-09-28 21:38:22.663025304 +0900` |

Phase 2F's read-only H2 1.4.200 fingerprints and current H2 2.2.220 conversion fingerprints compared equivalent for both candidates before runtime. The current runtime copies had these final physical file values after startup/shutdown:

| Network | Runtime copy size | Runtime copy SHA-256 |
|---|---:|---|
| Mainnet | 987,136 bytes | `15b634b0e9c36fdbdd3ca27c83b2b04adbe402b1b438db47ee5e69a501374e52` |
| Testnet | 483,328 bytes | `55cc24e281d2ed79548a308fcce3cc3afc35f2aae220aad22cc8144cf64c0cd7` |

H2 changed the physical bytes of the disposable H2 2.2 files during runtime use. The pre-runtime and post-runtime logical comparisons both returned `{"differences": {}, "equivalent": true}` against the original H2 1.4.200 fingerprints. This covers the 19 table schemas, row counts, deterministic per-table row digests, chain fingerprint and all eight Flyway history rows/checksums. No logical DDL, data, chain state or history mutation was observed. Physical copy hash changes are expected evidence of writable H2 open/close and are confined to `/tmp`.

## Runtime stack and execution

The current production runtime exercised was:

| Component | Version |
|---|---:|
| Java compile baseline | 17 (`--release 17`) |
| Spring Framework | 7.0.9 |
| Hibernate ORM | 7.2.25.Final |
| Hibernate Validator | 9.1.4.Final |
| Jakarta Persistence | 3.2.0 |
| Jakarta Servlet / WebSocket / Validation | 6.1.0 / 2.2.0 / 3.1.1 |
| Jetty | 12.1.13 EE11 |
| H2 / Flyway | 2.2.220 / 9.22.3 |

The opt-in `tools/Phase2fNisRuntimeProbe.java` was compiled against the clean reactor classes and packaged NIS runtime JARs, then run in a fresh JVM per network/JDK. It constructed production `AnnotationConfigApplicationContext(NisAppConfig.class)`, causing Spring's production DataSource, Flyway migration bean, Spring ORM `LocalSessionFactoryBuilder`, annotated NIS entities, Hibernate 7 `SessionFactory`, DAOs, cache and `NisMain`/`BlockAnalyzer` startup path to initialize. Peer auto-boot and harvesting were disabled; the embedded web listener was not started. The runtime directory was outside the repository.

For each candidate the production `BlockAnalyzer` replayed every persisted block through the production `BlockExecutor`/observers into transient caches. The probe then used production `BlockDao.count()` and read every height through `BlockDao.findByHeight()` in read-only transactional methods, checked continuity and every adjacent `prevBlockHash`, and checked the tip remained stable. The production block loading/replay also traversed persisted transaction/account relationships and populated account-state and namespace caches. All managed contexts and H2 databases closed, and the probe reported no remaining non-daemon threads.

### Mainnet

| Fingerprint | Phase 2F expected | Hibernate 7 observed (Java 17 and 25) |
|---|---|---|
| Network / marker | Mainnet / `0x68` | Mainnet / `0x68` |
| Genesis block hash | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | same |
| Height / persisted blocks | 2,001 / 2,001 | 2,001 / 2,001 |
| Tip block hash | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | same |
| Replay chain score | `24964532368849513` | same |
| Rebuilt account / account-state cache | 1,377 / 1,377 | 1,377 / 1,377 |
| Namespace cache | 1 | 1 |
| Full height/hash-link traversal | valid | valid |

The deterministic database fingerprint also observed 1,377 accounts, 1,377 transactions, 1,349 transfers, 22 importance transfers, 30 multisig modifications and 6 multisig signer modifications.

### Testnet

| Fingerprint | Phase 2F expected | Hibernate 7 observed (Java 17 and 25) |
|---|---|---|
| Network / marker | Testnet / `0x98` | Testnet / `0x98` |
| Genesis block hash | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | same |
| Height / persisted blocks | 1,601 / 1,601 | 1,601 / 1,601 |
| Tip block hash | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | same |
| Replay chain score | `79553937490635759` | same |
| Rebuilt account / account-state cache | 21 / 21 | 21 / 21 |
| Namespace cache | 1 | 1 |
| Full height/hash-link traversal | valid | valid |

The deterministic database fingerprint also observed 21 accounts, 20 transactions and 20 transfers.

## Flyway, schema and dependency audit

- Each Spring production startup reported Flyway current version `1.0.7`, 8 applied history rows and 0 pending migrations. `validateOnMigrate=false` is the production setting. Flyway ran no new migrations; no `repair`, baseline or history rewrite was used.
- The known Flyway 3.2.1 to 9.22.3 strict checksum-generation mismatch remains as documented by Phase 2F. The compatibility assumption remains that current production no-op `migrate()` ignores those historic checksum-generation differences and applies no migration to these candidates.
- No SQL migration or schema resource changed in this phase (`git diff -- nis/src/main/resources/db` was empty). Hibernate schema auto-update was not enabled by the production properties. Pre/post fingerprints confirmed no logical table/column creation or rewrite.
- The reactor-aware production runtime dependency tree resolved Spring 7.0.9, Hibernate ORM 7.2.25.Final, Jakarta Persistence 3.2.0 and Jetty EE11 12.1.13. Hibernate's `byte-buddy` 1.17.8 runtime dependency is present in the package; the pre-existing uncommitted POM edit removing its exclusion is required for that runtime packaging.
- Runtime graph and `nis/target/libs` audit found no migrated `javax.persistence`, `javax.servlet`, `javax.websocket` or legacy Bean Validation API; no Spring 5/6, Hibernate 5/6 or Jetty EE8 runtime. Java SE `javax.sql` is allowed. No dependency version was changed in Phase 2K-A.

## Regression and limitations

| JDK | Command | Result |
|---|---|---|
| Java 17.0.20.1 | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 17.0.20.1 | `mvn -B clean package` | PASS |
| Java 25.0.4.1 | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 25.0.4.1 | `mvn -B clean package` | PASS |

Counts match the Phase 2J post-merge baseline; no test source was added. The first Java 17 test attempt without local loopback permission failed when WireMock could not bind its local server; the same requested full command passed after local loopback was permitted. Java 25 Maven printed the known Guava `sun.misc.Unsafe` terminal-deprecation warning; it was not a persistence or test failure. Hibernate's existing H2 dialect advisory warning was also observed.

This validates the current Hibernate/Spring persistence runtime against these exact supplied candidates and confirms state interpretation against the recorded Phase 2F result. It does not independently authenticate their source, custody, quiescence, snapshot time or representativeness. The supplied candidate genesis and internal chain shape do not close that provenance gap. This phase did not repeat public network checkpoint research, an independent consensus/signature audit, or start networking/web transports.

## Git and gate status

- Phase 2K-A evidence is documentation-only; no source, POM or schema changes were made for it.
- The existing `.gitignore` edit remains uncommitted and was excluded. The pre-existing `nis/pom.xml` Byte Buddy exclusion removal is included in the follow-up build commit. No `legacy/` artifact was staged.
- Final local/origin branch status and commit SHA are recorded in the task completion report.
- Phase 2F provenance gate: **BLOCKED — trusted snapshot provenance remains unavailable**.
- Phase 2K final persisted-chain acceptance: **NOT ACCEPTED / NOT THIS PHASE**. This compatibility result cannot substitute for authenticated provenance and the designated final persisted-chain acceptance evidence.
