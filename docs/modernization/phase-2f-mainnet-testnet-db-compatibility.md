# Phase 2F — Mainnet / Testnet real database compatibility

## Final decision

**BLOCKED — REAL DATABASE ARTIFACT / COMPATIBILITY REQUIRED**

Neither a representative Mainnet DB nor a representative Testnet DB was available in this environment. The required per-network history provenance, H2 conversion, NIS runtime startup, matching-genesis validation, and before/after chain-state comparison therefore could not be performed. No synthetic fixture was substituted for either real network, and the Phase 2F real-database gate remains closed.

No database file was opened for writing, converted, migrated, or otherwise modified in this phase. No production code, dependency, schema, migration, or Flyway metadata was changed.

## Repository and starting state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `b48190e8b0e7ef36693f9fb0ff8d8d8181534705`
- Actual starting HEAD: `b48190e8b0e7ef36693f9fb0ff8d8d8181534705`
- Starting worktree: clean
- Starting local HEAD matched `origin/agent/nis-phase0-baseline`
- Expected runtime versions retained as context: Java 17 baseline, H2 2.2.220, Flyway 9.22.3, Hibernate 5.4.33.Final
- No implementation/test files changed; this phase adds this evidence record only.

## Artifact search and provenance

The repository, its resources, prior modernization references, developer data directory, and available local storage were checked for H2 database files. The search covered `/home`, `/mnt`, `/media`, `/srv`, `/tmp`, and the repository. No Mainnet or representative Testnet database copy was found.

| Network / file | Source and status | Size | SHA-256 | Assessment |
|---|---|---:|---|---|
| Mainnet | No local file, documented snapshot, or repository fixture found | — | — | **Unavailable** |
| Testnet | No representative real-network file found | — | — | **Unavailable** |
| Synthetic `test.mv.db` | `/home/harvestasya/nem/nis/data/test.mv.db`; prior Phase 2F report identifies this as a generated 5,000-block performance fixture, not a network snapshot | 509,284,352 bytes | `6e68d1a1c604e2bed0af9c0feb6ac17a6d60991f30d5ac8fc2bc408cf710184e` | Rejected as real Testnet evidence: its Testnet marker is `0x98`, but genesis hash `a590926ece7cb9aee4e72ba6b9617b9861265e4929c4e41e91d6e9da983cdc88` differs from the known Testnet genesis `33496b75b6e5827cd11f50070df2dd38b31e20398b166cb719dd544d4844ed59` |
| Empty/minimal Mijin `nis5_mijinnet.mv.db` | `/home/harvestasya/nem/nis/data/nis5_mijinnet.mv.db`; prior report found no application-table data | 20,480 bytes | `f3712f2efce98178d8b506351c2be41cc306c3c7426579af697c42c6f88d7ac5` | Not a representative Mainnet/Testnet chain DB |
| H2 speed-test `h2_speed_test.mv.db` | `/home/harvestasya/nem/nis/data/h2_speed_test.mv.db`; prior report found no application-table data | 20,480 bytes | `e876f1b2d1e30ececb4d507156997e4c92ff004ef3700d7956846e9afe9949b3` | Not a chain DB |

The listed files were inspected only as files for inventory and hashing; this phase did not open them through H2 or make disposable copies because they cannot satisfy the real-network acceptance criteria. Existing Phase 2F reports record that the synthetic fixture was converted on disposable copies in the earlier synthetic test. Those results remain synthetic evidence only.

The GitHub releases endpoint returned no releases. Artifact lists for the prior Java 17 Baseline and Java 25 Compatibility runs `36304308477` and `36304308480` were empty. No local CI artifact or documented backup location identified a real DB snapshot. The GitHub Actions artifacts checked contained no DB artifact to download.

## Required checks that remain unperformed

The absence of eligible input data prevents all per-network checks below. They are **not passed** and have not been inferred from synthetic data.

| Check | Mainnet | Testnet |
|---|---|---|
| Provenance, H2 source format/version, file set and hash | Not run: artifact unavailable | Not run: artifact unavailable |
| Read-only legacy `schema_version` inspection | Not run | Not run |
| Stored Flyway 3 checksum matched to an audited historical Git blob | Not run | Not run |
| Unknown/missing migration detection and actual baseline version | Not run | Not run |
| Pre-conversion chain-state snapshot | Not run | Not run |
| H2 1.4 export → H2 2.2.220 `FROM_1X` import on a disposable copy | Not run | Not run |
| Conversion-only row counts and state fingerprints | Not run | Not run |
| Flyway 9 history transition, retaining legacy history | Not run | Not run |
| Production-equivalent NIS startup / Hibernate reads | Not run | Not run |
| Network identity and matching genesis | Not run | Not run |
| Final chain height, block hash, representative state and counts | Not run | Not run |
| Opposite-network mismatch detection | Not run | Not run |

The previous synthetic checksum investigation established that Flyway 3.2.1 calculates CRC32 over raw migration bytes and Flyway 9.22.3 calculates a line-normalized checksum. It also demonstrated a possible new Flyway 9 history table on a synthetic copy while preserving the old table. It did not establish checksum provenance, baseline version, or safe migration behavior for either real network. Refer to [Phase 2F Flyway legacy checksum compatibility](phase-2f-flyway-legacy-checksum-compatibility.md) and [Phase 2F synthetic real-database validation](phase-2f-real-database-validation.md) for that bounded evidence.

## Artifact required to resume

The database operator/network owner must provide **two independent, representative, quiesced copies**: one Mainnet and one Testnet. For each copy, provide:

1. A read-only source snapshot or verified backup plus authorization/provenance identifying its network, source node/environment, snapshot height and capture time.
2. All H2 database files and required companion files from a clean database shutdown/checkpoint, without credentials, private keys, or unrelated node secrets.
3. SHA-256 hashes and file sizes from the source snapshot, and confirmation of the H2 version/format that last wrote it (or enough metadata to identify it safely).
4. Network/genesis identity and a known expected chain height/hash from the same snapshot or a matching reference.
5. Confirmation that the snapshot is representative and not a generated test/performance fixture.

Place each source in a protected read-only location outside the repository and provide an isolated disposable working-copy path. Do not commit database files or credentials. Once supplied, independently copy and hash each source before inspection; fail closed if provenance, history, checksums, network identity, or expected chain state is unexplained. Perform conversion/migration only on disposable copies and retain the originals unchanged.

## Validation and Git

- Java 17 / Java 25 DB tests: not run; no eligible real DB input and no reusable tooling was changed.
- Maven tests/package: not run; documentation-only change.
- Hosted CI: no DB artifact was present in the checked prior run artifacts. This phase did not claim a hosted DB validation.
- Final HEAD and push status are reported after committing this document; the worktree must be clean and the local branch must match origin.

## Gate status

The DB compatibility gate cannot be released. Mainnet and Testnet are independently **BLOCKED on representative database artifacts**. After those are supplied, resume with history inspection and checksum provenance before any H2 conversion or Flyway history transition. Do not use `repair`, rewrite checksums, or generalize one network's result to the other. Do not begin a later DB-engine, Spring 6, Hibernate 6, or Jakarta migration until both networks pass their own real-data checks.
