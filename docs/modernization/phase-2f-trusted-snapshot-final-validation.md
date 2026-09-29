# Phase 2F — trusted snapshot acquisition and final real-DB validation

## Decision

**BLOCKED — TRUSTED SNAPSHOT / EVIDENCE OWNER REQUIRED.** No source/operator and authenticated evidence bundle meeting the existing v2 contract was available for either network. No new snapshot was accepted, and no provenance claim was inferred from public-node agreement or runtime compatibility.

| Field | Value |
|---|---|
| Repository / branch | `nemnesia/nem` / `agent/nis-phase0-baseline` |
| Requested starting HEAD | `108cd9e3082fd69dd5d16fac4073289fbedaf6a9` |
| Actual starting HEAD | `108cd9e3082fd69dd5d16fac4073289fbedaf6a9` |
| Final HEAD | The commit containing this record; reported in the task closeout |
| Existing worktree change | `.gitignore` adds `legacy/`; preserved and excluded from this commit |

The authoritative acceptance contract is [the Phase 2F evidence contract](phase-2f-real-db-evidence-contract.md), implemented by `tools/phase2f_db_validation.py`. The v2 validator requires externally pinned Ed25519 operator and independent-checkpoint keys, signed records, exact artifact inventory/hash/size, transfer and timestamp consistency, quiescence evidence, correct network/genesis/height/tip, and a passing Mainnet/Testnet pair. No condition was weakened.

## Candidate bytes and handling

The supplied candidates were treated as forensic comparison files. They were not opened by H2, Flyway, or NIS, copied, rewritten, or staged in this phase.

