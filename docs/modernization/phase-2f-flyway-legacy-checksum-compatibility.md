# Phase 2F — Flyway legacy checksum compatibility

## Result

**Checksum compatibility sub-phase: COMPLETE — FLYWAY LEGACY COMPATIBILITY VALIDATED (for the synthetic legacy database fixture).**

**Overall Phase 2F real-database gate: PARTIAL / still closed.** Representative Mainnet and Testnet database copies were not available, so their recorded histories, conversion results, network identity, matching genesis, and chain state remain unverified.

No production database metadata was repaired or edited. No production Flyway configuration or migration scripts were changed.

## Repository and validation environment

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `b104964c82fad5dd36e440836f0f1d5289282c4d`
- Actual starting HEAD: `b104964c82fad5dd36e440836f0f1d5289282c4d`
- Starting worktree: clean; local branch matched `origin/agent/nis-phase0-baseline`
- Java: OpenJDK 17.0.20.1 for full tests/package; OpenJDK 25 for the focused checksum test
- Maven: 3.8.7
- Current Flyway: 9.22.3
- Historical Flyway: 3.2.1
- Current H2: 2.2.220
- Historical H2: 1.4.200
- Changed files: this document and `nis/src/test/java/org/nem/nis/dao/FlywayLegacyChecksumCompatibilityTest.java`

## Existing configuration and migration layout

The current NIS configuration uses Flyway location `db/h2`, history table `schema_version`, and `validateOnMigrate` from `flyway.validate`. That property is absent from the checked-in production properties, so the current effective value is `false`. The POM resolves Flyway 9.22.3 and H2 2.2.220. The historical POM used Flyway 3.2.1 and H2 1.4.200.

The repository contains eight versioned migrations, `1.0.0` through `1.0.7`. The prior real-database report documented old databases whose `schema_version` rows carry the Flyway 3 checksums listed below. Those values were re-read from a disposable copy of the available synthetic fixture; no Mainnet/Testnet database was available to inspect.

## Checksum experiment and cause

Both Flyway versions were run against the same current migration resources. Flyway 3.2.1's SQL resolver calculates CRC32 over the raw resource bytes (`SqlMigrationResolver.calculateChecksum(byte[])`). Flyway 9.22.3's `ChecksumCalculator.calculateChecksumForResource` reads lines through a `BufferedReader`, removes line terminators, filters a UTF-8 BOM on the first line, and calculates CRC32 over UTF-8 encoded line content. Consequently, identical SQL text with LF line endings has different checksum bytes between generations. This is a checksum algorithm/input-normalization change, not evidence that SQL statements differ.

The current eight scripts are ASCII-compatible UTF-8, have LF line endings, and have no BOM. The recorded size and SHA-256 refer to the exact working-tree bytes. Flyway 3 resolved values were also obtained from `Flyway 3.2.1 info().all()` and validated by `Flyway 3.2.1 validate()` against a copy of the synthetic fixture. Flyway 9 values were obtained from its resolved migration metadata (`info().all()`); strict validation against the old history reports all eight checksum mismatches.

| Version / file | Bytes | Raw SHA-256 | Line endings / BOM | Legacy stored checksum | Flyway 3.2.1 resolved | Flyway 9.22.3 resolved |
|---|---:|---|---|---:|---:|---:|
| `1.0.0` `V1.0.0__initial.sql` | 9656 | `b674cc861d25638f02fc41344201fa38c83a9a5ee75475433ac67dc71e92cf08` | LF / none | `1235287926` | `1235287926` | `-1137050968` |
| `1.0.1` `V1.0.1__min_cosignatories.sql` | 450 | `8d401d9bb187702667b840afbdccedf7b6ef548fcc19373606a2486ddecb643b` | LF / none | `-867359331` | `-867359331` | `-1083108641` |
| `1.0.2` `V1.0.2__increase_message_size.sql` | 73 | `0cece59769926ebebd6a5c5a4973438890e0fc632f133a39742dcb0cb17e49ef` | LF / none | `88353754` | `88353754` | `1259780587` |
| `1.0.3` `V1.0.3__namespace_tables.sql` | 2552 | `57c0e50a776763a3539cea3bd4f0ffb11f58e5642325a0b61a036c5cfa746b34` | LF / none | `1899963525` | `1899963525` | `57641099` |
| `1.0.4` `V1.0.4__mosaic_tables.sql` | 6282 | `958631c16e3f5f94ce3bb580125834947ff54ea2db3deb78e56875069194350c` | LF / none | `-1352422778` | `-1352422778` | `-1433398904` |
| `1.0.5` `V1.0.5__add_accounts_index.sql` | 76 | `6a9c09abb41f8c6b3cacd3c0045e975e2069a5a311c6fcfd5fbba59f13e93645` | LF / none | `1524875301` | `1524875301` | `-378188078` |
| `1.0.6` `V1.0.6__increase_message_size.sql` | 73 | `0ec115d393839c59e599df184e84e93bc2ca5cb5d71acc343e94b98a5678806a` | LF / none | `-1123275759` | `-1123275759` | `-1095467556` |
| `1.0.7` `V1.0.7__increase_message_size.sql` | 74 | `93e84fe1ddb2161083c89aa2a853442808ebe1f01d52deac8e52a729531538a4` | LF / none | `1393077514` | `1393077514` | `-1269544226` |

