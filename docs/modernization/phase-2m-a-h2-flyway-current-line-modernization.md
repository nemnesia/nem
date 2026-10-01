# Phase 2M-A — H2 / Flyway current-line modernization and persisted-chain validation

**Decision: COMPLETE — H2 / FLYWAY CURRENT-LINE MODERNIZATION VALIDATED**

This is a database-runtime compatibility result. It does not change any prior
phase decision. Phase 2F, Phase 2K, and Phase 2L-G remain **COMPLETE / CLOSED**;
Phase 2L-H remains **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED**,
and Phase 2L overall remains **PARTIAL**.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested and actual starting HEAD: `995a9274a6017ff8951af7215e2346e824d6a4cd`
- Starting `origin/agent/nis-phase0-baseline`: same; divergence `0/0`.
- Final tested implementation HEAD: `7bf3b51a4` (`[nis] db: modernize H2 and Flyway runtime`).
- The documentation commit follows that tested implementation commit and
  contains no code changes. The final local/origin HEAD is reported after push.
- No history was rewritten.
- The pre-existing, unstaged `.gitignore` change adding `legacy/` was preserved
  byte-for-byte and excluded from commits.
- Changed files: `nis/pom.xml` and this record. No production code, migration
  SQL, database artifact, schema, or protocol file changed.

## Baseline and selected versions

The effective starting database dependencies were H2 `2.2.220` and Flyway
`9.22.3`. The selected pair is H2 `2.5.250` and Flyway `12.11.0`.

The application remains on Spring `7.0.9`, Hibernate ORM `7.2.25.Final`,
Jakarta Persistence `3.2.0`, and Jetty `12.1.13` EE11. Java 25 remains the
primary runtime; Java 17 remains the required compatibility runtime. Compiler
release / bytecode target remains `17`.

The H2 2.5.250 release is the current H2 release line observed for this phase.
Flyway 13 was rejected because Redgate's Java-version guidance requires Java
21 for that major line; selecting it would break the Java 17 compatibility
lane. Flyway 12.11.0 is the current 12.x candidate observed and runs on Java
17. Its source identifies H2 `2.3.232` as the H2 version verified by Flyway.
Consequently Flyway emits an explicit warning with H2 2.5.250. The pair is not
described as vendor-verified: it was selected based on the compatibility
spike, full application tests, both-JDK chain reconstruction, and focused
Failsafe results documented below. This warning remains a known risk for a
future Flyway/H2 pair review.

H2 is supported by Flyway Core directly; the resolved graph does not require a
separate `flyway-database-h2` module. The NIS packaged runtime contains exactly
one H2 (`2.5.250`) and one Flyway (`flyway-core:12.11.0`) artifact, and no old
H2/Flyway duplicate. The runtime graph retains Spring 7 / Hibernate 7 / EE11,
Byte Buddy `1.17.8`, and Jakarta Persistence `3.2.0`; no legacy persistence,
servlet, validation, Spring 5/6, Hibernate 5/6, or Jetty EE8 artifact was
introduced.

Authoritative candidate references:

