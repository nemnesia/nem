# Phase 2L-A — Java 25 production baseline transition

## Decision

**PARTIAL — JAVA 25 PRODUCTION BASELINE NOT YET READY.** Java 25 is now explicit as the primary runtime/build baseline in the repository documentation, the Java 25 GitHub Actions workflow, CodeQL, and the NIS production Docker image. Java 17 remains an explicitly required compatibility lane, and all modules still compile to Java 17 bytecode. The requested Java 25 and Java 17 regression commands, the Java 25 production-equivalent bootstrap, both persisted-chain replays, and the Java 25 Docker image build passed.

The repository also contains a separate Jenkins path. The NIS Jenkinsfile delegates image selection to the externally maintained `_symbol` shared library and `symbolplatform/build-ci` registry. The prior Phase 2I-B investigation records that the Java 17 image was not published and the existing default image used Java 11. No Java 25 Jenkins image or live Jenkins run could be verified in this phase. Since that configured CI path cannot yet be moved to Java 25 from this repository, the phase is PARTIAL. This does not affect the successful Java 25 GitHub Actions configuration or local validations.

## Repository and Git baseline

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `89630b74dab3de173a1d72d884966981fe464b07`
- Actual starting HEAD: `89630b74dab3de173a1d72d884966981fe464b07` (matches request)
- Local and `origin/agent/nis-phase0-baseline` both started at that HEAD.
- The existing `.gitignore` edit adding `legacy/` was preserved unchanged, unstaged, and uncommitted.
- Tested Java baseline implementation commit: `12015882d` — `[nis] build: make Java 25 the primary runtime baseline`.
- The documentation commit follows the tested implementation commit; the final repository HEAD is recorded in the completion report.
- No DB artifact was staged or committed.

## Java baseline changes

| Area | Before | Phase 2L-A configuration |
|---|---|---|
| Primary production/runtime | Java 17 | Java 25 |
| Compatibility runtime | Java 25 additional target | Java 17 required compatibility lane |
| GitHub Actions | `Java 17 Baseline`, `Java 25 Compatibility` | `Java 25 Production Baseline`, `Java 17 Compatibility` |
| CodeQL build/scanner JDK | Zulu 17 | Zulu 25 |
| NIS container builder/runtime | Ubuntu 22.04 OpenJDK 17 | Temurin 25 JDK/JRE on Jammy |
| Root and module build guides | Java 17 minimum | Java 25 primary; Java 17 compatibility |
| Compiler release | Java 17 | Java 17 retained |

The compiler target and runtime baseline are separate. `core`, `deploy`, `peer`, and `nis` all use `maven-compiler-plugin` `3.15.0` with `<release>17</release>`. Builds on Java 25 therefore retain Java 17 language/API and class-file compatibility. Full test/package runs under both JDKs confirm this configuration. No Java 25-only language features, APIs, or bytecode were introduced.

The Java 25 CI workflow runs `mvn -B clean test` and then `mvn -B -DskipTests package`; the Java 17 compatibility workflow does the same. Java 25 is the primary required GitHub Actions lane; Java 17 remains required during Phase 2L-A. CodeQL 2.23.1 and later support Java 25; the current CodeQL workflow uses Java 25. `actions/setup-java@v6` supports Temurin 25.

## Toolchain compatibility

| Tool | Configured version | Java 25 evidence |
|---|---:|---|
| Local Maven | 3.8.7 | `clean test`, `clean package`, and Javadoc probe ran under OpenJDK 25.0.4.1 |
| Docker builder Maven | 3.6.3 (Ubuntu Jammy package) | Container build printed Maven and Java versions; Maven ran on Temurin 25.0.4.1 and built/package-prepared NIS successfully |
| Maven Compiler Plugin | 3.15.0 | Compiled all modules with `--release 17` on JDK 25 |
| Surefire / Failsafe | 3.5.6 | Surefire full tests passed under JDK 25; Failsafe remains configured at the same version |
| JaCoCo | 0.8.15 | Agent, instrumentation, and reports ran during Java 25 test/package; upstream release notes list official Java 25 support |
| Maven Javadoc Plugin | 3.12.0 | `mvn -B -Dmaven.repo.local=/tmp/phase2la-maven-jdk25-javadoc -pl core javadoc:javadoc` succeeded under JDK 25 |
| Maven Dependency Plugin | 3.11.0 | Runtime dependency tree resolved successfully; plugin properties ran in Java 25 Maven lifecycle |
| Maven JAR Plugin | 3.5.0 / 3.5.1 | Module artifacts were packaged successfully on Java 25 |