The resources contain no Flyway placeholders. Their simple SQL content has no evidence that parser/tokenizer behavior contributes to this checksum change. Resource loading was held constant by resolving the same classpath resources in each version. The focused regression test added here independently pins the Flyway 3 raw-byte CRC32 values for all eight current scripts.

### Git history caveat

The current resources reproduce the synthetic fixture's stored Flyway 3 checksums exactly, but repository history proves that not every historical checkout had these exact bytes. Commit `14fdd2286` (`lint: apply eclipse formatter`, 2021-10-30) changed `V1.0.0__initial.sql` from CRLF to LF without changing its SQL lines. The earlier CRLF version was 10013 bytes, SHA-256 `9d7dde6e13c00d3a2dbe563ff5531e0ca230b4115da2e04be0fcac8682c4ef2d`, and its Flyway 3 raw-byte CRC32 is `-119588282`, unlike the current LF checksum `1235287926`.

History also shows other edits/renames in the migration lineage: the initial migration came from the older `nis/populate.sql`; `1.0.3` and `1.0.4` have multiple historical commits; and `1.0.7` was derived from the earlier message-size migration with additional content. A historical change alone does not prove a particular production DB has that checksum, but it means rollout must validate each database's exact stored checksum against an audited set of shipped script variants. The eight matches in the synthetic fixture cannot be generalized to Mainnet/Testnet.

## Legacy-history behavior and policy comparison

| Option | Effect and assessment |
|---|---|
| Keep `validateOnMigrate=false` | This is the current behavior. It allows the old history to be read/no-op at `1.0.7`, but does not establish that the applied scripts are trusted. It is not sufficient as the rollout safety policy by itself. |
| Fail-closed legacy preflight | Recommended gate: read the applied rows and compare each version, success state, and stored checksum with a reviewed allowlist of Flyway 3 raw-byte checksums for the exact migration script variants shipped by NEM. Reject unknown versions, failed rows, missing scripts, duplicate rows, or an unapproved checksum before any migration. It does not mutate metadata and detects unrecognized script/history changes. Mainnet/Testnet rollout still requires testing this against copies first. |
| Duplicate legacy migration directory | Retains old scripts but does not by itself tell Flyway 9 to validate old history with Flyway 3 semantics. It adds maintenance and selection risk; not recommended as the only control. |
| Ignore checksum mismatch / relaxed Flyway validation | Could silently accept edited or unexpected scripts; not acceptable as the trust decision. |
| Flyway `repair` / direct checksum rewrite | Replaces stored checksums with the current generation's values and destroys the original trust evidence. Not used and not recommended as the first step. |
| Convert the existing old `schema_version` table in place | Unsafe without an explicitly supported conversion and backup; a test showed Flyway 9 cannot append directly to the Flyway 3 table schema. No metadata edits were made. |
| Preserve old table and start a new Flyway 9 history after preflight | A viable candidate was demonstrated on a disposable synthetic DB: preserve `schema_version`, create a new Flyway 9 history table, baseline it at the preflight-verified current maximum, and apply only a new migration. This intentionally adds a new history table while retaining old rows unchanged. It remains a candidate pending actual Mainnet/Testnet validation and production rollout design. |

### Disposable future-migration proof

