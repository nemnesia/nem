# Phase 2F follow-up — supplied Mainnet/Testnet database candidates

## 判定

**PARTIAL — 両 candidate の H2/Hibernate/Flyway 読み取り互換性は確認したが、Phase 2F real-database gate は解除しない。**

内部の network marker と genesis block hash は、それぞれ NIS production の Mainnet / Testnet genesis resource から独立に計算した値に一致した。両方とも H2 1.4.200 で open でき、H2 2.2.220 の disposable conversion 後も19表すべての schema / row count / deterministic digest が一致した。Flyway 3 history は8件すべて成功状態で、legacy checksum が監査可能な Git migration blob に一致した。Flyway 9 の production-equivalent `validateOnMigrate=false` migration は0件適用で成功し、NIS `BlockDaoImpl` から height 1 と tip を読み出せた。

一方、DBの名前以外に取得元、取得者、取得日時、quiesced snapshot の証拠、既知 snapshot height/tip checkpoint がない。genesis が本物でも、genesis 後の全 chain data が Mainnet/Testnet の実 snapshot から取得されたことや、代表 snapshot であることは証明しない。既存の Phase 2F gate は provenance-verified artifacts を要求しているため、今回の内部互換性結果は強い技術証拠として追加するが、実データ rollout gate は **BLOCKED のまま**とする。Phase 2F の過去の BLOCKED 記録は変更していない。

## Repository / 実行環境

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `5eb72df649e22703567572381f318b74e094d1cd`
- Starting local HEAD and `origin/agent/nis-phase0-baseline`:一致
- Java 17: OpenJDK `17.0.20.1`
- Java 25: OpenJDK `25.0.4.1`
- H2 source format: H2 `1.4.200`（read-only JDBC metadata）
- H2 converted runtime: `2.2.220`
- Hibernate ORM: `5.4.33.Final`
- Hibernate Validator used by the NIS DAO probe: `6.2.5.Final`
- Flyway: `3.2.1` legacy history semantics / `9.22.3` current runtime
- Existing Phase 2F read-only fingerprint, Git history audit, and copy-only conversion procedureを再利用。DB artifact や generated conversion output は Git に追加していない。

## Artifact inventory / provenance

`legacy/` には下記2ファイルのみがあり、両方とも開始時点で untracked、未変更のまま保持した。直接 H2 接続したのは `/tmp` に作った disposable copy のみ。source artifact は fingerprint / SHA-256 取得以外には使用していない。

| candidate | repository-relative path | bytes | source SHA-256 | H2 format/version |
|---|---|---:|---|---|
| Mainnet候補 | `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | H2 MVStore, metadata `1.4.200 (2019-10-14)` |
| Testnet候補 | `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | H2 MVStore, metadata `1.4.200 (2019-10-14)` |

既知の external provenance は「repository の `legacy/` に配置された」だけ。source owner/node/version、acquisition date、snapshot date、quiescence、trusted snapshot height/hash、元の完全な database file inventory は不明であり、受理済み production manifest は作成していない。artifacts が repository 内に置かれているため、Phase 2F tooling の `inventory` / `prepare-copy` が要求する outside-repository source policyにも適合しない。コピー作成と照合は既存 tooling と同等の hash 手順で `/tmp` 上に行った。

H2 history の `installed_on` は Mainnet が `2026-09-28 21:39:04`、Testnet が `2026-09-28 21:36:50` 頃に集中し、`installed_by` は空だった。これらは観測値であり、作成元や実データ性を推論する根拠にはしていない。

## 独立 network / genesis 判定

期待 genesis block hash は filename や DB の network byte から導出していない。production `NetworkInfos` が選択する `nemesis.bin` / `nemesis-testnet.bin` を `NemesisBlock.fromResource` で読み、production `HashUtils.calculateHash` で block hash を計算した。generation hash と block hash は区別した。

| 候補 | expected network marker | DB-observed marker | independently derived expected genesis block hash | DB genesis block hash | 判定 |
|---|---|---|---|---|---|
| Mainnet | `0x68` | `0x68` | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | marker/genesis 一致 |
| Testnet | `0x98` | `0x98` | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | marker/genesis 一致 |

