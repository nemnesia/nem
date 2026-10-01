# Phase 2N — NIS / Symbol Java 25 boundary closure

**Decision: COMPLETE / CLOSED — NO NIS-SIDE SYMBOL JAVA IMPLEMENTATION MIGRATION WAS REQUIRED.**

NIS's repository-controlled Java 25 build, tests, production image, and runtime
do not depend on Java/build implementation from `_symbol`. `_symbol` remains a
git submodule for the existing Symbol/Jenkins integration and related tooling.
No files were copied from it, no submodule pointer was changed, and no
Java-specific compatibility shim was added.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Required starting HEAD: `0315ff8056c52386aaec2f4c72a3eba6b17c7264`
- Actual starting HEAD: `8718733faf4e92c2a9d1e419f9f6fa812c4adb7f` (different)
- History review: the branch contains only the two legitimate Phase 2M-B
  commits on top of the requested revision: `ddd8c091f` (DAO test-fixture
  correction) and `8718733fa` (Phase 2M-B documentation/status). No history was
  rewritten.
- Starting local and origin refs both pointed to `8718733faf4e92c2a9d1e419f9f6fa812c4adb7f`;
  divergence was `0/0`.
- Pre-existing working-tree change: `.gitignore` adds `legacy/`. It was not
  edited, staged, or committed.
- Changes in this phase: this validation record and the current-status addition
  to `dependency-modernization.md`. No source, build, runtime, dependency,
  submodule, or CI configuration was changed.
- Final HEAD: the Phase 2N documentation commit; reported in the final Git
  report.

## `_symbol` dependency inventory

The superproject records `_symbol` in `.gitmodules` as
`https://github.com/symbol/symbol`, branch `dev`. The checked-out gitlink is
`0f4c95e7098bbd84a8ceb9e2a101496bdfe662cf`. The submodule remains present and
unchanged.

| Category | Observed dependency | Phase 2N disposition |
| --- | --- | --- |
| A. Java 25 technical dependency | No NIS/core/deploy/peer Java source imports Symbol Java packages. No NIS Maven POM declares a Symbol Java artifact. The resolved NIS runtime tree and packaged runtime libraries contain no `org.symbol`/Symbol Java artifact. Java 25 GitHub Actions and production container use Temurin 25 directly; Maven compiles with release 17. | No Symbol Java implementation is needed in NIS. No code was copied or adapted. |
| B. External hosted CI infrastructure | `nis/Jenkinsfile`, and the sibling `core`, `deploy`, and `peer` Jenkinsfiles, call the external `defaultCiPipeline` shared-library step and pass `java.Dockerfile`. The pinned shared-library mapping in `_symbol/jenkins/shared-library/resources/buildEnvironment/baseImages.yaml` maps Ubuntu LTS/base/latest to Java 11/17/21. Its image selection resolves to external `symbolplatform/build-ci` images. | Left unchanged. Java 25 image publication, shared-library mapping, and hosted executor are external follow-up under Phase 2L-H, not a repository Java implementation dependency. No speculative Java 25 tag was added. |
| C. Historical / convenience | `.gitmodules` and `init.sh` retain sparse checkout of the submodule's `jenkins/*`, `linters/*`, and `tests/*` paths. The submodule also supports existing project/monorepo workflows. | Retained. None of these paths participates in local Maven compile/test or the NIS application runtime. |
| D. Unrelated Symbol dependency | `_symbol/sdk/java` contains Symbol SDK/generated/example code, including NEM examples; NIS has no Java imports or dependency on it. OpenAPI prose mentions client SDKs as examples. | Left untouched; outside NIS Java 25 runtime/build needs. |

Source boundary evidence:

- `nis/Jenkinsfile` selects `java.Dockerfile`; the submodule shared library
  resolves the environment/image. The pinned map has Java 11, Java 17, and
  Java 21 entries, with no Java 25 image mapping.
- Root Java 25 CI is independently configured in
  `.github/workflows/java25-baseline.yaml` with Temurin 25. The Java 17 lane is
  independently configured in `java17-compatibility.yaml`.
- `nis/Dockerfile` uses `eclipse-temurin:25-jdk-jammy` for its build stage and
  `eclipse-temurin:25-jre-jammy` for its runtime stage. It builds/packages NIS
  using repository Maven and `infra/package.prepare.sh`; no `_symbol` Java
  source or package is involved.
- NIS, Core, Deploy, and Peer Maven compiler configurations retain
  `<release>17</release>`.