On a separate disposable clone of the synthetic `1.0.7` database:

1. A preflight compared all eight old `schema_version` checksums with current migration scripts' Flyway 3 raw-byte CRC32. All eight matched.
2. The old `schema_version` table was retained without edits.
3. Flyway 9.22.3 was configured to use a new `flyway_schema_history` table, baseline at `1.0.7`, and a temporary filesystem location containing test-only `V1.0.8`.
4. Flyway created the baseline and applied only `1.0.8`; the probe table contained the expected row. The new migration received Flyway 9 checksum `2102166045`.
5. The eight original `schema_version` rows remained unchanged.

This demonstrates the mechanics on this fixture without `repair` or editing prior checksums. It does not authorize using a fixed `1.0.7` baseline for every production DB: preflight must determine and approve each database's actual latest successful version, and production migrations must use the verified next version. No temporary `1.0.8` migration was added to the repository's production migration directory.

For comparison, Flyway 9 `migrate()` against the legacy `schema_version` table with the current production configuration performs no work at `1.0.7`. When a new migration was directed at that old history table, Flyway 9 failed to insert into its old structure because `version_rank` is NOT NULL and is not supplied by Flyway 9 (`NULL not allowed for column "version_rank"`). Thus the existing `validateOnMigrate=false` no-op is not proof that future migrations can be appended safely.

## H2 conversion and available database artifacts

The previous Phase 2F synthetic conversion was rechecked as context: an H2 1.4.200-format synthetic 5,000-block fixture was exported and imported into H2 2.2.220 using the H2 `FROM_1X` path. All 19 tables matched row counts and logical digests; Hibernate 5.4.33.Final opened the converted database and read the expected 5,000-block maximum. H2 documents the legacy conversion workflow in its [migration to 2.x guide](https://h2database.com/html/migration-to-v2.html). No original database was modified in these experiments.

This environment still has no representative Mainnet or Testnet DB artifact. The available `test.mv.db` is the synthetic 5,000-block fixture, not production chain data. Its network marker is Testnet (`0x98`), but its genesis hash (`a590926e…983cdc88`) does not match the known Testnet genesis (`33496b75…844ed59`), so it cannot validate matching-genesis behavior. Other discovered files are empty/minimal Mijin or H2 speed-test databases. No real Mainnet/Testnet startup, migration, network-mismatch, height/hash comparison, account/transaction count comparison, or matching-genesis comparison was performed.

## Validation

- Java 17 focused test: `mvn -B -pl nis -Dtest=FlywayLegacyChecksumCompatibilityTest test` — PASS (1 test).
- Java 25 focused test: same command — PASS (1 test) on OpenJDK 25.
- Java 17 full clean test: `mvn -B clean test` — PASS, 6,221 tests; failures 0, errors 0, skipped 0.
- Java 17 clean package: `mvn -B clean package` — PASS with the same 6,221 tests.
- The first sandboxed full test attempt failed in existing Core `HttpMethodClientTest` cases because WireMock could not bind loopback (`SocketException: Operation not permitted`). The approved rerun with loopback access passed; this was an environment restriction, not a test skip or application change.
- No hosted CI run was initiated for this checksum investigation. The focused test and local Java 17 full suite are the validation evidence for this commit.
- `git diff --check` is required before commit; final commit/remote state is recorded in the completion report.

## Production recommendation and remaining gates

For a future legacy DB rollout, do not use Flyway 9 `repair`, do not hand-edit checksums, and do not accept all checksum differences. First take and verify a backup; run a read-only, fail-closed preflight against that database's exact successful history and reviewed Flyway 3 checksum variants; reject any unexplained or unapproved row. Only after the DB's schema/data state and maximum version have been verified should a controlled migration plan preserve the legacy `schema_version` evidence and establish a Flyway 9 history for future migrations. Validate that plan against disposable Mainnet and Testnet copies before any operational rollout.

The checksum algorithm cause and a safe synthetic transition path are now reproducible, but the overall Phase 2F gate remains **incomplete** until representative Mainnet and Testnet copies are supplied and converted/started/compared. Required evidence still includes matching network/genesis, migration validation, pre/post chain height and latest block hash, and independent counts or other chain-state digests. Do not proceed to Spring 6, Hibernate 6, Jakarta, or a new database engine migration while this real-data gate remains open.
