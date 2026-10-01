# Phase 2L-C — Java 25 CI environment / integration lane closure

## Decision

**PARTIAL — JAVA 25 CI ENVIRONMENT NOT YET CLOSED.** A Java 25 NIS Jenkins-equivalent container was built locally from the pinned Symbol Java CI Dockerfile and successfully ran `setup_build.sh`, `build.sh`, and the unit portion of `test.sh`. The Java 25 unit suite passed (3,496 tests). The same scripts also compiled and passed the unit suite under Java 17. The integration lane was made lifecycle-correct so its Mockito agent resolves and Failsafe failures fail the stage; its 79 tests ran on both JDKs, with the same external-service/database and timing-sensitive failure categories. No Java 25-specific integration regression was identified.

Phase 2L cannot be closed from this repository alone: the pinned shared-library selection offers no Java 25 mapping, the Java 25 `symbolplatform/build-ci` image is not published, and hosted Jenkins access is unavailable here. The normal NIS Jenkins job currently derives `java-ubuntu-lts`, which is mapped to Java 11. External Symbol shared-library/image publication and hosted rollout remain required to make Java 25 selectable in the actual job.

## Repository and starting state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `cf2168b687bf006a49ca7cb6dad152e4eb6a333f`
- Actual starting HEAD: `cf2168b687bf006a49ca7cb6dad152e4eb6a333f` (matches)
- Starting local and `origin/agent/nis-phase0-baseline` HEAD: equal to the requested HEAD.
- Starting working tree: only the pre-existing `.gitignore` `legacy/` addition. It was not changed, staged, or committed.
- Changed repository files: `nis/scripts/ci/test.sh` and this record only.
- CI correction commit: `d594ce5bd8895655a6e0f3bee71a86e20b59e9dc` (`[nis] test: ensure Failsafe failures fail Jenkins`).
- Final HEAD: the Phase 2L-C validation commit; its full object ID is reported in the Git completion result.
- No production runtime, dependency, Docker, POM, schema, Flyway, protocol, or consensus configuration changed.
- Phase 2F and Phase 2K remain **COMPLETE / CLOSED**.

## Jenkins execution contract

NIS `Jenkinsfile` passes `operatingSystem=['ubuntu']`, `instanceSize='medium'`, and `ciBuildDockerfile='java.Dockerfile'` to `defaultCiPipeline`. It does not set `environment` or `otherEnvironments`.

In the pinned `_symbol` gitlink (`symbol/symbol` commit `0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf`):

1. `jobHelper.resolveCiEnvironmentName` uses the configured `environment` if supplied; otherwise it takes the prefix of `ciBuildDockerfile`, here `java`.
2. `defaultCiPipeline.resolveCiEnvironment` appends `-ubuntu-lts` for this name. With no `otherEnvironments`, the NIS choice resolves to `java-ubuntu-lts`.
3. `resolveBuildImageName` turns that into `symbolplatform/build-ci:java-ubuntu-lts`.
4. Jenkins selects an outer node from OS / architecture / size, then starts the Docker agent for the selected image on the same workspace (`reuseNode true`). `setup_build.sh`, `build.sh`, and `test.sh` are the repository stage scripts.

The shared-library `baseImages.yaml` maps Ubuntu LTS to Ubuntu 24.04 / Java 11, Ubuntu base to Ubuntu 22.04 / Java 17, and Ubuntu latest to Ubuntu 26.04 / Java 21. Those mappings are not all exposed by this NIS Jenkinsfile: without `otherEnvironments`, the NIS job's configured default is Java 11. Its builder's `build-ci-image.groovy` accepts `lts`, `base`, or `latest`; it builds `jenkins/docker/ubuntu/java.Dockerfile`, passes the selected YAML Java value as `JAVA_VERSION`, and publishes `symbolplatform/build-ci:<CI_IMAGE>-<OS>-<BASE_IMAGE>`. `build-ci-image-all.groovy` schedules the Java LTS and Ubuntu-latest jobs, not a Java 25 job.

The Jenkins Docker recipe installs headless OpenJDK, Git, curl, libssl, Maven, certificates, zip/unzip, Python/pip/venv, shellcheck, Codecov uploader, gitlint, and Gradle. It runs as `ubuntu` UID 1000. The outer agent/container launch is controlled by the external Symbol Jenkins shared library and Jenkins infrastructure.

