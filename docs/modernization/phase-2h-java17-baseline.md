# NIS modernization — Phase 2H Java 17 baseline

Status: **COMPLETE WITH RECORDED COMPATIBILITY LIMITATIONS**
Date: 2026-09-26 (Asia/Tokyo)
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested starting HEAD: `32188c9a18e2688039844e0e6eb9f50ad68b5487`
Actual starting HEAD: `32188c9a18e2688039844e0e6eb9f50ad68b5487`

Phase 2H implements the Java baseline/build/CI/Docker wave only. Spring,
Hibernate, Jetty, Flyway, H2, servlet/JPA namespaces, schema, and migrations
remain unchanged. No production Java source changed. The only Java source
change is a test-only integration fixture compatibility fix described below.
Phase 2D's `BLOCKED` history and the Phase 2E/2F/2G records were not edited.

## Baseline change

| Area | Before | After |
| --- | --- | --- |
| Production minimum / Maven compiler release | Java 11 | Java 17 |
| Additional compatibility target | Java 25 | Java 25, retained |
| Module compiler config | Each of `core`, `deploy`, `peer`, `nis` sets `maven-compiler-plugin:3.15.0` `<release>11</release>` | Each sets `<release>17</release>`; no `source`/`target` setting was added |
| GitHub unit/build CI | Java 25 workflow; CodeQL setup used Zulu Java 11 | New Java 17 baseline workflow; existing Java 25 workflow retained; CodeQL uses Zulu Java 17 |
| NIS Docker builder | Ubuntu 22.04, `openjdk-11-jdk-headless` | Same image/shape, `openjdk-17-jdk-headless` |
| NIS Docker runtime | Ubuntu 22.04, `openjdk-11-jre-headless` | Same image/shape, `openjdk-17-jre-headless` |
| User build documentation | Root and module READMEs described Java 11 as minimum | Root and module READMEs describe Java 17 as minimum |

Java 17 was selected in Phase 2G because supported Spring 6/7, Jetty 12,
Flyway 10+, and Hibernate 7 generations require Java 17+, while Java 25 is
recommended by Spring but not needed as a minimum. Java 25 therefore remains
an explicitly separate compatibility build/test target, not the production
floor.

All compiler configurations continue to use `release`, which constrains both
language/API surface and emitted bytecode. The repository has independent
module POMs rather than a common parent, so each module's existing compiler
configuration was updated in place. No Maven plugins or dependencies were
updated. Running the compiler on Java 11 now fails with the explicit message
`release version 17 not supported`, rather than retaining a Java 11
compatibility path.

The Java 17 GitHub Actions job is named **Java 17 Baseline** and reports as
**Build and unit test on Java 17 (production baseline)**. The existing job is
named **Java 25 Compatibility**. Both run package and unit-test steps on their
own exact JDK. CodeQL now builds/scans using Zulu 17.

Docker keeps the existing Ubuntu 22.04 builder/runner, amd64/arm image policy,
entrypoint, user, volume, and JVM flags. `MEMORY_MS=6G`, `MEMORY_MX=6G`,
`-Xms${MEMORY_MS}`, and `-Xmx${MEMORY_MX}` are unchanged. The builder remains a
JDK and the runtime remains a JRE. No GC, heap, container-size, distroless,
or Jetty changes were made.

## Java 17-only source compatibility fix

Java 17 compilation exposed one warning promoted to an error by the existing
`failOnWarning` / `-Werror` policy: `nis/src/it/java/org/nem/nis/dao/H2ITCase.java`
used the removal-marked `Long(long)` constructor through `Long::new`.
It now uses `Long::valueOf` when turning integer ids into decimal SQL literal
values. This integration fixture is `@Ignore`; its only relevant output is
the same numeric SQL text. The change is test-only, does not alter production
logic or runtime behavior, and is not a framework migration.

No production source compatibility fix was needed. No build-only dependency
or Maven plugin changed, so there are no plugin before/after versions or
plugin rollback requirements.

## Verification

The environment supplied OpenJDK 25.0.4 and Java 11, but no installed Java 17.
For Java 17 verification, the Ubuntu 24.04 `openjdk-17-jdk-headless`,
`openjdk-17-jre`, and `openjdk-17-jre-headless` packages (17.0.20.1) were
downloaded and extracted under `/tmp/nis-phase2h-jdk17`; system packages were
not installed. Maven was 3.8.7. The sandbox initially denied local sockets
for WireMock tests; the same full suite was rerun with socket access and
passed.

