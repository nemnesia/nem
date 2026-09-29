# Phase 2F — Real DB trusted provenance final handoff

## Final decision

**BLOCKED — CURRENT CANDIDATE PROVENANCE CANNOT BE RECONSTRUCTED.** The supplied databases have passed prior content, network/genesis, H2/Flyway, and NIS runtime checks, but this investigation found no authenticated source/custody or snapshot-consistency evidence bound to their exact hashes. Do not close the Phase 2F real DB gate.

| Field | Value |
|---|---|
| Repository / branch | `nemnesia/nem` / `agent/nis-phase0-baseline` |
| Requested starting HEAD | `0424ab5dc9ce119007882554b9bfdd23afc2057f` |
| Actual starting HEAD | `0424ab5dc9ce119007882554b9bfdd23afc2057f` |
| Final HEAD | The documentation commit for this handoff; exact SHA is in the completion report |
| Existing dirty change | `.gitignore` adds `legacy/`; preserved, not staged or committed |

The databases were never opened by H2, Flyway, or NIS. Only filesystem metadata and SHA-256 were read.

## Candidate integrity and previous technical results

Hashes/sizes below were measured at task start and again at completion; both measurements matched.

| Network | Candidate | SHA-256 | Size | Prior content/runtime evidence |
|---|---|---|---:|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | 1,073,152 bytes | Network/genesis match; NIS runtime-compatible; height 2,001; tip `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a`; prior public-node checkpoint corroboration |
| Testnet | `legacy/nis5_testnet.mv.db` | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | 1,064,960 bytes | Network/genesis match; NIS runtime-compatible; height 1,601; tip `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51`; prior public-node checkpoint corroboration |

These are content and compatibility facts from prior Phase 2F evidence, not proof of provenance. Agreement with a public node does not authenticate the node operator's response or prove who created/transferred these files.

## Evidence inventory

| Category | Finding |
|---|---|
| **Verified** | Current file sizes and hashes match prior recorded values. `git log --all -- legacy/nis5_mainnet.mv.db legacy/nis5_testnet.mv.db` returns no tracked artifact history. The files are ignored by the pre-existing uncommitted `.gitignore` rule. Their hashes occur in Phase 2F documentation, which records observations but is not a signed source statement. |
| **Corroborating, insufficient** | Prior Phase 2F reports contain network marker, genesis, chain height/tip, runtime read and public NIS checkpoint comparison. These show plausible, internally consistent chain data. They do not establish custody, capture process, or quiescence. Filesystem birth/mtime values in the earlier provenance note are not a trusted clock or provenance chain. |
| **Unavailable in repository** | Source organization/operator, source host and NIS node identity, source DB path, export/acquisition method, source and received transfer hashes, acquisition timestamp, shutdown/H2-close logs, storage snapshot records, detached signatures, signing keys/fingerprints, checkpoint signer identity, signed checkpoint record, CI/release/backup manifest linking exact candidate bytes. No Git LFS or release-artifact reference for these candidates was found. |
| **Unavailable to this environment** | External operator records, correspondence/email, source-host logs, private archive inventory, and out-of-band identity/key trust statements. No mailbox or artifact-owner evidence source was available to query. |

The known fact that the files were placed in this checkout's `legacy/` directory does not identify their source. Filenames and network markers are not provenance.

## Public/upstream research

Research was performed on 2026-09-29 UTC.

