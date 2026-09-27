# Phase 2F — Real DB validation readiness harness

## Decision

**COMPLETE — REAL DB VALIDATION HARNESS READY** means that the intake and evidence workflow is prepared. It does **not** mean either network database passed compatibility validation. The real Mainnet/Testnet gate remains **BLOCKED** until separate provenance-verified artifacts are supplied and each independently passes the procedure below.

No production Java, dependency, schema, or runtime behavior was changed. No Mainnet/Testnet candidate was opened, converted, or migrated. Disposable synthetic H2 fixtures were opened for scanner/tool smoke checks only; they are not chain compatibility evidence.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `cf655eeafb091c21231ed98c1d1c36bd0c177398`
- Actual starting HEAD: `cf655eeafb091c21231ed98c1d1c36bd0c177398`
- Starting worktree: clean; local branch matched `origin/agent/nis-phase0-baseline`
- Final HEAD: the commit that adds this readiness record; its exact hash is the final commit reported for this phase (a commit cannot contain its own hash).
- Runtime baseline: Java 17; H2 2.2.220; Flyway 9.22.3; Hibernate 5.4.33.Final; Jetty 12.1.13 EE8

## Artifact discovery and disposition

The repository, `/home/harvestasya`, and available `/tmp` database directories were checked for H2 database files. The known local candidates are not accepted as real Mainnet/Testnet evidence:

| Candidate | Finding | Disposition |
|---|---|---|
| `/home/harvestasya/nem/nis/data/test.mv.db` | Previously established synthetic 5,000-block speed/test fixture; genesis differs from canonical Testnet | Reject synthetic fixture |
| `/home/harvestasya/nem/nis/data/nis5_mijinnet.mv.db` | 20 KiB minimal Mijin DB, not a Mainnet or Testnet chain snapshot | Reject wrong network / no representative chain |
| `/home/harvestasya/nem/nis/data/h2_speed_test.mv.db` | 20 KiB H2 speed-test database | Reject speed-test fixture |
| `/tmp/phase2f-*`, `/tmp/nem-phase2f-*` | Prior disposable/synthetic conversion, Flyway, and tool smoke databases | Reject as phase work products, not independent network artifacts |

No eligible Mainnet DB and no representative Testnet DB were found. No candidate was opened or modified in this phase. Previous Phase 2F records remain unchanged. GitHub release / checked prior workflow artifact inventory did not provide a database artifact. Credentials and private artifact locations were not searched or recorded.

## Added tooling

- `tools/phase2f_db_validation.py`: standard-library Python CLI for artifact inventory, manifest checks, original re-verification, disposable copying, deterministic JSON fingerprint comparison, and fail-closed legacy Flyway history provenance audit.
- `tools/Phase2fDbFingerprint.java`: read-only JDBC inventory. Requires file-backed H2 URL parameters `ACCESS_MODE_DATA=r;IFEXISTS=TRUE`; sets JDBC read-only and repeatable-read. Emits database/driver versions, network marker from genesis block version, genesis/tip hashes, height, table/schema inventory, per-table row counts and deterministic SHA-256 digest ordered by primary key, and legacy `schema_version` contents.
- `tools/Phase2fFlyway9Audit.java`: Flyway 9.22.3 `info()` / `validateWithResult()` read-only probe. It does not call `migrate`, `baseline`, or `repair`; it refuses a non-read-only URL. A strict validation failure is evidence and must not be suppressed.
- `tools/test_phase2f_db_validation.py`: tooling tests for provenance/network/genesis/hash rejection, synthetic classification rejection, inventory completeness, source-preserving copy, fingerprint determinism and drift, and unknown/gapped/failed/unproven Flyway rows.

The Python tool requires a complete manifest and exact inventory. Staging is explicitly not acceptance: `prepare-copy` verifies file hashes and directory fingerprint before and after copying, rejects source/destination overlap, requires a new destination matching the manifest, and creates a private working-copy directory. Files in the disposable copy are owner-writable for conversion. The tool never changes original permissions. For stronger protection, the source should be mounted or supplied read-only by the artifact owner. Re-run `verify-original` after every validation stage.

