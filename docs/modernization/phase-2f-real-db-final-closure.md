# Phase 2F — real legacy database final closure

## Decision

**Status: COMPLETE — CLOSED.** Phase 2F accepts the supplied Mainnet and Testnet databases as real NIS operational data based on the project owner's operational provenance attestation. Their technical compatibility has been demonstrated through legacy H2/Flyway inspection and conversion, NIS runtime loading, post-Jakarta/Hibernate 7 runtime validation, and persisted-chain lifecycle acceptance.

The earlier provenance blocker is superseded as a Phase 2F technical acceptance requirement. It was a valid result under the earlier evidence contract, which required externally trusted provenance and snapshot evidence. The acceptance boundary is now aligned with Phase 2F's modernization purpose: establish that real operational legacy databases can be safely read by the modern runtime and preserve their existing chain state. External provenance assurance is a separate release or audit concern.

## Repository and closure state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested and actual starting HEAD: `89630b74dab3de173a1d72d884966981fe464b07`
- Starting branch relation: local branch matched `origin/agent/nis-phase0-baseline` (0 ahead, 0 behind)
- Final HEAD: the commit containing this closure record and the current-status update; its resolved hash is reported in the Git completion result because a commit cannot contain its own hash.
- Scope: documentation and acceptance evidence consolidation only; no production code, dependency, schema, migration, protocol, or consensus changes.

The starting worktree had unrelated pre-existing edits. They were retained and excluded from the closure commit.

## Project-owner operational provenance attestation

The project owner attests that `legacy/nis5_mainnet.mv.db` and `legacy/nis5_testnet.mv.db` are not synthetic fixtures or artificially generated compatibility databases. They are database data generated and accumulated through actual NIS operation. This is accepted here as **project-owner operational provenance**.

This attestation is not a third-party signed provenance statement, externally authenticated operator identity, signed custody manifest, independent archival attestation, or cryptographically verifiable external chain of custody. None of those properties is claimed.

## Artifact identity

The files are present in the workspace at closure time. Their current size and SHA-256 were rechecked and match the identities recorded by the earlier validation work:

