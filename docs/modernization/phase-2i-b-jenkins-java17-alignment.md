# NEM modernization — Phase 2I-B Jenkins Java 17 build path

Status: **BLOCKED — the configured Java 17 image is not published**
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Starting HEAD: `eb57b7169b0a9d758c7e9f2fca6fe97709f7699a`
Configuration/code final HEAD: `eb57b7169b0a9d758c7e9f2fca6fe97709f7699a` (no configuration or production code changes; this investigation is recorded in a separate documentation commit).

Phase 2I-B investigated the repository's Jenkins image selection and whether
NIS can use a confirmed Java 17 image through the pinned shared-library
interface. No Jenkins image was available to validate, so no Jenkinsfile or
CI script was changed. Jetty migration was not started.

## Repository Jenkins inventory

Repository-owned Jenkinsfiles are present in `core`, `deploy`, `gocrypto`,
`nis`, `openapi`, and `peer`, plus `.github/jenkinsfile/` and
`infra/jenkins/seed-job/`. The Java Maven modules `core`, `deploy`, `peer`,
and `nis` all select `ciBuildDockerfile = 'java.Dockerfile'`. Their build
scripts run `mvn clean compile -B`; their test scripts run `mvn test -B`
followed by `mvn failsafe:integration-test -B`. NIS also installs Core,
Deploy, and Peer dependencies in `scripts/ci/setup_build.sh`.

No other repository-owned Java image selector, Java version parameter, or
Jenkins Java installation configuration was found. The repository has no
Jenkins-specific Maven toolchain file. GitHub Actions Java 17/25 workflows
are separate hosted workflows and are not authoritative replacements for
the Jenkins gate.

## Jenkinsfile → shared library → image resolution

The NIS Jenkinsfile calls `defaultCiPipeline` with `operatingSystem =
['ubuntu']` and `ciBuildDockerfile = 'java.Dockerfile'`. The pinned `_symbol`
submodule is external (`https://github.com/symbol/symbol`) and is pinned at
`0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf`.

In that pinned shared-library source:

1. `defaultCiPipeline.groovy` publishes a `CI_ENVIRONMENT` choice parameter.
2. `resolveCiEnvironmentName` uses the Jenkinsfile's `environment` value if
   supplied; otherwise it derives `java` from `java.Dockerfile`.
3. The default choice gets the OS/base suffix, producing `java-ubuntu-lts`.
4. `jobHelper.resolveBuildImageName` prefixes ordinary tags with
   `symbolplatform/build-ci:`, so the default becomes
   `symbolplatform/build-ci:java-ubuntu-lts`.
5. A Jenkinsfile `environment = 'java-ubuntu-base'` would make that tag the
   default CI environment through this source-defined interface. The
   library's resolver treats names ending in `-base` as complete environment
   names and resolves them to `symbolplatform/build-ci:java-ubuntu-base`.

This confirms a repository-side tag selection interface in the pinned
library source. It does not confirm the globally installed Jenkins shared
library version or the live job's effective configuration. The shared
pipeline also executes `git submodule update --remote` during its setup
stage, so operators must identify the shared-library revision actually
loaded by Jenkins when validating the live run.

## Java version selection and image registry results

The pinned `_symbol/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml`
maps `ubuntu-lts` to Ubuntu 24.04 / Java 11, `ubuntu-base` to Ubuntu 22.04 /
Java 17, and `ubuntu-latest` to Ubuntu 26.04 / Java 21. The pinned
`_symbol/jenkins/docker/ubuntu/java.Dockerfile` defaults `JAVA_VERSION=11`;
the image builder passes the selected mapping's Java version as
`JAVA_VERSION` when it builds the `java` image.

Docker Hub's current `symbolplatform/build-ci` tag listing (queried
2026-09-26) contains 97 tags. It contains:

| Image tag | Registry digest | Evidence / assessment |
| --- | --- | --- |
| `java-ubuntu-lts` | `sha256:2d37b527b743b8dbf0a540b4da4e3c7b707770e120d1dc1622d77586679a2bdf` | Present; previously pulled and confirmed Java / `javac` 11.0.32, Maven 3.8.7. |
| `java-ubuntu-latest` | `sha256:6190dbcfd2a1c6ab28aca0962ee6e27d62186bdbe263f264bb493016b55aa336` | Present, but pinned mapping selects Java 21; not the Java 17 baseline. |
| `java-ubuntu-22.04` | `sha256:f1ed1259a103c56afb6c52dbfb187ad6769f0606150a13abce95e3f1efa03d2e` | Present; previously confirmed Java 11.0.20 / Maven 3.6.3. |
| `java-ubuntu-base` | — | **Absent** from the current registry tag listing and registry manifest lookup. No digest or runtime version can be verified. |

