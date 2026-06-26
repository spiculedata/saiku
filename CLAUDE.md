# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test, run

JDK 21 + Maven 3.9+ are required. The reactor is `saiku-bom → saiku-core → saiku-webapp → saiku-launcher`. `saiku-ui` is a separate SvelteKit app and is **not** in the Maven reactor.

```bash
mvn verify                                    # compile + unit tests + spotless:check (CI's gate)
mvn spotless:apply                            # auto-format Java (Palantir Java Format, bound in root pom)
mvn -pl saiku-core/saiku-service -am test     # one module's tests
mvn -pl saiku-core/saiku-service test -Dtest=RepositoryDatasourceManagerTest  # single test class
mvn -pl saiku-launcher -am -Dmaven.test.skip=true package    # build the runnable fat-JAR
mvn -P security verify                        # OWASP dependency-check (opt-in)
./scripts/install-hooks.sh                    # one-time: install pre-commit spotless hook
```

Run the launcher fat-JAR (Picocli + embedded Jetty 12 EE10):

```bash
java -jar saiku-launcher/target/saiku-4.0.1.jar serve --port 8080 --home ./saiku-home
# UI:    http://localhost:8080/ui/
# REST:  http://localhost:8080/saiku/api/...
# Login: admin / admin
```

For the SvelteKit UI (`saiku-ui/`, version-tracked independently as 3.17.0):

```bash
cd saiku-ui && npm install && npm run dev     # vite dev server
npm run check                                 # svelte-check + tsc
npm test                                      # vitest
npm run lint                                  # ESLint flat config (token-only rule)
npm run storybook                             # Storybook 10.4 design-system catalogue
npm run build                                 # static build → saiku-ui/dist
```

The UI is **Tailwind v4 + design-system primitives + Storybook**. Token bridge at `src/lib/styles/tailwind.css` uses `@theme inline` to map the 169 saiku-ui tokens (in `src/lib/styles/tokens.css`) onto Tailwind's namespace; primitives live at `src/lib/components/ui/` (shadcn-style with `tailwind-variants` + `bits-ui` Tooltip) and reusable widgets at `src/lib/design-system/` (14 primitives + 19 stories). ESLint bans raw tone classes (`bg-emerald-*`, `text-red-*`, `bg-amber-*`, `rose`, `orange`) outside `src/lib/design-system/` — token utilities only.

**Cascade-layer discipline (load-bearing):** every type-selector rule in `app.css` MUST live in `@layer base` so Tailwind utilities (in `@layer utilities`) can override them. Unlayered rules win against ALL layered rules regardless of specificity — that's the CSS cascade-layers spec, and the saiku-ui legacy `a { color: var(--accent) }` ate `text-primary-foreground` on every anchor-as-button until the wrap landed. Only the `*, *::before, *::after { box-sizing }` reset and the `html, body { height/margin }` root zeroing stay unlayered.

Live AI Query API regression suite against a running launcher: `saiku-launcher/test-ai-live.sh` (expects `./run.sh` already started; auth defaults `admin/admin`).

## GitHub Packages auth (non-obvious gotcha)

Saiku depends on four Spicule-published artifacts hosted on **GitHub Packages, which requires authentication for downloads even when public**. The root `pom.xml` registers four repos (`github-mondrian-saiku`, `github-olap4j`, `github-olap4j-xmlaserver`, `github-saiku-query`); each needs a matching `<server>` entry in `~/.m2/settings.xml` with a GitHub username + PAT (scope `read:packages`). One PAT covers all four. CI writes this `settings.xml` inline in `.github/workflows/ci.yml` using `secrets.GH_PACKAGES_TOKEN` — the auto-issued `GITHUB_TOKEN` will NOT work cross-org. Local forks of `mondrian-saiku`, `olap4j`, `olap4j-xmlaserver`, `saiku-query` installed in `~/.m2` will be preferred over the remote.

`lib/repo/` is an in-tree file-based Maven repo for vendored deps that aren't reachable on any public remote (currently `miredot-annotations`, Bintray-only). Use `${maven.multiModuleProjectDirectory}` (not `${project.basedir}`) when adding new entries so child modules inherit a URL pointing at the reactor root.

## Architecture

**Multi-module Maven reactor** (all artifacts `org.saikuanalytics:*:4.0.1`):