| Network | Artifact | Size | SHA-256 |
|---|---|---:|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` |
| Testnet | `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` |

These are H2 1.4.200 legacy files. Validation converted disposable copies for H2 2.2.220; the supplied originals were not used as writable runtime targets and remained unchanged in the recorded phase validations.

## Historical Phase 2F record

The historical decisions remain intact in their original records:

1. The initial Mainnet/Testnet compatibility gate could not proceed because representative real network artifacts were unavailable. Synthetic H2 work was correctly kept separate from real database acceptance.
2. The Flyway checksum investigation established the Flyway 3.2.1 raw-byte CRC32 versus Flyway 9.22.3 line-normalized checksum difference and safe handling constraints. At that point, real network histories had not yet been validated.
3. The real DB readiness and signed-evidence contract prepared a fail-closed intake process. The contract treated external provenance, quiescence, and trusted checkpoint evidence as prerequisites to the overall gate.
4. Once the two candidates were supplied, Phase 2F follow-up validated network markers and production genesis hashes, H2 1.4.200 readability, H2 2.2.220 disposable conversion, logical equivalence across 19 tables, Flyway history, and the NIS read path. The candidates passed technical checks, while the overall gate remained blocked by the then-required external provenance evidence.
5. Runtime/replay validation started the production NIS/Spring/Hibernate 5 runtime on disposable copies for both candidates, replayed persisted blocks, and confirmed chain and logical DB invariants. Its record explicitly preserved the provenance-only blocker.
6. Provenance closure and final handoff records later confirmed that no independent signed custody/snapshot proof could be reconstructed. Their `BLOCKED` results accurately represented the old evidence contract and are not rewritten by this closure.

The prior blocker was an **evidence/provenance blocker**, not a finding that the databases failed technical compatibility. No concrete Mainnet or Testnet compatibility failure is recorded in the reviewed runtime and persisted-chain evidence.

## Compatibility evidence accepted

The following existing records support this decision:

- [Phase 2F Flyway legacy checksum compatibility](phase-2f-flyway-legacy-checksum-compatibility.md) documents historical checksum behavior and safe legacy-history handling constraints.
- [Phase 2F supplied artifact follow-up](phase-2f-real-db-artifacts-followup.md) records H2 1.4.200 inspection, H2 2.2.220 disposable conversion, network/genesis consistency, legacy history checks, and preservation of the original artifacts.
- [Phase 2F runtime/replay validation](phase-2f-real-db-runtime-replay-validation.md) records production NIS runtime startup, persisted block replay, complete height/hash-link traversal, stable chain state, and logical database fingerprints on Java 17 and Java 25.
- [Phase 2J-F post-merge Jakarta validation](phase-2j-f-jakarta-ee11-merge-post-validation.md) validates the merged Jakarta/EE11 and Hibernate 7 integration. Its synthetic H2 persistence tests are framework-port evidence, not by themselves real database evidence.
- [Phase 2K-A post-Jakarta legacy DB runtime compatibility](phase-2k-a-post-jakarta-legacy-db-runtime-compatibility.md) opens both exact candidates using Spring 7 / Hibernate ORM 7 on Java 17 and Java 25, replays the stored chains, traverses blocks, and compares logical fingerprints before and after runtime use.
- [Phase 2K-B persisted-chain final acceptance](phase-2k-b-post-jakarta-persisted-chain-final-acceptance.md) validates process/context/database close and reopen, committed and rolled-back persistence behavior, marker cleanup, and restoration of the original logical fingerprints for both networks and both JDKs.

Flyway acceptance is bounded: both artifacts have eight successful legacy history rows through `1.0.7`, zero pending migrations, and the production runtime's `validateOnMigrate=false` no-op startup was demonstrated without applying migrations, repairing checksums, or rewriting history. Strict Flyway 9 validation still reports the known Flyway 3-to-9 checksum algorithm differences. This closure does not claim strict cross-generation checksum validation or approve a future migration policy; those limitations remain documented.

The H2 and runtime lifecycle probes used disposable copies, and the persisted-chain acceptance returned all logical table schemas, row counts, row digests, chain values, and Flyway history to baseline after cleanup. This demonstrates validation without damaging the supplied database artifacts.

## Final acceptance boundary

### Accepted

- The project owner's attestation that both artifacts are real data generated and accumulated through actual NIS operation.
- Mainnet/Testnet network-marker and genesis consistency.
- Legacy H2 readability and demonstrated conversion of disposable H2 1.4.200 copies to H2 2.2.220.
- Legacy schema and Flyway-history compatibility to the extent demonstrated by the recorded checks; no migration or history rewrite was performed.
- Persistence mapping compatibility and modern runtime open/read behavior.
- Mainnet and Testnet persisted-chain reconstruction, block continuity, and lifecycle behavior under the post-Jakarta Spring 7 / Hibernate 7 runtime.
- Logical state preservation across validation and cleanup using disposable copies, with the original artifacts unchanged.
- Subsequent Jakarta/Hibernate runtime evidence as corroboration of modern-stack compatibility.

### Not claimed

- Third-party cryptographically signed provenance.
- Externally authenticated identity of the original NIS operator.
- Signed custody manifest.
- Independent archival chain of custody.
- Legal or forensic provenance certification.
- Strict Flyway 3-to-9 checksum equivalence, or authorization for future production migrations.

## Residual risk and future provenance work

Project-owner operational provenance establishes the artifacts' origin for this technical acceptance. It does not independently verify source-system identity, acquisition, capture-time quiescence, or external custody. Those matters may be specified as separate release-provenance, supply-chain assurance, artifact-custody, or external-audit criteria if a future release requires them. They do not permanently block Phase 2F's technical compatibility gate.

## Conclusion

Phase 2F is **COMPLETE / CLOSED**. The supplied Mainnet and Testnet legacy databases are accepted as real NIS operational data based on project-owner attestation. Technical compatibility is demonstrated through legacy H2/Flyway validation, runtime database loading, post-Jakarta/Hibernate compatibility, and persisted-chain lifecycle acceptance. Independent third-party cryptographic provenance has not been established and is not claimed; it is no longer a blocking requirement for the Phase 2F technical compatibility gate.
