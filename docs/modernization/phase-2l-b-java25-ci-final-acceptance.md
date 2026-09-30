# Phase 2L-B — Java 25 production baseline / CI final acceptance

## Decision

**PARTIAL — JAVA 25 CI VALIDATION INCOMPLETE.** Java 25 remains the repository's primary production/build runtime and Java 17 remains the compatibility lane; compiler release 17 is unchanged. The Phase 2L-A Java 25 production-equivalent probe and full Java 17/25 test/package results remain valid. This phase also executed the NIS Jenkins build and unit-test scripts with Java 25. However, the configured Jenkins contract selects an externally published `symbolplatform/build-ci` image, whose pinned and current upstream Java image mappings stop at Java 21. No Java 25 executor image or private hosted Jenkins execution could be confirmed. The external shared-library/image owner must publish and expose a Java 25 image before the repository's Jenkins path can select and enforce this baseline.

The actual NIS Jenkins integration-test path also exposed pre-existing environment and script limitations: the separate Failsafe Maven invocation lacks a resolved Mockito agent property and starts no tests; a combined invocation runs the integration suite but reports external-node, hard-disk database, and performance-sensitive failures. These results are recorded without disabling tests or treating Failsafe's direct-goal zero exit status as an accepted passing test result.

## Repository and starting state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `baacf4109378de059a173dbcfac95a3dfb793064`
- Actual starting HEAD: `baacf4109378de059a173dbcfac95a3dfb793064` (matches)
- Starting `origin/agent/nis-phase0-baseline`: same HEAD
- Initial working tree: only the pre-existing `.gitignore` addition of `legacy/`; it was not edited, staged, or committed.
- Phase 2L-B source/build/Jenkins configuration changes: none. This record and the Phase 2L-A status clarification are documentation-only.
- Phase 2K: **COMPLETE / CLOSED**, unchanged.
- Phase 2F: **COMPLETE / CLOSED**, unchanged. Historical provenance blockers are superseded by the formal Phase 2F closure decision. The supplied databases are accepted as real NIS operational data based on project-owner attestation; third-party cryptographic provenance is not claimed and is not a Phase 2F blocker.

## Jenkins topology and Java selection contract

The NEM gitlink `_symbol` is pinned to `0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf` from `https://github.com/symbol/symbol` (`dev` branch in `.gitmodules`). The NIS Jenkinsfile calls `defaultCiPipeline` with Ubuntu, medium instance size, and `ciBuildDockerfile = 'java.Dockerfile'`. It requests the Docker publisher for `nemofficial/nis-client`; this Jenkinsfile property does not build or select a Java image from NEM's production `nis/Dockerfile`.

The pinned shared-library contract works as follows:

1. `jobHelper.resolveCiEnvironmentName` takes `java` from `java.Dockerfile` unless the Jenkinsfile supplies `environment`.
2. `defaultCiPipeline.resolveCiEnvironment` makes `java-ubuntu-lts` the default `CI_ENVIRONMENT` choice and adds `otherEnvironments` as manual alternatives.
3. `resolveBuildImageName` converts a selected name to `symbolplatform/build-ci:<name>` and the Docker agent pulls that image on a Jenkins node selected by OS, architecture, and instance size (default architecture is arm64).
4. The external image builder maps Ubuntu base variants to Java versions and passes `JAVA_VERSION` to its Java Dockerfile.

In the pinned submodule's `baseImages.yaml`, Ubuntu LTS maps to Java 11, base to Java 17, and latest to Java 21. The current upstream `dev` sources checked on 2026-10-01 retain the same Java mapping and the same selection shape; no Java 25 mapping or selection contract is present. The Java Docker image recipe installs OpenJDK and Maven inside the externally built image. Thus NEM can choose a tag through the current library interface, but it cannot create/publish the required Java 25 `symbolplatform/build-ci` image or make a nonexistent image usable from this repository. No speculative `environment` value was added.

