# Hibernate Validator EL message interpolation check

## Scope and status

- Requested and actual starting HEAD: `035fd20996c3b32b3c8716dc99f0ae854c2101d7`
- Branch: `agent/nis-phase0-baseline`
- Goal: check the runtime path after the existing Hibernate Validator 6.2.5.Final upgrade. This is not a Jakarta migration.
- Status: **COMPLETE — current NIS validation bootstrap requires an EL implementation to initialize correctly.**
- Phase 2F Mainnet/Testnet real-database compatibility remains **BLOCKED** pending provenance-verified artifacts. No database fixture was treated as real network data.

## Runtime dependency and bootstrap

Before this change the NIS runtime dependency tree contained Spring 5.3.39, Hibernate ORM 5.4.33.Final, Hibernate Validator 6.2.5.Final, `jakarta.validation-api:2.0.2` (the Bean Validation 2.0 artifact with `javax.validation.*` classes), JBoss Logging 3.4.1.Final, and Classmate 1.5.1. It contained no `javax.el`, `jakarta.el`, or EL implementation.

NIS does not call `Validation.buildDefaultValidatorFactory()` directly in production. `NisWebAppInitializer` extends Spring `WebMvcConfigurationSupport`; Spring's inherited `mvcValidator()` returns `OptionalValidatorFactoryBean` when Bean Validation is available. `@Valid` is used on three `AccountController` request parameters of type `PrivateKey`. That type currently has no Bean Validation constraints, and the production sources contain no custom constraint annotations, EL message templates, or validation message bundle.

There is nevertheless a live bootstrap failure. With the original runtime classpath, direct default factory creation fails at factory initialization:

```text
javax.validation.ValidationException: HV000183: Unable to initialize 'javax.el.ExpressionFactory'
  caused by NoClassDefFoundError: javax/el/ELManager
```

This happens before constraint metadata initialization, validation, or interpolation. Spring catches that `ValidationException` as part of its optional-validator fallback. The resulting `OptionalValidatorFactoryBean` has no target validator: validation calls fail with `IllegalStateException: No target Validator set`, so the reachable Spring `@Valid` path is silently inactive. This is a production bootstrap problem even though NIS currently has no constrained `PrivateKey` fields and does not currently use EL expressions in its own validation messages.

Hibernate Validator's 6.2 reference guide says Java SE deployments need an EL implementation for the default interpolator and recommends `org.glassfish:jakarta.el:3.0.3`; the guide also describes `ParameterMessageInterpolator` as non-spec-compliant. The artifact name is misleading for this dependency line: the 3.0.3 jar supplies `javax.el.ExpressionFactory` and `com.sun.el.ExpressionFactoryImpl`, not Jakarta EL 4+ packages. The choice keeps the existing `javax.validation` / Spring 5.3 / Hibernate Validator 6.2 contract. Sources: [Hibernate Validator 6.2 reference guide](https://docs.hibernate.org/validator/6.2/reference/en-US/html_single/) and [Hibernate Validator 6.2 release information](https://hibernate.org/validator/releases/6.2/).

## Reproduction and fix

The test-only no-EL reproduction showed that `Validation.buildDefaultValidatorFactory()` failed during factory creation with the root cause above. Spring's production MVC validator setup then produced an uninitialized optional validator. A standalone EL expression test with the selected implementation showed the expression was evaluated (`validatedValue=3`, expression `validatedValue * 2`, resolved message `EL computed 6`), rather than returned literally.

Added `org.glassfish:jakarta.el:3.0.3` to the NIS runtime dependencies. No Spring, ORM, Validator, Jetty, H2, Flyway, Java baseline, schema, API, or protocol version was changed. No Hibernate Validator non-standard interpolator is configured.

The focused regression test uses the default `Validation.buildDefaultValidatorFactory()` and verifies:

