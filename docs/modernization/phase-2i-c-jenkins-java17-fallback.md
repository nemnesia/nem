# NEM modernization — Phase 2I-C Jenkins Java 17 fallback investigation

Status: **BLOCKED — external image/shared-library owner required**
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested / actual starting HEAD: `c51a7bdf7e8484b281275e5b954347d4c162e51b`
Final configuration/evidence HEAD: `c51a7bdf7e8484b281275e5b954347d4c162e51b` (documentation-only phase; the report is committed separately).

Phase 2I-C checked whether NEM can select and run a reproducible Java 17
Jenkins build container using only repository-controlled changes. No
repository-controlled path satisfies the full pipeline, immutable selection,
and Java 17 verification requirements. No Jenkinsfile, CI script, dependency,
application source, or production behavior was changed. Jetty migration was
not started.

## Pinned `_symbol` interface

The NEM gitlink pins external repository `https://github.com/symbol/symbol`
at `0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf`. The checked-in Java Jenkinsfiles
(`core`, `deploy`, `peer`, `nis`) set `ciBuildDockerfile = 'java.Dockerfile'`.
The shared-library image-selection path in that pinned revision is:

1. `defaultCiPipeline.groovy` creates a `CI_ENVIRONMENT` Jenkins choice
   parameter from `resolveCiEnvironment(jenkinsfileParams)`.
2. `jobHelper.resolveCiEnvironmentName` uses a Jenkinsfile `environment`
   string if present; otherwise it reads the prefix of
   `ciBuildDockerfile`, yielding `java`.
3. Unless the value ends in `-base`, `-lts`, or `-latest`,
   `resolveCiEnvironment` appends the first OS and `-lts`. The automatic
   choice is therefore `java-ubuntu-lts`.
4. `jobHelper.resolveBuildImageName` prefixes values without a colon with
   `symbolplatform/build-ci:`. Values containing a colon are passed through
   as complete image references.
5. The Docker stage uses that result as its image. `dockerArgs` only adds
   Docker run arguments; it does not select or build the image.

The declared Jenkinsfile property `environment = 'java-ubuntu-base'` would
select `symbolplatform/build-ci:java-ubuntu-base` as the default through this
source-defined interface. The pinned `_symbol` mapping describes
`ubuntu-base` as Ubuntu 22.04 with Java 17, but that tag has not been
published in the registry.

`otherEnvironments` appends choices after the default and can contain a full
image reference. For example, a user could manually select an
`otherEnvironments` entry containing a digest; `resolveBuildImageName` would
pass it through. That is a selectable manual alternative, not a reproducible
repository default. The `environment` resolver appends an OS/base suffix to
full references that do not happen to end in `-base`, `-lts`, or `-latest`,
so putting a digest directly in `environment` produces a malformed
reference. The supported choice is a Jenkins `choice`, not a documented
arbitrary environment-variable override. Although source does not validate
the submitted value before passing it to Docker, relying on a value outside
the parameter choices would be undocumented Jenkins behavior and is not
accepted as a baseline.

Top-level `ARCHITECTURE` choices are `arm64` and `amd64`; absent an explicit
selection the shared library defaults the build container to `arm64`. NIS
publishing also uses the selected architecture. An immutable image used by
this pipeline must therefore be a multi-architecture manifest, or the
pipeline must intentionally pin architecture-specific digests.

## Candidate image results

### `symbolplatform/build-ci` images

The pinned `baseImages.yaml` maps `ubuntu-lts` to Java 11,
`ubuntu-base` to Java 17, and `ubuntu-latest` to Java 21. The image builder
can manually build `CI_IMAGE=java`, `OPERATING_SYSTEM=ubuntu`,
`BASE_IMAGE=base`; its expected multi-architecture tag is
`symbolplatform/build-ci:java-ubuntu-base`. The scheduled/all-image pipeline
dispatches Java LTS and Java latest builds, but not Java base.