これらのファイルは過去の synthetic 5,000-block fixture とは file hash、height、genesis、table counts が異なるため、同一ファイル／同一 fingerprint ではない。ただし、その fixture の派生物か、別の synthetic chain か、実 network snapshot かは provenance なしでは決定できない。

## Read-only fingerprints / structural checks

既存 `tools/Phase2fDbFingerprint.java` を H2 1.4.200 と H2 2.2.220 の各 JDBC driver で実行。file URL は `ACCESS_MODE_DATA=r;IFEXISTS=TRUE` とし、JDBC connection も read-only / repeatable-read に設定した。全19 tableを primary-key order で走査して row count と SHA-256 digest を作成した。

| network | height / blocks | genesis hash | tip block hash | accounts | transactions | transfers | additional non-empty tables |
|---|---:|---|---|---:|---:|---:|---|
| Mainnet | 2,001 / 2,001 | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | 1,377 | 1,377 | 1,349 | importancetransfers 22; multisigmodifications 30; multisigsignermodifications 6 |
| Testnet | 1,601 / 1,601 | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | 21 | 20 | 20 | all other transaction/state tables empty |

H2 2.2 read-only queries confirmed heights are unique and contiguous from 1 through tip, every block's network version byte matches the expected network, and zero adjacent blocks have a `prevBlockHash` unequal to the preceding stored `blockHash`. Full table scans and these structural checks found no read/query inconsistency. H2 2.2 has no `CHECK TABLE` SQL command (`CHECK TABLE BLOCKS` returns syntax error); no recovery/repair tool was run. Therefore this is evidence of successful open, full logical scan, and chain-link consistency, not a claim that every physical free-space/page structure has been independently certified by an H2 recovery checker.

## Legacy Flyway history

Both `schema_version` tables have columns `version_rank`, `installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`. Each contains exactly the contiguous repository versions `1.0.0`–`1.0.7`, installed ranks 1–8, type `SQL`, success `true`, no unknown/pending repository version, and no gaps. Stored checksums are the same for each candidate:

| Version | script | stored / Flyway 3.2.1 raw-byte CRC32 | audited matching Git blob |
|---|---|---:|---|
| 1.0.0 | `V1.0.0__initial.sql` | 1235287926 | `f17c854c87ed9d6adf6bdc32ce4608ec580055f7` |
| 1.0.1 | `V1.0.1__min_cosignatories.sql` | -867359331 | `58161288f0c8af749414c08627b75942f6776d92` |
| 1.0.2 | `V1.0.2__increase_message_size.sql` | 88353754 | `6e820e17b2df2ead18bc6058f1f8e8ad9654737b` |
| 1.0.3 | `V1.0.3__namespace_tables.sql` | 1899963525 | `22a9e15c5df60503bf18e47eb70cef755525b674` |
| 1.0.4 | `V1.0.4__mosaic_tables.sql` | -1352422778 | `ef69b1321f259eafc02fb3b17cd67efddbda4988` |
| 1.0.5 | `V1.0.5__add_accounts_index.sql` | 1524875301 | `5e9fd16e4c5d0f4fddd2b40e6a507f3277ac0409` |
| 1.0.6 | `V1.0.6__increase_message_size.sql` | -1123275759 | `5cdb2b958129dff03968f7281fae4037cfe416b2` |
| 1.0.7 | `V1.0.7__increase_message_size.sql` | 1393077514 | `fee814335787390f8f3b23aeadd2d04fd845fa60` |

`audit-flyway-history` accepted both histories: each stored checksum matches an audited Git migration blob's Flyway 3 raw-byte CRC32. This proves consistency of the history row with a repository migration revision; checksum provenance alone does not prove that this DB's schema/data was produced by that script.

Flyway 9.22.3 strict read-only `validateWithResult()` reports the already documented generation mismatch for all 8 rows, and **0 pending migrations**. Resolved Flyway 9 checksums are the same as recorded in [the prior checksum compatibility evidence](phase-2f-flyway-legacy-checksum-compatibility.md); none was repaired or rewritten. With the production-equivalent `validateOnMigrate=false` configuration, Flyway 9.22.3 `migrate()` returned success, current `1.0.7`, pending 0, migrations executed 0, for both candidates.

