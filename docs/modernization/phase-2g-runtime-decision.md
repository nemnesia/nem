# NIS modernization — Phase 2G runtime decision

Status: **COMPLETE WITH RECORDED COMPATIBILITY LIMITATIONS**
Investigation date: 2026-09-26 (Asia/Tokyo)
Repository: `nemnesia/nem`
Branch: `agent/nis-phase0-baseline`
Requested starting HEAD: `cc838b1fa66c0fa51d1bfe6066e2e4bb2e1b3110`
Actual investigation HEAD: `252f00df460412bfa4533a9443501a0878b815cb`

This is a documentation-only decision gate. No production or test code,
dependency, build configuration, Docker image, servlet/JPA namespace, schema,
migration SQL, or runtime behavior was changed. The Phase 2D `BLOCKED`
history and Phase 2E/2F findings remain recorded in their existing documents.
In particular, the Phase 2F H2 compatibility mode, migration behavior,
offline H2 1.4-to-2.x conversion requirement, and unavailable matching-genesis
chain-state comparison remain regression constraints.

The requested starting revision is the Phase 2F implementation commit in this
checkout's history, but the checked-out branch had already advanced to
`252f00d` (the Phase 2F verification commit) when this investigation began.
That later commit was retained as the true parent; no reset or history rewrite
was performed.

## Decision summary

1. **Java 11 is not a viable baseline for a community-maintained complete
   runtime family.** Jetty 12, Spring Framework 6/7, Flyway 10+ and supported
   Hibernate 7 all require Java 17 or later. Remaining on Java 11 means keeping
   EOL Spring 5.3 / Jetty 9.4 and old Hibernate/Flyway lines, or using an
   incomplete and short-lived mix of old and new components.
2. **Set the future production minimum to Java 17.** It is sufficient for the
   selected maintained framework generations, avoids Java 11's stack ceiling,
   and leaves operators the choice of a Java 17, 21, or 25 runtime.
3. **Keep Java 25 as an additional test/runtime target, not the minimum.**
   Spring Framework 7 recommends Java 25, but retains and fully tests its
   Java 17 baseline. Hibernate 7.4 lists Java 17, 21, 25, and 26. Java 25 is
   readily available in official container images, but making it mandatory
   would remove operational flexibility without a demonstrated NIS-specific
   benefit.
4. **A Jakarta migration is unavoidable for maintained Spring/Hibernate
   generations.** Spring 6 requires Java 17 and Jakarta EE 9; Spring 7 and
   Hibernate 7 use Jakarta EE 11 / Jakarta Persistence 3.2. Jetty 12 EE8 can
   temporarily host a javax application, but it does not make javax Spring 5
   or Hibernate 5 maintained, nor can the EE8 servlet layer be mixed with a
   Jakarta Spring 7 application.
5. **Do not upgrade every component in one step.** Establish the Java/build
   baseline first. Migrate Jetty's embedded-server APIs as a bounded runtime
   step, then change the Jakarta servlet/Spring MVC/WebSocket family and
   Hibernate/JPA integration as a deliberately coupled step. Keep Flyway/H2
   as a separately gated database-engine/API wave, retaining every Phase 2F
   regression condition.

The version numbers below identify target series/candidates based on upstream
information checked on the investigation date. They are not authorization to
change dependencies, and exact patch versions must be refreshed from upstream
before an implementation starts.

## Current baseline and repository coupling

The starting runtime is Java 11 bytecode; Java 25 is only an additive test
target. Runtime dependencies are Spring `5.3.39`, Hibernate `5.4.33.Final`,
`javax.persistence-api:2.2`, Servlet `javax.servlet:4.0.1`, Jetty `9.4.x`,
H2 `2.2.220`, and Flyway `9.22.3`. The existing migration sequence is
V1.0.0–V1.0.7. Phase 2F requires offline H2 1.4 export / H2 2 import for
production database files. A matching-genesis full chain-state comparison is
unavailable. The test discovery baseline is 624 classes / 6,218 tests, with
the known public-peer timeout in `NisPeerNetworkHostTest`.