Docker Hub's `symbolplatform/build-ci` tag list was queried on 2026-09-26.
It contains 97 tags; `java-ubuntu-base` is absent. A registry manifest lookup
also fails for that tag. No immutable digest, architecture manifest, pull,
or Java runtime check exists for this expected image. Existing
`java-ubuntu-lts` was previously pulled and measured at Java / `javac`
11.0.32 and Maven 3.8.7. `java-ubuntu-latest` exists but the pinned
configuration maps it to Java 21, so it is not used as Java 17 verification.

### Docker Official Maven + Temurin 17 candidate

The image `maven:3.9.16-eclipse-temurin-17-noble` was pulled by immutable
multi-architecture index digest:

`maven@sha256:f1ab639371711ac150c8e344bff7d39d8161c17a531b5dd653d3fa03af554807`

The Docker Hub `library/maven` metadata and manifest show Linux `amd64` and
`arm64/v8` images. The candidate was pulled and executed on `linux/amd64`:

- Java: Temurin `17.0.20.1`
- `javac`: `17.0.20.1`
- Maven: `3.9.16`
- Present: `git`, `curl`, `bash`
- Missing in the image: `gitlint`, `codecov`, `python3`/`pip`, `shellcheck`,
  `gradle`, `zip`, and `unzip`

The selected multi-architecture index digest is immutable. Its manifest
identifies the source as
`https://github.com/carlossg/docker-maven.git#1efa2614402e9645749d6e235c93ada60762b267:eclipse-temurin-17-noble`,
base image `eclipse-temurin:17-jdk-noble`, and the Docker Official Images
attestation marker. The runtime and Maven versions were measured from the
pulled digest; the registry manifest advertises both `amd64` and `arm64/v8`.

This is not a complete replacement for the shared Java build image. Before
the normal Java build/test stages, pinned `defaultCiPipeline` runs
`verifyCommitMessage()`, which calls `linters/scripts/lint_last_commit.sh`
and invokes `gitlint`. For the NIS Java module, the shared pipeline also
executes the Jacoco check and Codecov upload, which invokes `codecov`.
Those required commands are missing from the Maven image. It also lacks
other tools included in the existing `java.Dockerfile` build image. The
official image is only an optional parameter choice through
`otherEnvironments`; it cannot be selected as the repository default by the
current `environment` resolver without suffixing the reference. It was not
adopted as NIS's Jenkins baseline.

## Repository-controlled fallback assessment

| Route | Finding |
| --- | --- |
| A — NEM Jenkinsfile selects an existing public Java 17 image by immutable digest | **Not available.** `symbolplatform/build-ci:java-ubuntu-base` is absent. The official Maven image has a verified digest but current resolver cannot make it the default through `environment`, and it lacks tools required by the standard shared pipeline. |
| B — NEM repository-owned Dockerfile creates Jenkins build agent image | **Not available through the pinned shared-library interface.** `ciBuildDockerfile` is used to derive a registry tag; the shared pipeline directly starts a Docker agent from that image. It does not build an agent image from the NEM workspace Dockerfile. The external `_symbol` image builder builds only its own `jenkins/docker/<os>/<tool>.Dockerfile` and publishes to `symbolplatform/build-ci`. A new NEM image would require an external build/publish/registry and Jenkins integration owner. |
| C — official Temurin/Maven image through shared-library override | **Selectable manually, but not a usable standard path.** `otherEnvironments` allows a full-reference choice, but not as the repository default. The verified Maven image lacks `gitlint` and `codecov`, which the pinned pipeline requires. A custom full image added to that list would also need operator selection rather than serving as the enforced default. |
| D — update `_symbol` to upstream dev for existing support | **No useful new interface found; update rejected.** Upstream `dev` at `14a0cc16ef277875fb9af71b3a0d03b80f27283e` retains the same `CI_ENVIRONMENT`, `environment`, `otherEnvironments`, image resolver, and base-image mapping; it does not add a digest-default or arbitrary-image property, and Docker Hub still has no `java-ubuntu-base` tag. The comparison spans 85 commits and changes many unrelated Catapult, SDK, docs, and build files. In the pipeline code, `defaultCiPipeline.groovy` changes Docker args to add `--ulimit nofile=65536:65536`; `publish.groovy` adds Gradle publishing and changes artifact repository handling. Updating the pin would introduce unrelated behavior without resolving Java 17 selection. |

