# Phase 2F — supplied database runtime / chain replay validation

## 判定

**COMPLETE — SUPPLIED DB CANDIDATE RUNTIME COMPATIBILITY VALIDATED**

Mainnet candidate と Testnet candidate のそれぞれについて、H2 2.2.220 上で production の Spring `NisAppConfig` を起動した。Flyway と Hibernate の初期化後、`NisMain` の通常の startup path が `BlockAnalyzer.analyze` を実行し、保存済み chain の全 block を genesis から tip まで `BlockExecutor` / transaction observers に通して transient NIS cache を再構築した。runtime が認識した tip、height、chain score、cache 状態は各 DB candidate の値と整合し、追加の全-height DAO traversal でも欠落 block と previous-block hash の不一致はなかった。

これは **提供された2つの candidate の runtime compatibility** の判定である。取得元、quiesced snapshot、trusted snapshot height/tip の外部証拠は依然ない。そのため、より広い **Phase 2F Mainnet / Testnet real-database authenticity / rollout gate は `BLOCKED — TRUSTED REAL-DATABASE PROVENANCE REQUIRED` のまま**であり、今回の結果では解除しない。

## Repository / environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `eb6adb30f1e126fdc1d0f3b7847217b807585e7a`
- Starting local HEAD == `origin/agent/nis-phase0-baseline`
- Java 17: OpenJDK `17.0.20.1`
- Java 25: OpenJDK `25.0.4.1`
- Production stack exercised: H2 `2.2.220`, Flyway `9.22.3`, Hibernate ORM `5.4.33.Final`, Hibernate Validator `6.2.5.Final`, Spring `5.3.39`
- No production source, dependency, POM, schema, migration script, protocol, consensus, or network constant was changed.

### Provided candidates

