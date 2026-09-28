# Phase 2F — Real DB provenance and quiescence evidence contract

## Status

**COMPLETE — EVIDENCE CONTRACT READY, REAL DB GATE STILL BLOCKED.** This closes the evidence-contract readiness task only. It does not authenticate the currently supplied files or close the Phase 2F real-database gate.

| Item | Value |
|---|---|
| Repository / branch | `nemnesia/nem` / `agent/nis-phase0-baseline` |
| Requested starting HEAD | `0a99c46b221f90cc51ce3da0d497527c8d9bfcef` |
| Actual starting HEAD | `0a99c46b221f90cc51ce3da0d497527c8d9bfcef` |
| Final HEAD | Recorded by the commit containing this document and validator |
| Pre-existing worktree change | `.gitignore` adds `legacy/`; deliberately left unstaged and unchanged |

The supplied DBs were not opened by H2, Flyway, or NIS during this task. Their identities and runtime compatibility remain as documented in the preceding Phase 2F records.

## Existing evidence reviewed

Reviewed the existing Phase 2F readiness, compatibility, artifact follow-up, and provenance-closure documents, plus `tools/phase2f_db_validation.py` and `tools/test_phase2f_db_validation.py`. The prior v1 manifest/intake validator checks file inventory and internal network/genesis/height/tip claims, but its operator and quiescence fields were plain assertions. They do not authenticate who made those assertions. v1 validation is now explicitly reported as intake-only and unauthenticated.

The preceding evidence establishes useful corroboration and prior runtime results. A public node agreeing with a candidate checkpoint does not establish where the local file came from or how it was captured. Runtime readability, genesis, linkage, and filesystem timestamps likewise do not prove source ownership or quiescence.

## Required v2 evidence contract

The authenticated bundle is JSON plus evidence files, all outside the repository. The complete manifest bytes are signed as delivered; do not reformat it after signing. Its `database_files` array and `directory_fingerprint_sha256` bind the exact artifact file paths, sizes, and SHA-256 values. The authenticated acquisition record includes both source-side hashes and received/post-transfer hashes, each exactly matching the supplied artifact, plus acquisition-time height/tip. `artifact_id` identifies the primary H2 file. `classification` must be `production`; synthetic fixtures are rejected.

The manifest must bind these claims:

- `network`, artifact ID, complete file inventory, file size/hash, source path, and directory fingerprint.
- Source organization/operator, source system and NIS node identity, network, DB path, NIS/H2 and legacy Java/Flyway versions (or the existing evidence-backed `unknown` form).
- Acquisition method/time, snapshot time, DB/application writer state, observed network/genesis/height/tip at acquisition, and evidence references.
- Expected genesis evidence independent of the DB contents.
- A structured quiescence method and timestamped evidence.
- An independently sourced checkpoint with source/operator/URL, retrieval time, network, height, block hash, expected genesis, evidence record, and separate signer.
- Evidence inventory entries with unique ID, kind, bundle-relative path, exact size, and SHA-256.

### Authentication and trust boundary

For a machine `pass`, the source operator signs the exact manifest bytes with detached Ed25519. The validator receives the expected operator ID and public key as explicit command-line inputs from a trusted channel. It verifies that the manifest signer, declared source operator, and externally pinned key fingerprint agree. A key merely embedded in the evidence bundle is not a trust anchor.

The independent checkpoint record is separately signed with Ed25519. Its signer ID and public key are also pinned out-of-band and must be distinct from the artifact source operator. The signed record must match the manifest network, genesis, snapshot height, and tip hash. Unsigned HTTPS, a filename, a node marker, or an unverified Git identity may be supporting evidence but cannot produce machine `pass` in this implementation.

This authenticates the accountable statements and binds them to bytes; it cannot make a dishonest trusted operator truthful. Trust owners must establish the operator and checkpoint signing identities out-of-band. Keep private keys and credentials out of the repository.

### Quiescence acceptance

Accepted machine-evaluable methods are:

1. `normal-nis-shutdown-h2-close`: a successful NIS shutdown and completed H2 close precede the snapshot time; signed evidence identifies the same source system and DB path; the acquisition record states NIS stopped and H2 writer closed; and artifact file hashes/sizes are recorded at acquisition.
2. `application-consistent-storage-snapshot`: the DB writer is frozen before snapshot completion and signed storage evidence attests to an atomic, application-consistent snapshot on the same source system. The acquisition method must agree.

Claims such as “backup”, “copied from server”, “idle”, or “snapshot” alone do not pass. Evidence timestamps are parsed with timezone and ordering checks. Manifest signatures and evidence-file hashes bind the records. The normal-shutdown route is the clearest default for NIS/H2; the storage snapshot route is acceptable only when the storage/application-consistency mechanism is explicitly evidenced.

## Validator behavior

