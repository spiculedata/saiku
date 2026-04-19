# Phase 1 Group D — Packaging & CLI (implementation plan)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to execute task-by-task.

**Goal:** `java -jar saiku.jar` starts the server in < 5 seconds. `saiku serve` works as a CLI.

**Base branch:** `development` (post Phase 1 Group C merge).

**Tech stack:** Spring Boot 4.x, Picocli 4.7+, embedded Jetty (bundled by Spring Boot), YAML config.

**Effort:** ~2 sessions.

---

## Tasks

### Task 1D.1 — Parent pom to `spring-boot-starter-parent`

Change root `pom.xml` to inherit from `org.springframework.boot:spring-boot-starter-parent:4.0.x`. This brings in managed versions for Spring Framework 7, Jackson, embedded Jetty, Jakarta APIs, logging, and the `spring-boot-maven-plugin` for fat-JAR packaging. Delete duplicate `<properties>` for versions Spring Boot already manages.

### Task 1D.2 — New `saiku-server` module (Spring Boot app)

Recreate `saiku-server/` as a module:
- `SaikuApplication.java` with `@SpringBootApplication` + `public static void main`
- `@ImportResource("classpath:saiku-beans.xml")` to keep existing bean wiring (or migrate beans to `@Configuration` classes incrementally)
- Dep on `spring-boot-starter-web` (replaces the old `saiku-webapp` WAR assembly)
- Servlet config: merge `saiku-webapp/src/main/webapp/WEB-INF/` contents into `saiku-server/src/main/resources/` (Spring Boot's embedded Jetty serves from classpath by default)

### Task 1D.3 — `application.yml`

Replace `saiku-beans.properties` + environment variables with a single `application.yml` at `saiku-server/src/main/resources/application.yml`. Document every property. Spring Boot's `@ConfigurationProperties` or `${saiku.*}` placeholders reference it.

Example shape:
```yaml
saiku:
  server:
    port: 8080
  repository:
    data-dir: ./saiku-data
  foodmart:
    url: jdbc:h2:./data/foodmart;MODE=MySQL
    schema: ./data/FoodMart4.xml
```

### Task 1D.4 — Picocli CLI

Add Picocli as dep. Wire a top-level `saiku` command with subcommands:
- `saiku serve [--port N] [--data <dir>]` — delegates to `SpringApplication.run(SaikuApplication.class, args)`
- `saiku version` — prints Maven-filtered build info
- `saiku config validate [path]` — parses the YAML and reports errors

Set `Main-Class` in the fat JAR manifest to the Picocli entry point, not the Spring Boot one.

### Task 1D.5 — Drop WAR packaging

Delete the `saiku-webapp` module entirely (its contents merged into `saiku-server` in 1D.2). Update root pom `<modules>`. Delete `web.xml`, `saiku-beans.xml` (or move to `saiku-server/src/main/resources/`), all `WEB-INF/` artefacts.

### Task 1D.6 — Fix runtime file paths

Paths like `../../data/foodmart_h2.sql` (relative to servlet container CWD) become unreliable in a fat JAR. Resolve from the JAR location or an explicit `saiku.data.dir` config:
- Data init scripts: read from classpath via `@Value("classpath:data/foodmart_h2.sql")` or `ResourceLoader`
- User datadir: `saiku.repository.data-dir` defaults to `./saiku-data`, created on first launch

### Task 1D.7 — Fat JAR + smoke test

`mvn -pl saiku-server package` should produce `saiku-server/target/saiku-server-*.jar`. Test: `java -jar saiku-server-*.jar serve --port 18080` and hit `GET /saiku/rest/saiku/api/info/release`.

---

## Exit criteria

- `java -jar saiku-server-*.jar` starts in < 5 seconds and serves `/saiku/rest/saiku/api/info/release` with HTTP 200
- `saiku version` prints a Maven-filtered version string
- `saiku config validate` exits 0 on the default `application.yml`
- `saiku-webapp` module deleted; no more `web.xml`
- CI builds and publishes the fat JAR on tag
- Phase 0 test suite still green

## Risk-ordered task list

| # | Risk | Reversible? | Ship independently? |
|---|------|-------------|---------------------|
| 1D.1 Parent pom | Med (version cascades) | Yes | Yes |
| 1D.2 saiku-server module | Low | Yes | Yes |
| 1D.3 application.yml | Low | Yes | Yes |
| 1D.4 Picocli CLI | Low | Yes | Yes |
| 1D.5 Drop WAR | Med | Yes | Land AFTER 1D.2 is proven |
| 1D.6 Runtime paths | Low | Yes | Yes |
| 1D.7 Fat JAR smoke | Low | Yes | Last |
