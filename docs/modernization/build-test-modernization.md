# NIS build and test infrastructure modernization

Status: Phase 2B implementation and verification<br>
Audit date: 2026-09-07<br>
Repository: `nemnesia/nem`<br>
Branch: `agent/nis-phase0-baseline`<br>
Starting HEAD: `7c7cca29238127507db3dea020ae0e6c51f093f2`

## Scope and result

This phase makes selected Maven lifecycle plugin versions explicit while
preserving Java 11 bytecode compatibility, Java 25 test support, test
discovery, JaCoCo, packaging, and runtime dependencies. No production Java
source, database schema, migration, state, cache, validation, observer,
serialization, or consensus code was changed.

The result is:

> **PHASE 2B READY WITH CONDITIONS**

The normal clean package lifecycle is now explicit for the modules. Maven
itself remains unpinned, byte-for-byte artifact reproducibility remains
unresolved, and integration tests retain their known environment-dependent
baseline. These are documented conditions for a later build-policy decision;
they do not block the isolated Mockito wave.

## Starting build baseline

- Maven `3.8.7`; no Maven Wrapper, `.mvn/maven.config`, or repository Maven
  version pin.
- Java 11 `11.0.32` and OpenJDK 25 `25.0.4` were used for verification.
- The compiler release remains `11` on both JDKs.
- The root POM is an aggregator/packaging POM for `core`, `deploy`, `peer`,
  and `nis`. The modules do not inherit from the root POM.
- There is no shared parent, `<dependencyManagement>`, or
  `<pluginManagement>`.
- The root default goal remains `install`.

Before this phase, the effective POM inherited old Maven super-POM versions
for unpinned lifecycle or auxiliary plugins, including clean `2.5`, install
`2.4`, deploy `2.7`, resources `2.6` in three modules, release `2.5.3`, site
`3.3`, antrun `1.3`, and assembly `2.2-beta-5`. This inventory is a reason for
future review, not a reason to update all plugins in one wave.

## Changes made

| POMs | Plugin | Version | Reason |
| --- | --- | --- | --- |
| root and all four modules | `maven-clean-plugin` | `3.5.0` | Make the clean lifecycle explicit. |
| root and all four modules | `maven-install-plugin` | `3.1.4` | Make the default install lifecycle explicit. |
| root and all four modules | `maven-deploy-plugin` | `3.1.4` | Make deploy resolution explicit without executing a deployment. |
| `core`, `deploy`, `peer` | `maven-resources-plugin` | `3.5.0` | Match the already explicit NIS resources plugin and remove inherited `2.6`. |

The resources pin is verified with clean builds and content inventories. The
plugin's incremental resource change-detection behavior was not separately
re-baselined; future incremental-build policy should treat that as an explicit
verification point.

No compiler, Surefire, Failsafe, build-helper, JaCoCo, Spotless, Javadoc, or
JAR version was changed. The Peer JAR plugin remains `3.5.1`; it was already
updated by the repository's recent Dependabot change, while the other modules
remain on `3.5.0`.

## Plugin and POM policy

Option A was selected: keep the existing independent-module structure and pin
only the lifecycle plugins required for this wave in each POM. A root
`pluginManagement` block would not manage these modules without changing their
parent relationship. Parent conversion or common plugin configuration would
change Maven inheritance and is therefore deferred.

The following explicitly versioned plugins remain unchanged:

| Function | Versions retained |
| --- | --- |
| compiler | `3.15.0`, release `11` |
| Surefire / Failsafe | `3.5.6` |
| build-helper | `3.6.1` |
| JaCoCo | `0.8.15` |
| Spotless | `2.46.1` |
| Javadoc | `3.12.0` |
| JAR | `3.5.0`, Peer `3.5.1` |
| NIS dependency / exec / resources / velocity | `3.11.0` / `3.6.3` / `3.5.0` / `1.1.0` |

The unpinned effective release, site, antrun, assembly, and other auxiliary
plugins remain deferred because they are not required to validate the normal
Java 11/25 package and unit-test path. Deploy was not run against a remote
repository.

Maven remains documented at `3.8.7`; no wrapper or Maven major upgrade was
introduced. Wrapper adoption affects distribution and contributor policy and
is a `NEEDS USER DECISION` item.

## Test and build semantics preserved

The following existing wiring was inspected and left unchanged:

- `src/test/java` discovery by Surefire.
- `src/it/java` registration by build-helper.
- Failsafe integration-test execution and report configuration.
- JaCoCo agent injection, report, and check executions.
- `argLine`, including the Java 25 test-only `--add-opens` profile.
- compiler warning and `-Xlint` policy.
- test JAR creation, NIS dependency copying, and package layout.

The Java 25 profile and all test-only opens remain in place. The Java 11
Mockito reflective-access warning remains a known test-harness compatibility
debt and is intentionally deferred to Phase 2C.

## Verification

Commands used after the POM changes:

```text
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -B clean package -DskipTests
JAVA_HOME=/tmp/nis-phase1-jdk25/usr/lib/jvm/java-25-openjdk-amd64 mvn -B clean package -DskipTests
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -B test
JAVA_HOME=/tmp/nis-phase1-jdk25/usr/lib/jvm/java-25-openjdk-amd64 mvn -B test
JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 mvn -B install -DskipTests
```

| Runtime and command | Result | Module test counts where applicable |
| --- | --- | --- |
| Java 11 clean package | PASS | Not run |
| Java 25 clean package | PASS | Not run |
| Java 11 unit tests | Known baseline environment failure | Core 2361/0/0, Deploy 65/0/0, Peer 306/0/0, NIS 3486/0/1; total 6218/0/1 |
| Java 25 unit tests | PASS | Core 2361/0/0, Deploy 65/0/0, Peer 306/0/0, NIS 3486/0/0; total 6218/0/0 |
| Java 11 install lifecycle | PASS | Tests skipped |

Counts are `tests/failures/errors`; all unit-test runs reported zero skips.
The Java 11 error is the known external public-peer boot test
`NisPeerNetworkHostTest.defaultHostCanBeBooted`. Its failure does not occur in
consensus or state execution assertions. Java 25 network tests are variable
in this environment; the post-change run completed with no error.

The Phase 1 Failsafe baseline remains `56 pass / 9 failures / 19 errors / 2
skipped`. Integration tests were not rerun in this documentation/build-only
wave because Failsafe version, executions, source registration, and test
configuration were not changed. The known failures remain classified as
external peer/local node dependencies, prepared H2 prerequisites,
performance/statistical thresholds, legacy Mockito reflection, and sandbox
WireMock socket restrictions.

### Test discovery comparison

The post-change Surefire reports contain the same module report-file counts as
the baseline: Core 218, Deploy 9, Peer 45, and NIS 352. The aggregate test
count is 6218, with no skipped tests. No test discovery reduction was observed.

### Artifact comparison

Using the retained pre-change inventory and a post-change Java 11 clean
package, all seven module JARs had identical entry counts and no added or
removed entries:

| Artifact group | Main JAR entries | Test JAR entries |
| --- | ---: | ---: |
| Core | 352 | 485 |
| Deploy | 31 | 25 |
| Peer | 80 | 88 |
| NIS | 622 | not produced |

The NIS copied dependency directory retained the same 71 file names. All
manifest fields, including the existing JAR plugin version and Java 11
metadata, matched the retained baseline inventory. Raw JAR SHA-256 values are
not expected to match because the JARs retain build-time ZIP timestamps; the
pre-change binary JARs were not retained, so byte-level entry-content hashing
was not claimed. No class/resource/package-layout difference was observed in
the available comparison.

## CI and Docker status

No CI file was changed. The existing Java 25 workflow still runs clean package
and unit tests, and the existing Java 11 CodeQL workflow is retained. The
repository does not yet have a Java 11/25 build matrix; adding one is deferred
to a later CI/regression phase.

`nis/Dockerfile` remains unchanged: Ubuntu 22.04 builder and runner images,
OpenJDK 11 build/runtime, and `MEMORY_MS/MEMORY_MX=6G`. Production container
migration is outside this phase and remains separate from Java 25 CI
validation.

## Scope safety statement

The following are all **no** for this phase:

| Area | Changed? |
| --- | --- |
| runtime dependency versions | No |
| production Java source | No |
| schema or migrations | No |
| cache or state implementation | No |
| consensus, validation, observers, or serialization | No |
| production Docker/runtime configuration | No |

## Deferred items and decisions

### Deferred

- Phase 2C Mockito/test-harness modernization and subsequent removal testing
  for test-only opens.
- Spring/Jetty/servlet runtime alignment, Hibernate/validation review, and
  H2/Flyway compatibility waves from the Phase 2A plan.
- Maven auxiliary-plugin review, Maven core upgrade, and broader CI matrix
  work.
- Byte-for-byte reproducible JAR policy, including timestamps and Maven core
  pinning.
- All runtime dependency, schema, cache, persistent-state, and memory work.

### NEEDS USER DECISION

1. Whether to adopt Maven Wrapper and/or a repository Maven version policy;
   this affects distribution and contributor requirements.
2. Whether to introduce a root parent/plugin-management structure; this changes
   Maven inheritance and should be a deliberate build-architecture change.
3. Whether byte-for-byte JAR reproducibility, including timestamp policy, is a
   required distribution contract.
4. The Spring/Jakarta, database upgrade, production container, and security
   priority decisions recorded in the Phase 2A document remain open.

## Phase 2B gate

**PHASE 2B READY WITH CONDITIONS**

Java 11 and Java 25 clean package verification passed, Java 25 unit tests did
not show a new functional regression, test discovery was preserved, and no
runtime or production behavior was changed. The conditions are the unpinned
Maven core/auxiliary-plugin policy, unresolved byte-for-byte reproducibility,
and the retained environmental integration baseline.

The exact recommended next phase is **Phase 2C — Mockito modernization**.
Phase 2C was not started by this change.