No undocumented image environment variable, Jenkins parameter override, or
NEM-owned image build path was found that provides an enforced,
reproducible Java 17 default under the pinned library. Jenkins API callers
may be able to submit values outside a choice list, but this is not a
documented or validated contract and is not used.

## Existing Java validation and CI

Phase 2I-C made no Java source, Maven, test script, or application changes;
the Java 17 clean test/package suite was not rerun. The latest local Java 17
record remains Phase 2I-A: OpenJDK / `javac` `17.0.20.1`, Maven `3.8.7`,
`mvn -B clean test` with 624 classes / 6,218 tests and zero failures,
errors, or skips, and `mvn -B -DskipTests package` success.

On starting commit `c51a7bdf7e8484b281275e5b954347d4c162e51b`, hosted
GitHub Actions reported:

- [Java 17 Baseline run 36235747400](https://github.com/nemnesia/nem/actions/runs/36235747400): success, Temurin 17 clean test and package.
- [Java 25 Compatibility run 36235747397](https://github.com/nemnesia/nem/actions/runs/36235747397): success, Temurin 25 clean test and package.

Java 25 remains a compatibility target and was not substituted for Java 17
runtime verification. No live Jenkins controller/agent was accessible.
There is no Jenkins build, Java/Javac/Maven output, clean test result, or
package result for Phase 2I-C. Do not infer a Jenkins pass from the GitHub
Actions runs or the pulled candidate image.

The prior Core timing-sensitive Failsafe issue and NIS 9 failures / 21
errors / 2 skipped remain unchanged. The Phase 2F H2 1.4 offline
export/import requirement, migration history/checksum constraints,
representative Mainnet/Testnet database verification, and
matching-genesis full chain-state comparison remain explicit. No database
or Failsafe behavior was altered.

## External owner handoff and gate

The `_symbol` image/shared-library owners must:

1. Publish the configured Java 17 `java-ubuntu-base` image for `amd64` and
   `arm64`, verify Java / `javac` / Maven and required shared-pipeline tools,
   and publish its multi-architecture digest; or publish an equivalent
   supported Java 17 CI image.
2. Add/document a shared-library property or resolver path that lets a
   repository Jenkinsfile select a full image reference or immutable digest
   as the **default**, without the current OS/base suffix transformation.
3. Preserve required pipeline tools (`gitlint`, `codecov`, and other
   existing CI utilities), expose/log the effective image reference, and
   confirm the globally installed library revision used by Jenkins.
4. Provide a live Jenkins job run on Java 17 with `java -version`,
   `javac -version`, `mvn -version`, successful
   `mvn -B clean test`, and successful
   `mvn -B -DskipTests package`. Existing `mvn clean compile -B`,
   `mvn test -B`, and `mvn failsafe:integration-test -B` stages and known
   Failsafe baseline must not be silently changed or reclassified.

After that external interface and image exist, NEM can set the supported
image reference in `core`, `deploy`, `peer`, and `nis` Jenkinsfiles, log the
runtime versions early, and verify an actual Jenkins execution.

**Final status: BLOCKED — EXTERNAL IMAGE / SHARED-LIBRARY OWNER REQUIRED.**
The Phase 2I Jetty entry gate remains **BLOCKED**. Do not begin Jetty
migration. Rollback point is starting HEAD
`c51a7bdf7e8484b281275e5b954347d4c162e51b`; this phase changes only this
investigation record.