Reports, manifest files, database artifacts, and fingerprints must be kept outside the repository and outside the original database directory. The Java probes also require evidence output outside the repository and database directory. Do not commit reports that contain private paths or operator identity.

## Manifest contract

Use one manifest per network and one isolated original directory per artifact. JSON format version 1 is accepted. Required top-level fields:

- `manifest_version: 1`
- `network`: exactly `mainnet` or `testnet`
- `classification`: exactly `production` (synthetic, speed-test, Mijin, or unknown classifications are rejected)
- `complete_file_inventory: true`
- `original_artifact_path`, `disposable_working_copy_path`
- `database_files`: complete relative path, exact byte size, and SHA-256 for every file, including required H2 companions; no symlinks or unlisted files
- `directory_fingerprint_sha256`: SHA-256 over sorted `path NUL size NUL lowercase-file-sha LF` records
- `operator`, `execution_environment`
- `provenance`: source/owner, acquisition and snapshot ISO-8601 timestamps with timezone, snapshot height and block hash, independently sourced expected genesis block hash and evidence, quiesced attestation and evidence, legacy NIS/Java/H2/Flyway versions (record `unknown` only with per-version `legacy_version_evidence`)
- After the copy has been inspected, also record observed network/version, genesis hash and evidence, chain height, and tip block hash. The full manifest validator requires these and checks them against the network and snapshot claims.
- Optional `notes` string.

Network version bytes (`0x68` Mainnet, `0x98` Testnet) are consistency checks only. They do not authenticate an artifact. Expected genesis hash must come from an independently trusted operator/checkpoint, and observed genesis/tip/height must come from the read-only DB inspection. Do not use the NEM generation hash as a block hash.

Reject before conversion if provenance is unknown; network or independent genesis evidence is absent; snapshot height/hash cannot be verified; snapshot is not known quiesced; file list/hash/fingerprint is incomplete; any file is unlisted, missing, symlinked, or changed; source/copy overlap; the artifact is synthetic, speed-test, Mijin, empty, or not representative; the observed network/genesis/height/tip disagrees; history has unknown/gapped/failed/unproven migration rows; or any action would modify the original. Never infer Mainnet from Testnet or vice versa.

## Read-only and evidence procedures

Run commands from repository root. Keep `ARTIFACT`, `WORK`, `EVIDENCE`, and manifest paths on protected storage outside this repository. Each network uses its own values and manifest. Example variables below are placeholders, not paths to existing real artifacts.

```bash
export ARTIFACT=/protected/input/mainnet-snapshot
export WORK=/protected/work/mainnet-disposable
export EVIDENCE=/protected/evidence/mainnet
mkdir -m 700 -p "$EVIDENCE"
python3 tools/phase2f_db_validation.py inventory "$ARTIFACT" --report "$EVIDENCE/inventory.json"
```

The operator reviews inventory, provenance, quiescence proof, independently sourced expected genesis and snapshot block checkpoint, then creates `$EVIDENCE/manifest.json` using the exact inventory values. The manifest's `disposable_working_copy_path` must equal `$WORK`. Do not call `prepare-copy` until source identity and provenance have been accepted by the database owner. Copying by itself never marks the manifest accepted.

```bash
python3 tools/phase2f_db_validation.py prepare-copy \
  "$EVIDENCE/manifest.json" "$ARTIFACT" "$WORK" \
  --report "$EVIDENCE/copy.json"
```

Use the source H2 JDBC URL read-only and the source H2 driver matching its actual version. For initial H2 1.4.200 inspection, use only settings supported by that old driver (do not pass H2 2.x settings such as `NON_KEYWORDS`):

```text
jdbc:h2:file:/protected/input-or-work/nis5_mainnet;ACCESS_MODE_DATA=r;IFEXISTS=TRUE
```

For converted H2 2.2.220 runtime reads, the project settings also use `MODE=LEGACY;NON_KEYWORDS=VALUE`; confirm options against that exact driver. Do not pass these 2.x options to the 1.4.200 driver.

Use `nis5_testnet` only for a separately accepted Testnet artifact. Ensure no H2 process has the database open. Set credentials only in `NIS_DB_USER` / `NIS_DB_PASSWORD` environment variables if the artifact requires them; never put them in a URL, manifest, report, shell history, or commit.