The source inventory confirms the modernization is cross-cutting:

- `deploy` embeds Jetty directly and its bootstrappers, servlet filters,
  listeners, and error handlers import `javax.servlet.*`.
- NIS controllers and interceptors use `javax.servlet.*` and
  `javax.validation.Valid`; DB entities use `javax.persistence.*`.
- WebSocket configuration uses Spring MVC, Spring messaging, SockJS, and
  Jetty WebSocket modules.
- The ORM layer uses Spring's `org.springframework.orm.hibernate5`,
  `LocalSessionFactoryBuilder`, `HibernateTransactionManager`, Hibernate's
  `SessionFactory`, deprecated `org.hibernate.Criteria` / `Criterion`,
  `createSQLQuery`, `ResultTransformer`, and Hibernate-specific cascade/type
  APIs. Native SQL and HQL are present throughout DAOs.
- Hibernate 5.4's JAXB 2 / activation / transaction API dependencies are
  currently explicit or transitive runtime concerns. A JDK 17+ runtime does
  not bundle JAXB, so the Jakarta API/provider set must be reviewed explicitly
  during the ORM migration.
- `nis/Dockerfile` still uses Java 11. Current `nis/src/main/resources/db/*`
  configuration and packaged overlays include Phase 2F compatibility
  settings.

No runtime or test-discovery commands were run in Phase 2G: this phase only
records upstream compatibility evidence and a migration design. The test
baseline above is inherited from the Phase 2F report, not re-measured here.

## Candidate comparison

| Component | Current baseline | Java 11 option | Java 17 recommended family | Support/compatibility observation |
| --- | --- | --- | --- | --- |
| Compiler / runtime | Java 11 minimum; Java 25 test | Keep Java 11 | Java 17 minimum; Java 25 additional CI/runtime | Spring 7 supports JDK 17–25+ and recommends 25; Hibernate 7.4 lists 17/21/25/26. |
| Spring Framework | 5.3.39 | 5.3.x is last `javax` generation; OSS support ended Aug 2024 | 7.0.x; consider 6.2 only as a short Jakarta EE10 bridge | 7.0.x is current production line. Spring 6.2 OSS support ended June 2026; commercial support may remain. |
| Hibernate ORM | 5.4.33.Final | Hibernate 5.4 is old/EOL; Hibernate 6.6 can run Java 11 in recent patch releases but is Jakarta-only and currently limited support | 7.1/7.2 have explicit Spring 7 integration documentation; 7.4 is Hibernate's latest stable line and supports Java 17+ / JPA 3.2 | Exact Spring–Hibernate 7.4 pairing requires an integration spike: Spring 7 documentation explicitly describes 7.1 integration while current Hibernate 7.4 is newer. |
| Persistence API | `javax.persistence:2.2` | 2.2 is tied to unsupported framework generation | `jakarta.persistence:3.2` | Spring 7 and Hibernate 7.4 both align to Jakarta Persistence 3.2. |
| Servlet API | `javax.servlet:4.0.1` | Servlet 4.0 is Java EE 8; Jetty 12 EE8 module can host it | `jakarta.servlet:6.1` with Jetty 12.1 EE11 | `javax` and `jakarta` types are distinct binary names and cannot be mixed as if they were one API. |
| Jetty | 9.4.x | Jetty 9.4 is EOL. Jetty 11 is Java 11 but also EOL. | 12.1.x (Java 17, EE8 through EE11); target EE11 for final family | Maintained Jetty line requires Java 17. APIs/artifact layout differ from Jetty 9, including environment-specific `ee*` modules and WebSocket APIs. |
| Flyway | 9.22.3 | Java 11-compatible Flyway 9 is old; Flyway 10+ requires Java 17 | 13.x, current line at research date; Java 17+; v14 is announced to require Java 21 | H2 itself is a supported Flyway database, but the latest H2 patch exceeds Flyway 13.8's verified version ceiling (details below). |
| H2 | 2.2.220 | Java-compatible, current upstream line continues beyond 2.2; this does not solve Java 11 Flyway/framework blockers | 2.5.250 is the current upstream release; exact Flyway verification remains open | H2 project publishes releases but no formal maximum-Java matrix/support SLA was found. H2 2.5.250 must not be called Flyway-verified by current evidence. |

