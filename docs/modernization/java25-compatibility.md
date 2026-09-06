# NIS modernization — Phase 1 Java 25 compatibility

## Scope and starting point

Phase 1 validates that the existing NIS implementation can be compiled,
tested, and started on Java 25 while preserving the Java 11 source/API
baseline. This phase does not modernize dependencies, alter consensus or
validation behavior, change serialization, change database schema, or change
cache/state semantics.

The Phase 1 starting point is the Phase 0 closure commit:
`5b67878c6acbb750334930e3576323b35155c05c`.
The original baseline is `3620879f2bf8a81091e2d5a75a576b301d78d3fa`, and the
Phase 0 artifact commit is
`8cb326951595afa84913e5b68f18d2c0b87fae87`.

Only the following Java 25 compatibility changes are included:

- JDK 25 compiler diagnostic compatibility profiles in the four module POMs;
- test-only module opens for the legacy Mockito/Spring CGLIB test setup;
- a safe empty `argLine` default so direct Failsafe goal invocation does not
  pass the unresolved late-bound JaCoCo token to the forked JVM;
- a GitHub Actions Java 25 build and unit-test validation workflow;
- this compatibility record.

No production Java source, dependency version, database schema, network
configuration, cache implementation, observer, execution, rollback, crypto,
hash, or serialization code was changed.

## Compatibility audit

| Finding | Classification | Phase 1 action |
| --- | --- | --- |
| All modules compile with `maven-compiler-plugin` 3.15.0 and `<release>11</release>`. JDK 25 accepts the release target. | NONE | Kept Java 11 release unchanged. |
| JDK 25 reports `dangling-doc-comments`, `lossy-conversions`, and `this-escape` diagnostics in existing source/tests; the build treats warnings as errors. | REQUIRED | JDK-25-only compiler profiles exclude these three new diagnostics while retaining `-Xlint:all` and `failOnWarning`. No source behavior changed. |
| `mockito-all:1.10.19` uses old CGLIB reflection. Java 25 blocks access to JDK internals used by the test harness. | REQUIRED | Added the minimal test-fork opens observed in the test suite: `java.lang`, `java.net`, `java.security.cert`, and `java.util`. No production JVM option was added. |
| Spring 4.3/CGLIB uses `ClassLoader.defineClass` during application startup. | REQUIRED for Java 25 runtime | A bounded startup smoke test runs with only `--add-opens=java.base/java.lang=ALL-UNNAMED`; the launch requirement is documented below. The Java 11 production Docker image was not changed. |
| No production `sun.*`, `com.sun.*`, or `jdk.internal.*` usage was found by source audit. Reflection uses public application/JDK APIs. | NONE | No production source change. |
| No finalizer or SecurityManager dependency and no removed JVM flag was found in the launch configuration. | NONE | No change. |
| Spring, H2, Flyway, Jackson, Jersey/HTTP, crypto, logging, and JAXB/API dependency modernization. | DEFER | Not part of Java 25 compatibility. |
| Maven's installed Guava emits a `sun.misc.Unsafe` deprecation warning under JDK 25. | DEFER | External Maven installation warning; no repository dependency change. |

The compiler and test plugins already have versions that work with the JDK 25
build in this environment: compiler 3.15.0, Surefire/Failsafe 3.5.6, and
build-helper 3.6.1. No plugin upgrade was required.

## Java version policy

Java 11 compatibility was retained. The project still compiles with
`--release 11`; Java 25 support is an additional runtime and test target, not a
source-language upgrade. The JDK-25 profiles are activated only for JDK 25 or
newer, leaving the existing Java 11 build and CodeQL job unchanged.

This preserves the least-cost compatibility policy: Java 11 remains the legacy
supported environment, while Java 25 validates the existing Java 11 API
surface. Removing Java 11 support is not required by the Phase 1 changes.

## Reproducible commands and environments

The repository has no Maven wrapper or Maven version pin. The measured Maven
version is Apache Maven 3.8.7. The Phase 0 survey used OpenJDK 17.0.20. The
Java 25 run used an isolated Ubuntu OpenJDK 25.0.4 installation under `/tmp`
and did not modify the system JDK installation:

