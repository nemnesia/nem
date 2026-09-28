# Phase 2F — real DB provenance / quiescence / checkpoint closure

## Decision

**BLOCKED — trusted artifact provenance and quiesced-snapshot evidence remain unavailable.**

The supplied candidates' stored tips were independently corroborated against current public NIS nodes for both networks, including adjacent block linkage. This is useful external checkpoint evidence, but it does not identify the candidates' source systems, acquisition process, or capture-time state. The public node responses were unauthenticated HTTP responses, and the node registry does not establish operator identity. No claim is made that either file was captured from those nodes or at the observed time. There is still no direct evidence that either artifact was taken after a clean NIS/H2 shutdown or by an application-consistent snapshot mechanism. The Phase 2F real-database gate therefore remains **BLOCKED**.

This follow-up does not repeat the prior runtime compatibility work. The Java 17/25 production Spring/NIS startup and persisted-block cache reconstruction, logical DB invariance, Flyway history and original-file immutability results are preserved in [runtime/replay validation](phase-2f-real-db-runtime-replay-validation.md) and [artifact follow-up](phase-2f-real-db-artifacts-followup.md).

## Repository and artifact state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `38ca2917dd3430046d42efdb5acf927c6fad134c`
- Actual starting HEAD: `38ca2917dd3430046d42efdb5acf927c6fad134c`
- Starting tracked worktree: clean; local HEAD equaled `origin/agent/nis-phase0-baseline`.
- Both `legacy/*.mv.db` files were already untracked. They were not opened with H2, copied, modified, or staged in this phase.
- Final HEAD: recorded by the commit containing this document.
- Changed file: this document only. No production source, dependency, DB, or validation harness changed.

Initial and final file checks:

| Network candidate | Path | Size | SHA-256 at start | SHA-256 at end | Filesystem timestamps (local `+09:00`) |
|---|---|---:|---|---|---|
| Mainnet | `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | birth `2026-09-28 21:39:04.365`; mtime `2026-09-28 21:40:07.899` |
| Testnet | `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | birth `2026-09-28 21:36:50.579`; mtime `2026-09-28 21:38:22.663` |

Filesystem birth/mtime and the repository path are local corroborating metadata only; neither establishes when or where the database snapshot was acquired. Git history contains no DB artifact path/object (`git log --all -- legacy/nis5_mainnet.mv.db legacy/nis5_testnet.mv.db` returned no commits). The earlier tracked note [artifact follow-up](phase-2f-real-db-artifacts-followup.md) says the two candidates were supplied under `legacy/` and kept untracked; it also explicitly says source node/owner, acquisition and snapshot times, quiescence, trusted snapshot checkpoint, and complete original file inventory were not provided.

## Evidence inventory

| Evidence class | Findings |
|---|---|
| **Verified** | Candidate file paths, byte sizes, and start/end hashes above; candidate files remained untracked. Prior disposable-copy inspection found Mainnet/Testnet network markers and production-resource genesis matches, contiguous linked chains at heights 2,001 / 1,601, and tip hashes matching the table below. Prior Java 17/25 runtime replay and 19-table logical fingerprints are documented in the linked Phase 2F records. |
| **Corroborating but insufficient** | Local filesystem birth/mtime; the tracked note that the candidates were placed under `legacy/`; current public NIS API responses from two Mainnet endpoints and three Testnet endpoints matching the candidate historical tip and neighbors; NEM's published NIS API documentation describing the block-at endpoint; the community node registry listing queried endpoints. These do not authenticate the file transfer or prove snapshot quiescence. |
| **Unverified** | Source node/data directory, source operator, source NIS/H2 versions at capture, authorization, artifact acquisition date, snapshot/copy procedure, full companion-file inventory at source, and whether the DB was stopped/checkpointed or captured atomically while running. |
| **Unavailable** | Signed backup/snapshot manifest; operator attestation tied to the exact hashes; source-host/node logs; trusted capture-time height/tip checkpoint record; independently verifiable storage/object version metadata; a signed or archival publication that binds these exact artifact hashes to a capture event. |

The filename, network marker, matching genesis, internal chain linkage, Flyway checksum-to-Git-blob match, and runtime compatibility are not used as substitutes for external provenance. The prior Flyway CRC32 match associates legacy history rows with candidate historical repository migration blobs, but it does not establish who created the database or that the data was generated by those scripts.

## Independent historical checkpoint query

### Method and source quality

