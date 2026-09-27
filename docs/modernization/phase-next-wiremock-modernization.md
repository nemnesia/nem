# Core WireMock test infrastructure modernization

## Status

**COMPLETE — WireMock 1.58 was replaced with stable WireMock 3.x and the HTTP test contract passed on Java 17 and Java 25.**

This is a test-only modernization. Production Java code, production dependencies, protocol behavior, persistence, and runtime configuration are unchanged. Phase 2F Mainnet / Testnet real-database compatibility remains BLOCKED pending provenance-verified database artifacts.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `53793e966a603c9c215f8c86e199f920e88b7c3e`
- Starting worktree: clean; branch synchronized with origin
- WireMock implementation commit: `b60092bc94ae3baefd7405848c4cc161a8dcadea`
- Documentation commit and final branch HEAD: recorded by the commit that adds this report

## WireMock release and coordinates

Before:

```text
com.github.tomakehurst:wiremock:1.58:standalone (test)
```

After:

```text
org.wiremock:wiremock-standalone:3.13.2 (test)
```

WireMock's upstream installation guide currently identifies `3.13.2` as the 3.x test release, documents the standalone artifact, and labels 4.x as beta. The standalone distribution is an uber JAR whose dependencies are mostly relocated under alternate packages to avoid conflicts. The 3.13.2 artifact is present in Maven Central. Sources: [WireMock installation guide](https://wiremock.org/docs/download-and-installation/), [WireMock 3.13.2 release](https://github.com/wiremock/wiremock/releases/tag/3.13.2), [Maven Central artifact](https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/3.13.2/).

The resolved standalone 3.13.2 POM declares no transitive dependencies. Inspection of the JAR found its embedded Jetty under relocated `wiremock.org.eclipse.jetty` packages. This keeps WireMock's test server implementation separate from NIS's production Jetty 12 packages. No WireMock 4.x beta was used.

## Usage inventory and source adaptations

`HttpMethodClientTest` was the only Core test that starts a WireMock server. It uses:

- `WireMockServer` constructed on the existing fixed port `8890`, with explicit `start()` / `stop()` lifecycle
- static GET / POST DSL and `urlEqualTo` path matching
- a JSON response with status 200, `content-type`, and body
- a delayed response to exercise the client's socket timeout
- client-side checks of request method and headers
- connection-refusal, cancellation, and URI error scenarios

There is no WireMock rule/extension, proxy, HTTPS setup, or previous explicit WireMock request verification. The following narrowly-scoped adaptations were made:

- `UrlMatchingStrategy` became WireMock 3's `matching.UrlPattern` type.
- The removed server-level `addRequestProcessingDelay(10000)` call became an equivalent `withFixedDelay(10000)` on the timeout stub.
- Two tests stopped importing WireMock 1's relocated `wiremock.org.apache.commons.lang.StringUtils`; Java 17 `String.repeat` now creates the same test strings.
- A focused `WireMockCompatibilityTest` was added. It starts on an ephemeral port, matches GET path + query + header, returns and asserts status 202 + response header + body, verifies the received request, calls `stop()`, and asserts the server is no longer running.

No existing assertion was weakened, no test was disabled, and no JVM open option was added.

## Dependency comparison and production isolation

Before, the Core test tree represented WireMock 1.58 standalone plus `org.slf4j:slf4j-api:1.7.12`; the POM carried exclusions for legacy Jetty, Guava, Jackson, Apache HttpClient, JSONassert, XMLUnit, JSONPath, and jopt-simple. After, the WireMock test tree contains only `org.wiremock:wiremock-standalone:3.13.2`; the exclusions are unnecessary because its POM has an empty dependency list and its implementation dependencies are shaded.

The Core runtime dependency tree before and after is unchanged. The actual runtime classpath generated with `maven-dependency-plugin` and `includeScope=runtime` contains no WireMock artifact. The NIS production `target/libs` package also contains no WireMock artifact. The dependency remains test scope in `core/pom.xml`; no production dependency or artifact was changed.

## Baseline and final tests

The baseline targeted tests (`HttpMethodClientTest`, `MosaicDescriptorTest`, `MosaicIdTest`) passed: 49 tests, 0 failures/errors/skips. A first baseline full Core run had one transient failure in `AsyncTimerTest.visitorIsNotifiedOfDelays` (2,361 tests); an unchanged rerun passed all 2,361. This timing-sensitive baseline failure was recorded separately from the WireMock change.

After the change, the targeted tests plus the new WireMock behavior test passed: 50 tests, 0 failures/errors/skips. Full Core tests passed 2,362 tests, 0 failures/errors/skips.

The previous repository baseline was 6,224 tests. The single additional test is the new `WireMockCompatibilityTest`; no existing tests were removed.

| Runtime | Command | Result |
|---|---|---|
| Java 17.0.20.1, Maven 3.8.7 | `mvn -B clean test` | PASS; 6,225 tests, 0 failures, 0 errors, 0 skipped |
| Java 17.0.20.1, Maven 3.8.7 | `mvn -B clean package` | PASS; same 6,225 tests, no failures/errors/skips |
| Java 25.0.4.1, Maven 3.8.7 | `mvn -B clean test` | PASS; 6,225 tests, 0 failures, 0 errors, 0 skipped |
| Java 25.0.4.1, Maven 3.8.7 | `mvn -B clean package` | PASS; same 6,225 tests, no failures/errors/skips |

The full test suite also exited normally after the WireMock server shutdown test; no server thread prevented Maven/JVM termination.

## Hosted CI

The implementation commit `b60092bc94ae3baefd7405848c4cc161a8dcadea` triggered both hosted workflows:

| Workflow | Run | Result | Details |
|---|---:|---|---|
| Java 17 Baseline | [36348856960](https://github.com/nemnesia/nem/actions/runs/36348856960) | **FAIL** | `Run clean unit tests` exited 1; package step skipped. Public check annotations only report `Process completed with exit code 1`. The Actions job-log API returned HTTP 403 (`Must have admin rights to Repository`), so the failing test and cause could not be determined. |
| Java 25 Compatibility | [36348856953](https://github.com/nemnesia/nem/actions/runs/36348856953) | **PASS** | Workflow completed successfully. |

The Java 17 failure is unresolved and is not attributed to WireMock based on the available evidence: all Java 17 local full-suite tests and package passed, while the hosted log needed to identify the failing test is inaccessible. A push of this documentation-only follow-up triggers a fresh pair of checks on the final HEAD; those run IDs and results are reported in the task result. Until the final Java 17 hosted run succeeds or its failure is diagnosed and corrected, the hosted-CI acceptance criterion remains open.

## Remaining limitations

- Tests that use `HttpMethodClientTest` still bind fixed local port `8890`; the new WireMock lifecycle probe uses an ephemeral port.
- The existing baseline timing-sensitive `AsyncTimerTest.visitorIsNotifiedOfDelays` failed once before this change and passed on rerun; it is unrelated to WireMock.
- Phase 2F Mainnet / Testnet real database validation remains **BLOCKED**; no synthetic fixture is treated as real-network evidence.