- `saiku-bom` — central dependency catalogue. Versions live here (Spring 6.2.x, Spring Security 6.5.x, slf4j 2.0.16, log4j 2.24.3, Jackson 2.18.6, Jersey 3.1.9, Arrow 18, Caffeine 3.1.8, mondrian fork 4.8.1.x). Child poms reference `${...}` and must `<import>` the BOM via `dependencyManagement`. The root pom shadows the same versions defensively so a child that forgets to import the BOM doesn't silently inherit stale numbers — keep them in sync.
- `saiku-core/saiku-olap-util` — olap4j helpers.
- `saiku-core/saiku-service` — business logic: OLAP queries (`OlapQueryService`, `ThinQueryService`), schema discovery, drill-through, async, totals, AI plan/translate (`olap/ai/`), schema generation (`schema/generate/`), datasource/session/user/cache services.
- `saiku-core/saiku-semantic` — YAML semantic layer (Phase 3 work).
- `saiku-core/saiku-web` — JAX-RS (Jersey 3.1) REST resources under `org.saiku.web.rest.resources.*` (`AiQueryResource`, `Query2Resource`, `OlapDiscoverResource`, `BasicRepositoryResource2`, `DataSourceResource`, `ExporterResource`, `AdminResource`, `InfoResource`, `SessionResource`, `StatisticsResource`, `schemagen/`). REST mount is `/saiku/api/*`. Co-located markdown (`AiQueryPlan.md`) documents agent-facing contracts alongside the code.
- `saiku-webapp` — packages the Servlet web app (`packaging=war`). Spring 6 wiring lives in `src/main/webapp/WEB-INF/applicationContext*.xml` + `spring-rest.xml` + `spring-servlet.xml`. Auth is Spring Security 6.5 (memory-backed for the embedded build via `applicationContext-spring-security-memory.xml`; `users.properties` seeds admin/admin).
- `saiku-launcher` — runnable distribution. Picocli CLI (`SaikuLauncher.ServeCommand`) + embedded **Jetty 12 EE10** WebApp serving the saiku-webapp WAR. Shade-plugin produces `saiku-<version>.jar` containing the WAR under `webapp/saiku.war`, the H2 FoodMart seed SQL, and the Mondrian schema template `seed/foodmart.sds.template`. On first run it materialises `saiku-home/` (data, repository, sessions, logs, plugins) next to the JAR. Sessions persist via Jetty `FileSessionDataStore`.

`saiku-ui` (SvelteKit 2 + Svelte 5 + Vite 6 + monaco + ECharts + apache-arrow) is built independently and served as static assets at `/ui/` — the launcher serves the Maven-built `dist/` from inside the JAR.

**Mondrian backend chain** (see `docs/mondrian-fork.md`): Saiku uses the Spicule fork `pentaho:mondrian:4.8.1.x` which ships a Calcite-based SQL planner alongside the original SqlQuery builder. **Calcite is the default**; force legacy with `-Dmondrian.backend=legacy`. Native Calcite dialect mappings live in `mondrian.calcite.CalciteDialectMap` (H2, HSQLDB, MSSQL, MySQL/MariaDB, Oracle, PostgreSQL); unknown DBs fall through to Calcite's `SqlDialectFactoryImpl` and on failure to legacy with a one-shot WARN. Un-excluding Calcite from `saiku-webapp/pom.xml` pulls in `calcite-core:1.41`, `avatica-core:1.27`, `guava:33.4.8-jre` (pinned — Calcite needs a newer API than Mondrian's historical 18.0), `protobuf-java:3.25.8` (pinned — beats the 2.4.1 transitive from `serenity-bdd → operadriver`).

**AI Query API** (`/saiku/api/ai/*`, see `docs/AI-QUERY-API.md`): typed REST surface so agents query cubes **without ever seeing MDX**. Three core endpoints: `GET /ai/cubes`, `GET /ai/schema/{connection/catalog/schema/cube}` (self-describing — measures, dims, sample members with unique names, JSON Schema, example bodies), `POST /ai/query` (records or matrix output, typed `{value, formatted, unit}` cells). Server validates names against the live cube and returns `VALIDATION_ERROR` 400s with `field` + `available` candidate lists for self-correction. Phase 3 enrichment overlays display-name renames from `<datasource>.generated.json` and accepts either canonical or display names in any field.

