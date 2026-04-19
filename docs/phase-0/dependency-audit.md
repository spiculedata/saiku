# Phase 0 dependency audit

**Snapshot:** 2026-04-19 (end of Phase 0), default reactor `saiku-core` + `saiku-webapp` only.

Captured by `mvn -q dependency:tree` against the post-Phase-0 tree.

## Tree size

Full resolved tree: **330 lines** across all Phase 0 modules. Down from pre-Phase-0 once `saiku-ui`, `saiku-server`, `saiku-bi-platform-plugin-p7.1`, the commercial licensing module and the MarkLogic backend were cut.

## Headline red flags — must-fix before the JDK-21 runtime actually runs

| Dependency | Version | Problem | Fix phase |
|---|---|---|---|
| `org.springframework:spring-*` | **4.1.6.RELEASE** (2015) | Does not run on JDK 17+. Uses `javax.*` servlet API. | **Phase 1** (migrate to Spring Boot 4.x / Spring 7 + `jakarta.*`). |
| `org.springframework.security:spring-security-*` | **4.0.1.RELEASE** (2015) | Same JDK/namespace problem. | Phase 1. |
| `com.sun.jersey:jersey-*` | **1.19** (2015) | Dead branch. JAX-RS 1 (`javax.ws.rs`). Not JDK-11-compatible without a lot of `--add-opens`. | Phase 1 (migrate to Jersey 3.x / Spring WebMVC). |
| `org.hibernate:hibernate-core` | **4.3.5.Final** (2014) | Incompatible with modern JDKs; uses `javax.persistence`. | Phase 2/3 (replace entirely — nothing core depends on full ORM; raw JDBC or Spring `JdbcTemplate` suffices). |
| `org.jasig.cas.client:cas-client-*` | **3.3.2** (2016) | End-of-life Jasig lineage; `javax.servlet` dependency. | Phase 7 (auth rewrite → OIDC/SAML). |
| `org.apache.jackrabbit:jackrabbit-*` | **2.16.1** (2018) | Hundred-transitive sprawl. **23 Jackrabbit/javax lines in the tree.** | **Phase 2** deletes it entirely (replaced with filesystem repo). |

## Jackson

Mixed era: `jackson-core/databind/annotations:2.5.1` sit alongside legacy `jackson-core-asl:1.8.8`, `jackson-mapper-asl:1.8.8`, `jackson-jaxrs:1.9.2`, `jackson-xc:1.9.2`. Bump all `com.fasterxml.jackson.*` to **2.17+** during Phase 1; dead codehaus `jackson-*-asl` artefacts will drop out once Jersey 1.19 is replaced.

## Log4j

Tangle: `log4j:log4j:1.2.14` (EOL 2015) + Log4j2 bridge (`log4j-1.2-api`, `log4j-api`, `log4j-core:2.23.1`) + `slf4j-log4j12:1.6.4`. Phase 1 should consolidate on Log4j2 over SLF4J2, drop the 1.x JAR and the `log4j12` bridge.

## Other oddities worth flagging for later

- `commons-httpclient:commons-httpclient:20020423` — that's a 2002 daily-build version string. Replace with Apache HttpClient 5 in Phase 5 (Arrow pipeline / HTTP clients).
- `hsqldb:hsqldb:1.8.0.10` — also 2010-era. Bump with the test harness work in Phase 1.
- `cglib:cglib:2.2` + `cglib-nodep:2.2` — dragged in by old Spring. Goes away when Spring is upgraded.
- `javax.servlet:javax.servlet-api:3.1.0` — Phase 1 `jakarta.servlet` migration swaps it.

## JDK-21 compatibility summary

| Area | Status on JDK 21 |
|---|---|
| Compile with `-source 21 -target 21` | ✅ green (`mvn verify` on `saiku-core` + `saiku-webapp`). |
| JAXB (`javax.xml.bind`) | ✅ re-added as explicit `jakarta.xml.bind-api:2.3.3` + `com.sun.xml.bind:jaxb-impl:2.3.9` (legacy `javax.*` namespace, not 3.x). |
| Runtime | ❌ NOT verified. Spring 4.1 and Jersey 1.19 will fail on JDK 17+. No existing tests spin up a web context; first proof-of-life for the full runtime lands in Phase 1. |

## Dependabot

Enabled via `.github/dependabot.yml` on a weekly cadence for Maven and GitHub Actions. Limited to 5 concurrent PRs so it doesn't flood review. Tune if it starts proposing Spring 6 upgrades in isolation — prefer to group Spring bumps with the Phase 1 migration.