- provider version is `6.2.5.Final` and the validation API package is `javax.validation`;
- built-in `@NotBlank` / `@Size` constraints validate and interpolate the standard constraint message;
- an actual EL expression evaluates to `EL computed 6`;
- a `ValidationMessages.properties` template resolves and a literal message remains literal;
- the actual `NisWebAppInitializer.mvcValidator()` initializes and validates through Spring's MVC path.

The production validation messages currently use built-in/default behavior only; no current NIS production annotation uses an EL expression. EL support is needed to make the default ValidatorFactory bootstrap, and thus Spring's existing validator path, functional. The new fixture expression only verifies EL capability and does not add application validation semantics.

## Validation results

| Runtime/check | Result |
|---|---|
| Java 17 focused `HibernateValidatorCompatibilityTest` | 3 tests passed, 0 failures/errors/skips |
| Java 17 focused `mvn -B -pl nis -am -Dtest=HibernateValidatorCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test` | 3 tests passed, 0 failures/errors/skips |
| Java 17 sandbox `mvn -B clean test` | Environment failure: WireMock could not bind loopback; 2,361 tests reached, 16 errors from `FatalStartupException`, no assertion failures |
| Java 17 full `mvn -B clean test` with loopback permission | Passed on retry: 6,224 tests, 0 failures/errors/skips |
| Java 17 full `mvn -B clean package` with loopback permission | Passed: full tests and package, 0 failures/errors/skips |
| Java 25 focused compatibility test | 3 tests passed, 0 failures/errors/skips |
| Java 25 full `mvn -B clean test` with loopback permission | Passed: 6,224 tests, 0 failures/errors/skips |
| Java 25 full `mvn -B clean package` with loopback permission | Passed: full tests and package, 0 failures/errors/skips |
| Hosted Java 17 / Java 25 CI | Pending final commit |

The first loopback-enabled Java 17 `clean test` had one unrelated failure in `PoiImportanceCalculatorTest.spamLinksDoNotHaveABigImpactOnImportance`; its isolated retry passed, and both subsequent Java 17 full `clean package` and a second full `clean test` passed. The first sandboxed test attempt's WireMock bind errors were environmental and did not recur with loopback access. The suite emits expected peer/network log messages, but the completed Java 17/25 runs had no failed tests.

JUnit reports for the completed full run totaled 6,224 tests (core 2,361; deploy 65; peer 306; NIS 3,492), with zero failures, errors, or skips. This count is recorded as observed; no test was added or removed to target a historical count.

## Changed files

- `nis/pom.xml`: add the Java SE EL implementation at runtime.
- `nis/src/test/java/org/nem/nis/validation/HibernateValidatorCompatibilityTest.java`: exercise default provider, standard messages, real EL evaluation, bundle/literal templates, and Spring MVC validator bootstrap.
- `nis/src/test/resources/ValidationMessages.properties`: fixture bundle message.
- This document.

Runtime dependency tree after the change resolves Spring 5.3.39, Hibernate ORM 5.4.33.Final, Hibernate Validator 6.2.5.Final, `jakarta.validation-api:2.0.2`, JBoss Logging 3.4.1.Final, Classmate 1.5.1, and `org.glassfish:jakarta.el:3.0.3` at **runtime scope**. It contains one EL implementation and no `javax.el:javax.el`, EL 4+/Jakarta EL, or second EL provider. The production source compile classpath does not gain the EL API; the application runtime gets the provider required by default interpolation. `mvn -B -pl nis dependency:tree -Dscope=runtime ...` completed successfully; the NIS packaged runtime libraries contain `jakarta.el-3.0.3.jar`. Maven reactor builds also passed dependency convergence checks.

## Remaining limitations

- Current production constraints do not exercise EL expressions; the test proves the default Hibernate Validator EL feature itself.
- This phase does not validate Mainnet/Testnet databases. The real DB compatibility gate remains blocked until provenance-verified Mainnet and Testnet artifacts are available.
- Final commit, push, and hosted CI identifiers are added to the task report after validation completes.