- Repository source/config searches found no `org.symbol` import, Symbol Java
  artifact, or Java class copied from `_symbol`. `init.sh`'s submodule checkout
  and the Jenkins shared-library references are the relevant repository
  connections.

There was therefore no source provenance/porting obligation for Java code.
The Java implementation under `_symbol/sdk/java` belongs to the separate
Symbol SDK and is intentionally not copied. NIS continues owning its own Java
17-compatible NEM implementation and its Java 25 runtime configuration.

## Validation

Runtime versions:

| Lane | Java | Maven |
| --- | --- | --- |
| Primary | OpenJDK `25.0.4.1` | Apache Maven `3.8.7` |
| Compatibility | OpenJDK `17.0.20.1` | Apache Maven `3.8.7` |

| Lane | Command | Result |
| --- | --- | --- |
| Java 25 | `mvn -B clean test` | PASS: 6,228 tests; 0 failures, 0 errors, 0 skipped. |
| Java 25 | `mvn -B -DskipTests clean package` | PASS; packaging completed. |
| Java 17 | `mvn -B clean test` | Initial run: 3,497 NIS tests, one failure in `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance`; isolated rerun passed 1/1, and full clean rerun passed 6,228 tests with 0 failures, errors, or skips. |
| Java 17 | `mvn -B -DskipTests clean package` | PASS; packaging completed. |

The initial Java 17 assertion uses unseeded `SecureRandom` to add edges to a
PageRank graph and then asserts a narrow statistical ratio. It was unrelated to
Symbol or Java 25 implementation and was not changed. The exact test passed on
isolated rerun and the full Java 17 regression passed on rerun. The initial
failure is retained in this record rather than omitted.

The production-equivalent NIS disposable Testnet runtime probe passed on both
JDKs using `nis/scripts/ci/setup_test.sh` followed by teardown. It generated the
repository-owned `0x98` Testnet genesis, launched `org.nem.deploy.CommonStarter`,
passed heartbeat and block endpoint readiness, initialized Spring/Hibernate
and the current H2/Flyway runtime, and shut down cleanly. Each setup used its
own `nis/target/nis-it.*` home. Setup and teardown exit status were zero. This
runtime path uses no Symbol Java artifact.

## Runtime dependency and package audit

`mvn -B -o -f nis/pom.xml dependency:tree -Dscope=runtime` succeeded. The NIS
runtime graph contains the accepted stack, including Spring `7.0.9`, Hibernate
ORM `7.2.25.Final`, Hibernate Validator `9.1.4.Final`, Jakarta Persistence
`3.2.0`, Jetty `12.1.13` EE11, H2 `2.5.250`, Flyway `12.11.0`, and Bouncy Castle
`bcprov-jdk18on:1.86`.

No Symbol Java artifact, legacy JPA/Servlet/Validation API, Spring 5/6,
Hibernate 5/6, Jetty EE8, or old H2/Flyway generation appeared in the NIS
runtime dependency tree. Mockito and WireMock are not in the runtime tree or
the 95-JAR NIS runtime package; they remain test-scope tooling. No Java 25-only
bytecode was produced; compiler release 17 remains in force. No protocol,
consensus, serialization, persistence schema, or Flyway migration behavior was
changed.

## External hosted Jenkins boundary

Phase 2L-H remains **BLOCKED — EXTERNAL JENKINS JAVA 25 OWNER ACTION REQUIRED**.
The pinned Symbol shared library has no Java 25 selectable environment, and
the hosted `symbolplatform/build-ci` image/mapping/run remain controlled by
external owners. No hosted execution or Java 25 image publication is claimed
here. Hosted Jenkins Java 25 execution remains an external infrastructure
follow-up and is not a blocker for repository-controlled Phase 2 technical
completion.

## Final Phase 2N status

**COMPLETE / CLOSED.** The audit found no NIS-side Symbol Java/build
implementation required for Java 25. Existing repository-controlled Java 25
and Java 17 test/package paths and NIS runtime startup pass without adding or
modernizing Symbol-side code. `_symbol` remains intact for its existing
shared-library, submodule, and unrelated Symbol project roles. The external
hosted Jenkins gap remains separate under Phase 2L-H.

Phase 2F, Phase 2K, and Phase 2M remain **COMPLETE / CLOSED**. Phase 2L-H
remains **BLOCKED** and Phase 2L overall remains **PARTIAL**. The next phase is
**Phase 2O — Final Dependency Modernization Audit**.