Compile the read-only scanners with the repository's Java 17 JDK and the exact H2/Flyway dependencies. Example using locally resolved Maven artifacts:

```bash
mkdir -m 700 -p /tmp/phase2f-classes
javac -d /tmp/phase2f-classes tools/Phase2fDbFingerprint.java
java -cp "/tmp/phase2f-classes:$HOME/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar" \
  Phase2fDbFingerprint \
  'jdbc:h2:file:/protected/work/mainnet-disposable/nis5_mainnet;ACCESS_MODE_DATA=r;IFEXISTS=TRUE' \
  "$EVIDENCE/before-fingerprint.json"
python3 tools/phase2f_db_validation.py validate-manifest \
  "$EVIDENCE/manifest.json" "$ARTIFACT" --report "$EVIDENCE/identity-check.json"
python3 tools/phase2f_db_validation.py audit-flyway-history \
  "$EVIDENCE/before-fingerprint.json" --report "$EVIDENCE/flyway3-provenance.json"
```

Use the version-specific H2 JAR for the initial inspection (not necessarily 1.4.200 if metadata proves a different version). Fingerprints include every PUBLIC table and refuse a table without columns/primary key rather than silently omitting it. The all-table row digest is SHA-256 over typed, length-prefixed cell values in primary-key order. This is a deterministic database logical fingerprint, not a cryptographic proof that an operator-provided snapshot is authentic. `transactions` is a convenience aggregate over transaction table names present; the report lists which recognized tables contributed. Table digests and full schema inventory remain authoritative for schema differences.

The Python `audit-flyway-history` compares every successful ordered legacy `schema_version` SQL row against the raw-byte CRC32 of matching migration blobs reachable in Git history (`git log --all --follow` / `git show`). It reports matching commit, blob, byte size, SHA-256, line endings, and BOM. A matching checksum establishes that a historical script blob is a candidate for the stored checksum; CRC32 collisions cannot be excluded and this alone does not prove the DB's data was actually produced by that script. Unknown versions, gaps, unsuccessful rows, script/description/type mismatch, and missing historical checksum candidate fail closed.

After recording the actual copy path in the manifest, verify the source again after each major step (including after runtime startup):

```bash
python3 tools/phase2f_db_validation.py verify-original \
  "$EVIDENCE/manifest.json" "$ARTIFACT" --report "$EVIDENCE/original-after.json"
```

## H2 1.4.x → 2.2.220 conversion on disposable copy only

Never convert the source directory. Preserve `$WORK/source` as the verified staged original and make a second conversion input copy so the pre-conversion evidence remains available. Store SQL export and converted H2 database in a separate private target directory. Verify source hash before/after. Use the old H2 engine for SQL export and H2 2.2.220 for import, following H2's 1.x-to-2.x migration procedure. For the known 1.4.200 case:

```bash
mkdir -m 700 -p "$WORK/conversion-input" "$WORK/converted"
cp -a "$WORK"/nis5_mainnet.mv.db "$WORK/conversion-input/"
sha256sum "$WORK/conversion-input/nis5_mainnet.mv.db" > "$EVIDENCE/conversion-source.sha256"
java -cp "$HOME/.m2/repository/com/h2database/h2/1.4.200/h2-1.4.200.jar" \
  org.h2.tools.Script -url 'jdbc:h2:file:'"$WORK"'/conversion-input/nis5_mainnet;IFEXISTS=TRUE' \
  -user "$NIS_DB_USER" -password "$NIS_DB_PASSWORD" -script "$WORK/legacy-export.sql" \
  >"$EVIDENCE/h2-export.stdout" 2>"$EVIDENCE/h2-export.stderr"
export_rc=$?; printf '%s\n' "$export_rc" >"$EVIDENCE/h2-export.exit-code"; test "$export_rc" -eq 0
java -cp "$HOME/.m2/repository/com/h2database/h2/2.2.220/h2-2.2.220.jar" \
  org.h2.tools.RunScript -url 'jdbc:h2:file:'"$WORK"'/converted/nis5_mainnet;MODE=LEGACY;NON_KEYWORDS=VALUE' \
  -user "$NIS_DB_USER" -password "$NIS_DB_PASSWORD" -script "$WORK/legacy-export.sql" \
  -options FROM_1X >"$EVIDENCE/h2-import.stdout" 2>"$EVIDENCE/h2-import.stderr"
import_rc=$?; printf '%s\n' "$import_rc" >"$EVIDENCE/h2-import.exit-code"; test "$import_rc" -eq 0
```

