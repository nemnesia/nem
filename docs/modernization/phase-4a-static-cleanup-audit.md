# Phase 4a — Java Static Cleanup Audit

## Repository baseline

- Branch: `agent/nis-public-testnet-node`
- Starting local HEAD: `114365b5f0551cd2c5df787e30c2e86b7af0994d`
- Starting origin HEAD: `114365b5f0551cd2c5df787e30c2e86b7af0994d`
- Starting divergence: ahead `0`, behind `0`
- Starting working tree: clean
- Java: OpenJDK `25.0.4.1` (Ubuntu)
- Maven: Apache Maven `3.8.7`
- Final validated source HEAD: `6be35333fdd7bce6515055df30526bcf56b48e84`

The source commit above is the code revision exercised by the final build and test commands below. This audit record is added in a separate documentation commit.

## Existing analysis and compiler configuration

- `core`, `deploy`, `peer`, and `nis` compile with `--release 25`, `-Xlint:all`, `showWarnings`, and `failOnWarning=true`.
- Test compilation also enables `-Xlint:all` with the repository's existing exclusions for dangling doc comments, lossy conversions, and `this` escape.
- No Checkstyle, PMD, SpotBugs/FindBugs, Error Prone, Sonar, or CodeQL configuration was found.
- Jenkins entry points use the repository build and JaCoCo coverage; no separate static-analysis job was found.
- Spotless is configured for formatting and unused-import removal. Its broad apply goal was not run to avoid reformatting unrelated files.

## Analysis methods and findings

- Inspected all module POMs, Jenkins entry points, compiler flags, production `javax.*` imports, deprecation markers, and production suppressions.
- Parsed explicit imports in main, test, and integration-test Java sources with the JDK 25 compiler AST. The initial scan found 10 unused explicit imports; the repeat scan after cleanup found 0.
- The six unused `org.hibernate.annotations.Cascade` imports were left after the entity mappings had moved to Jakarta Persistence cascade annotations. Four other unused imports were removed from `HttpConnector`, `Ee11ProductionWebRuntimeProbe`, and `JsonErrorHandlerTest`.
- Removed the private `DefaultComparisonContext.getAnalyzeLimitAtHeight(height)` wrapper. It ignored `height` and returned `getMaxBlocksPerSyncAttempt()` unchanged. The constructor now passes that same value directly. Removed the helper's stale `UnusedParameters` suppression with it.
- No public or protected API, mapped field, serialized field, reflective target, configuration contract, protocol field, or runtime registration was removed.
- No additional local, catch, field, private method/class, unreachable-flow, redundant-condition, cast, or assignment candidate was proven safe to remove. The available compiler does not diagnose unused local variables or fields; ambiguous indirect-use candidates were retained.
- Production has no remaining Jakarta-era `javax.servlet`, `javax.persistence`, `javax.validation`, or `javax.websocket` imports. Remaining `javax.*` uses are Java SE APIs such as `javax.sql` and `javax.security`.

### Suppressions and retained review candidates

- Removed the one stale `@SuppressWarnings("UnusedParameters")` attached to the deleted private helper.
- Retained `@SuppressWarnings("unused")` on public API and contract types such as transaction IDs, peer API IDs, connector interfaces, deployment policy, and read-only state views. These have external, framework, or contract usage that repository-local direct references cannot establish.
- Retained `PoiOptionsBuilder(BlockHeight)` and its unused-parameter suppression. Its constructor is public; removing the argument or overload is API cleanup outside this phase.
- Retained serial, raw/unchecked, try-resource, and other narrow suppressions where the compiler warning still corresponds to serializable types, legacy Hibernate query/result APIs, generic bridges, or intentionally unused resource handles.
- `AbstractServerBootstrapper.createHandlers()` retains a `removal` suppression for the current Jetty API. Changing that call needs a Jetty-specific behavior/runtime review.

### Deprecated APIs

**SAFE FIX:** no deprecated API call had a verified mechanical replacement within this phase.

**DEFER:**

- Hibernate deprecation suppressions remain in DAO/retriever query paths using legacy raw `Query`, `list()`, or `uniqueResult()` result access. Replacing these requires query/result-cardinality tests and is not a mechanical cleanup.
- The Jetty removal-suppressed handler API remains pending a focused Jetty 12 runtime compatibility check.
- Project-owned `@Deprecated` members remain: compatibility aliases in `HttpStatus` and the deprecated `TransactionController` endpoint method. They are public surface and retained.
- JDK 25 `jdeprscan` found no deprecated Java SE API hits in resolved class references. It reported two unresolved Jetty `ContentResponse` / `HttpFields.Mutable` references because the scanner class path did not resolve those types; the compiler build remains the authoritative source check for unsuppressed calls.

## Validation

- **Compiler warnings before:** 0 Java compiler warnings. The starting `mvn -B clean test` compiled every module with `-Xlint:all` and `failOnWarning=true`; it then encountered the NIS test failure recorded below.
- **Compiler warnings after:** 0 Java compiler warnings. The final `mvn -B clean test` again compiled all four modules under the same gates. Maven startup's Guava `sun.misc.Unsafe` deprecation message and the test JVM class-sharing message are runtime/tool warnings, not javac warnings.
- **Final unit run:** `mvn -B clean test` compiled all modules and ran 6,229 tests: Core 2,364, Deploy 61, Peer 306, NIS 3,498. It had one failure, `NisMainTest.initLoadsDbSynchronouslyIfDelayBlockLoadingIsDisabled` (expected loading false, observed true), with no errors or skips. The same order-sensitive failure was present before the cleanup and is documented in `nis-public-testnet-acceptance.md`; running `NisMainTest` alone passed all 19 tests.
- **Package:** `mvn -B -DskipTests package` passed for the complete reactor after the final source cleanup. This was a packaging check, not a test result.
- **Failsafe:** the README's direct plugin invocation did not initialize the configured Mockito agent property and failed before tests could start. Running `mvn -B dependency:properties failsafe:integration-test failsafe:verify` initialized it; Core passed 7/7 and Peer passed 2/2. NIS ran 79 tests and reported 5 failures, 19 errors, and 2 skips. Failures include stochastic `BlockScorerITCase` assertions, a POI memory threshold, external NEM Ninja timeouts, missing Jetty `MultiException` at test runtime, a refused local node connection, and the required separate Mijin DB fixture being unavailable. No Failsafe failure was caused by an edited source line.
- **External validation:** `EXTERNAL VALIDATION REQUIRED`. Public Testnet/node validation was not run for this static cleanup. Existing Failsafe cases that contact NEM Ninja also timed out in this environment.
- **Diff audit:** `git diff --check` passed before the commits. The source diff contained only the verified import removals and private helper removal.

## Removed items

- Unused imports: 10 (6 obsolete Hibernate cascade imports; 4 other explicit imports).
- Unused local variables: 0 proven safe to remove.
- Unused fields: 0 removed.
- Unused private methods/classes: one private helper removed.
- Redundant code: one pass-through helper removed.
- Stale suppressions: one `UnusedParameters` suppression removed with its helper.

## Remaining candidates

- Public/framework-facing `unused` suppressions and the public ignored `PoiOptionsBuilder` height parameter remain review candidates because of API or indirect-use constraints.
- Hibernate and Jetty deprecation-suppressed calls remain deferred as listed above.
- Broader dead-field/private-method analysis would require a repository-aware analyzer that models reflection, dependency injection, ORM, and serialization; no such tool is configured, and no new analyzer was introduced in this cleanup phase.