- [H2 releases](https://github.com/h2database/h2database/releases)
- [H2 SCRIPT / RUNSCRIPT migration guidance](https://h2database.com/html/tutorial.html)
- [H2 migration to version 2](https://h2database.com/html/migration-to-v2.html)
- [Flyway 13 Java requirement](https://documentation.red-gate.com/flyway/flyway-blog/looking-forward-to-flyway-v13)
- [Flyway releases](https://github.com/flyway/flyway/releases)
- [Flyway H2 support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/h2)
- [H2 referential-action behavior](https://h2database.com/html/grammar.html)

## Configuration and migrations

The production URL retains `MODE=LEGACY`, `NON_KEYWORDS=VALUE`, and
`DB_CLOSE_DELAY=-1`. Flyway continues using `db/h2`, the `schema_version`
history table, and the existing production `validateOnMigrate` setting.
Hibernate schema auto-update remains disabled. All migration resources
`V1.0.0` through `V1.0.7` are unchanged.

On a fresh disposable database, both the old and selected stacks applied all
8 migrations, ended at `1.0.7`, and reported zero pending migrations. Candidate
Flyway successfully validated the eight existing history entries on the
persisted copies and performed no migration there. The 8 migration identities,
script names, checksums, and success flags match exactly:

| Version | Checksum |
| --- | ---: |
| 1.0.0 | 1235287926 |
| 1.0.1 | -867359331 |
| 1.0.2 | 88353754 |
| 1.0.3 | 1899963525 |
| 1.0.4 | -1352422778 |
| 1.0.5 | 1524875301 |
| 1.0.6 | -1123275759 |
| 1.0.7 | 1393077514 |

The clean replay schema inspection covered 397 table/column/PK/FK/index
metadata rows across 19 application tables plus migration history. Table and
column definitions, JDBC types and declared lengths, nullability, defaults,
primary keys, FK columns, and indexes matched. The only observed metadata
representation difference was the JDBC referential-action code on 40 foreign
key records: H2 2.2.220 reported `RESTRICT` (code 1), while H2 2.5.250 reports
`NO ACTION` (code 3) for the unchanged migration DDL. H2 documents that it does
not support deferred constraint checking and that `NO ACTION` therefore fails
immediately like `RESTRICT`; this is a documented engine representation
difference, not a migration rewrite. Both engines retain the same 40 FK
relationships. The full DAO/fork/delete regression suite also passed on the
candidate stack.

The application sequence `TRANSACTION_ID_SEQ` preserved its next-value
semantics on disposable persisted Mainnet and Testnet copies: both versions
reported next values `1378` and `21`, respectively. The accepted originals
were never opened by either engine.

## Persisted database conversion and logical comparison

Both inputs are real, runtime-derived NIS persisted data. The original accepted
files were not opened, mounted, migrated, normalized, or used as runtime homes.
The compatibility work used disposable copies only. The H2 migration path was
offline SQL export from a copy using its existing engine, followed by import
with the target engine using `RUNSCRIPT`; no direct in-place file upgrade was
attempted. This follows H2's documented export/import approach where file
format upgrade is not established.

For the H2 2.2.220-to-2.5.250 disposable transition:

| Network | Pre-transition copy | Converted copy | Opened/replayed candidate copy |
| --- | --- | --- | --- |
| Mainnet | 942,080 bytes; SHA-256 `edbc82c5655eb5cc799d087d4929ab827d3062a526c73095e711c6b2f724e8fa` | 942,080 bytes; SHA-256 `7393e7f820618d0065d6fedc23b14e8ab12a222d5eb78b3a90c76d4d5b4ce545` | 958,464 bytes; SHA-256 `ae17556883540c5872c44eda062dc5748906b5960f427e6bcaceafa4d1760230` |
| Testnet | 454,656 bytes; SHA-256 `3afbc9fb3a1d7635d48f88c8d71272a6cbb132d3397a744f7afcaafe674d625f` | 454,656 bytes; SHA-256 `d292f654dbe12f27a792091218e1fdb04bc0872e5ade3bcf90dd7ede78a5356e` | 471,040 bytes; SHA-256 `4fc0a9fe46e3f2d2bb62168b6375ef05889e2dfbd3469239f0b49ec0c460529f` |

Physical hashes changed as expected across export/import and runtime open. The
logical fingerprint compared all 19 application tables, every row count and
per-table digest, schema metadata, and all eight Flyway history rows. Excluding
only the engine and JDBC-driver version fields, every fingerprint field was
identical for each network before and after transition.

### Mainnet

- Network marker: `0x68`
- Genesis: `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4`
- Height / blocks: `2,001`
- Tip: `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a`
- Replay score: `24964532368849513`
- Account / account-state cache: `1,377 / 1,377`
- Namespace cache: `1`
- All adjacent block links valid.

### Testnet

- Network marker: `0x98`
- Genesis: `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5`
- Height / blocks: `1,601`
- Tip: `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51`
- Replay score: `79553937490635759`
- Account / account-state cache: `21 / 21`
- Namespace cache: `1`
- All adjacent block links valid.

The production Spring/NIS `NisAppConfig`, Hibernate SessionFactory, DAOs, and
full `BlockAnalyzer` replay ran against candidate-stack copies on Java 17 and
Java 25. Each run reported Flyway `1.0.7`, applied `8`, pending `0`; Spring
context and H2 shutdown completed, with no remaining non-daemon runtime
threads. Representative DAO traversal and chain hashes matched the accepted
baseline exactly.

## DAO, transaction, and integration validation

The existing suite exercised Hibernate entity mappings, HQL/native DAO
operations, commit/rollback, flush, cascades, lazy/eager relations, fork
handling, and delete ordering against H2 2.5.250. The
`Hibernate7PersistenceRuntimeTest` checks commit visibility from another
session, rollback invisibility, Spring/SessionFactory close, block cascade, and
lazy transaction relationships.

The Phase 2L-G repository-owned disposable Testnet runtime was created anew for
each run; it generated the deterministic Testnet genesis and used the existing
funded controller fixture. On both JDKs, two clean reprovision runs each
executed the 16 controller Failsafe tests plus `H2StorageSpeedITCase`:

| JDK | Run | Tests | Failures | Errors | Skips | Failsafe verify |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 17.0.20.1 | 1 | 17 | 0 | 0 | 0 | PASS |
| 17.0.20.1 | 2 | 17 | 0 | 0 | 0 | PASS |
| 25.0.4.1 | 1 | 17 | 0 | 0 | 0 | PASS |
| 25.0.4.1 | 2 | 17 | 0 | 0 | 0 | PASS |

The first attempted run from an already-started, separate loopback runtime
failed with connection errors after that process exited between execution
sessions. It was not an H2/database failure. The successful runs put setup,
Maven execution, Failsafe `verify`, and teardown in one process session. A
separate sandbox-restricted attempt returned `Operation not permitted`; the
approved loopback-enabled reruns above passed. No tests were excluded or
skipped.

## Full reactor and packaging

Maven version was `3.8.7`.

| JDK | Command | Result |
| --- | --- | --- |
| 17.0.20.1 | `mvn -B -o clean test` | PASS: 6,228 tests, 0 failures, 0 errors, 0 skipped |
| 17.0.20.1 | `mvn -B -o -DskipTests package` | PASS |
| 25.0.4.1 | `mvn -B -o clean test` | PASS: 6,228 tests, 0 failures, 0 errors, 0 skipped |
| 25.0.4.1 | `mvn -B -o -DskipTests package` | PASS |

Per-module test counts on each JDK: Core 2,364; Deploy 61; Peer 306; NIS
3,497. The total remains the accepted 6,228 test baseline. The separate
Failsafe subset above added 17 integration executions per run and is not
included in the full-reactor Surefire total. Java 25 emitted the known
Maven/Guava `sun.misc.Unsafe` deprecation warning. Package runs also showed
JaCoCo execution-data mismatch warnings from switching runtime test data; the
package result was successful. Neither warning affected test outcomes.

## Accepted original artifact integrity

Filesystem-only observations before and after all validation were identical:

| Original artifact | Size | SHA-256 | mtime epoch |
| --- | ---: | --- | ---: |
| `legacy/nis5_mainnet.mv.db` | 1,073,152 | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` |
| `legacy/nis5_testnet.mv.db` | 1,064,960 | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` |

No DB artifact was staged or committed.

## Phase decision and remaining boundaries

**Phase 2M-A: COMPLETE — H2 / FLYWAY CURRENT-LINE MODERNIZATION VALIDATED.**
H2 2.5.250 and Flyway 12.11.0 are implemented in `nis/pom.xml`; unchanged
migrations replay, persisted logical state and full NIS chain identities are
preserved, DAO/transaction behavior passes, the disposable controller/H2
integration subset passes repeatedly on Java 17 and Java 25, and both full
reactor/package gates pass. The Flyway “verified through H2 2.3.232” warning
and the documented H2 FK metadata representation difference are retained as
explicit limits; no checksum repair, baseline, schema-auto-update, migration
SQL change, or original-file conversion was used.

Phase 2L-H stays **BLOCKED** pending external publication of the Java 25
`build-ci` image, a selectable shared-library mapping, and an actual hosted
Jenkins Java 25 execution. This database validation does not resolve that
external gate. Phase 2F, Phase 2K, and Phase 2L-G closure decisions remain
unchanged.
