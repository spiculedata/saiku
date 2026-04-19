# Phase 1 — Build & deploy hygiene (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship Saiku as a single command (`java -jar saiku.jar` or `saiku serve`), modernise the build, and delete the last of the dead weight.

**Architecture:** Big-bang Spring XML → Spring Boot 4 / Spring 7, `javax.*` → `jakarta.*` namespace migration, Jersey 1.19 → Spring MVC, embedded Jetty fat JAR, Picocli CLI, distroless Docker. All riding on the Phase 0 test harness.

**Base branch:** `phase-0-safety-net` (pre-merge; Phase 1 builds on top).

**Tech Stack:** Spring Boot 4.0.x, Spring Framework 7, JDK 21, Picocli, Jakarta EE APIs, Maven 3.9+, Docker (distroless).

**Companion:** See `docs/plans/2026-04-18-saiku-modernisation-design.md` for the why.

---

## Task ordering (low-risk first, big-bang migration last)

Tasks grouped so each is independently reviewable and each group leaves the tree buildable.

### Group A — dead weight

1. **Delete `saiku-bi-platform-plugin-p7.1`** — directory and all references.
2. **Delete `saiku-ui`** — legacy Backbone/Grunt module (rebuilt in Phase 4/6).
3. **Delete `saiku-server`** — distribution assembly (replaced by fat JAR).
4. **Clean up root `pom.xml` commented-out module references** — tidy aftermath.

### Group B — Maven BOM

5. **Extract a `saiku-bom` module.** All dependency versions centralised. Child poms drop their `<version>` tags where possible. New module added to the reactor.

### Group C — Spring Boot 4 migration (big-bang)

6. **Parent pom parents to `spring-boot-starter-parent:4.0.x`** and pick a `spring-boot.version` property. Adds the dependency management.
7. **Delete `saiku-webapp/WEB-INF/saiku-beans.xml`** and convert bean wiring to `@Configuration` classes living in `saiku-core/saiku-service` and `saiku-webapp`.
8. **`javax.*` → `jakarta.*` automated rewrite** using OpenRewrite `org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta`. One big commit, review-as-mass-transformation.
9. **Replace Jersey 1.19 with Spring MVC `@RestController`** for every `com.sun.jersey` resource in `saiku-core/saiku-web` and `saiku-webapp`. This is the bulk of Phase 1's work.
10. **Delete `com.sun.jersey:*`, `jackrabbit:*`-adjacent servlet dependencies, and Spring 4.x dependencies** from the BOM — all superseded.
11. **Spring Security 6 migration** — upgrade alongside Spring Boot. Roles/auth config moves to `SecurityFilterChain` bean.

### Group D — packaging

12. **Create `SaikuApplication` (Spring Boot entry point) in a new top-level `saiku-server` module** — yes we re-introduce the module, but as a Spring Boot fat JAR producer, not a Tomcat distribution builder.
13. **Picocli CLI with subcommands `serve`, `version`, `config validate`** — `serve` delegates to `SpringApplication.run`.
14. **`application.yml`** replaces `saiku.properties` / bean XML config.
15. **Drop WAR packaging** from `saiku-webapp` entirely — merge its contents into `saiku-server` or delete if redundant.

### Group E — distribution

16. **Distroless Dockerfile** + multi-stage Maven build. Non-root user. Healthcheck on `/actuator/health`.
17. **GitHub release automation** — on tag, publish the fat JAR + Docker image.
18. **(Optional) Homebrew tap** — `saiku/homebrew-saiku` with a formula that downloads the released JAR.

---

## Exit criteria

- `java -jar saiku-server-*.jar` starts the server in < 5 seconds.
- `docker run saiku/saiku` works end-to-end (home page loads; one olap4j query works).
- One `application.yml` replaces the XML tangle.
- CI publishes a fat JAR + Docker image on every tag.
- No `javax.*` imports remain in source.
- Phase 0 test suite still green; new integration tests cover the `/actuator/health` surface and one full MDX round-trip.

---

## Conventions

- Same as Phase 0: bite-sized commits, fresh subagent per task where the skill helps, no `--amend`, no `--no-verify`.
- Group C (big-bang Spring migration) is the riskiest. Subagent dispatches here should be briefed with full context on the current Spring 4.1 / Jersey 1.19 surface so they don't surprise-jump to a half-migrated state.
- If Group C subagents drift beyond spec, STOP and escalate.

## Session-scope reality check

A full Phase 1 end-to-end is 4–6 weeks of elapsed work on a real schedule. In a single interactive session we can realistically land Groups A and B (dead weight + BOM). Groups C–E need their own focused sessions, each gated on the previous one.