The [NEM NIS API documentation](https://docs.nem.io/pages/Developers/nem-dev-basics-docker/08.blockchain-requests/docs.en.html) documents `POST /block/at/public` with a height body and block fields including `height`, `prevBlockHash`, `version`, and `timeStamp`. The [NEMnodes Mainnet list](https://nemnodes.org/nodes/) and [Testnet list](https://nemnodes.org/nodes_testnet/) list the endpoints below as NIS1 nodes. At retrieval, each queried endpoint's `/node/info` returned NIS `0.6.102`; Mainnet reported network ID `104`, Testnet `-104`.

Queries were made at **2026-09-28 20:36 UTC** (2026-09-29 05:36 JST) using the public read-only block API. For each candidate height `H`, the API response at `H+1` supplies the candidate block hash as its `prevBlockHash`; responses at `H` and `H+2` provide the preceding link and following link. The NIS API's block response does not return its own block hash directly. The `timeStamp` below is the block's NEM network-relative timestamp, not the DB acquisition/capture time.

The external responses were plain HTTP, without authenticated transport or a signed archive. Node-list inclusion and matching responses corroborate a public-chain checkpoint, but the registry does not certify endpoint operators; endpoint/operator independence is not fully established, especially for Testnet hosts under `nemtest.net`. Exact-hash web searches did not surface an archival publication for either hash. Therefore the result is classified as **external checkpoint corroboration**, not a trusted snapshot manifest.

### Mainnet candidate — height 2,001

Expected network version: `1744830465` (`0x68000001`); node `/node/info` network ID: `104`.

| Height | Hash source/value | Block timeStamp | Version |
|---:|---|---:|---:|
| 2,000 | `prevBlockHash` returned by block 2,001: `0b3ba0cc095a748a1c4303a1c61798f3bdbfd4e751d7baaaf83e267dce295e35` | 228,795 (from block 2,000 response) | `1744830465` |
| **2,001** | `prevBlockHash` returned by block 2,002: **`dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a`** | **228,882** | `1744830465` |
| 2,002 | `prevBlockHash` returned by block 2,003: `cbb9e58d1237dacdf89b04d755d8a3445ab8f3118b703a04851c51ae99cad570` | 229,111 (from block 2,002 response) | `1744830465` |

All values, including the neighboring `prevBlockHash` values, matched at both endpoints:

- `http://7338.work:7890/block/at/public` — NEMnodes Mainnet listing identified node name `7338tkpk`; `/node/info` returned network ID 104, NIS 0.6.102, Debian / Java 11.0.14.
- `http://75.119.142.41:7890/block/at/public` — NEMnodes Mainnet listing identified `Alpaquita`; `/node/info` returned network ID 104, NIS 0.6.102, BellSoft / Java 11.0.19.

**Result:** the candidate's height/tip hash is independently corroborated by two currently listed Mainnet nodes and adjacent block linkage. No trusted capture-time record binds this checkpoint to the DB artifact's creation or acquisition.

### Testnet candidate — height 1,601

Expected network version: `-1744830463` (`0x98000001`); node `/node/info` network ID: `-104`.

| Height | Hash source/value | Block timeStamp | Version |
|---:|---|---:|---:|
| 1,600 | `prevBlockHash` returned by block 1,601: `e4fc1c6d6b98e6c915f87dfd052e88cd5e3ab71992b1a012933fa27258712d91` | 314,125,839 (from block 1,600 response) | `-1744830463` |
| **1,601** | `prevBlockHash` returned by block 1,602: **`9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51`** | **314,126,018** | `-1744830463` |
| 1,602 | `prevBlockHash` returned by block 1,603: `a5649660436e69457d17376be3dd76a4d75c941b04c2e3122619cccf823a0718` | 314,126,042 (from block 1,602 response) | `-1744830463` |

All values, including the neighboring `prevBlockHash` values, matched at all three endpoints:

- `http://tortuga.nemtest.net:7890/block/at/public` — listed Testnet node; `/node/info` returned network ID -104, NIS 0.6.102, Ubuntu / Java 11.0.26.
- `http://ocracoke.nemtest.net:7890/block/at/public` — listed Testnet node; same reported network ID/version/platform family.
- `http://ntn1.dusanjp.com:7890/block/at/public` — listed Testnet node under a different DNS domain; same reported network ID/version/platform family.

**Result:** the candidate's height/tip hash is independently corroborated by three currently listed Testnet nodes and adjacent block linkage. The registry and responses do not establish three distinct operators or bind any response to the artifact acquisition event.

### Query reproducibility

Example request (repeat per height and endpoint):

```bash
curl --fail-with-body -sS -H 'Content-Type: application/json' --data '{"height":2002}' http://7338.work:7890/block/at/public
```

The response at height 2,002 included `prevBlockHash.data = dcda75bc…af02a`; similarly the Testnet response at height 1,602 included `prevBlockHash.data = 9406f3d9…bd51`. This query is read-only and does not interact with either local DB artifact.

## Quiescence and provenance decision

| Requirement | Mainnet | Testnet | Evidence / decision |
|---|---|---|---|
| Network identity | PASS | PASS | Prior disposable-copy internal marker plus independently derived production genesis match; public nodes returned matching network ID/version. Internal evidence alone is not provenance. |
| Genesis verification | PASS | PASS | Prior production genesis-resource comparison; unchanged as historical evidence. |
| Runtime compatibility | PASS | PASS | Prior production Spring/NIS startup, cache reconstruction, full height traversal, linkage, chain score and state evidence; no replay rerun here. |
| Schema / Flyway compatibility | PASS | PASS | Prior disposable conversion and runtime results: schema/history and 19 logical table fingerprints unchanged; Flyway 1.0.7, 8 applied, 0 pending. |
| Chain linkage | PASS | PASS | Prior full candidate traversal; public API also agrees at adjacent checkpoint heights. |
| Independent external tip checkpoint | **PASS — corroborated** | **PASS — corroborated** | Exact candidate tip hash and adjacent links agreed across two / three public NIS nodes at query time. Trusted capture-time checkpoint provenance remains unavailable. |
| External artifact provenance | **BLOCKED** | **BLOCKED** | No source node/host, owner/operator attestation, acquisition method/time, authorized backup record, or hash-bound transfer manifest. |
| Quiesced/consistent snapshot evidence | **NOT VERIFIED** | **NOT VERIFIED** | No clean NIS/H2 shutdown record, H2 checkpoint/close evidence, or application-consistent atomic snapshot procedure. Logical consistency only shows the candidate can be read and is internally coherent. |
| Original artifact integrity | PASS | PASS | Start/end sizes and hashes match exactly; original files were not opened via H2 in this phase. |

The independent query establishes that the candidate's stored tip hash exists at the stated height in public Mainnet/Testnet node responses today. It cannot establish that the artifact was acquired from those nodes, at the block's timestamp, at a particular later point, or from a quiesced database. Thus the **checkpoint hash comparison is corroborated**, while the **trusted snapshot/provenance assertion remains unverified**.

## Minimum external evidence to close the remaining gate

The Mainnet operator and Testnet operator must each provide, separately:

1. **Hash-bound source statement:** named source node/system and operator/owner; network; original source data-directory identity; NIS and H2 versions; authorization to use the snapshot; acquisition date/time with timezone; snapshot date/time; complete H2 file/companion inventory; SHA-256 and size for each file. The statement/manifest must be signed or delivered through an authenticated channel and bind the exact candidate SHA-256 above (or explain a verifiable hash-preserving transfer from source to candidate).
2. **Quiescence record:** NIS process stop and successful H2 database close/checkpoint logs before copying, or a documented application-consistent atomic snapshot mechanism with its completion record. A filesystem mtime or successful later open is not sufficient.
3. **Capture checkpoint:** snapshot height and tip hash recorded by the source operator at capture time, with a second independent node/archive or signed historical checkpoint confirming that same network/height/hash. Include the previous/next hash where available and the source identity/time. The public-node comparisons above are available as supplemental confirmation, not as a substitute for the signed capture record.
4. **Transfer chain:** source manifest hashes must match the received original files; record who transferred/received them and when. Keep originals read-only outside the repository and rerun the existing `tools/phase2f_db_validation.py` intake/original verification on disposable copies.

Each network remains independently gated. Mainnet evidence cannot satisfy Testnet requirements, and vice versa. Do not promote either candidate to “trusted” until all three evidence categories—provenance, quiescence, and capture checkpoint—are tied to its exact hash.

## Tests, CI, and final gate

- No source/tool code changed; no Maven or unit tests were rerun. This phase performed read-only public checkpoint queries and local size/hash/stat/Git-history inspection only.
- Prior tooling/runtime test and CI results are documented in the linked Phase 2F reports; those runs predate this document and are not a CI run for this final documentation commit.
- Hosted CI status for this docs-only commit could not be queried locally: `gh auth status` reported the configured GitHub token invalid. No application-code validation was needed for this documentation-only change.
- Mainnet candidate: **BLOCKED** on external provenance and quiescence; historical tip externally corroborated.
- Testnet candidate: **BLOCKED** on external provenance and quiescence; historical tip externally corroborated.
- **Phase 2F overall real-database gate: BLOCKED — trusted provenance / quiesced snapshot evidence required.** No DB gate is released by this follow-up.