**Observability** (see `docs/observability.md`): opt-in OpenTelemetry via the OTel Java agent (`io.opentelemetry.javaagent:opentelemetry-javaagent`), vendored into the Docker image at `/opt/saiku/otel/opentelemetry-javaagent.jar`. Activated only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set — the entrypoint (`docker/saiku-entrypoint`) and dist wrappers (`dist/run.sh`, `dist/run.bat`) conditionally append `-javaagent` based on the env var. With the var unset, the agent is never loaded (zero overhead). Auto-instruments Jetty, Jersey, JDBC (every Mondrian SQL emission), `java.net.http.HttpClient`, Log4j 2 (trace_id/span_id in MDC), JVM metrics, DBCP2 pool stats. `log4j2.xml` PatternLayout uses `%notEmpty{[trace_id=%X{trace_id} span_id=%X{span_id}] }` so trace brackets vanish cleanly when the agent isn't attached. Custom spans (Tier 2 — `ThinQueryService.execute`, AI Query metrics, cellset cache) are out of scope until Tier 1 produces data telling us which spans matter.

## Testing baseline

`TESTING.md` is the source of truth. As of Phase 0: **10 unit tests** total, all in `saiku-core/saiku-service` (`NoReHashPasswordEncoderTest`, `RepositoryDatasourceManagerTest`). No live MDX/FoodMart harness in the suite — `MondrianFoodMartHarnessTest` was drafted but the bundled HSQL dump + `FoodMart4.xml` do not line up under the current Mondrian fork. `saiku-core/saiku-web` (REST layer), `saiku-webapp`, and `saiku-ui` currently have zero coverage. CI (`.github/workflows/ci.yml`) runs `mvn verify` on Linux + macOS against JDK 21; the `dist` job uploads `saiku-dist-<version>.zip` (launcher fat-JAR + `dist/run.sh` + `dist/run.bat` + `dist/README.md`) as an artifact.

## Conventions specific to this repo

- **Java format**: Palantir Java Format via Spotless. `mvn spotless:apply` before committing or the pre-commit hook will reject. The check is bound to the `verify` phase — `mvn spotless:check` standalone fails on `saiku-bom` (pom-only, no spotless binding).
- **Spring XML wiring, not JavaConfig**: the webapp wires beans through `applicationContext-*.xml` files. New beans go there, not via `@Configuration`.
- **JAX-RS over Spring MVC**: REST resources are Jersey 3.1 (`jakarta.ws.rs.*`), not Spring `@RestController`. The mount point is configured in `spring-rest.xml`.
- **Reactor compiler pinned to JDK 6 source in root pom** but `saiku-launcher` overrides to `<release>21</release>`. Don't "fix" the root pom — modules that need 21 set it locally.
- **Commit message format**: `#<issue> - <description>` per `CONTRIBUTING.md`. Default branch is `development`; PRs go there, not `master`. The repo uses `jgitflow` (`mvn jgitflow:feature-start`, `release-start`, `hotfix-start`).
- **Branching strategy: Gitflow.** All new work goes on a `feature/<name>` branch off `development`. Hotfixes branch off `main` as `hotfix/<name>` and merge back to both `main` and `development`. Release prep happens on `release/<version>` branches off `development`, which then merge to `main` (tagged) and back to `development`. Never push directly to `main` or `development` — always PR. Chores and refactors that don't fit `feature/` use `chore/<name>`. Stick to Gitflow conventions; the `mvn jgitflow:*` commands enforce them automatically when used.
- **Planning docs**: `docs/plans/` holds the Phase 1–7 modernisation plans (build hygiene, FS repo, YAML semantic layer, SvelteKit port, Arrow IPC / async cache, full Svelte rewrite, platform features). The Phase 0 dependency audit is at `docs/phase-0/dependency-audit.md`. Read these before invasive changes.
- **Svelte 5 effect discipline**: never call a state-writing helper synchronously from inside `$effect` if the helper also reads the same `$state`. The effect's dep-tracking captures the read, the write inside the same tick re-queues the effect, and Svelte bails after 128 iterations with `effect_update_depth_exceeded` — visible to the user as a stuck UI state AND every event handler in the component going inert (the whole reactivity graph tears down). Defer with `queueMicrotask(() => fn(...))` or wrap in `untrack(() => ...)` from `svelte`. Diagnostic signal: if multiple buttons in one modal/panel stop working at once, check the browser console for that error code before debugging the buttons individually.
- **Mondrian 4 virtual cubes**: when adding a MeasureGroup to a virtual cube, declare `<NoLink dimension="X"/>` for every dim that doesn't apply — Mondrian doesn't infer it, the cube fails to load with "No link for dimension X in measure group Y". When changing the canonical FoodMart schema, mirror the change between `saiku-home/data/FoodMart4.xml` (runtime) AND `saiku-launcher/src/main/resources/seed/FoodMart4.xml` (launcher bundle source).

Wiki subfolder: saiku
Wiki consult: before architectural, library, or tooling decisions, check pages/decisions/ in this project's wiki via the llmwiki skill. Capture new decisions with /wiki-note.