Source references: [pinned NIS pipeline contract](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/shared-library/vars/defaultCiPipeline.groovy), [environment resolver](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/shared-library/vars/jobHelper.groovy), [image mapping](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml), [image builder](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/infra/jenkins/build-ci-image.groovy), [image recipe](https://github.com/symbol/symbol/blob/0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf/jenkins/docker/ubuntu/java.Dockerfile).

## Image availability and local container execution

The public `symbolplatform/build-ci:java-ubuntu-latest` manifest exists; its inspected image was Ubuntu 26.04.1, OpenJDK/Javac 21.0.12.1, Maven 3.9.12. Docker registry manifest checks found no `symbolplatform/build-ci:java-ubuntu-25` or `symbolplatform/build-ci:java-ubuntu-jdk25` tag. The pinned and current shared-library maps contain no Java 25 entry.

A prospective container was nevertheless constructed locally from the pinned `jenkins/docker/ubuntu/java.Dockerfile` recipe with `FROM_IMAGE=ubuntu:26.04` and `JAVA_VERSION=25`:

```bash
docker build --pull --build-arg FROM_IMAGE=ubuntu:26.04 \
  --build-arg JAVA_VERSION=25 -f java.Dockerfile \
  -t nem-phase2lc-build-ci-java25:prospective .
```

The local image ID is `sha256:61cbbe4d985719b15348ff57467abdaf4be150a1ff6ed38374e449815910032b` (linux/amd64). It contains Ubuntu 26.04.1, OpenJDK/Javac `25.0.4.1` (`openjdk-25-jdk-headless` package `25.0.4.1+1-1~26.04.4`), and Maven `3.9.12` (Ubuntu package `3.9.12-1`). `JAVA_HOME` and `MAVEN_HOME` are unset by this recipe; `java`/`javac` resolve from the system alternatives and Maven uses `/usr/share/maven`. The container ran as `ubuntu` UID 1000. Compiler release remained 17.

The image is a local prospective build, not a Symbol-published image and not selectable by the current NIS shared-library contract. It was built for `linux/amd64`; no hosted Jenkins executor or multi-architecture published manifest was tested.

Commands executed in clean disposable source copies mounted into that image:

```text
bash scripts/ci/setup_build.sh
bash scripts/ci/build.sh
bash scripts/ci/test.sh
```

`setup_build.sh` installed Core, Deploy, and Peer; all three Maven invocations passed. `build.sh` (`mvn clean compile -B`) passed. On Java 25, `test.sh` passed the 3,496-test unit suite with 0 failures, 0 errors, 0 skips, then launched all 79 Failsafe integration tests. On Java 17 `17.0.20.1` mounted read-only into the same Ubuntu 26.04/Maven 3.9.12 container tooling, setup and compile passed and the 3,496-test unit suite also passed 0/0/0.

## Integration lane and narrow CI correction

`defaultCiPipeline` invokes the repository `test.sh` in its normal “run tests” stage; there is no separate optional NIS integration-test stage and the repository has no `setup_test.sh`. Therefore the Failsafe execution is part of this Jenkins test gate.

Before this phase the script used two Maven processes:

```bash
mvn test -B
mvn failsafe:integration-test -B
```

The second process did not resolve `${org.mockito:mockito-core:jar}` in the configured agent argument, so the fork failed before any integration tests ran. Also, calling `failsafe:integration-test` without `failsafe:verify` could return `BUILD SUCCESS` despite failing reports. `nis/scripts/ci/test.sh` now uses one Maven session and verifies the reports:

```bash
mvn test failsafe:integration-test failsafe:verify -B
```

This leaves all tests enabled and makes actual Failsafe failures fail the Jenkins stage. The corrected command launched the integration fork on both JDKs and returned Maven `BUILD FAILURE` for reported integration failures, as intended.

The suite contained the same 79 tests on each runtime:

| Class group | Count | Result / external dependency |
|---|---:|---|
| `BasicNodeSelectorITCase` | 2 | Local selection tests; pass on both JDKs. |
| `BlockScorerITCase` | 13 | Randomized/time-sensitive chain-score assertions; 2 failures on Java 25, 4 on Java 17. |
| `DefaultHashCachePerformanceITCase` | 6 | Performance/timing assertions; 3 failures on each JDK. |
| `HttpConnectorITCase` | 3 | Contacts public NEM nodes; 1 failure and 1 timeout/error on each JDK. |
| Controller acceptance cases | 16 | Account, block, push, and transfer endpoints; 16 connection-refused errors on each JDK because the required local NIS service is not running. |
| DAO / storage cases | 4 | Require external hard-disk DB/data; 4 errors on each JDK. Includes an incompatible H2 file under `/home/harvestasya/nem/nis/data/nis5_mijinnet.mv.db` and an H2 storage case issuing `SET FOREIGN_KEY_CHECKS=0`. |
| `H2ITCase` | 1 | Explicitly skipped on both JDKs. |
| `PoiImportanceCalculatorITCase` | 14 | Local calculation/performance path; 1 assertion failure on each JDK. |
| `TimeSynchronizationITCase` | 20 | Simulation/timing-sensitive; 1 failure and 1 skip on each JDK. |

Totals: Java 25 **79 tests, 8 failures, 21 errors, 2 skipped**; Java 17 **79 tests, 10 failures, 21 errors, 2 skipped**. The differences were in randomized/performance-sensitive assertions. External-node timeouts, refused local service connections, database/configuration failures, and the timing categories occurred on both JDKs. No Java 25-specific integration compatibility failure was observed. A prior Java 17 host-only run stopped before Failsafe because `bob.nem.ninja` could not resolve; the matched container comparison above passed all unit tests and did reach the same Failsafe suite on both runtimes.

These integration results do not establish a green required Jenkins gate. The corrected script correctly rejects this environment until its external NEM node, local services, required hard-disk database, and timing-test conditions are provisioned or their intended CI contract is clarified. No test was deleted, skipped, or weakened.

## Java 25 full-reactor regression

Java 25 `mvn -B clean test` and `mvn -B clean package` both passed in the prospective container. Each run discovered **6,227 tests, 0 failures, 0 errors, and 0 skipped** (Core 2,364; Deploy 61; Peer 306; NIS 3,496). The Java 25 runtime was OpenJDK/Javac `25.0.4.1`, Maven `3.9.12`. Phase 2L-C changes only the NIS Jenkins test invocation and does not change the production runtime or persisted-chain code; the Phase 2L-A production runtime probe and Phase 2K Mainnet/Testnet acceptance remain applicable.

## Hosted Jenkins and external change boundary

No Jenkins controller URL/session, Jenkins credential, VPN access, external executor administration, or Docker Hub publishing credential is available in this workspace. No hosted run was attempted or fabricated.

The external change required to make Java 25 selectable is owned by the `symbol/symbol` shared-library and build-ci image maintainers, plus Symbol Jenkins/Docker Hub administrators. A minimal handoff is:

1. In shared-library `resources/buildEnvironment/baseImages.yaml`, add an Ubuntu `jdk25` entry using `ubuntu:26.04` and `java: 25`.
2. In `infra/jenkins/build-ci-image.groovy`, expose `jdk25` in `BASE_IMAGE`; schedule `java`/`jdk25` in `build-ci-image-all.groovy`.
3. In `shared-library/vars/defaultCiPipeline.groovy`, recognize the `-jdk25` suffix as a complete environment name. Publish and verify both architecture images and the common manifest tag `symbolplatform/build-ci:java-ubuntu-jdk25` using the existing `java.Dockerfile` recipe.
4. After the shared-library change is pinned into NEM, set NIS `Jenkinsfile` `environment = 'java-ubuntu-jdk25'` so Java 25 becomes the Jenkins choice/default. Run the hosted NIS job and capture its commit, agent/image, Java, Maven, unit, Failsafe, and final results.

The NEM repository cannot publish this external image or alter the hosted shared-library/job configuration. A locally reproducible image is useful compatibility evidence, but it is not a substitute for that external mapping or published executor.

## Database and status boundaries

Both supplied Mainnet/Testnet DB originals were excluded from the disposable source copies and were not opened by this phase. A close-out read-only identity check matched the accepted Phase 2K values: Mainnet `1,073,152` bytes / `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5`, mtime `2026-09-28 21:40:07.899431491 +0900`; Testnet `1,064,960` bytes / `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87`, mtime `2026-09-28 21:38:22.663025304 +0900`. They were never mounted into the validation containers. The CI-only script change does not affect production runtime, dependencies, Flyway, or chain behavior. No persisted-chain replay was repeated.

- Phase 2F: **COMPLETE / CLOSED**, per its formal closure record.
- Phase 2K: **COMPLETE / CLOSED**, unchanged.
- Phase 2L-A / 2L-B: historical records remain unchanged; this is the current Phase 2L-C status.
- Phase 2L-C: **PARTIAL**. NIS Java 25 container compatibility and unit lane passed, and the split-Failsafe defect is fixed. Java 25 is not selectable from the current external shared-library contract, no Java 25 image is published, hosted Jenkins could not be run, and the required integration test stage fails on missing external prerequisites and timing-sensitive assertions under both Java versions.

## Remaining risks

- Java 25 is not the actual NIS Jenkins runtime until the external image/mapping is published and pinned.
- Hosted Jenkins operation, multi-architecture image publication, and hosted branch execution remain unverified.
- The now-correctly-failing integration lane needs a provisioned/contracted test environment before an end-to-end hosted pass can be accepted.
- Existing time/random/performance integration assertions are nondeterministic across Java versions and need separate owner review; they were not relaxed here.
- No production or persisted-chain behavior was changed by this phase.