~~~text
openjdk version "25.0.4" 2026-07-21
Apache Maven 3.8.7
~~~

Java 25 build:

~~~bash
env JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:/usr/bin:/bin \
  mvn -B clean package -DskipTests
~~~

Java 25 unit tests use the normal forked test mode so that the JDK-25 test
profile applies its test-only opens:

~~~bash
env JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:/usr/bin:/bin \
  mvn -B test
~~~

For comparison, the Java 11 package command was run with the installed Java 11
JDK:

~~~bash
env JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-11-openjdk-amd64/bin:/usr/bin:/bin \
  mvn -B clean package -DskipTests
~~~

The normal Failsafe command retains the repository's existing semantics:
`failsafe:integration-test` can return `BUILD SUCCESS` even when reports
contain failures because verification is a separate goal. Always inspect
`target/failsafe-reports`; use `mvn -B verify` when a failing report should
make the build fail.

The completed Java 25 comparison run selected the existing integration classes
and omitted only the 10,000-iteration `BlockCipherStressITCase`, which was too
long for this bounded validation run:

~~~bash
MAVEN_OPTS='--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.security.cert=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED' \
mvn -B -DforkCount=0 -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dit.test='DsaSignerPerfITCase,ParallelVerifyPerfITCase,SparseMatrixPerfITCase,NetworkSimulatorITCase,BasicNodeSelectorITCase,BlockScorerITCase,DefaultHashCachePerformanceITCase,HttpConnectorITCase,AccountControllerITCase,BlockControllerITCase,PushControllerITCase,TransferControllerITCase,BlockDaoITCase,H2ITCase,H2StorageSpeedITCase,MissingTransactionITCase,TransferDaoITCase,PoiImportanceCalculatorITCase,TimeSynchronizationITCase' \
  failsafe:integration-test
~~~

The `MAVEN_OPTS` form is retained here for the explicitly in-process
(`forkCount=0`) comparison command. Normal forked tests do not require it.

## Results

### Build

Java 25 `mvn -B clean package -DskipTests` passed for all five reactor projects.
Java 11 `mvn -B clean package -DskipTests` also passed after the Java 25
profiles were added; the profiles are inactive on JDK 11.

### Unit tests

The normal forked Java 25 run completed successfully:

| Module | Passed | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| `core` | 2361 | 0 | 0 | 0 | PASS |
| `deploy` | 65 | 0 | 0 | 0 | PASS |
| `peer` | 306 | 0 | 0 | 0 | PASS |
| `nis` | 3486 | 0 | 0 | 0 | PASS |
| **Total** | **6218** | **0** | **0** | **0** | **PASS** |

The Phase 0 Java 17 comparison was 6217 passes and one external-peer error.
The Java 25 run did not reproduce that network error in the available elevated
network environment; it did not require a functional code change. The
previous Java 17 default-run Mockito errors are removed by the scoped test
fork configuration, not by weakening or disabling tests.

### Integration tests and report semantics

The bounded Java 25 run reported the following in the selected Failsafe
reports. It is not a full one-to-one run because the long block-cipher stress
case was omitted:

| Module | Passed | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `core` | 5 | 5 | 0 | 0 | 0 |
| `deploy` | 0 | 0 | 0 | 0 | 0 |
| `peer` | 2 | 2 | 0 | 0 | 0 |
| `nis` | 49 | 79 | 9 | 19 | 2 |
| **Total** | **56** | **86** | **9** | **19** | **2** |

The reports show no new Java 25 module-access or reflection errors after the
test profile was applied. Existing non-functional categories remain:

- external peer timeout/inactive peer in `HttpConnectorITCase`;
- local-node acceptance tests without a reachable prepared node;
- H2 storage and missing-transaction cases without their prepared schema/data;
- statistical or machine-dependent thresholds in `BlockScorerITCase`,
  `DefaultHashCachePerformanceITCase`, `PoiImportanceCalculatorITCase`, and
  `TimeSynchronizationITCase`.