“Maintained” here means the upstream project identifies the series as current
production/stable or continues publishing it, and does not mean a separately
purchased vendor support contract. Flyway OSS has its own EULA/license terms;
license acceptance and exact artifact source are implementation-gate items.

## Option A — retain Java 11

**Finding: not feasible for a fully community-maintained runtime family.**

- Jetty 12 is the recommended stable/supported generation and requires Java
  17. Jetty 11 and 10 still list Java 11 but are EOL/unsupported; Jetty 9.4 is
  also EOL.
- Spring Framework 6.2 and 7.0 require Java 17. Spring 5.3 is the last
  `javax` branch and its open-source support ended in August 2024. Commercial
  support offerings do not make it community-maintained.
- Flyway v10+ requires Java 17 bytecode. Flyway 9.22.3 was selected in Phase
  2F because it fit Java 11, but it is not the current maintenance line.
- Hibernate 6.6's newest patch releases list Java 11 compatibility, but it
  uses Jakarta Persistence and is listed in limited-support status. It cannot
  keep the current `javax.persistence` model intact. Hibernate 7 requires
  Java 17.
- H2 is not the blocker: current H2 is Java 11-capable based on upstream
  project history, and its current release is maintained. The integrated
  Spring/Jetty/Flyway stack is the blocker.

Keeping Java 11 is technically possible only by accepting EOL Spring 5.3 and
Jetty 9.4 plus old Hibernate/Flyway, or by using a partial transition whose
servlet/JPA namespaces do not form one coherent supported family. Running
EOL artifacts because they happen to execute would retain upstream security
and maintenance risk and is not the recommended path.

## Option B — Java 17 minimum, Java 25 additional target

**Finding: feasible and recommended.** Java 17 is sufficient for the current
Spring 7, Jetty 12, Hibernate 7, and Flyway 13 generations. Java 25 should be
kept in CI and exercised as a supported production runtime target after the
stack is migrated.

### Namespace and API work

- **`javax` to `jakarta`: required for Spring 6+/Hibernate 6+.** Spring 6
  moved to Jakarta EE 9; Spring 7 uses Jakarta EE 11. Migrate servlet imports
  to `jakarta.servlet`, JPA mappings/imports to `jakarta.persistence`,
  validation to `jakarta.validation`, and applicable transaction/annotation
  APIs to `jakarta.transaction` / `jakarta.annotation`. API versions must
  match the chosen EE generation. Avoid a global text-only rename: some
  unrelated packages that still validly use `javax` are not part of this
  change.
- **Spring ORM integration:** Spring 7's Hibernate-native integration moved
  from `org.springframework.orm.hibernate5` to
  `org.springframework.orm.jpa.hibernate` and is documented for Hibernate
  ORM 7.1. `SessionFactoryLoader`, bean wiring, current-session context,
  `HibernateTransactionManager`, exception translation, and transaction
  boundaries need source and behavior review. Keep Spring/Hibernate versions
  aligned; do not treat a Spring-only POM bump as a valid intermediate state.
- **Hibernate queries and mappings:** Hibernate 6 removed legacy Criteria
  (`org.hibernate.Criteria`, `org.hibernate.criterion`) and `createSQLQuery`;
  use JPA Criteria or a deliberate query rewrite and `createNativeQuery`.
  Result transformers and type registration APIs changed; Hibernate 7 also
  removes old compatibility APIs and tightens HQL/query behavior. NIS has
  broad use of legacy Criteria, HQL, native SQL, `LongType`,
  `DISTINCT_ROOT_ENTITY`, Hibernate `SAVE_UPDATE` cascades, and result
  transformers. Convert and assert the meaning/result ordering of each query;
  compile success alone is inadequate. Do not alter SQL semantics or DAO
  transaction/rollback ordering.