| Candidate | Relative path | Bytes | Start / end SHA-256 | Internal evidence |
|---|---|---:|---|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | marker `0x68`; production-resource genesis hash match; height 2,001; tip `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` |
| Testnet | `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | marker `0x98`; production-resource genesis hash match; height 1,601; tip `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` |

The supplied files remained untracked user-provided artifacts. The probe never passed their paths to H2, Flyway, Hibernate, or NIS. They were copied to a new `/tmp` workspace before conversion and runtime startup. Their hashes at the end remained byte-for-byte equal to the starting hashes above.

As recorded in [the prior artifact follow-up](phase-2f-real-db-artifacts-followup.md), the only known external provenance is that these files were supplied under `legacy/`. Trusted source/node, acquisition and snapshot dates, quiescence, and independently trusted snapshot checkpoints remain unknown. Internal marker/genesis/chain evidence is not a substitute for that provenance.

## Runtime path and what was replayed

Each converted DB was copied once more into an isolated runtime folder, named according to the matching network, and started in its own JVM. The classpath put an external, temporary `config.properties` first; it set the matching `nem.network`, disposable `nem.folder`, `nis.shouldAutoBoot=false`, `nis.shouldAutoHarvestOnBoot=false`, `nis.delayBlockLoading=false`, and `nis.useNetworkTime=false`.

The probe constructed `AnnotationConfigApplicationContext(NisAppConfig.class)`, not a mock persistence context. This initialized the production NIS bean graph, production DataSource, Flyway bean (`migrate()` init method), Hibernate `SessionFactory`, DAOs, `BlockAnalyzer`, cache, and `NisMain`. `NisMain`'s `@PostConstruct` loaded the network's production nemesis and synchronously joined the normal `BlockAnalyzer` startup analysis. With auto-boot disabled, it did not call peer-network boot. The embedded Jetty/HTTP listener was not started; no REST server, peer discovery, synchronization, harvesting, or external callback was run.

In `BlockAnalyzer`, every persisted block is mapped from `DbBlock`, passed to `BlockExecutor.execute` with production transaction observers, and applied to an initially empty copy of the NIS cache. NIS recalculated importances, updated the last-block layer and chain score, then committed the reconstructed cache. The probe additionally read **every height** from 1 through the stored tip using `BlockDao`, checked exact height continuity, and verified each block's stored hash equals the next block's `prevBlockHash`.

This is the production NIS **persisted-block startup replay / cache reconstruction** path for the supplied blocks. It is not an independent consensus/signature audit, a network synchronization test, a REST/WebSocket test, or proof that the source snapshot is authentic. No newly replayed state was persisted to the database.

## Results

### Mainnet candidate

| Measurement | Result |
|---|---|
| Configured / observed network | `mainnet` / Mainnet |
| Genesis hash | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` |
| Persisted blocks / DAO count | 2,001 / 2,001 |
| Runtime initialized height | 2,001 |
| Runtime tip hash | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` |
| Runtime accumulated chain score | `24964532368849513` |
| Rebuilt account / account-state cache | 1,377 / 1,377 |
| Rebuilt namespace cache | 1 |
| Full DAO traversal / adjacent hash links | pass / all valid |
| Runtime load flag | completed (`loaded=true`) |

The converted DB had 1,377 accounts, 1,377 transactions, 1,349 transfers, 22 importance transfers, 30 multisig modifications, and 6 multisig signer modifications.

### Testnet candidate

| Measurement | Result |
|---|---|
| Configured / observed network | `testnet` / Testnet |
| Genesis hash | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` |
| Persisted blocks / DAO count | 1,601 / 1,601 |
| Runtime initialized height | 1,601 |
| Runtime tip hash | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` |
| Runtime accumulated chain score | `79553937490635759` |
| Rebuilt account / account-state cache | 21 / 21 |
| Rebuilt namespace cache | 1 |
| Full DAO traversal / adjacent hash links | pass / all valid |
| Runtime load flag | completed (`loaded=true`) |

The converted DB had 21 accounts, 20 transactions, and 20 transfers; the remaining tracked state tables were empty as recorded in the previous artifact follow-up.

### H2 / Flyway / mutation audit

The runtime copy used the current production H2 URL flags `MODE=LEGACY;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1`. Both Spring startup paths completed; logs identified Hibernate ORM `5.4.33.Final` and Hibernate Validator `6.2.5.Final`.

At runtime, Flyway reported current version `1.0.7`, 8 applied history rows, and 0 pending migrations. The production configuration uses `validateOnMigrate=false`; no new migration was applied. Before/after fingerprints were captured with the existing `tools/Phase2fDbFingerprint.java` and compared with `tools/phase2f_db_validation.py compare-fingerprints`. Both comparisons returned `{"differences": {}, "equivalent": true}`. This includes all 19 table schemas, row counts and deterministic row digests, block/genesis/tip state, and all eight `schema_version` rows/checksums. The known Flyway 3 vs Flyway 9 strict checksum-generation difference remains; there was no `repair`, history rewrite, or metadata edit.

The H2 `.mv.db` physical hashes **did change** after the writable runtime open and explicit H2 shutdown, while logical fingerprints remained identical:

| Network | Converted copy before runtime: bytes / SHA-256 | Runtime copy after startup/shutdown: bytes / SHA-256 |
|---|---|---|
| Mainnet | 942,080 / `992a48403aaf10fa2b0aacbf2a7935076990eabee26614eac57cdebb4aa5e582` | 991,232 / `fe7c375809e987d24684739316e016d1f9cd49755c13e88e8e243d109e6c962f` |
| Testnet | 454,656 / `ede0c684bcde4ca7b9e3c53ce9ec232a397c64b99da3f5e0c6cc750e9efcab32` | 499,712 / `aafc7af40c9515e8089aeef7643b291c72211d56ec3f1835b15e1cd23706de4` |

Those are disposable copy hashes only. The persistent file bytes changed during writable H2 runtime use; the audit establishes that no logical schema, history, row, chain, or table-digest change was observed. H2 also created a `.trace.db` diagnostic sidecar under the temporary runtime folder. No runtime database or sidecar was written into the repository.

## Reproducible probe

The opt-in probe is `tools/Phase2fNisRuntimeProbe.java`. It requires the repository's normal built module classes/runtime jars and an external config directory placed first on the classpath. Prepare converted disposable H2 2.2.220 files using the copy-only process from [the artifact follow-up](phase-2f-real-db-artifacts-followup.md); do not point the runtime at `legacy/`.

Example for one disposable Mainnet copy (repeat with Testnet and matching values):

```bash
WORK=/tmp/nis-runtime-mainnet
mkdir -p "$WORK/config" "$WORK/runtime/nis/data" "$WORK/classes"
cp /path/to/converted/nis5_mainnet.mv.db "$WORK/runtime/nis/data/nis5_mainnet.mv.db"
cat > "$WORK/config/config.properties" <<EOF
nem.folder=$WORK/runtime
nem.network=mainnet
nis.shouldAutoBoot=false
nis.shouldAutoHarvestOnBoot=false
nis.delayBlockLoading=false
nis.useNetworkTime=false
EOF
MODULE_CLASSES=$(find . -type d -path '*/target/classes' -print | paste -sd: -)
RUNTIME_CP="$WORK/config:$MODULE_CLASSES:nis/target/libs/*"
javac -cp "$RUNTIME_CP" -d "$WORK/classes" tools/Phase2fNisRuntimeProbe.java
timeout 30s java -cp "$WORK/classes:$RUNTIME_CP" Phase2fNisRuntimeProbe mainnet
```

The class refuses a runtime data directory inside the repository or an auto-boot configuration. It follows production Spring startup and verifies chain loading, all-height DAO continuity/linkage, Flyway status, and cache/score results. Cleanup closes the peer scheduler and the otherwise unmanaged `HttpConnectorPool` async client (via test-tool-only reflection), closes Spring, and issues H2 `SHUTDOWN` on the disposable copy. The probe asserts that no non-daemon thread remains. It must run in a fresh JVM for each candidate.

## Validation

- Java 17 runtime probe: Mainnet and Testnet pass; each process exit status 0; Spring close, H2 shutdown, and `NON_DAEMON_THREADS=none` observed.
- Java 25 runtime probe: Mainnet and Testnet pass with the same hashes/heights/scores/cache counts; each exits successfully with no remaining non-daemon threads.
- Probe compilation: `javac` against the built module classes and `nis/target/libs/*` succeeded; the actual class in `tools/Phase2fNisRuntimeProbe.java` was compiled and run on both JDKs.
- Existing Phase 2F tooling unit tests: `PYTHONPATH=tools python3 -m unittest -v tools/test_phase2f_db_validation.py` — 21 passed.
- The first unit-test invocation without `PYTHONPATH=tools` failed only to import the sibling tooling module; rerunning with the repository's tool directory on `PYTHONPATH` passed all 21 tests.
- Full `mvn clean test` / `mvn clean package` and hosted CI were not run: changes are an opt-in diagnostic tool and documentation only, with no production Java, Maven, dependency, or normal test lifecycle change. Runtime behavior was directly exercised by the tool on Java 17 and 25.

## Remaining gates

- Supplied Mainnet and Testnet candidate runtime compatibility: **validated independently for each candidate** as described above.
- External artifact provenance, quiesced snapshot proof, and trusted snapshot height/tip checkpoint: **not supplied**.
- Phase 2F real Mainnet/Testnet database authenticity and rollout gate: **BLOCKED** until provenance-verified artifacts and trusted checkpoints are provided and accepted. Runtime compatibility does not establish authenticity.
- No Spring 6, Hibernate 6, Jakarta, or database-engine modernization should use these candidate-only results as a substitute for the blocked gate.