| Network | Existing candidate | Size | SHA-256 at start | SHA-256 at end | Result |
|---|---|---:|---|---|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | Prior network/genesis, H2/Flyway and NIS runtime evidence remains valid for content compatibility; provenance is still blocked |
| Testnet | `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | Prior network/genesis, H2/Flyway and NIS runtime evidence remains valid for content compatibility; provenance is still blocked |

No new candidate artifact was acquired. The previous known heights/tips remain documented in the prior Phase 2F runtime and provenance records; this phase did not reopen the databases to derive them.

## Source and evidence search

Repository records and tooling reviewed:

- `docs/modernization/phase-2f-real-db-evidence-contract.md`
- `docs/modernization/phase-2f-real-db-provenance-closure.md`
- `docs/modernization/phase-2f-real-db-provenance-final-handoff.md`
- `docs/modernization/phase-2f-real-db-validation-readiness.md`
- `tools/phase2f_db_validation.py`
- `tools/test_phase2f_db_validation.py`
- Other Phase 2F artifact, checksum, runtime, and chain-state records
- Git history for both artifact paths, ignored-file status, and repository files matching signature/key/manifest/checkpoint/snapshot names

No artifact acquisition record, operator identity statement, source-side hash, transfer record, signed manifest, detached signature, trusted public key/fingerprint, key-identity binding, shutdown/H2-close log, application-consistent storage record, or signed independent checkpoint package was found. Git history contains no DB artifact commit. The only existing worktree change is the pre-existing `.gitignore` rule for `legacy/`.

External primary-source research was performed on 2026-09-29:

- The [official NEM Node Operation Guide](https://nemproject.github.io/nem-docs/pages/Guides/node-operation/docs.en.html) says an operator may download a Mainnet database snapshot from `bob.nem.ninja` for node bootstrap and then synchronize the remaining chain. This establishes that a public bootstrap snapshot distribution has been documented; it does not authenticate the source operator for these candidates, bind their exact hashes, provide transfer custody evidence, or attest to quiescence. The guide does not provide a Testnet DB snapshot acquisition with the required evidence.
- The [NemProject/nem upstream repository and releases](https://github.com/NemProject/nem/releases) provide signed software-release context, but no signed DB manifest or release asset tying either candidate hash to a capture event was located.
- Public-node/API agreement recorded by prior Phase 2F work remains checkpoint corroboration only. It does not satisfy the contract's signed, independently trusted checkpoint requirement and says nothing about the candidate files' source or capture method.

An HTTPS download or public repository location would establish a transport endpoint, not the required operator/key identity and snapshot claims. No authenticated operator channel or trusted keys were available in this environment. No message was sent to an external party, and no unsigned public snapshot was downloaded because it could not pass the unchanged evidence contract.

## Per-network result

| Contract check | Mainnet | Testnet |
|---|---|---|
| New artifact from trusted source | **BLOCKED — not acquired** | **BLOCKED — not acquired** |
| Operator identity / out-of-band key binding | **NOT PROVIDED** | **NOT PROVIDED** |
| Signed manifest and source-side hash | **NOT PROVIDED** | **NOT PROVIDED** |
| Transfer/custody integrity | **NOT VERIFIED** | **NOT VERIFIED** |
| NIS shutdown + H2 close or application-consistent snapshot | **NOT VERIFIED** | **NOT VERIFIED** |
| Independent signed checkpoint and out-of-band key trust | **NOT PROVIDED** | **NOT PROVIDED** |
| v2 validator | Not run: required bundle and pinned keys absent | Not run: required bundle and pinned keys absent |
| New snapshot runtime validation | Not run: no trusted snapshot passed intake | Not run: no trusted snapshot passed intake |
| Existing candidate integrity | Start/end hash and size match | Start/end hash and size match |

The existing candidates' earlier compatibility results are not promoted to provenance evidence. Existing candidate provenance remains unestablished even if a future trusted snapshot has similar or identical chain content.

## Exact owner handoff

For **each network separately**, the artifact owner must provide:

1. The DB artifact plus a detached-Ed25519-signed v2 manifest binding network, filename, byte size, SHA-256, H2/NIS versions, genesis, snapshot height/tip, source system/node, operator identity, capture/acquisition times, and acquisition method.
2. Source-side hash/size, post-transfer hash/size, transfer method, and timestamps bound by the operator signature.
3. The operator public key and fingerprint, plus an identity-to-key binding delivered through an out-of-band trust channel. A key included only beside the DB is not trusted.
4. Timestamped NIS graceful-shutdown, process-exit, and H2-close/flush evidence proving the copy followed DB close; **or** an authenticated application-consistent storage snapshot record proving writer freeze, consistency guarantee, snapshot boundary, and completion order.
5. A separately signed checkpoint record for the same network/genesis/height/block hash, signed by an identity independent of the DB operator, with its own public key/fingerprint and out-of-band trust binding.
6. A complete evidence inventory with file sizes and SHA-256 values, delivered with the exact original artifact bytes.

Then stage both network bundles and public keys outside the checkout and use the documented `evaluate-evidence-pair` invocation in the evidence contract. Only after a pair PASS should the new artifacts be re-run through the existing H2/Flyway/NIS compatibility procedure on disposable working copies. If the source cannot attest contemporaneously to the current candidate hashes, provide new snapshots; do not relabel the existing candidates. A DB file by itself is insufficient.

## Validation and final gate

- Evidence validator regression: `python3 -m unittest tools/test_phase2f_db_validation.py` was attempted as written, but this invocation failed to import the sibling module (`ModuleNotFoundError`). Correct repository-root invocation, `python3 -m unittest discover -s tools -p 'test_phase2f_db_validation.py'`, passed **40/40** tests. No validator code changed.
- No Java/Maven build was run: the phase made no Java, production, dependency, or build changes, and no new DB was available for runtime validation.
- Hosted Java 17 / Java 25 CI is run against the documentation commit and recorded in the completion report. The preceding final HEAD had successful Java 17 and Java 25 test/package workflows.
- No signed evidence package was evaluated; no runtime compatibility claim was made for a new trusted artifact.

**Final Phase 2F gate: BLOCKED — TRUSTED SNAPSHOT / EVIDENCE OWNER REQUIRED.** Closure depends on an external Mainnet and Testnet artifact owner supplying the authenticated acquisition, quiescence, and independent-checkpoint bundles described above. Existing content/runtime evidence remains useful but cannot substitute for them.
