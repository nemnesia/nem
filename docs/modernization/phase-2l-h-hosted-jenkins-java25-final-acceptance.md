# Phase 2L-H — Hosted Jenkins Java 25 Final Acceptance

**BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED.** The repository's pinned Jenkins contract does not select a Java 25 image, the queried public Docker Hub tag `symbolplatform/build-ci:java-ubuntu-25` has no manifest, and this workspace has no hosted Jenkins endpoint or execution credentials. No hosted Jenkins run was performed, so this record does not claim hosted Java 25 acceptance or close Phase 2L.

This is a hosted acceptance check, not a repository CI redesign. No source, test, dependency, runtime, or Jenkins configuration change was justified without a hosted failure to diagnose. The only new repository change is this validation record.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested starting HEAD: `59f378cd6e13a5e91f920a051a24f9b105c026ca`
- Actual starting HEAD: `59f378cd6e13a5e91f920a051a24f9b105c026ca`
- Starting `origin/agent/nis-phase0-baseline`: same commit; no divergence.
- Existing working-tree change: `.gitignore` adds/retains the `legacy/` entry. It was pre-existing, left untouched, and excluded from this phase's commit.
- Final HEAD: recorded by the documentation commit and final Git report.

## Hosted Jenkins execution contract

The NIS entrypoint is [`nis/Jenkinsfile`](../../nis/Jenkinsfile). It invokes `defaultCiPipeline`, declares Ubuntu and `ciBuildDockerfile = 'java.Dockerfile'`, and supplies the Docker publisher/package settings. The submodule `_symbol` is pinned at `0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf` (`v2.4.0-3158-g0f4c95e709`).

In that pinned shared library:

1. `resolveCiEnvironmentName` derives `java` from `java.Dockerfile`.
2. `resolveCiEnvironment` adds the `-ubuntu-lts` suffix for the default environment, so the default choice is `java-ubuntu-lts` (with other configured environment choices also presented as a Jenkins parameter).
3. `jobHelper.resolveBuildImageName` converts the selected identifier to `symbolplatform/build-ci:<identifier>` unless it already contains a tag.
4. The pipeline launches that image as a Docker agent with `reuseNode true` and runs the repository's setup/build/test scripts. The test path points to `scripts/ci/setup_test.sh` and `scripts/ci/test.sh`.

The pinned `_symbol/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml` maps `ubuntu-lts` to Ubuntu 24.04 / Java 11, `ubuntu-base` to Ubuntu 22.04 / Java 17, and `ubuntu-latest` to Ubuntu 26.04 / Java 21. It contains no Java 25 mapping. Therefore the NIS default maps to the Java 11 `symbolplatform/build-ci:java-ubuntu-lts` image; the repository's Java 25 baseline alone does not change the hosted executor.

The public Docker Hub manifest query for `symbolplatform/build-ci:java-ubuntu-25` returned `no such manifest`. The previously recorded prospective local Java 25 image (Java `25.0.4.1`, Maven `3.9.12`) was not a published, hosted-selectable image. No evidence available in this run establishes another published Java 25 tag or private-registry availability. Jenkins's configured Docker credential identifier is `docker-hub-token-symbolserverbot`; its secret was neither read nor available for an authenticated registry check.

## Hosted run evidence

- Jenkins job/build and URL: **NOT AVAILABLE — no Jenkins endpoint, session, credential, or hosted-job tool was supplied to this workspace.**
- Triggering commit / hosted checkout: **NOT OBSERVED.**
- Selected hosted agent/image: **NOT OBSERVED.**
- Hosted `java -version`, `javac -version`, and `mvn -version`: **NOT OBSERVED.**
- Hosted setup/build/test stages, Surefire/Failsafe reports, controller result, fixture provisioning, cleanup, and final job status: **NOT RUN / NOT OBSERVED.**
- Hosted execution date: **No hosted execution occurred.** This record was prepared on 2026-10-01 UTC.

Because the requested primary evidence must come from the real hosted Jenkins path, local Java 25 success from earlier phases is historical repository compatibility evidence only and is not substituted here. No initial hosted failure or rerun exists to classify. No tests or gates were skipped or masked in this phase; they were not executed on hosted Jenkins.

## Repository-owned disposable fixture contract

The current repository test entrypoint is wired to `setup_test.sh` and `test.sh`. The setup script creates an isolated `target/nis-it.*` home, generates the deterministic disposable Testnet genesis through the repository-owned fixture generator, launches the NIS process, and waits for its heartbeat and block endpoint before reporting readiness. The test script preserves the Maven result while running teardown and invokes Failsafe through `failsafe:verify`. These are source-level contract observations; hosted workspace writability, port isolation, process lifecycle, fixture generation, and cleanup on both success and failure were **not exercised** in Jenkins during this phase.

Phase 2L-G's previously accepted local Java 17/25 controller and full-reactor results remain historical evidence. This phase neither reran nor changed those checks. No claim is made that the hosted executor ran the 16 controller tests, focused H2 test, or the full reactor.

## Accepted database artifacts

The accepted Mainnet/Testnet database files were not opened, copied, mounted, or used by this hosted-CI investigation. Read-only filesystem metadata and hashes observed at the end of the investigation:

| Artifact | Size | SHA-256 | mtime (epoch seconds) | Result |
|---|---:|---|---:|---|
| `legacy/nis5_mainnet.mv.db` | 1,073,152 bytes | `8a9a534e6fdf99760e8e9b048e662859ccce1c33802202a2cd6483329ec013d5` | `1790599207` | Matches accepted Phase 2F/2K evidence |
| `legacy/nis5_testnet.mv.db` | 1,064,960 bytes | `23a5d1505d216dae7ba7ae8bde0da3cfc3111d346bc50b45b95e292e212a8a87` | `1790599102` | Matches accepted Phase 2F/2K evidence |

These were read-only observations, not a before/after validation run of the artifact lifecycle. No database artifact was staged or committed.

## Phase status and required external actions

- Phase 2F: **COMPLETE / CLOSED**; unchanged.
- Phase 2K: **COMPLETE / CLOSED**; unchanged.
- Phase 2L-F: historical **PARTIAL**; its record remains unchanged.
- Phase 2L-G: **COMPLETE / CLOSED**; its record remains unchanged.
- Phase 2L-H: **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED.**
- Phase 2L overall: **PARTIAL**; this phase does not close hosted Java 25 CI acceptance.

Required owners/actions:

1. **Symbol build-ci image owner:** publish a supported Java 25 Jenkins build image and make it pullable by the hosted Jenkins Docker credential. Record its immutable image/tag and Java/Maven versions.
2. **Symbol shared-library owner:** add/pin the Java 25 environment mapping and expose a selectable `CI_ENVIRONMENT` value consistent with the published image.
3. **Jenkins infrastructure/job owner:** authorize and run the normal NIS Jenkins pipeline at the intended commit using that image; retain build ID/URL, checkout SHA, image/agent identity, Java/Maven versions, Surefire/Failsafe results, disposable fixture setup/teardown, and final status.

Until those actions are complete and the actual hosted job passes its required gates, Phase 2L must remain PARTIAL. No external repository, registry, shared library, or Jenkins infrastructure was changed by this phase.
