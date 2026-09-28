# Bouncy Castle provider modernization

## Status

**COMPLETE — the EOL `jdk15on` provider artifact was replaced with the supported Java 8+ `jdk18on` artifact and all Java 17 / Java 25 test and package runs passed.**

This change updates one production dependency concern in Core. It does not change NEM protocol, serialization, consensus, schema, migration scripts, Spring, Hibernate, Java baseline, or `javax.*` contracts. The Phase 2F Mainnet / Testnet real-database gate remains **BLOCKED** pending provenance-verified DB artifacts.

## Repository state

- Repository / branch: `nemnesia/nem` / `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `d05367301f98c6a73a7a6c4b0672da9b44432bf7`
- Starting worktree: clean and synchronized with origin
- Final HEAD: recorded by the commit adding this report

## Candidate review and selection

| Candidate | Existing usage and boundary | Decision |
|---|---|---|
| `org.bouncycastle:bcprov-jdk15on:1.70` (Core production) | Directly used for NEM Keccak/RIPEMD hashes, secp256k1 operations, and AES-CBC support. Upstream marks the pre-1.71 `jdk15on` artifact family EOL. Current `bcprov-jdk18on:1.86` is the Java 8+ provider line and keeps the `org.bouncycastle` packages. | **Selected.** Same provider family, stable supported coordinate, runtime Java 17/25 validation, existing deterministic signature vector, and added fixed hash vectors. |
| `org.apache.httpcomponents:httpasyncclient:4.1.5` (Core production) | Apache marks HttpAsyncClient 4 as EOL and recommends HttpClient 5. The 5.x async APIs use a different event-driven model, affecting Core networking semantics. | Not selected; needs a separate HTTP transport migration and request/cancellation/timeout evidence. |
| `org.apache.commons:commons-math3:3.6.1` (Core production) | Used in Core math code, including importance calculations. The Math 4 line is still beta and Apache describes it as a major release requiring source changes. | Not selected; needs separate numeric compatibility evidence. |
| `com.googlecode.matrix-toolkits-java:mtj:1.0.4` (Core test) | Test-only numerical fixture dependency; Maven Central lists only 1.0.4 for this coordinate. | Not selected; test-only and no successor coordinate to migrate to. |
| `com.googlecode.velocity-maven-plugin:velocity-maven-plugin:1.1.0` (NIS build plugin) | Legacy plugin declaration has no configured execution in `nis/pom.xml`, so it is not invoked by the normal Maven lifecycle. | Not selected; changing an inactive plugin declaration would not modernize runtime and is unrelated to the chosen concern. |

The Spring, Hibernate, Validator, H2, Flyway, Jetty, EL, and WireMock versions were treated as established compatibility boundaries and left unchanged. Maven plugins in the active module POMs were reviewed; no plugin change was needed for this one-dependency task.

Sources for the deferred candidates: [HttpAsyncClient 4 EOL notice](https://hc.apache.org/httpcomponents-asyncclient-4.1.x/index.html), [HttpClient 5 migration guide](https://hc.apache.org/httpcomponents-client-5.6.x/migration-guide/index.html), [Commons Math release history](https://commons.apache.org/proper/commons-math/changes-report.html), [MTJ Central coordinates](https://central.sonatype.com/artifact/com.googlecode.matrix-toolkits-java/mtj).

## Upstream and compatibility basis

Bouncy Castle's official download page lists release **1.86** as the current Java release and describes the `jdk18on` provider binaries as compiled for Java 1.8 and later. The upstream Java distribution repository states that releases before 1.71 using the `jdk15on` suffix are EOL and should not be used for new development. Maven Central publishes `org.bouncycastle:bcprov-jdk18on:1.86`; its POM declares no transitive dependencies. Sources: [Bouncy Castle release/download page](https://www.bouncycastle.org/download/bouncy-castle-java/), [Bouncy Castle Java distribution guidance](https://github.com/bcgit/bc-java), [Maven Central artifact](https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on/1.86).

The migration keeps the Java baseline at 17; the Java 25 runtime is separately tested below. It does not introduce Jakarta APIs or new dependency coordinates beyond the provider artifact replacement.

## Source usage and compatibility boundary

Before the change, Core's compile dependency tree contained only `org.bouncycastle:bcprov-jdk15on:1.70` for Bouncy Castle and no additional Bouncy Castle artifacts. Core source uses the provider in:

- `Hashes` for provider-registered Keccak-256, Keccak-512, and RIPEMD160 digests.
- secp256k1 signing, verification, curve operations, and key generation.
- the Ed25519 account cipher's AES-CBC / PKCS7 implementation.

The artifact replacement preserves the Java package names used by these APIs. Compilation with the repository's `-Werror` option revealed that BC 1.86 deprecates the direct `AESEngine()` and `CBCBlockCipher(BlockCipher)` constructors. The implementation now uses `AESEngine.newInstance()` and `CBCBlockCipher.newInstance(...)`; cipher mode, key/IV parameters, and padding remain unchanged. No deprecation suppression or compiler flag change was made.

`HashesTest` now asserts fixed Keccak-256 and RIPEMD160 outputs for `abc`. Existing `SecP256K1DsaSignerTest.signerProducesCorrectSignatureUsing256bitSha3` asserts the deterministic signature bytes for private key 1 and message `NEM`; it passed unchanged. Existing cipher, key generation, Ed25519 signature, and hash tests also passed.

## Dependency tree and production package

| View | Before | After |
|---|---|---|
| Core compile/runtime | `org.bouncycastle:bcprov-jdk15on:1.70` | `org.bouncycastle:bcprov-jdk18on:1.86` |
| Core test tree | Same single BC artifact, compile scope | Same single BC artifact, compile scope |
| BC transitive dependencies | None in the resolved BC tree | None; the 1.86 Central POM has no `<dependencies>` |
| NIS production `target/libs` | Old BC artifact before clean package | `bcprov-jdk18on-1.86.jar`; no `jdk15on` artifact |

The runtime dependency change is the intended single provider replacement; no additional runtime artifact was introduced. Spring/Hibernate/Jetty/servlet and other dependency versions did not change. The new artifact was resolved in an initially empty `/tmp/nem-bc186-m2` Maven local repository. Maven output identified `https://repo.maven.apache.org/maven2/` (Central) as the source for the provider and dependencies/plugins fetched into that fresh cache.