References: [pinned shared-library pipeline source](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/shared-library/vars/defaultCiPipeline.groovy), [pinned base-image mapping](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml), [current upstream pipeline](https://github.com/symbol/symbol/blob/dev/jenkins/shared-library/vars/defaultCiPipeline.groovy), and [current upstream Java mapping](https://github.com/symbol/symbol/blob/dev/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml).

### Hosted Jenkins availability

No Jenkins controller URL, Jenkins credential, VPN/session, or external executor administration capability was available in this workspace. The configured GitHub CLI credential also reports invalid authentication; no credential values were read or recorded. Consequently, there is no hosted Jenkins build number, commit/run record, agent label, effective image, or hosted JDK/Maven evidence. This is an external operational boundary, separate from local Java 25 compatibility.

## Jenkins-equivalent Java 25 execution

Runtime used: OpenJDK and `javac` `25.0.4.1`, Maven `3.8.7`; compiler output remained `--release 17`.

The NIS shared pipeline's repository scripts were inspected and run:

| Jenkins stage / script | Command or action | Result |
|---|---|---|
| setup build | `nis/scripts/ci/setup_build.sh`; installs `core`, `deploy`, and `peer` with `mvn install -DskipTests=true -B` | PASS for all three modules on Java 25 |
| build | `nis/scripts/ci/build.sh` → `mvn clean compile -B` | PASS; compiled 489 NIS production sources with release 17 |
| tests | `nis/scripts/ci/test.sh` → `mvn test -B` | PASS on network-enabled rerun: 3,496 tests, 0 failures, 0 errors, 0 skipped |
| integration tests | script's separate `mvn failsafe:integration-test -B` | The Maven command exits 0 but runs zero tests after fork startup fails: `-javaagent:${org.mockito:mockito-core:jar}` is unresolved in this new Maven invocation. This is not accepted as a passing integration suite. |

The first script attempt stopped during module installation because the sandbox made the shared `~/.m2` cache read-only. The script was rerun with a copy of the Maven cache under `/tmp`; no source or POM changes were made. The first NIS Surefire attempt counted 3,496 tests with one DNS error resolving `bob.nem.ninja`. An isolated network-enabled rerun of `LocalHostDetectorTest` passed 9/9; the subsequent complete NIS `mvn test -B` rerun passed 3,496/3,496.

To test the Failsafe property/lifecycle boundary, a single Maven invocation ran `mvn test failsafe:integration-test -B`. The unit suite passed, but Failsafe ran 79 integration tests and reported 8 failures, 21 errors, and 2 skipped. The report includes timeouts/refusals to public NEM nodes, unavailable localhost services expected by HTTP/controller acceptance tests, a legacy hard-disk H2 file rejected as invalid at `/home/harvestasya/nem/nis/data/nis5_mijinnet.mv.db`, and performance/time synchronization assertions. Maven's direct `failsafe:integration-test` goal returned BUILD SUCCESS despite those integration results because the lifecycle does not invoke `failsafe:verify`; the failing report is not treated as success. The unrelated operator-provided `legacy/nis5_mainnet.mv.db` and `legacy/nis5_testnet.mv.db` files were not opened by these Jenkins script runs.

No tests were disabled or edited. The integration suite's two reported skips are retained in the result above. The observed Failsafe issues are not proven Java 25 runtime incompatibilities: some require hosted services/data, and the agent-property failure arises from the split Maven invocation. They do mean this workspace did not produce a green end-to-end Jenkins integration run.

## Java 25 and Java 17 baseline regression

No build configuration or production code changed in Phase 2L-B. The baseline regression records from Phase 2L-A therefore remain authoritative for the full reactor:

| JDK | Command | Result |
|---|---|---|
| Java 25 OpenJDK `25.0.4.1`, Maven `3.8.7` | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 25 OpenJDK `25.0.4.1`, Maven `3.8.7` | `mvn -B clean package` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 17 OpenJDK `17.0.20.1`, Maven `3.8.7` | `mvn -B clean test` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |
| Java 17 OpenJDK `17.0.20.1`, Maven `3.8.7` | `mvn -B clean package` | PASS: 6,227 tests; 0 failures, 0 errors, 0 skipped |

Phase 2L-A's Java 25 production-equivalent Spring/Hibernate/Byte Buddy/Jetty runtime probe and Mainnet/Testnet persisted-chain replay remain PASS. This phase made no runtime, dependency, Docker, schema, Flyway, chain, or database changes. The Java 17 compatibility lane and bytecode release 17 remain configured.

## DB and schema boundaries

The Phase 2K Mainnet/Testnet acceptance remains **COMPLETE / CLOSED**. This phase did not rerun the chain probe because no production/build runtime input changed, and it did not open the original candidates. No original database file was written, copied into the repository, or staged. The previous accepted artifact identities and chain fingerprints remain those in the Phase 2K-B and Phase 2L-A records. No schema SQL or Flyway history was changed.

## Final decision and remaining work

**Phase 2L status: PARTIAL — JAVA 25 CI VALIDATION INCOMPLETE.** Java 25 is correctly the primary production runtime in the NIS container, primary Java GitHub Actions lane, and CodeQL configuration. Java 17 compatibility and compiler release 17 remain intact. Full Java 25/17 regressions and the Phase 2L-A runtime probe pass; the NIS Jenkins build and unit-test scripts also pass under Java 25.

Jenkins cannot yet be declared Java 25 primary because the pinned/current external shared-library image map only supplies Java 11, 17, and 21 choices, the Java 25 Jenkins image publication/selection is externally owned, and no live hosted Jenkins run was accessible. The actual Jenkins Failsafe lane also needs a lifecycle-safe Mockito agent setup and an integration environment with its required nodes, services, and database inputs before its tests can be accepted. Do not mark Phase 2L COMPLETE until an external Java 25 build image/contract and a green required hosted Jenkins execution are available, or the repository's required Jenkins contract is deliberately redefined by its owners.

This does not reopen or block Phase 2F or Phase 2K. Phase 2F is **COMPLETE / CLOSED** under its formal closure record; Phase 2K is **COMPLETE / CLOSED** under its persisted-chain final acceptance record. Phase 2L-A's former `PARTIAL` is its historical phase result; this Phase 2L-B record is the current decision.
