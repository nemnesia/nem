# NEM modernization — Phase 2I-A Java 17 CI alignment

Status: **BLOCKED — Java 17 hosted CI is passing; Jenkins build path remains Java 11**
Date: 2026-09-26 (Asia/Tokyo)
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested starting HEAD: `ae9ba564a12e96eb7338b6a524673902f8a8b7bb`
Actual starting HEAD: `ae9ba564a12e96eb7338b6a524673902f8a8b7bb`

Phase 2I-A aligns the repository's hosted Java baseline and investigates the
configured Jenkins build path. Jetty and application modernization were not
started. The earlier BLOCKED entry-gate record in
`phase-2i-entry-gate.md` is retained as history; this document records the
follow-up work and current gate state.

## GitHub Actions inventory and change

Before this change, `.github/workflows/java17-baseline.yaml` and
`.github/workflows/java25-compatibility.yaml` triggered on pushes to `dev` and
`main`, pull requests targeting `dev`, and manual dispatch. There were no
pushes to those branch names and no pull request targeting `dev` for
`agent/nis-phase0-baseline`; consequently the public Actions API reported zero
runs for the branch. A workflow file existing on the branch did not by itself
start a hosted run.

Both workflows now include `agent/nis-phase0-baseline` in the push branch
filter. They retain `workflow_dispatch`, use `actions/checkout@v7` and
`actions/setup-java@v6`, request Temurin 17 and Temurin 25 respectively, and
use Maven dependency caching. Each workflow prints `java -version` and
`mvn -version`, runs `mvn -B clean test`, then `mvn -B -DskipTests package`.
The Java 17 workflow is explicitly named as the production baseline; Java 25
remains a separate compatibility target. No permissions or path filters
restrict these push events.

The workflow changes were pushed in commit
`f073b136c427458e4b80f0b8a13fca8a12898ec7`. Hosted Actions ran on that exact
commit:

| Workflow | Run | Commit | Result |
| --- | --- | --- | --- |
| Java 17 Baseline | [36234867721](https://github.com/nemnesia/nem/actions/runs/36234867721) | `f073b136c427458e4b80f0b8a13fca8a12898ec7` | Success; clean test and package steps completed |
| Java 25 Compatibility | [36234867700](https://github.com/nemnesia/nem/actions/runs/36234867700) | `f073b136c427458e4b80f0b8a13fca8a12898ec7` | Success; clean test and package steps completed |

The workflow pages expose the successful run and commit but require GitHub
authentication to view job logs. The workflow requested Temurin 17/25, but
the precise hosted patch JDK and Maven versions printed by the job cannot be
retrieved from this unauthenticated environment. Do not infer those patch
versions from the successful status.

## Local validation

Local Java 17 validation used OpenJDK `17.0.20.1` (Ubuntu) and Maven
`3.8.7`.

- `mvn -B clean test`: passed on final rerun; 624 test classes and 6,218 tests,
  with 0 failures, 0 errors, and 0 skipped.
- The first full run had one timing-sensitive failure in
  `ExceptionUtilsTest.propagateSetsThreadInterruptFlagWhenMappingInterruptedException`.
  Its targeted rerun passed 18/18 tests; the subsequent full clean test run
  passed. No source change was made for this scheduler-sensitive result.
- `mvn -B -DskipTests package`: passed for all modules.

Java 25 was not rerun locally in Phase 2I-A. The hosted Java 25 compatibility
run above passed both commands. Phase 2H's local Java 25 success remains part
of the earlier recorded baseline.

Failsafe integration tests were not rerun in this CI-alignment phase. The
Phase 2H / Phase 2F records remain authoritative: Core timing failure and NIS
9 failures / 21 errors / 2 skipped, including legacy H2 1.4 file errors. The
H2 1.4 to H2 2.x offline export/import requirement is unchanged. These known
results were not reclassified or fixed here.

## Jenkins investigation

`nis/Jenkinsfile` selects `java.Dockerfile`. Its pinned `_symbol` submodule
provides the Dockerfile and shared-library image mapping. `.gitmodules` points
`_symbol` to the externally owned `https://github.com/symbol/symbol`
repository; this checkout pins it at
`0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf`. No `_symbol` files or gitlink were
changed.

The Jenkins shared library resolves the NIS default build image to
`java-ubuntu-lts`, which runs `symbolplatform/build-ci:java-ubuntu-lts`. The
pinned Dockerfile defaults `JAVA_VERSION=11`, and the shared-library mapping
also identifies `ubuntu-lts` as Java 11. Running the configured image
confirmed:

- Java: OpenJDK `11.0.32`
- `javac`: `11.0.32`
- Maven: `3.8.7`
- Image digest: `sha256:2d37b527b743b8dbf0a540b4da4e3c7b707770e120d1dc1622d77586679a2bdf`

The shared-library mapping's `ubuntu-base` Java 17 image is not currently
published under the tested registry name (`docker manifest unknown`). The
available `java-ubuntu-22.04` image was also tested and contains Java
`11.0.20` / Maven `3.6.3`, so it is not a Java 17 path. The pinned shared
library constructs the default image name and does not expose a safe,
immutable image-digest override through the current NIS Jenkinsfile closure.
The live Jenkins controller/agent itself is not accessible here; its host
JDK is therefore **unverified**. The configured in-pipeline build container,
however, is confirmed Java 11.

No Jenkinsfile workaround was added because the repository does not own the
image/shared-library definition needed to select a verified immutable Java
17 image safely. Required external action: the `_symbol`/Jenkins owners must
publish a Java 17 build image with the required build tools and expose/select
it through the shared-library pipeline configuration (preferably by an
immutable digest); then the Jenkins job must log `java -version`,
`javac -version`, and `mvn -version` and confirm the actual agent/container
path uses Java 17. The NIS repository can then select that supported image
using the interface the owners provide. Java 25 must not become the minimum.

## Scope and gate decision

Changed files in the CI-alignment commit:

- `.github/workflows/java17-baseline.yaml`
- `.github/workflows/java25-compatibility.yaml`

No production dependencies, production Java sources, Jetty/Spring/Hibernate,
servlet or WebSocket code, database schema, migration SQL, runtime behavior,
or Phase 2F compatibility gate were changed. Java 11 is no longer an
implicit target of the repository's hosted branch workflows, but remains the
confirmed default in the external Jenkins build image.

**Phase 2I-A status: BLOCKED.** GitHub-hosted Java 17 clean test/package
validation is successful, and Java 25 compatibility CI is also successful.
The gate cannot be marked passed while the configured Jenkins build
container is Java 11 and the live Jenkins agent version is unavailable. Do
not begin Jetty 12 work on the basis of the hosted Actions result alone.

The Phase 2I Jetty migration entry gate can be reconsidered after the
external Java 17 Jenkins image/configuration is available, the Jenkins
execution path reports Java 17 and Maven versions, and a Jenkins build on
this branch succeeds. Keep the Phase 2F offline database conversion,
migration-history/checksum, representative database, and matching-genesis
chain-state gates unchanged.

Rollback point for the workflow change is the prior entry-gate commit
`ae9ba564a12e96eb7338b6a524673902f8a8b7bb`; reverting only the two workflow
files restores the previous hosted trigger behavior. The follow-up record is
committed separately so neither the Phase 2I BLOCKED history nor Phase 2H/2F
records are overwritten.