The H2 2.2 file SHA-256 did change when the writable disposable copy was opened and closed by Flyway migrate:

| copy | immediately before Flyway | immediately after Flyway |
|---|---|---|
| Mainnet | `4ef286a74bc42580d8045656408520b839a6f806c0b55939823f984c27e1b2ea` | `5fbba42afb002a32c0a958b71d6e6a66e53e0f37d562a38508f58073a6245fa3` |
| Testnet | `138e6576be5536e7a561eed1db12353bf647f9b45096b7b7ebb0a476835bf93b` | `194526435fdc3cf6a1d197bc5575eda1804d129d848023b9d79fa94e481a8d36` |

This physical file change is not hidden or attributed to an unobserved cause. The post-Flyway read-only fingerprints compare `equivalent: true` for all schema, row counts, table digests, genesis/height/tip fields, and the full legacy history rows; no logical schema/history/content change was observed. No `repair`, baseline, DDL, or manual metadata edit was used.

## H2 conversion and NIS read path

For each candidate, a private disposable directory under `/tmp/phase2f-artifacts.*` was created. H2 1.4.200 produced SQL with `org.h2.tools.Script`; H2 2.2.220 imported into an empty separate target with `org.h2.tools.RunScript -options FROM_1X` and the NIS compatibility URL settings `MODE=LEGACY;NON_KEYWORDS=VALUE`. No conversion ran on the originals.

`compare-fingerprints` returned `equivalent: true` for both H2 1.4.200 pre-conversion fingerprints and H2 2.2.220 converted fingerprints: all 19 table schema definitions, row counts and logical digests matched, as did network, genesis, height and tip hash.

The NIS `SessionFactoryLoader` mapped production `DbBlock` / `DbAccount` entities against the converted database using Hibernate ORM 5.4.33.Final. In a read-only transaction through `BlockDaoImpl`, `count()`, `findByHeight(1)`, and `findByHeight(max)` succeeded on both. The result matched the JDBC fingerprint above. The same probes ran on Java 17 and Java 25; logs showed Hibernate Validator 6.2.5.Final and H2 2.2.220. No full `CommonStarter` NIS server boot, peer activity, sync, `BlockAnalyzer` replay, or network API server was started; DAO-level production persistence reads were used to avoid starting background/network services and to keep the investigation read-only.

## Original immutability

The original files were never opened through H2 or Flyway. Final hashes exactly match the starting hashes:

```text
8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5  legacy/nis5_mainnet.mv.db
23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87  legacy/nis5_testnet.mv.db
```

## Validation and remaining conditions

- Existing Phase 2F tooling unit tests: `python3 -m unittest -v test_phase2f_db_validation.py` — **21 passed**.
- Java 17 `17.0.20.1`: H2 1.4 read-only fingerprint, H2 1.4→2.2 copy conversion/fingerprint comparison, Flyway 9 audit/strict validate, production-equivalent no-op migrate, Hibernate/NIS DAO reads — **passed as described**.
- Java 25 `25.0.4.1`: H2 2.2 fingerprint, Flyway no-op migration on a separate disposable copy, Hibernate/NIS DAO reads — **passed**.
- Maven clean test/package: **not run**; no source, POM, or test code was changed. No hosted CI was started for a documentation-only evidence update.
- Mainnet and Testnet internal technical results are separate; neither result is generalized to the other.
- Full NIS application startup and full network-chain replay were not performed.
- External source/provenance, quiescence, independently trusted snapshot height/tip hash, and representativeness remain unverified. Matching genesis supports network identity but is not a provenance chain or a snapshot checkpoint.
- The known Flyway 9 strict checksum mismatch remains. The current production `validateOnMigrate=false` no-op path is observed, but future migration policy remains governed by the earlier fail-closed checksum decision; no future migration was applied here.
- The supplied `.mv.db` files remain untracked, user-provided artifacts and are intentionally excluded from commit.

**Phase 2F Mainnet / Testnet real-database compatibility gate remains BLOCKED until both candidate artifacts receive independently verifiable provenance, quiescence and trusted snapshot checkpoints and the remaining production-runtime/replay acceptance is completed.**

Do not begin Spring 6 / Hibernate 6 / Jakarta / a new DB-engine migration on the strength of these candidate-only results.