- **Jetty server and WebSocket APIs:** Jetty 12 has a new artifact/module
  organization and EE-specific servlet/websocket modules (`ee8-*` through
  `ee11-*`). Rework `Server`, handler/context construction, servlet
  registration, filters/listeners, class-list configuration, lifecycle, and
  WebSocket/SockJS bridge APIs against Jetty 12.1 EE11. Jetty 12.1's EE8
  compatibility is a real javax deployment environment; it does not adapt
  Jakarta Spring classes to javax or bridge class identity.
- **Spring MVC/WebSocket:** migrate servlet/controller/interceptor signatures,
  web initializer and `DispatcherServlet` integration, CORS configuration,
  message broker and SockJS endpoint wiring. Recheck `HandlerInterceptor`
  APIs (including removal of the deprecated adapter), STOMP endpoint
  registration, allowed-origin behavior, handshake, session lifecycle, and
  HTTP/WebSocket route contract.
- **Validation and transaction APIs:** Jakarta Validation 3.1 and Jakarta
  Transactions 2.x belong to the Spring 7 / EE11 family. Upgrade Validator
  together, check constraint/exception behavior, transaction annotation
  discovery, rollback-on-exception rules, and Spring's Hibernate transaction
  manager behavior.
- **JAXB / Activation:** JDK 17+ does not bundle JAXB. Determine which
  dependencies are actually required after Hibernate 7/Spring changes, then
  align `jakarta.xml.bind` API + runtime provider and `jakarta.activation`
  explicitly. Avoid pulling both `javax` and `jakarta` implementations into
  production unless a verified transitive user needs each.
- **Flyway API/runtime:** current construction already uses the fluent
  `Flyway.configure()` API from Phase 2F. Flyway 13 remains Java 17+, while
  Flyway 14 is announced to raise the minimum to Java 21. Inventory direct API
  calls, configuration keys, database modules, and license/repository needs;
  do not combine its engine change with servlet/JPA source migration.

### Coherence and database candidate caveat

The target family is Spring Framework 7.0.x + Jetty 12.1.x EE11 + Hibernate
ORM 7.x + Jakarta Persistence 3.2. This matches Jakarta EE 11. Hibernate 7.1
is explicitly named in Spring 7's ORM API documentation; Hibernate 7.4 is
Hibernate's newer current stable series and matches the same JPA 3.2 / Java 17
baseline, but its precise Spring 7 integration must pass an implementation
spike. Do not choose a Hibernate version beyond Spring's documented or
empirically verified pairing without recording that compatibility evidence.

For the DB side, Flyway 13.8.0 documents Java 17+ and its release notes say
H2 verification was increased to 2.2.224. H2's current upstream release is
2.5.250, and the Flyway H2 driver page says H2 1.2.137 and later are
supported, but Flyway itself warns when the H2 engine is newer than its
verified version. The opened Flyway request to verify H2 2.4.240 confirms
this gap remains real. Therefore:

- Candidate maintained pair: Flyway 13.x + H2 2.5.x, **subject to explicit
  Flyway/H2 migration replay and DAO verification**; this is a target
  hypothesis, not an officially verified patch pair today.
- Most recently explicitly Flyway-verified H2 patch at the source date:
  2.2.224. It provides a conservative test anchor but should not be mistaken
  for the current H2 release.
- Preserve Phase 2F's exact existing conclusions and baseline throughout:
  H2 1.4 files need offline export/import, legacy URL compatibility
  settings stay as required, migrations remain V1.0.0–V1.0.7 and unchanged,
  migration history is not repaired/rebased, and matching-genesis chain-state
  comparison remains unproven.

## Option C — Java 25 minimum

**Finding: technically feasible but not justified as the production minimum.**