The Phase 0 complete-suite result was 59 pass, 8 failures, 19 errors, and 2
skips. Because the Java 25 run omitted the stress case and ran in a different
environment, the aggregate counts are not treated as a performance or
functional regression. The Failsafe reports, rather than the direct goal exit
code, are the source of these counts.

### Runtime smoke test

A bounded Java 25 startup was run against an isolated empty H2 database with
automatic boot disabled. With the single required runtime open
`--add-opens=java.base/java.lang=ALL-UNNAMED`, NIS:

- migrated the H2 schema;
- loaded and verified the nemesis metadata;
- completed block loading at height 1; and
- started the websocket and HTTP Jetty endpoints.

Without that option, old Spring CGLIB failed at
`ClassLoader.defineClass`. This is the only Java 25-specific production
startup requirement observed. A Java 25 launch must therefore use the
following minimal addition until the deferred Spring/CGLIB replacement is
addressed:

~~~bash
java --add-opens=java.base/java.lang=ALL-UNNAMED \
  -Xms6G -Xmx6G -cp /usersettings:./nis/*:./libs/* \
  org.nem.deploy.CommonStarter
~~~

The existing Docker image remains Java 11 and was not modified. This runtime
option is not added to the Java 11 production launcher; production image
migration is a separate decision.

## Consensus and state regression

No blockchain, database, cache, observer, serialization, or cryptographic code
was changed. The Phase 0 snapshot helper remains the comparison mechanism for
block height/hash, generation hash, chain score, representative account
state/importance/public-key/multisig state, namespaces, mosaics, expirations,
and deterministic transaction results.

The deterministic unit and block execution suites passed on Java 25. A
representative full-chain Mainnet/Testnet database is not available in this
checkout, and the snapshot helper was not run against one. Consequently the
Phase 1 status is explicitly:

**NOT FULL-CHAIN VERIFIED**

No state or consensus equality is inferred from the unit result alone.

## CI and Docker

`.github/workflows/java25-compatibility.yaml` adds a Java 25 build and unit
test job for pushes to `dev`/`main`, pull requests to `dev`, and manual runs.
The existing Java 11 CodeQL workflow remains in place and was not changed.
The new workflow was not executed by this local session; GitHub Actions must
provide the CI result.

The current NIS Docker image uses Ubuntu 22.04, OpenJDK 11 JDK in the builder,
OpenJDK 11 JRE in the runner, and `MEMORY_MS=6G` / `MEMORY_MX=6G`. A Java 25
validation image could use an Ubuntu 24.04-compatible OpenJDK 25 JRE or an
Eclipse Temurin 25 JRE. The local Docker daemon was unavailable, so image build
compatibility was not executed. The production image remains unchanged; Java
25 image migration and its launch-option policy are deferred.

## Phase 1 gate

**PHASE 1 READY WITH CONDITIONS**

The Java 25 build and normal unit tests pass, and the bounded integration
reports show no new Java 25 compatibility failure. The conditions are that
full-chain Mainnet/Testnet state comparison, the omitted long stress test, a
real Java 25 Docker image build, and hosted GitHub Actions execution remain to
be collected. These are evidence gaps, not observed consensus or Java 25
functional regressions.

## Deferred Phase 2+ items

- Replace the obsolete Mockito/CGLIB test dependency after a separately scoped
  compatibility assessment.
- Modernize Spring, H2, Flyway, Jersey/HTTP, Maven reproducibility, logging,
  and other dependencies independently.
- Decide and validate production Docker migration from Java 11 to Java 25.
- Obtain a controlled representative chain fixture and repeat exact snapshot,
  replay, startup, and memory measurements.
- Design cache architecture, persistent/bounded state, and memory reduction
  only after the Phase 0 measurements; no such implementation belongs here.

No user decision is required to continue the Java 25 compatibility work under
the stated Phase 1 scope. Production Docker migration and all deferred
modernization items require separate review before implementation.