`tools/phase2f_db_validation.py evaluate-evidence` is fail-closed. It does not open H2 or mutate DBs. It verifies the externally pinned source signature, exact candidate files and inventory, signed acquisition/source/fingerprint/quiescence records, independent checkpoint signature, network marker and genesis/height/tip agreement, and timestamp ordering. It hashes the artifact inventory before and after evaluation and rejects a change during evaluation. It rejects bundle path traversal and symlinks. The original artifact directory and evidence bundle must be outside the repository; reports must not overwrite protected inputs.

`evaluate-evidence-pair` requires separately passing Mainnet and Testnet bundles and rejects a same-network pair. Its output is specifically `PROVENANCE_QUIESCENCE_EVIDENCE_PASS_BOTH_NETWORKS`; it still states that overall Phase 2F acceptance requires the previously established runtime and chain-state acceptance. A single-network pass never generalizes to the other network.

The old `validate-manifest` command is retained for safe intake checks but reports `accepted_for_intake_only` and `NOT_AUTHENTICATED_V1_MANIFEST`. Do not interpret its result as provenance acceptance.

### Operator commands when evidence is received

Keep the bundle, source DB files, and trusted public keys outside the Git checkout. Confirm the key fingerprints with the trust owners through a separate channel. Use distinct operator/checkpoint identities for each network as appropriate.

```bash
python3 tools/phase2f_db_validation.py evaluate-evidence-pair \
  --mainnet-manifest /secure/evidence/mainnet/manifest.json \
  --mainnet-artifact-dir /secure/artifacts/mainnet \
  --mainnet-operator-key /secure/trust/mainnet-operator.pub.pem \
  --mainnet-operator-id ORG/mainnet-operator \
  --mainnet-checkpoint-key /secure/trust/mainnet-checkpoint.pub.pem \
  --mainnet-checkpoint-signer-id INDEPENDENT/mainnet-checkpoint \
  --testnet-manifest /secure/evidence/testnet/manifest.json \
  --testnet-artifact-dir /secure/artifacts/testnet \
  --testnet-operator-key /secure/trust/testnet-operator.pub.pem \
  --testnet-operator-id ORG/testnet-operator \
  --testnet-checkpoint-key /secure/trust/testnet-checkpoint.pub.pem \
  --testnet-checkpoint-signer-id INDEPENDENT/testnet-checkpoint \
  --report /secure/evidence/phase2f-evidence-evaluation.json
```

The command exits nonzero on missing/invalid evidence. Preserve the signed source bundle and machine report as review evidence. The pair result closes only the provenance/quiescence sub-gate; the Phase owner must also confirm the existing H2/Flyway/runtime/chain-state evidence references the same artifact hashes before closing Phase 2F.

## Current candidate evaluation

No new authenticated v2 manifest, source-operator signature, source-system acquisition record, quiescence evidence, or separately pinned checkpoint signature was available in the repository or the supplied candidate context. Therefore the new validator cannot be run successfully against either candidate, and no evidence values were fabricated. The original DBs were not opened.

| Network | Candidate SHA-256 / size | Prior internal identity/runtime/checkpoint evidence | Provenance | Quiescence | Result |
|---|---|---|---|---|---|
| Mainnet | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` / 1,073,152 bytes | Previously validated; public checkpoint corroboration is not provenance | **BLOCKED** | **NOT VERIFIED** | No authenticated source bundle |
| Testnet | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` / 1,064,960 bytes | Previously validated; public checkpoint corroboration is not provenance | **BLOCKED** | **NOT VERIFIED** | No authenticated source bundle |

Prior recorded tips remain: Mainnet height 2,001, `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a`; Testnet height 1,601, `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51`. These are not newly re-derived and are not treated as independent checkpoint evidence in this task.

## Tests and remaining requirements

The `tools/test_phase2f_db_validation.py` suite now covers signed positive evidence and rejection of missing provenance, missing quiescence, artifact hash/size mismatch, a different source DB path, wrong network, checkpoint mismatch, unauthenticated/unpinned signer, operator/checkpoint signer reuse, malformed/tampered manifest/evidence, timestamp ordering, and Mainnet/Testnet pair confusion. Test fixtures use disposable fake bytes and temporary keys; they are harness tests only, not NIS DB evidence.

Still required from external artifact owners, **for each network independently**:

1. An identified source organization/operator and source NIS system/node, DB path, versions, and acquisition statement.
2. Original artifact file list, exact size/SHA-256, complete directory fingerprint, snapshot/acquisition timestamps, observed height/tip/genesis/network, and authenticated acquisition record.
3. Direct NIS shutdown + H2 close records before snapshot, or evidence of an atomic application-consistent snapshot with the DB writer frozen. Include the capture/transfer sequence and source-side hashes.
4. A separately authenticated, independent checkpoint source and signed record matching network, genesis, snapshot height, and tip hash.
5. Public keys and signer identities delivered/pinned through a trust channel independent of the evidence bundle.

The Mainnet and Testnet original hashes must also remain unchanged. Until all evidence passes for both networks and prior compatibility evidence is matched to those exact hashes, **Phase 2F real DB gate remains BLOCKED**. No Spring, Hibernate, Jetty, database, schema, protocol, or production behavior was changed.