| Runtime / command | Result |
| --- | --- |
| Java 17 `java -version` / `mvn -version` | PASS: OpenJDK 17.0.20.1; Maven 3.8.7 running on Java 17 |
| Java 17 `mvn -B clean test` | PASS: 6,218 tests, 0 failures, 0 errors, 0 skipped across 624 classes; Core 2,361, Deploy 65, Peer 306, NIS 3,486 |
| Java 17 `mvn -B -DskipTests package` | PASS; all modules packaged |
| Java 25 `mvn -B clean test` | PASS: 6,218 tests, 0 failures, 0 errors, 0 skipped; same module totals and discovery |
| Java 25 `mvn -B -DskipTests package` | PASS; all modules packaged |
| Java 11 `mvn -B -pl core clean compile` | Expected failure: `release version 17 not supported`; confirms Java 11 is no longer a supported build baseline |
| Java 17 `mvn -B test failsafe:integration-test` | Reactor command completed. Failsafe reports known/environment-dependent failures below; the direct `integration-test` goal does not fail the Maven lifecycle for recorded test failures |
| Docker image build / container smoke | NOT RUN: Docker client exists, but daemon socket `/var/run/docker.sock` returned permission denied |

The Java 17/25 unit suites cover crypto/signature/hash, serialization, DAO and
H2/Flyway in-memory setup, Spring test contexts, networking-related code, and
consensus/state tests. A standalone production-container startup/API smoke
was not performed because the Docker daemon was inaccessible. No application
test or build failure required a Spring/Hibernate/Jetty/Jakarta migration.

### Failsafe integration results and limits

The integration command ran Surefire unit tests and Failsafe integration
tests in one Maven process so JaCoCo's `@{argLine}` was populated. Running the
Failsafe goal alone had first returned zero tests and an unresolved
`{argLine}` error, so that invocation is not counted as a test run.

- Core: seven Failsafe test classes completed; one timing assertion failed
  in `ParallelVerifyPerfITCase` because sequential signature verification
  was faster on this run. Sparse-matrix and block-cipher stress ITs passed.
- Peer: two Failsafe cases passed.
- NIS: 79 Failsafe cases, 9 failures, 21 errors, 2 skipped. The failure set
  consists of previously known environment-sensitive block-score/performance
  assertions, cache/POI timing thresholds, and an external peer timeout. The
  error set includes HTTP acceptance tests with no remote test server, a
  hard-disk H2 integration context that opens the old
  `/home/harvestasya/nem/nis/data/test.mv.db`, and an H2 speed fixture that
  requires local data. That file is still H2 1.4 format; H2 2.2.220 reports
write format 1 versus supported format 3. The result directly confirms the
Phase 2F offline-export/import limitation remains; it does not indicate a
Java 17 in-memory persistence regression.

The Phase 2F Failsafe note recorded 9 failures / 19 errors / 2 skipped. This
run retained the same failure and skip counts and had 2 more errors; those
were the `BlockDaoITCase` and `TransferDaoITCase` context-load errors caused
by the H2 1.4 `test.mv.db` file. They remain under the Phase 2F offline
conversion gate and are not reclassified as resolved.

The Phase 2F DB gates remain unchanged and unresolved: offline H2 1.4 to 2.x
export/import for existing files; retain V1.0.0–V1.0.7 and their SQL/history;
do not repair/rebaseline Flyway history to bypass checksum limitations;
obtain representative Mainnet/Testnet database verification; and do not
claim matching-genesis full chain-state equivalence without the matching
snapshot. No production database migration was run by Phase 2H.

The pinned `_symbol` Git submodule was not changed. Its independently owned
Jenkins utility Dockerfiles still contain Java 11 references; the Java GitHub
Actions workflows and this repository's NIS production Dockerfile are updated
here. Jenkins agent/toolchain versions from that external submodule were not
verified in Phase 2H and must be checked before claiming the Jenkins executor
itself is on Java 17.

## Rollback and Phase 2I entry gate

The rollback point is the unchanged parent commit
`32188c9a18e2688039844e0e6eb9f50ad68b5487`. The Phase 2H commit can be
reverted normally; no history rewrite is needed. Rollback does not change or
repair any database files.

Before Phase 2I begins, require:

1. The Java 17 and Java 25 build/unit matrix remains green in CI with 6,218
   tests discovered and no new unit failures/errors.
2. The production Docker image builds and starts on Java 17, with its current
   entrypoint and HTTP/heartbeat/persistence startup smoke verified. This was
   not possible in the current environment because Docker daemon access was
   denied.
3. The Jenkins build executor JDK is identified; the pinned `_symbol`
   submodule's Java 11 utility images are evaluated with their owners rather
   than silently treated as Java 17.
4. Any integration failures are classified against the existing CI baseline
   and prerequisites. The H2 1.4 file error must remain tracked under Phase
   2F's offline conversion gate.
5. Jetty/API work remains separate: do not include Spring/Jakarta/Hibernate
   changes in the Jetty wave.

Phase 2H does not start Phase 2I.
