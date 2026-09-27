# Isolated Hibernate Validator patch modernization

Status: **PARTIAL — local Java 25 clean-package attempt is blocked by loopback policy; hosted CI pending**  
Repository: `nemnesia/nem`  
Branch: `agent/nis-phase0-baseline`  
Requested / actual starting HEAD: `a7ea14e133372e62d6b8562fe23e3792d537c791`  
Final HEAD: recorded in the final task report after commit  
Date: 2026-09-27

## Scope and selected task

This phase audited current modernization records, root and module POMs, CI
workflows, runtime dependencies, and NIS validation usage. It selected one
isolated production dependency boundary: update Hibernate Validator within
the existing Bean Validation 2 / `javax.validation` line.

| Candidate | Current → candidate | Decision |
| --- | --- | --- |
| Hibernate Validator | `6.2.0.Final` → `6.2.5.Final` | Selected: a patch update of the provider used by NIS, with the same Bean Validation 2 API line and no persistence schema or protocol change. The canonical Maven group is `org.hibernate.validator`; the previous coordinate was relocated there. |
| Spring Framework | `5.3.39` → Spring 6+ | Deferred: requires a broad `javax.*` to `jakarta.*` and Servlet contract migration. |
| Hibernate ORM | `5.4.33.Final` → Hibernate 6+ | Deferred: changes persistence behavior and remains gated by provenance-verified Mainnet/Testnet DB validation. |
| H2 / Flyway | `2.2.220` / `9.22.3` → newer lines | Deferred: real database conversion, history provenance, and chain-state evidence remain the governing gate. |
| Jetty | `12.1.13 EE8` | Already modernized; not repeated here. |
| Crypto, serialization, protocol, consensus | Various | Not selected: these boundaries can change consensus-visible behavior and require separate byte-level / chain-state evidence. |
| Maven plugins / test stack | Existing pinned configuration | Deferred: broad build/test wiring change is less isolated than the selected provider patch. |

The Hibernate Validator 6.2 upstream release and reference guide identify
`6.2.5.Final` as the final patch in the 6.2 series, support Java 8/11/17, and
retain Jakarta Bean Validation 2.0, whose Java package namespace is still
`javax.validation`. The guide reports no API changes in the patch release.
The series is limited-support; this is a bounded patch modernization, not a
claim that the component is currently actively maintained or a comprehensive
security remediation.

References:

- [Hibernate Validator 6.2 releases](https://hibernate.org/validator/releases/6.2/)
- [Hibernate Validator 6.2 reference guide](https://docs.hibernate.org/validator/6.2/reference/en-US/html_single/)

## Changes

- NIS now declares `org.hibernate.validator:hibernate-validator:6.2.5.Final`
  rather than the relocated `org.hibernate:hibernate-validator:6.2.0.Final`.
- Added `HibernateValidatorCompatibilityTest`, which validates valid and
  invalid fixture values using `javax.validation` `@NotBlank` and `@Size`.
- No application endpoint, validation rule, Spring/Hibernate ORM, H2, Flyway,
  Jetty, schema, migration script, serialization format, protocol, or network
  behavior was changed.

The focused test uses Hibernate Validator's
`ParameterMessageInterpolator`. A direct probe of the default interpolator
failed because this runtime dependency graph does not include a `javax.el`
implementation (`HV000183`, `NoClassDefFoundError: javax/el/ELManager`). The
test therefore proves the provider and `javax.validation` constraint contract;
it does not claim to validate EL-backed message interpolation. No EL runtime
dependency was added as part of this isolated patch.

## Dependency comparison

NIS runtime dependency tree before:

```text
org.hibernate:hibernate-core:5.4.33.Final
  org.jboss.logging:jboss-logging:3.4.1.Final
  com.fasterxml:classmate:1.5.1
org.hibernate.validator:hibernate-validator:6.2.0.Final
  jakarta.validation:jakarta.validation-api:2.0.2
```

After:

```text
org.hibernate:hibernate-core:5.4.33.Final
  org.jboss.logging:jboss-logging:3.4.1.Final
  com.fasterxml:classmate:1.5.1
org.hibernate.validator:hibernate-validator:6.2.5.Final
  jakarta.validation:jakarta.validation-api:2.0.2
```

The only resolved runtime artifact version change in this focused tree is
Hibernate Validator itself. Its validation API artifact is named
`jakarta.validation-api`, but version `2.0.2` supplies the existing
`javax.validation.*` packages; this does not start a Jakarta namespace
migration. Hibernate ORM and its dependencies are unchanged. The canonical
group also removes Maven's relocation warning from the old coordinate.

## Compatibility and operational impact

- Java baseline remains 17. Hibernate Validator 6.2.5's stated minimum is
  Java 8, so both the production baseline and Java 25 compatibility runtime
  are above that minimum.
- Spring remains 5.3.39 and consumes the same Bean Validation 2
  `javax.validation` API contract.
- No DB schema, Flyway history, serialization, REST contract, network
  protocol, consensus, or genesis data is touched.
- Rollback is a single POM coordinate/version revert plus removal of the
  focused test; no data rollback is needed.
- The patch release notes describe a warning fix. No application-specific
  validation constraints or custom validators were found that rely on
  Hibernate Validator internals.

## Validation

Environment: OpenJDK `17.0.20.1`, OpenJDK `25.0.4.1`, Maven `3.8.7`.

| Command | Result |
| --- | --- |
| Java 17 `mvn -B -pl nis -am -Dtest=HibernateValidatorCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS, 1 test, 0 failures/errors/skips. Runtime log reports Hibernate Validator `6.2.5.Final`. |
| Java 17 `mvn -B clean test` | PASS, all reactor modules; 0 failures, errors, or skips. The first sandbox attempt failed to bind WireMock loopback (`java.net.SocketException: Operation not permitted`); retry with normal elevated command execution passed. |
| Java 17 `mvn -B clean package` | PASS, all reactor modules and package phase. Initial sandbox attempt had the same loopback restriction; retry passed. |
| Java 17 `mvn -B -DskipTests package` | PASS, all five reactor modules packaged. This is compile/package evidence and does not replace the blocked full test phase. |
| Java 25 focused compatibility test | PASS, 1 test, 0 failures/errors/skips. |
| Java 25 `mvn -B clean test` | PASS, all reactor modules; 0 failures, errors, or skips. |
| Java 25 `mvn -B clean package` | PARTIAL: test phase stopped in `core` because WireMock loopback startup returned `java.net.SocketException: Operation not permitted` (2,361 tests counted, 0 assertion failures, 16 errors, 0 skipped); package goal did not run. |
| Java 25 `mvn -B -DskipTests package` | PASS, all five reactor modules packaged after the successful full clean test. |
| Hosted GitHub Actions Java 17 / Java 25 | To be recorded after push. |

The loopback failure during the Java 25 combined clean-package invocation is
an environment restriction, not evidence of a Hibernate Validator regression:
the Java 25 full clean test and a separate package build both pass. The newly
added test passes on both local JDKs. Hosted CI is still required to validate
the normal repository workflow independently.

## Remaining gates

- **Phase 2F real Mainnet/Testnet DB compatibility remains BLOCKED.** No
  provenance-verified Mainnet or Testnet database artifact was available.
  This patch does not use synthetic fixtures to alter that status.
- Jenkins Java 17 image/shared-library ownership remains an independent
  infrastructure blocker.
- EL-backed Bean Validation message interpolation was not tested by this
  patch; no EL dependency was added.

This phase does not authorize Spring 6, Hibernate 6, Jakarta, or a database
engine migration. Those remain separate gated work.
