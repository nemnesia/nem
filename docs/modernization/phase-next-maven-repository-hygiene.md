# Maven repository hygiene and Central-only resolution

## Status

**COMPLETE — Central-only resolution is verified locally and hosted Java 17 / Java 25 CI passed.**

No production Java code, dependency version, runtime behavior, protocol, schema, or migration SQL changed. The Phase 2F Mainnet / Testnet real-database compatibility gate remains BLOCKED pending provenance-verified database artifacts.

## Repository state

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested and actual starting HEAD: `def768cc3812b8465ecbb8d804b1402cbf83d80f`
- Starting worktree: clean; local branch matched `origin/agent/nis-phase0-baseline`
- Final POM/configuration implementation HEAD: `286422f0b931d03a49942f71d2f701ca394298ae`
- This evidence document is committed as a documentation-only follow-up; the final branch HEAD is reported with the task result.

## Repository definitions

Before the change, the only artifact repository definitions in the project POM files were:

| POM | ID | URL | Transport | Finding |
|---|---|---|---|---|
| root `pom.xml` | `repo2_maven_org` | `http://repo2.maven.org/maven2` | HTTP | Historical Central mirror; redundant and insecurely specified |
| `nis/pom.xml` | `hibernate-repo` | `https://repository.jboss.org/nexus/content/repositories/central/` | HTTPS | JBoss path serving Central; redundant |
| `nis/pom.xml` | `springsource-repo` | `http://repo.springsource.org/release` | HTTP | Historical repository; no artifact required it |

No `<pluginRepositories>` or `<distributionManagement>` definitions were found in the project POMs. The other POM `<profile>` elements do not add repositories. HTTP URLs in `<organization>` metadata are not artifact repositories.

The three repository blocks were removed. The final effective POM for the root and NIS module lists only Maven's standard `central` repository and standard Central plugin repository (`https://repo.maven.apache.org/maven2`). No replacement custom Central repository was added.

## Central-only resolution procedure

Maven Central was made the only eligible source with a temporary settings mirror (`mirrorOf=*`), and each runtime used a newly created empty Maven local repository. Maven debug/transfer logs showed plugin and dependency downloads from `central-only` at `https://repo.maven.apache.org/maven2`; the old repository IDs were mirrored to that URL in the pre-removal probe. The post-removal probes used the same Central-only mirror. This rules out a successful build caused by artifacts cached from the legacy repositories.

The temporary settings file was equivalent to:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors>
    <mirror>
      <id>central-only</id>
      <url>https://repo.maven.apache.org/maven2</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

The final clean commands were:

```bash
M2_J17="$(mktemp -d)"
mvn -B -X -s /tmp/nem-central-only-settings.xml \
  -Dmaven.repo.local="$M2_J17" clean test
mvn -B -s /tmp/nem-central-only-settings.xml \
  -Dmaven.repo.local="$M2_J17" clean package

M2_J25="$(mktemp -d)"
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
PATH="/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH" \
mvn -B -X -s /tmp/nem-central-only-settings.xml \
  -Dmaven.repo.local="$M2_J25" clean test
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
PATH="/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH" \
mvn -B -s /tmp/nem-central-only-settings.xml \
  -Dmaven.repo.local="$M2_J25" clean package
```

`-X` was used on the first invocation for each JDK to retain artifact source evidence. The initial pre-removal Java 17 `clean test`, also using an empty local repository and the Central-only mirror, succeeded. It proved the repositories were not needed before editing; the post-removal builds confirm the final POM state.

## Validation results

| Runtime | Operation | Result |
|---|---|---|
| Java 17.0.20.1, Maven 3.8.7 | fresh-cache `clean test` | PASS; 6,224 tests, 0 failures, 0 errors, 0 skipped |
| Java 17.0.20.1, Maven 3.8.7 | same-cache `clean package` | PASS |
| Java 25.0.4.1, Maven 3.8.7 | fresh-cache `clean test` | PASS; 6,224 tests, 0 failures, 0 errors, 0 skipped |
| Java 25.0.4.1, Maven 3.8.7 | first `clean package` | One transient `AsyncTimerTest.initialDelayIsRespected` failure (`Expected: <1>`, observed `<0>`); no errors. No test or timeout changes were made. |
| Java 25.0.4.1, Maven 3.8.7 | unchanged `clean package` rerun | PASS; 6,224 tests, 0 failures, 0 errors, 0 skipped |

The Java 25 package failure was isolated to a timing-sensitive test and did not reproduce on the immediate unchanged rerun. The initial successful Java 25 `clean test` and the successful package rerun are distinct results; the transient failure is retained here rather than hidden.

## Dependency and plugin comparison

The NIS dependency tree was captured before the POM cleanup and after it using the reactor (`-pl nis -am`) so local reactor artifacts were represented consistently. Both trees contain the same 101 dependency coordinates and versions. No dependency or plugin version was changed. The normal build resolved the project's pinned plugins (including clean, compiler, surefire, dependency, and packaging plugins) from Maven Central in the empty-cache probes.

## Hosted CI

The local GitHub CLI has an invalid stored credential, so authenticated `gh` inspection was unavailable. Public Actions run metadata was readable after push. Both workflows completed successfully for commit `311f02c920bc929c11b61e941bd5d6c10315c8ca`:

| Workflow | Run ID | Result |
|---|---:|---|
| Java 17 Baseline | [36320970656](https://github.com/nemnesia/nem/actions/runs/36320970656) | Success; clean unit tests and package steps passed |
| Java 25 Compatibility | [36320970643](https://github.com/nemnesia/nem/actions/runs/36320970643) | Success; clean unit tests and package steps passed |

No credential was generated or changed, and no workflow was modified. A documentation-only follow-up commit records these results; final-HEAD CI is checked separately in the task report.

## Scope and remaining gates

- Changed files: root `pom.xml`, `nis/pom.xml`, and this document.
- The repository cleanup affects Maven artifact sources only; production behavior and the dependency graph remain unchanged.
- Phase 2F Mainnet / Testnet real-database validation remains **BLOCKED** because provenance-verified real DB artifacts are unavailable. Synthetic database results do not clear that gate.
- Hosted Java 17 / Java 25 CI should be checked when repository access is available.