The commands above are a procedure template: confirm the exact H2 `Script`/`RunScript` CLI options against the selected JAR (`-help`) before execution and record exact commands, versions, exit status, stdout/stderr and hashes. Do not use shell `set +e` to hide failures; capture each exit status and stop on nonzero. Protect logs and SQL export as sensitive database-derived data. Use separate DB names/directories for Mainnet and Testnet. For other old H2 versions, stop and confirm the supported export path first.

Scan the converted database using H2 2.2.220 in read-only mode and compare it to the pre-conversion fingerprint:

```bash
java -cp "/tmp/phase2f-classes:$HOME/.m2/repository/com/h2database/h2/2.2.220/h2-2.2.220.jar" \
  Phase2fDbFingerprint \
  'jdbc:h2:file:/protected/work/converted/nis5_mainnet;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=LEGACY;NON_KEYWORDS=VALUE' \
  "$EVIDENCE/post-h2-fingerprint.json"
python3 tools/phase2f_db_validation.py compare-fingerprints \
  "$EVIDENCE/before-fingerprint.json" "$EVIDENCE/post-h2-fingerprint.json" \
  --report "$EVIDENCE/h2-conversion-comparison.json"
```

Any genesis/network/height/tip/schema/count/table digest change stops the procedure. Investigate; do not waive or normalize unexplained differences.

## Flyway 9.22.3 and NIS runtime gates

The current application config (`nis/src/main/java/org/nem/specific/deploy/appconfig/NisAppConfig.java`) uses `db/h2`, table `schema_version`, and `flyway.validate`. Existing Phase 2F evidence shows Flyway 3.2.1 raw-byte CRC32 differs from Flyway 9.22.3 line-normalized checksums. Synthetic evidence also showed that Flyway 9 cannot append to the legacy Flyway 3 table schema (`version_rank NOT NULL`), while a separate Flyway 9 history table is only a synthetic migration candidate. Therefore:

1. Do not run `migrate`, `repair`, or `baseline` against the artifact or converted copy as an exploratory step.
2. Run the read-only Java Flyway audit against the converted disposable copy. Use classpath entries for Flyway 9.22.3, H2 2.2.220, Gson 2.10.1 and SLF4J dependencies resolved from Maven (`nis/target` or `~/.m2`). Use `filesystem:nis/src/main/resources/db/h2` and `schema_version` for the legacy history inspection:

```bash
javac -cp "$HOME/.m2/repository/org/flywaydb/flyway-core/9.22.3/flyway-core-9.22.3.jar" \
  -d /tmp/phase2f-classes tools/Phase2fFlyway9Audit.java
java -cp "/tmp/phase2f-classes:nis/target/classes:$HOME/.m2/repository/org/flywaydb/flyway-core/9.22.3/flyway-core-9.22.3.jar:$HOME/.m2/repository/com/h2database/h2/2.2.220/h2-2.2.220.jar:$HOME/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar" \
  Phase2fFlyway9Audit \
  'jdbc:h2:file:/protected/work/converted/nis5_mainnet;ACCESS_MODE_DATA=r;IFEXISTS=TRUE;MODE=LEGACY;NON_KEYWORDS=VALUE' \
  filesystem:nis/src/main/resources/db/h2 schema_version "$EVIDENCE/flyway9-legacy-audit.json"
```

Add any transitive runtime JAR required by the resolved Flyway classpath; record the exact classpath/JAR versions. Validation mismatch is a stop/manual-design point. Never auto-repair or edit metadata. Do not interpret `validateOnMigrate=false` no-op as proof of trusted history.