| Factor | Java 17 minimum + test/runtime Java 25 | Java 25 minimum | Assessment |
| --- | --- | --- | --- |
| Binary compatibility | Compile release 17, test on 17/21/25; bytecode and dependencies set the minimum | Can compile release 25 | No inherent need to compile for 25; using newer bytecode would exclude older operators. |
| Library support | Spring 7 supports 17–25+; Hibernate 7.4 lists 17/21/25/26; Jetty/Flyway require 17 | Same libraries run on 25 | No material library advantage; Spring recommends 25 but deliberately retains 17. |
| Operational flexibility | Operators can use supported 17, 21, or 25 LTS JDKs | Requires 25 everywhere | Java 17/21 capable deployment platforms remain available longer, with fewer rollout constraints. |
| Future maintenance | Java 17 baseline remains supported by the selected line; can raise later when an upstream requirement demands it | Shorter distance to Spring's recommended latest LTS | Java 25 could reduce a future migration if a dependency raises its floor, but v14 Flyway requiring Java 21 still does not force Java 25. |
| Containers | Official Temurin 17/21/25 images are available | Official Temurin 25 image is available | Availability is not a deciding factor; current Docker needs to change from Java 11 either way. |
| Memory / GC observability | Modern JDK observability is available, with target JDK-specific baseline | Same tools plus newest JDK defaults | No proven NIS-specific benefit without controlled measurement. |
| Deployment risk | Expand CI to 17/25 and canary on 25 while retaining 17 fallback | Every operator and image must jump directly to 25 | Java 17 is the lower-risk compatibility transition. |

No upstream binary-compatibility rule requires production to run on the exact
JDK used for test compilation; the release target and tested runtime matrix
must be explicit. JDK 25 minimum may be reconsidered if a future supported
dependency floor requires it or NIS's measured production needs demonstrate a
concrete benefit. Neither condition is established here.

## Jetty 12 EE8 transitional step

Jetty 12 EE8 can load Java EE 8 applications using Servlet 4.0 in `javax.*`
while sharing a Jetty 12 core. This could isolate some server/container API
work from namespace conversion. It is **not recommended as a long-lived
transitional production family** because Spring 5.3 remains EOL, Hibernate
5.4 remains old/EOL, and Java 11 support is still unavailable (Jetty 12 needs
Java 17). It may have value as a short-lived, test-only or separately
reviewed subwave if Jetty 9-specific bootstrap work is large and its own
rollback boundary is clear. It should not be presented as completing runtime
modernization or as a bridge that makes EE8/Jakarta EE11 types interoperable.

## Proposed implementation waves

No implementation wave is authorized or started by this report. The sequence
below minimizes coupled changes while retaining independent checks and
rollback points.

| Wave | Scope | Coupling and rollback gate |
| --- | --- | --- |
| **Phase 2H — Java 17 baseline / build / CI / Docker** | Change compiler release and supported runtime policy to 17; retain Java 25 test/runtime target; update Maven toolchain/CI and builder/runtime container. Do not change framework, schema, migrations, or application behavior. | Roll back with the build/runtime configuration only. Pass clean build, all existing tests/discovery on JDK 17 and 25, package layout, container startup, current HTTP endpoints, and confirm Java 11 removal policy. Do not tune heap/GC. |
| **Phase 2I — Jetty 12 server/runtime** | Upgrade Jetty 9.4 to Jetty 12.1 and map embedded server/bootstrap APIs. Use the EE8 environment only if a short-lived namespace-preserving transition is needed; final target is EE11. | Jetty API/artifact and bootstrap changes can be isolated from Spring/ORM dependency changes using EE8. Verify HTTP endpoints, static/error handling, lifecycle/shutdown, filters, WebSocket handshake/STOMP/SockJS behavior, and both supported JDKs. The eventual move to EE11 is coupled to Jakarta/Spring. |
| **Phase 2J — Jakarta servlet + Spring 7 web/runtime** | Migrate servlet/validation namespaces, servlet API, Spring 5.3→7, MVC, WebSocket, messaging, and related Jetty EE11 modules as one coherent web-stack change. | These APIs share namespace and runtime class identity; do not split Spring 7 from the Jakarta servlet API/Jetty EE11 module. Verify routes, JSON/error policy, CORS, WebSocket/STOMP topics, session/transaction integration, and all tests. |
| **Phase 2K — Hibernate 5.4→7 / JPA 2.2→3.2** | Migrate persistence imports, mappings, Spring ORM integration, query API and Hibernate types; pair with a Spring-supported ORM 7 version. | Jakarta JPA mappings and Spring7 ORM integration are coupled. May run alongside 2J only if a single PR/branch keeps one coherent runtime graph; otherwise land immediately after 2J with a hard compile/runtime gate. Verify every DAO query, transaction/flush behavior, mappings, rollback/fork operations, migration-created schema metadata, and deterministic state tests. |
| **Phase 2L — Flyway/H2 current line** | Evaluate Flyway 13.x and H2 2.5.x, plus current license/artifact/module requirements. Keep production migration SQL and compatibility URL behavior unchanged. | Separate from web/API waves. Require clean V1.0.0–V1.0.7 replay, Flyway history no-op on a converted 2F database, checksum behavior without repair, schema/type metadata comparison, row-level digests and HQL/native DAO tests. Repeat offline export/import on disposable copies; production file conversion stays operator-controlled. |