The pinned image builder supports a manual `BASE_IMAGE=base` build and would
name the resulting image `symbolplatform/build-ci:java-ubuntu-base`, with
architecture-specific tags and a multi-architecture manifest. Its
`build-ci-image-all` job does not currently dispatch a Java `base` build;
the automatic Java builds cover the default LTS and `latest` variants. Thus
the Java 17 mapping exists, but its expected image has not been published.

The tags that currently exist are mutable. The shared pipeline has a
`CI_ENVIRONMENT` parameter and its image resolver passes strings containing a
colon through as a full image reference, while the default environment
resolver adds an OS/base suffix unless the name ends in `-base`, `-lts`, or
`-latest`. The pinned Jenkinsfile interface does not expose a dedicated
immutable digest property as the default. A digest can be offered as an
additional selectable environment, but that alone does not make the default
reproducibly pinned. No mutable tag has been treated as Java 17 based on its
name alone.

## Validation and current evidence

No Jenkins job/controller/agent was accessible. Therefore this phase has no
Jenkins run result and no Jenkins runtime Java, `javac`, or Maven version.
The prior inspection of `symbolplatform/build-ci:java-ubuntu-lts` remains
valid for that image only: Java / `javac` 11.0.32 and Maven 3.8.7. It is not a
Java 17 validation.

GitHub Actions remains green on the previous Phase 2I-A final commit
`eb57b7169b0a9d758c7e9f2fca6fe97709f7699a`:

- [Java 17 Baseline run 36235166171](https://github.com/nemnesia/nem/actions/runs/36235166171): success, Temurin 17; clean test and package passed.
- [Java 25 Compatibility run 36235166205](https://github.com/nemnesia/nem/actions/runs/36235166205): success, Temurin 25; clean test and package passed.

The Phase 2I-A local Java 17 baseline remains the latest local test record:
OpenJDK / `javac` 17.0.20.1, Maven 3.8.7, 624 classes / 6,218 tests with no
failures, errors, or skips, and package success. Phase 2I-B changed
documentation only, so those build commands were not rerun. No Java 25 local
validation was run in this phase.

The existing Phase 2F / Phase 2H known Failsafe results remain unchanged,
including Core timing failure and NIS 9 failures / 21 errors / 2 skipped.
Legacy H2 1.4 file failures remain subject to the offline export/import
compatibility gate. No attempt was made to change or reinterpret them.

## External owner handoff

The `_symbol` / Jenkins image owners need to:

1. Publish the already configured `ubuntu-base` Java 17 image through the
   image builder (`CI_IMAGE=java`, `OPERATING_SYSTEM=ubuntu`,
   `BASE_IMAGE=base`) for both `amd64` and `arm64`, then publish and verify
   the multi-architecture `symbolplatform/build-ci:java-ubuntu-base` tag.
2. Record the resulting immutable multi-architecture digest and provide a
   supported shared-library/Jenkinsfile setting that makes this exact
   reference the default. Avoid relying on `latest` or an unverified
   floating tag. If the current resolver cannot express an immutable
   default, add a documented image-reference property or correct the
   resolver in the externally owned shared library.
3. Confirm the live Jenkins job uses a shared-library revision that supports
   the selected image setting. The job must expose or log the effective
   `CI_ENVIRONMENT` and final image reference.

After the image is published and the interface is documented, the NIS
repository can set `environment = 'java-ubuntu-base'` in the Java module
Jenkinsfiles (`core`, `deploy`, `peer`, and `nis`) if the shared library
resolves the tag as described above. The Jenkins build should print
`java -version`, `javac -version`, and `mvn -version` before build/test
stages, and execute `mvn -B clean test` and
`mvn -B -DskipTests package`. The current scripts instead split clean
compile, unit test, and Failsafe integration test stages; any command
adjustment must preserve the recorded Failsafe baseline and avoid masking
its known failures.

## Gate decision

**Phase 2I Jetty migration entry gate: BLOCKED.** GitHub Actions Java 17 is
successful, but the expected Java 17 Jenkins build image is absent, its
immutable digest cannot be recorded, and the live Jenkins runtime and Maven
commands cannot be validated. Do not begin Jetty migration.

Reconsider the gate only after all of the following hold:

- The external image owners publish a Java 17 build image and an immutable
  multi-architecture reference, with its Java / `javac` / Maven versions
  verified.
- A repository/shared-library configuration selects that reference
  reproducibly and the live Jenkins job confirms the effective reference.
- Jenkins runs on Java 17 and succeeds at
  `mvn -B clean test` and `mvn -B -DskipTests package`; known Failsafe
  failures are compared with their existing baseline and not silently
  reclassified.
- GitHub Actions Java 17 remains successful.
- Phase 2F's H2 1.4 offline conversion requirement, V1.0.0–V1.0.7 migration
  history/checksum constraints, representative database verification, and
  matching-genesis full-chain comparison remain explicit.

Rollback point: starting HEAD
`eb57b7169b0a9d758c7e9f2fca6fe97709f7699a`. This phase added no
configuration or production changes; the investigation document can be
reverted independently.