No Maven Enforcer, Maven Toolchains, shading, or assembly plugin configuration was found in the Java reactor. The NIS packaging script `infra/package.prepare.sh package` ran successfully in the Java 25 Docker builder. No build-tool or dependency version was changed for this baseline transition.

The first standalone Javadoc invocation using the pre-existing `~/.m2` cache stopped before plugin execution because the restricted cache could not create an artifact lock file. Retrying with the fresh writable cache under `/tmp` succeeded. Javadoc emitted 100 existing documentation warnings; no Java 25 plugin/runtime error occurred. Java 25 Maven emitted the known Guava `sun.misc.Unsafe::objectFieldOffset` terminal-deprecation warning. Hibernate's existing H2 dialect advisory also appeared; neither warning caused a build or persistence failure.

## Production runtime and persisted-chain regression

The validation used the existing `tools/Phase2fNisRuntimeProbe.java` against fresh copies of the H2 2.2.220 converted Mainnet and Testnet files previously created from the supplied runtime-derived databases. The probe ran under Java 25.0.4.1 with built reactor classes and the packaged `nis/target/libs/*` runtime. It used production `NisAppConfig`, Spring context creation, the Hibernate ORM 7 mappings, Flyway, DAO traversal, NIS startup and complete `BlockAnalyzer` replay. Peer auto-boot and harvesting were disabled. No block or transaction was added. H2 logs and runtime files were outside the repository, and the originals were never opened by the runtime.

| Fingerprint | Mainnet expected | Java 25 observed | Testnet expected | Java 25 observed |
|---|---|---|---|---|
| Network marker | `0x68` | `0x68` | `0x98` | `0x98` |
| Genesis hash | `438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4` | Same | `6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5` | Same |
| Height / block count | `2,001` | `2,001` | `1,601` | `1,601` |
| Tip block hash | `dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a` | Same | `9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51` | Same |
| Replay score | `24964532368849513` | Same | `79553937490635759` | Same |
| Account / account-state cache | `1,377 / 1,377` | Same | `21 / 21` | Same |
| Namespace cache | `1` | `1` | `1` | `1` |
| Block linkage | All adjacent links valid | Valid | All adjacent links valid | Valid |
| Flyway | `1.0.7`, 8 applied, 0 pending | Same; no migration | `1.0.7`, 8 applied, 0 pending | Same; no migration |

Both Java 25 probe processes reported `loaded=true`, a complete block-link scan, clean Spring context close, H2 `SHUTDOWN`, and `NON_DAEMON_THREADS=none`. The JVM class-load trace showed `net.bytebuddy.ByteBuddy` and `ClassLoadingStrategy` loading from the packaged `byte-buddy-1.17.8.jar` in both runs.

The existing focused `Ee11ProductionWebRuntimeProbe` and `Hibernate7PersistenceRuntimeTest` were run on Java 25 with:

```text
mvn -B -pl nis -am -Dtest=Ee11ProductionWebRuntimeProbe,Hibernate7PersistenceRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result: **4 tests passed, 0 failures, 0 errors, 0 skipped**. This exercised Jetty 12.1.13 EE11 startup/shutdown and the Hibernate/Jakarta persistence runtime path.

### Original database integrity

The supplied files are real NIS runtime-derived persisted-chain data, produced by actual NIS operation; they are not synthetic fixtures. The original artifacts were not modified. Start and end size, SHA-256 and mtime matched:

| Candidate | Start/end size | Start/end SHA-256 | Start/end mtime |
|---|---:|---|---|
| `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `2026-09-28 21:40:07.899431491 +0900` |
| `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `2026-09-28 21:38:22.663025304 +0900` |

The files remain outside Git tracking. `nis/src/main/resources/db` and its Flyway SQL had no changes. Flyway ran no migration against either disposable copy, and Hibernate schema auto-update is not enabled.

## Full regression

Local runtime/tool versions:

- Java 25: OpenJDK `25.0.4.1` (Ubuntu), Maven `3.8.7`
- Java 17: OpenJDK `17.0.20.1` (Ubuntu), Maven `3.8.7`

| Runtime | Command | Result |
|---|---|---|
| Java 25 | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 25 | `mvn -B clean package` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped; all modules packaged |
| Java 17 | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 17 | `mvn -B clean package` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped; all modules packaged |

Per-module tests: Core 2,364; Deploy 61; Peer 306; NIS 3,496. No tests were added or disabled. The first Java 25 test attempt was blocked by sandbox loopback restrictions in WireMock (17 Core errors). With loopback access enabled, one `AsyncTimerTest.visitorIsNotifiedOfOperationStarts` timing assertion failed; its isolated 16-test rerun passed, followed by a full Java 25 clean test and clean package with all 6,227 tests passing. The first restricted-socket attempt and transient timing failure are recorded rather than hidden.

## Dependency and namespace audit

The reactor-aware NIS runtime dependency tree and packaged `nis/target/libs` show:

- Spring Framework `7.0.9`
- Hibernate ORM `7.2.25.Final`
- Jakarta Persistence API `3.2.0`
- Jakarta Servlet API `6.1.0`
- Jakarta WebSocket API `2.2.0`
- Jakarta Validation API `3.1.1`
- Jetty `12.1.13` EE11
- Byte Buddy `1.17.8`
- H2 `2.2.220`; Flyway `9.22.3`

No Spring 5/6, Hibernate ORM 5/6, Jetty EE8, migrated `javax.persistence`, `javax.servlet`, `javax.websocket`, legacy `javax.validation`, legacy Hibernate integration package, or duplicate Persistence/Servlet/WebSocket API was present. `javax.sql` was not treated as a forbidden API. Byte Buddy appears as Hibernate's runtime dependency and its classes were observed loading from the packaged JAR during both real-chain probes.

The Docker production image built successfully as `nem-phase2l-a-java25-validation`. The builder log confirmed Maven `3.6.3` running on Temurin `25.0.4.1`, and the final runner returned Temurin `25.0.4.1` from `java -version`. Maven 3.6.3 is the Ubuntu Jammy package in the builder; local and GitHub Actions regression commands use their runner-provided Maven. The Docker runtime image was built and its JRE version checked; the NIS process itself was not launched inside the image because node runtime settings are operator-provided.

## Phase status and remaining gates

- **Phase 2K persisted-chain acceptance: COMPLETE**, unchanged. This phase reran the requested Java 25 chain regression without repeating the full 2K lifecycle acceptance.
- **Phase 2F trusted provenance: BLOCKED**, independently. The databases are real NIS runtime-derived data, but independent authenticated custody / trusted snapshot provenance remains unavailable. This limitation does not invalidate their software compatibility evidence.
- **Jenkins CI baseline: unresolved external path.** The pinned `_symbol` image/shared-library state has no verified Java 25 Jenkins executor. Prior Phase 2I-B evidence documented that its Java 17 image was unavailable and the default CI image was Java 11. A published Java 25 image, an effective live Jenkins image selection, and a Java 25 Jenkins run are still needed before claiming every repository CI path has transitioned.
- **Hosted CI on final commits:** the Java 25 and Java 17 workflows are configured to run on pushes; final hosted run results should be recorded once those runs complete.

The runtime stack, protocol behavior, consensus, serialization, network identities, schemas, Flyway history, and persisted chain data were not changed. The only production deployment configuration change is the Java 25 Docker JDK/JRE base; no Maven dependency or compiler bytecode baseline was raised.

## References

- [JaCoCo releases (0.8.15: official Java 25 support)](https://github.com/jacoco/jacoco/releases)
- [GitHub Actions `setup-java` Java 25 example](https://github.com/actions/setup-java)
- [CodeQL 2.23.1 Java 25 support](https://github.blog/changelog/2025-09-26-codeql-2-23-1-adds-support-for-java-25-typescript-5-9-and-swift-6-1-3/)
- [Phase 2I-B Jenkins Java 17 image investigation](phase-2i-b-jenkins-java17-alignment.md)
- [Phase 2K-B persisted-chain final acceptance](phase-2k-b-post-jakarta-persisted-chain-final-acceptance.md)

**Phase 2L-A status: PARTIAL.** Java 25 is configured and validated for production packaging/runtime and required GitHub Actions CI; the configured external Jenkins CI path still lacks a verified Java 25 executor.