Do not merge 2J and 2K merely for convenience: they are semantically
separate concerns and have separate focused validation. They can share an
integration branch after independently compiling, because Spring 7's ORM
integration requires Hibernate 7. Do not split Spring 7 from Jetty EE11 or
Hibernate 7 from Jakarta Persistence. Any mismatch in chain-state snapshot,
query output, SQL write set, DB rows, transaction boundaries, or block/fork
behavior is a stop/rollback condition.

## Performance baseline design (measurement only)

No heap, GC, cache, thread-pool, CPU, or application tuning is included in
these waves. Before/after measurements should use the same hardware/container
limits, node configuration, database snapshot and genesis, chain height,
network/request workload, warm-up time, and observation duration. Record JDK,
framework versions, container digest, JVM flags, OS/kernel, cgroup limits,
database file hash, and workload generator version with every run.

| Measurement | Collection method | Reporting unit / comparison |
| --- | --- | --- |
| Heap | JMX/JFR heap summaries and GC logs; periodic `jcmd GC.heap_info` | Used/committed/max bytes; steady-state and peak |
| RSS | Container/cgroup memory plus `/proc/<pid>/status` `VmRSS` or equivalent | Bytes, sampled at fixed interval; separate container limit from process RSS |
| Metaspace | JMX `MemoryPoolMXBean` and JFR | Used/committed bytes and post-warmup trend |
| Direct/native memory | BufferPoolMXBean for direct/mapped buffers; NMT summary where enabled; container RSS residual | Bytes by source; explicitly note NMT excludes third-party native/JDK allocations and NMT's overhead |
| Thread count | JMX `ThreadMXBean` / JFR and OS process thread count | Live/peak Java and OS threads |
| GC pause / allocation | Unified GC logs + JFR GC/allocation events | Pause p50/p95/max and total pause; allocated bytes/sec and object allocation rate |
| CPU utilization | Container/cgroup CPU and JFR process CPU | CPU-seconds and percent of allocated cores; same sampling interval |
| Request throughput | Fixed open-loop HTTP workload and server counters; report route mix and errors | Successful requests/sec, latency p50/p95/p99, error/timeout rate |
| Block processing throughput | Replay the same matching-genesis chain range; record processed heights/time and replay phase | Blocks/sec, transactions/sec, completion duration; state digest equality is a correctness gate |

Run repeated trials, report median and spread, and preserve raw logs/JFR
outside the repository with a compact summary checked into the later
performance-baseline artifact. JFR can provide allocation, CPU, GC pause, and
thread data. If NMT is used, use the same setting on every compared runtime
and disclose its documented overhead; it is an observer, not a tuning signal
in this phase.

## Risks and unresolved decisions

- Java 17 requires a coordinated Docker, CI, compiler-release and operator
  runtime change; available Java 11 operators need a planned notice/rollout.
- Full Mainnet/Testnet chain-state compatibility is not proven because the
  available DB copy has a genesis mismatch. Framework/database waves cannot
  claim full state equivalence without a matching-genesis snapshot.