## Validation

Baseline focused crypto coverage on the old provider passed on Java 17 and Java 25: 54 tests per JDK. After the update, the focused crypto set passed on both JDKs: 56 tests, including the two new fixed digest vectors.

Full reactor results after the update:

| JDK | Command | Result |
|---|---|---|
| Java 17.0.20.1 / Maven 3.8.7 | `mvn -B -Dmaven.repo.local=/tmp/nem-bc186-m2 clean test` | PASS; 6,227 tests, 0 failures, 0 errors, 0 skipped |
| Java 17.0.20.1 / Maven 3.8.7 | `mvn -B -Dmaven.repo.local=/tmp/nem-bc186-m2 clean package` | PASS; same test count and no failures/errors/skips |
| Java 25.0.4.1 / Maven 3.8.7 | `mvn -B -Dmaven.repo.local=/tmp/nem-bc186-m2 clean test` | PASS; 6,227 tests, 0 failures, 0 errors, 0 skipped |
| Java 25.0.4.1 / Maven 3.8.7 | `mvn -B -Dmaven.repo.local=/tmp/nem-bc186-m2 clean package` | PASS; same test count and no failures/errors/skips |

Test counts by module: Core 2,364, Deploy 65, Peer 306, NIS 3,492. The previous 6,225 baseline increased by exactly the two new hash-vector assertions. Java 25 emitted the existing Maven-runtime `sun.misc.Unsafe` warning from Maven's bundled Guava; no BC deprecation warning remained after the factory API adaptation.

The initial sandboxed Maven attempt could not resolve Central DNS; the final fresh-cache runs used Central with authorized network access. This was an environment network restriction, not an application test failure.

## Hosted CI and remaining limits

On implementation/documentation commit `be48b04aaae829b3c088d74181b7c973327d3193`, both hosted workflows passed:

| Workflow | Run | Result |
|---|---:|---|
| Java 17 Baseline | [36409868303](https://github.com/nemnesia/nem/actions/runs/36409868303) | **PASS** — clean tests and package |
| Java 25 Compatibility | [36409868300](https://github.com/nemnesia/nem/actions/runs/36409868300) | **PASS** — clean tests and package |

The final documentation-only follow-up's hosted checks are reported in the task result. The local full test and package results above are from the final source changes.

This validation establishes compatibility against the repository's synthetic/test fixtures and fixed cryptographic vectors. It does **not** establish Mainnet or Testnet real-database compatibility. Phase 2F remains **BLOCKED** until provenance-verified real DB artifacts are supplied and validated independently for both networks.