- The [NEM Node Operation Guide](https://nemproject.github.io/nem-docs/pages/Guides/node-operation/docs.en.html) describes downloading a Mainnet `nis5_mainnet-*.mv.db` snapshot from `bob.nem.ninja` to bootstrap a node and continue synchronization. Its linked current snapshot resolves in the indexed page to `nis5_mainnet-3_500_060.mv.db.gz`; fetching that large dynamic artifact timed out in this environment. The guide does not identify the supplied 2,001-height file, provide a detached data signature/checksum, identify a source operator for this candidate, or document a quiescence record for it. It also does not provide a Testnet candidate link.
- The [NEM upstream releases page](https://github.com/NemProject/nem/releases) includes signed software release records. No DB file or manifest connecting those releases to either supplied database hash was found.
- A [2018 community forum guide](https://forum.nem.io/t/simple-guide-to-diy-nem-blockchain-analysis/12703) describes copying a local NIS DB and refers to snapshots hosted at `bob.nem.ninja`. It is a community procedure, not an authenticated statement about these artifacts. It does not bind either candidate hash or provide signed shutdown/transfer evidence.
- Exact-hash searches for both candidate tip hashes returned no indexed results. Previous public-node API corroboration is retained as corroboration only; it is not a signed operator manifest or out-of-band trust path.
- The current official distribution reference demonstrates that snapshot files have been published for node bootstrap. It does not prove these local files came from that service. The direct dynamic archive was not downloaded or hash-compared; no inference is made from a matching filename or height.

The findings fall into **Case C**: the source/operator and custody chain for the current candidate hashes cannot be reconstructed from available records. A publicly advertised snapshot service is not enough to retrofit that chain.

## Network-specific gate

| Criterion | Mainnet | Testnet |
|---|---|---|
| Network / genesis / chain content | Prior validation PASS | Prior validation PASS |
| Runtime and H2/Flyway compatibility | Prior validation PASS | Prior validation PASS |
| Independent public checkpoint agreement | Corroborated; not authenticated provenance | Corroborated; not authenticated provenance |
| Exact artifact identity | Hash/size verified locally | Hash/size verified locally |
| Trusted source/operator identity | **BLOCKED** | **BLOCKED** |
| Custody / transfer integrity authenticated by source | **BLOCKED** | **BLOCKED** |
| Quiesced/application-consistent snapshot evidence | **NOT VERIFIED** | **NOT VERIFIED** |
| Independent signed checkpoint and out-of-band key trust | **NOT VERIFIED** | **NOT VERIFIED** |
| Existing candidate accepted by v2 evidence validator | No evidence bundle to evaluate | No evidence bundle to evaluate |

**Validator pair evaluation: NOT RUN / NOT PASS.** No signed v2 evidence package was present. The validator was not bypassed, and no v1 intake manifest was promoted to trusted status.

## Exact external evidence request

Send this request separately for Mainnet and Testnet to the operator/source that can attest to the DB. If that operator cannot establish a contemporaneous chain of custody for the supplied bytes, provide a new trusted snapshot and evidence bundle. **Providing another DB file by itself is insufficient.**

### 1. Artifact and acquisition statement — signed by source operator

Provide an Ed25519-signed v2 manifest and the evidence files it hashes. The signed manifest must include:

- Network (`mainnet` or `testnet`) and primary artifact filename/logical ID.
- Exact source artifact file list, each byte size and SHA-256, and deterministic directory fingerprint.
- Source organization, accountable operator ID, source system/host ID, NIS node ID, source DB path, NIS/H2/Flyway versions where known.
- Export/snapshot timestamp and acquisition/transfer timestamp in UTC, acquisition method, and DB/application state at capture.
- Observed network, genesis hash, block height, and tip hash at capture.
- Source-side hashes before transfer and received/acquired hashes after transfer, all matching the delivered bytes.
- Operator signer ID and public-key fingerprint.

Deliver the signer public key through a separate trusted channel and identify who confirmed the identity-to-key binding. A key included only inside the same evidence bundle is not trusted.

### 2. Quiescence evidence — same source operator/system

Choose one evidenced path:

**Normal NIS shutdown:** provide timestamped shutdown initiation/completion records, NIS process termination/exit status, H2 clean close/flush evidence, and prove snapshot/copy occurred after H2 closed. Bind the records to the source system and DB path.

**Application-consistent storage snapshot:** identify the snapshot mechanism and its application-consistency guarantee; provide writer freeze/pause evidence, snapshot start/completion ordering and timestamps, and the source-system/storage snapshot ID. A generic “backup”, “node idle”, or “file copy” statement is insufficient.

In either path, source-side and received artifact hashes/sizes must be recorded and authenticated by the operator signature.

### 3. Independent checkpoint — signer independent from DB operator

Provide a separately signed Ed25519 checkpoint record containing network, genesis hash, height, block hash, source/URL, retrieval time, and signer identity. The height and block hash must match the snapshot claims. Deliver the checkpoint public key and its identity/fingerprint trust path separately from both the DB bundle and DB operator. The checkpoint signer must be independent of the artifact source/operator.

### 4. Re-evaluation

Place each network's artifact and evidence bundle outside the Git checkout, pin both public keys out-of-band, then use the documented `evaluate-evidence-pair` command in [the evidence contract](phase-2f-real-db-evidence-contract.md). Preserve the signed bundle and machine-readable result. The pair result closes only the provenance/quiescence sub-gate; confirm the prior runtime/Flyway/chain-state report names these exact artifact hashes before closing Phase 2F.

If a trusted source supplies different artifact bytes, do not relabel these candidates. Treat the new DBs as new candidates and repeat the existing intake, H2 conversion, Flyway audit, network/genesis, runtime, and chain-state validation on disposable copies.

## Changes, tests, and final status

- No code, dependencies, runtime configuration, or DB artifacts changed.
- This is a documentation-only handoff; Maven and tooling tests were not rerun. The validator and its 40-test suite remain as recorded in the evidence-contract phase.
- The existing `.gitignore` `legacy/` change was preserved and excluded from this work's commit.
- Final Phase 2F status: **BLOCKED — trusted external evidence required**. For the existing hashes, provenance cannot currently be reconstructed. Safest release path is a new trusted Mainnet snapshot and a separate trusted Testnet snapshot, each with the authenticated package above; an owner may instead recover and sign adequate source-side contemporaneous evidence tied to these exact hashes.