- Hibernate 7 migration is sizable: legacy Criteria, Hibernate-specific
  cascades, native-query return types, result transformers, HQL behavior,
  custom mappings and transaction integration all need dedicated review.
- Spring's explicitly documented Hibernate 7 integration point names 7.1;
  Hibernate 7.4 is newer and stable but requires pairing evidence before pin.
- Flyway 13.8's H2 verification maximum is 2.2.224 while H2 2.5.250 is
  current. The latest pair is plausible but not upstream-verified as a pair.
- Existing H2 production files still require an offline export/import process;
  modernization does not authorize automatic in-place conversion or schema
  rewrite. Phase 2F's checksum validation limitation remains.
- Jetty EE8 might create an additional short-lived artifact/configuration
  branch; adopting it is optional and should be justified by measured
  engineering isolation, not treated as a necessary phase.
- Spring 7's current minimum is Java 17; future release generations may
  increase it. Re-check upstream matrices before each wave.
- Remaining decision for implementation planning: approve Java 17 as the
  future production minimum and Java 25 as an additional test/runtime target.
  This report recommends that policy; it does not change the repository's
  Java 11 production configuration.

## Official upstream references

All support and compatibility claims above refer to upstream sources checked
on 2026-09-26. Maven Central recency alone was not used as selection evidence.

- Spring Framework [support/version matrix](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions): Spring 5.3/6.2/7 JDK ranges, namespace boundary, and support state.
- Spring Framework [7.0 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes): JDK 17 baseline, Jakarta EE 11 APIs, migration requirements.
- Spring Framework [ORM/Hibernate reference](https://docs.spring.io/spring/reference/7.0-SNAPSHOT/data-access/orm/hibernate.html) and [LocalSessionFactoryBean 7.0 API](https://docs.spring.io/spring-framework/docs/7.0.0-M8/javadoc-api/org/springframework/orm/jpa/hibernate/LocalSessionFactoryBean.html): Hibernate 7 integration package/API and documented ORM 7.1 compatibility. The reference currently labels its newest snapshot as unreleased; implementation must use released documentation/Javadocs.
- Eclipse Jetty [version compatibility table](https://jetty.org/docs/jetty/12.1/index.html), [download/support table](https://jetty.org/download.html), and [deployment environments](https://jetty.org/docs/jetty/12/operations-guide/deploy/index.html): Java minimum, stable/EOL state and EE8/EE11 namespace environments.
- Hibernate ORM [release/support matrix](https://hibernate.org/orm/releases/) and [7.4 compatibility page](https://hibernate.org/orm/releases/7.4/): Java/JPA versions and stable/limited/EOL designations.
- Hibernate ORM [7.0 migration guide](https://docs.hibernate.org/orm/7.0/migration-guide/): removed/deprecated Hibernate APIs and query migration changes; review the actual chosen ORM major's matching migration guide during implementation.
- Redgate Flyway [Java API requirements](https://documentation.red-gate.com/flyway/reference/usage/api-java), [engine release notes](https://documentation.red-gate.com/fd/release-notes-for-flyway-engine-179732572.html), [H2 driver reference](https://documentation.red-gate.com/flyway/reference/database-driver-reference/h2), and [H2 verification request](https://github.com/flyway/flyway/issues/4272): current Java line, Flyway 13.8 H2 verification ceiling, and newer-H2 warning context.
- H2 [official repository/current release](https://github.com/h2database/h2database/releases) and [2.x migration guide](https://h2database.com/html/migration-to-v2.html): current patch/release activity and H2 1.4 conversion constraints already recorded in Phase 2F.
- Eclipse Temurin [official Docker image](https://hub.docker.com/_/eclipse-temurin): official Java 25 image example and maintained Java runtime images.
- Oracle JDK [diagnostic tools](https://docs.oracle.com/en/java/javase/25/troubleshoot/diagnostic-tools.html) and [Native Memory Tracking limitations](https://docs.oracle.com/en/java/javase/25/vm/native-memory-tracking.html): JFR/GC/CPU/memory observation and NMT coverage boundaries.