3. The production runtime test is only against a converted disposable copy, in a fully isolated NIS folder and with peer/synchronization activity disabled. Copy `infra/package` to `$WORK/runtime`, configure only that copy's `nis/config.properties` so `nem.folder` points to `$WORK/runtime`, `nem.network` matches the accepted artifact, auto boot/harvest/network time are disabled where configurable, and bind ports are isolated. Copy the converted DB to `$WORK/runtime/nis/data/nis5_mainnet.mv.db` or `nis5_testnet.mv.db` (plus required companion files), then hash it. Block outbound network at the environment level. Capture schema/history hashes before and after startup. The production launcher is `infra/package/nix.runNis.sh` / `org.nem.deploy.CommonStarter`; it may write to the DB and runs configured Flyway startup. Before allowing it to start, an operator must review the exact configuration and decide how the legacy-history/new-history-table migration candidate will be integrated. If that policy is not approved or strict history validation cannot be explained, do not start NIS. The launch command, after this review and only against the disposable copy, is:

```bash
(cd "$WORK/runtime/nis" && java -Xms1G -Xmx2G -cp ".:./*:../libs/*" org.nem.deploy.CommonStarter) \
  >"$EVIDENCE/nis-startup.stdout" 2>"$EVIDENCE/nis-startup.stderr"
nis_rc=$?; printf '%s\n' "$nis_rc" >"$EVIDENCE/nis-startup.exit-code"
```

Stop it using the reviewed local shutdown mechanism and record the shutdown result. The runtime test must record DB open, Flyway, Hibernate, network/genesis/height/tip, read-only API checks if safe, and every changed file/table. Network mismatch, schema mutation beyond explicitly approved migration, changed chain state, or peer activity is a failure.
4. Re-run `Phase2fDbFingerprint` after startup, `compare-fingerprints` against pre-conversion state, and `verify-original` against the immutable source manifest. For true migration, compare both the pre-conversion and final fingerprints. Require independently trusted expected Mainnet/Testnet genesis for each artifact.
5. Only after Mainnet and Testnet separately pass provenance, history, conversion, Flyway policy, runtime, genesis and state-equivalence gates can the Phase 2F real DB gate be released. One network cannot stand in for the other.

## Tests and observed results

- Tooling tests: `(cd tools && python3 -m unittest -v test_phase2f_db_validation.py)` — 21 tests passed.
- Java 17 JDK `17.0.20.1`, Maven `3.8.7` available.
- Both Java utilities compile with Java 17. The fingerprint utility scanned a disposable H2 1.4.200 and its H2 2.2.220 `FROM_1X` imported 5,000-block synthetic DB copy; fingerprints compared `equivalent: true` with no differing fields. This is a tooling smoke test only, not Mainnet/Testnet chain DB validation. Flyway audit was also pointed at a deliberately minimal throwaway schema lacking a valid Flyway history table; Flyway reported that the history table was absent. That incomplete stub is not a Flyway compatibility result and did not mutate any candidate artifact.
- No Maven production code/build configuration was changed. Hosted CI nevertheless ran the repository Java 17 clean-test/package workflow (run `36312940555`, success) and Java 25 compatibility workflow (run `36312940541`, success) for tooling commit `b11c9257fdc5d72d1b4db48428d8c270a510eafd`. These runs contain no real DB artifact and do not validate Mainnet/Testnet compatibility. No local full Maven test/package was run because changes are limited to standalone tooling/documentation; the workflow did run the standard repository suite and package.
- No Mainnet or representative Testnet DB passed intake; their actual compatibility status is untested and blocked.

## Remaining gate and artifact owner action

Request from the NIS/Mainnet and Testnet database operators, independently for each network: (a) a quiesced, authorized representative H2 snapshot and all companion files; (b) source node/version and acquisition/snapshot provenance; (c) expected snapshot height/tip hash and independently trusted expected genesis block hash; (d) legacy H2/Flyway/NIS versions if known; (e) file hashes or a read-only source from which they can be recomputed; and (f) confirmation it is not synthetic, speed-test, or empty. The owner must attest the snapshot shutdown/quiescence and network identity. No artifact location or credential is committed here.

**Phase 2F real-database compatibility gate remains BLOCKED pending provenance-verified Mainnet and Testnet DB artifacts.** Do not begin a later DB engine, Spring 6, Hibernate 6, or Jakarta migration before both networks pass.
