# Saiku Modernisation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Modernise the Saiku open-source BI platform in eight phases, shipping a user-visible improvement at the end of each phase, ending with a single-binary distribution, git-friendly filesystem state, YAML semantic layer, Arrow query pipeline, and Svelte UI.

**Architecture:** Incremental modernisation, engine-up. Phase 0 installs a safety net (tests + CI) so later refactors are reviewable. Phases 1–3 modernise the Java backend and configuration. Phases 4–6 modernise the frontend in a bridge-then-rewrite pattern. Phases 7–8 are ongoing menus of independent features.

**Tech Stack:** JDK 21, Spring Boot 4.0, Spring Framework 7, Maven, Jetty (embedded), Picocli, Jackson, Apache Arrow, AG Grid Community, Monaco, ECharts, SvelteKit, Vite, TypeScript, Playwright, Testcontainers, Spotless.

**Companion document:** The full rationale, trade-offs, and "deliberately not doing" lists are in `docs/plans/2026-04-18-saiku-modernisation-design.md`. This plan is execution-oriented and should be read alongside that design.

**Plan structure:** Phase 0 is specified at bite-sized (2–5 min) task granularity, ready to execute immediately. Phases 1–8 are specified at task-list granularity because their concrete steps depend on what Phase 0 reveals. Each later phase will be expanded into its own bite-sized plan (`docs/plans/YYYY-MM-DD-phase-N-<topic>.md`) before execution begins.

**Repository facts as of plan creation (2026-04-18):**
- Top-level Maven modules: `saiku-core`, `saiku-ui`, `saiku-webapp`, `saiku-server`. `saiku-bi-platform-plugin-p7.1` is on disk but already commented out of the reactor.
- `saiku-core` sub-modules: `saiku-olap-util`, `saiku-service`, `saiku-web`.
- Maven compiler source/target: **1.8** (plus a legacy 1.6 plugin config fragment to clean up).
- **No GitHub Actions workflows exist.** Only issue/PR templates in `.github/`.
- **No Spotless / formatter plugin configured.**
- `saiku-core` has only 2 files matching `*Test.java`/`*IT.java` — test coverage is effectively zero.

---

## Phase 0 — Safety net (2 weeks, bite-sized)

### Task 0.1 — Create a Phase 0 worktree

**Files:**
- N/A (git operation)

**Step 1: Create the worktree**

Run: `git worktree add ../saiku-phase-0 -b phase-0-safety-net`
Expected: `Preparing worktree (new branch 'phase-0-safety-net')` and a new directory at `../saiku-phase-0`.

**Step 2: Switch to the worktree for all subsequent tasks**

Run: `cd ../saiku-phase-0`
Expected: `pwd` returns the new path.

**Step 3: Sanity build on the current codebase**

Run: `mvn -pl saiku-core/saiku-olap-util,saiku-core/saiku-service -am -DskipTests clean install`
Expected: BUILD SUCCESS. Captures the pre-change baseline.

---

### Task 0.2 — Bump Maven compiler to JDK 21

**Files:**
- Modify: `pom.xml` (top-level `<maven.compiler.source>` / `<maven.compiler.target>`; remove the stray 1.6 plugin config)
- Modify: any child `pom.xml` that overrides source/target (grep first).

**Step 1: Grep for every compiler version declaration**

Run: `grep -rnE "maven.compiler|<source>|<target>" --include='pom.xml'`
Record every hit.

**Step 2: Change every `1.8` and `1.6` source/target to `21`**

Use Edit on each file individually. Never `replace_all` across the repo without eyeballing.

**Step 3: Verify Maven detects JDK 21**

Run: `mvn -v`
Expected: `Java version: 21...`. If not, install JDK 21 via SDKMAN: `sdk install java 21-tem && sdk use java 21-tem`.

**Step 4: Attempt the build**

Run: `mvn clean install -DskipTests -fae`
Expected: may fail with deprecation / removal errors. Record each failure.

**Step 5: Fix compilation failures module-by-module**

For each failure: read the code, make the minimal change (e.g. replace removed `com.sun.*` APIs, add `--add-opens` where absolutely necessary, update library versions when a library is JDK21-incompatible). Commit each module's fixes separately.

**Step 6: Build cleanly**

Run: `mvn clean install -DskipTests`
Expected: BUILD SUCCESS.

**Step 7: Commit**

```bash
git add pom.xml saiku-core/**/pom.xml saiku-webapp/pom.xml saiku-server/pom.xml
git commit -m "build: target JDK 21 across all modules"
```

---

### Task 0.3 — Add a Spotless formatter with pre-commit hook

**Files:**
- Modify: `pom.xml` (root `<build>` and `<pluginManagement>`)
- Create: `.git/hooks/pre-commit` (via a tracked installer script)
- Create: `scripts/install-hooks.sh`

**Step 1: Add the Spotless plugin to root `pom.xml`**

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.46.1</version>
  <configuration>
    <java>
      <palantirJavaFormat/>
      <removeUnusedImports/>
      <trimTrailingWhitespace/>
      <endWithNewline/>
    </java>
  </configuration>
  <executions>
    <execution>
      <goals><goal>check</goal></goals>
    </execution>
  </executions>
</plugin>
```

**Step 2: Apply once to establish the baseline**

Run: `mvn spotless:apply`
Expected: many files reformatted. This is a one-time churn.

**Step 3: Commit the reformat as its own commit**

```bash
git add -A
git commit -m "style: apply Spotless/palantir-java-format baseline"
```

**Step 4: Verify `spotless:check` is clean**

Run: `mvn spotless:check`
Expected: `Spotless check passed`.

**Step 5: Create the hook installer**

`scripts/install-hooks.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cat > "$ROOT/.git/hooks/pre-commit" <<'EOF'
#!/usr/bin/env bash
exec mvn -q spotless:check
EOF
chmod +x "$ROOT/.git/hooks/pre-commit"
echo "pre-commit hook installed"
```

**Step 6: Install and commit**

Run: `chmod +x scripts/install-hooks.sh && ./scripts/install-hooks.sh`

```bash
git add pom.xml scripts/install-hooks.sh
git commit -m "build: add Spotless + pre-commit hook"
```

---

### Task 0.4 — Build a Testcontainers harness for Mondrian + FoodMart

**Files:**
- Create: `saiku-core/saiku-service/src/test/java/org/saiku/test/MondrianFoodMartContainer.java`
- Create: `saiku-core/saiku-service/src/test/resources/FoodMart.xml` (the standard Mondrian demo schema — download from mondrian-olap/mondrian GitHub)
- Modify: `saiku-core/saiku-service/pom.xml` (add `testcontainers`, `junit-jupiter`, `postgresql` test-scoped deps)

**Step 1: Write the failing test first**

`saiku-core/saiku-service/src/test/java/org/saiku/test/FoodMartHarnessTest.java`:
```java
package org.saiku.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FoodMartHarnessTest {
  @Test
  void canConnectAndQueryUnitSales() throws Exception {
    try (MondrianFoodMartContainer c = new MondrianFoodMartContainer()) {
      c.start();
      String mdx = "SELECT [Measures].[Unit Sales] ON 0 FROM [Sales]";
      double result = c.executeScalar(mdx);
      assertThat(result).isGreaterThan(0.0);
    }
  }
}
```

**Step 2: Run it — it MUST fail**

Run: `mvn -pl saiku-core/saiku-service test -Dtest=FoodMartHarnessTest`
Expected: compilation failure (class `MondrianFoodMartContainer` not defined).

**Step 3: Implement `MondrianFoodMartContainer`**

Minimal implementation: starts a `PostgreSQLContainer`, loads FoodMart DDL + data (use the published `mondrian-data-foodmart-json` or SQL dumps), opens an olap4j connection pointed at an embedded Mondrian schema XML that references the FoodMart tables. Exposes `executeScalar(String mdx)`.

**Step 4: Run the test — it MUST pass**

Run: `mvn -pl saiku-core/saiku-service test -Dtest=FoodMartHarnessTest`
Expected: PASS, total time <60s (warm), <180s (cold container pull).

**Step 5: Commit**

```bash
git add saiku-core/saiku-service/pom.xml saiku-core/saiku-service/src/test/**
git commit -m "test: add Testcontainers Mondrian FoodMart harness"
```

---

### Task 0.5 — Wire a baseline integration test for `OlapDiscoverService`

**Files:**
- Create: `saiku-core/saiku-service/src/test/java/org/saiku/service/olap/OlapDiscoverServiceIT.java`

**Step 1: Write the failing test**

Asserts: given a connection to the FoodMart harness, `getAllConnections()` returns a tree containing `Sales` cube with `Measures.[Unit Sales]`.

**Step 2: Run it — must fail (assertions not met, or wiring incomplete).**

**Step 3: Wire the service against the harness.**

Use the real `OlapDiscoverService` and `OlapMetaExplorer`. No mocks. If wiring exposes a coupling problem, document it in `TESTING.md` — don't fix it here.

**Step 4: Run — must pass.**

**Step 5: Commit.**

```bash
git commit -m "test: integration test for OlapDiscoverService against FoodMart"
```

---

### Task 0.6 — Write `TESTING.md` inventory

**Files:**
- Create: `TESTING.md`

**Contents:**
- What the harness covers
- What it doesn't (UI, web resources, auth, query execution path under load)
- Known flakes or quarantined tests (empty for now)
- How to run: `mvn verify` locally; Docker required for Testcontainers.

**Step 1: Write the file.**

**Step 2: Commit.**

```bash
git commit -m "docs: add TESTING.md inventory"
```

---

### Task 0.7 — GitHub Actions CI matrix

**Files:**
- Create: `.github/workflows/ci.yml`

**Step 1: Write the workflow**

```yaml
name: ci
on:
  push:
    branches: [development]
  pull_request:
jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    concurrency:
      group: ci-${{ github.ref }}-${{ matrix.os }}
      cancel-in-progress: true
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Verify
        run: mvn -B -ntp verify
      - name: Spotless check
        run: mvn -B -ntp spotless:check
```

**Step 2: Commit, push the branch, open a draft PR to watch CI run.**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: GitHub Actions matrix build on JDK 21"
git push -u origin phase-0-safety-net
gh pr create --draft --title "Phase 0: safety net" --body "See docs/plans/2026-04-18-saiku-modernisation-implementation-plan.md"
```

**Step 3: Watch the first run; fix whatever breaks on Linux/macOS that passed locally.**

---

### Task 0.8 — Playwright UI smoke test

**Files:**
- Create: `saiku-ui/tests/smoke.spec.ts`
- Create: `saiku-ui/playwright.config.ts`
- Modify: `.github/workflows/ci.yml` (add a `ui-smoke` job)

**Step 1: Write the failing test**

```ts
import { test, expect } from '@playwright/test';

test('login, open a cube, drag a dim, see data', async ({ page }) => {
  await page.goto('http://localhost:8080');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('admin');
  await page.getByRole('button', { name: 'Login' }).click();
  await expect(page.getByText('Sales')).toBeVisible();
  // ... drag-and-drop asserted via a data-test hook to be added in the UI
});
```

**Step 2: Run locally against a running Saiku (`mvn -pl saiku-server jetty:run` in one terminal, `npx playwright test` in another). Fix selectors until green.**

**Step 3: Add a `ui-smoke` job to `ci.yml`** that starts Saiku in the background, waits for the port, runs Playwright, uploads traces on failure.

**Step 4: Commit.**

```bash
git commit -m "test: Playwright UI smoke (login → open cube → drag)"
```

---

### Task 0.9 — Dependency audit and Dependabot

**Files:**
- Create: `.github/dependabot.yml`
- Create: `docs/phase-0/dependency-audit.md`

**Step 1: Run `mvn dependency:tree -Dverbose > /tmp/deps.txt` and commit a summary to the audit doc.** Highlight:
- Jackrabbit and its transitives (count them for Phase 2 satisfaction).
- Any `javax.*` dependencies that will need migration in Phase 1.
- Any library pinned to a version unsupported on JDK 21.

**Step 2: Enable Dependabot**

```yaml
version: 2
updates:
  - package-ecosystem: maven
    directory: /
    schedule: { interval: weekly }
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule: { interval: weekly }
```

**Step 3: Commit.**

```bash
git commit -m "build: dependency audit + Dependabot config"
```

---

### Task 0.10 — Exit criteria check + merge

**Step 1: Verify every Phase 0 exit criterion from the design doc:**
- [ ] `mvn verify` green on CI in <10 min.
- [ ] Playwright smoke test green.
- [ ] Dependency report committed.
- [ ] Formatter + hook installed.

**Step 2: Mark PR ready for review; merge after Tom's review.**

**Step 3: Delete the worktree.**

Run (from main repo): `git worktree remove ../saiku-phase-0`

---

## Phase 1 — Build & deploy hygiene (2–3 weeks, task-list outline)

**Pre-execution:** expand into bite-sized plan `docs/plans/YYYY-MM-DD-phase-1-build-hygiene.md`.

**Worktree:** `../saiku-phase-1` off `phase-1-build-hygiene`.

**Tasks:**

1. **Delete `saiku-bi-platform-plugin-p7.1`** directory. Uncomment Pentaho-specific code paths in remaining modules. Verify build.
2. **Maven BOM module.** New `saiku-bom` module listing every dependency version. Parent `pom.xml` inherits from the BOM. All child `<dependency>` entries drop their `<version>` tag.
3. **Spring 5/XML → Spring Boot 4.0 (big-bang).** Convert `saiku-webapp/WEB-INF/saiku-beans.xml` to `@Configuration` classes. `javax.*` → `jakarta.*` across all modules (servlet, validation, inject). Delete `web.xml` in favour of Spring Boot's embedded Jetty.
4. **Embedded Jetty + fat JAR.** `saiku-server` becomes the Spring Boot entry point producing a runnable JAR. WAR packaging removed.
5. **Picocli CLI.** `saiku serve`, `saiku version`, `saiku config validate`. Wire `serve` to the Spring Boot application.
6. **`application.yml`.** Single configuration file replaces Spring XML + `saiku.properties`.
7. **Distroless Docker image.** Multi-stage build: Maven stage → distroless runtime. Healthcheck hits `/actuator/health`.
8. **GitHub release automation.** On tag, CI publishes the fat JAR and Docker image.
9. **(Optional)** Homebrew tap repo `saiku/homebrew-saiku`.

**Exit criteria:**
- `java -jar saiku-server-*.jar` starts in <5s.
- `docker run saiku/saiku` works end-to-end.
- Single `application.yml` replaces the XML tangle.
- CI publishes JAR + image on tags.
- Pentaho plugin code gone from the tree.

---

## Phase 2 — Repo & config: kill Jackrabbit (2 weeks, task-list outline)

**Worktree:** `../saiku-phase-2`.

**Tasks:**

1. **Define the filesystem layout schema** in `docs/phase-2/layout.md`. Document every file type and its directory.
2. **Implement `FilesystemDatasourceManager implements IDatasourceManager`** (see `saiku-core/saiku-service/src/main/java/org/saiku/service/datasource/IDatasourceManager.java`). Reads/writes YAML for datasources, XML for schemas (YAML comes in Phase 3), JSON for saved queries.
3. **Wire the new manager behind a config flag** (`saiku.repository.type: filesystem | jcr`). Both work side-by-side during transition.
4. **`saiku migrate jcr-to-fs`** CLI command. Dumps an existing JCR repository into the filesystem layout.
5. **Simple ACL: `roles.yml`.** Role → path-prefix permissions. Replace `SecurityAwareConnectionManager` JCR dependence.
6. **Optional git-sync.** When `saiku.repository.git.enabled=true` and the data dir is a git repo, every write becomes a commit authored by the logged-in user.
7. **Remove Jackrabbit dependencies.** Delete the JCR repository bean, all related code paths. Measure JAR size before/after.
8. **Migration integration test.** Testcontainers-style test that runs the CLI migration on a sample JCR repo and verifies the filesystem output.

**Exit criteria:**
- Fresh install creates `saiku-data/` with sample content; end-to-end works.
- Migration tool converts a real JCR repo with zero data loss.
- JAR size drops by ~30–40% (measured).
- Jackrabbit artefacts absent from `mvn dependency:tree`.

---

## Phase 3 — YAML semantic layer (3–4 weeks, task-list outline)

**Worktree:** `../saiku-phase-3`.

**Tasks:**

1. **Internal IR.** Java records for `CubeDefinition`, `Dimension`, `Level`, `Measure`, `Join`, `Security`. Immutable, builder-friendly.
2. **YAML schema + Jackson bindings.** Publish the JSON Schema at `/schemas/cube-v1.json` for IDE autocompletion.
3. **Parser with line-accurate error messages.** Test each validation rule (unknown dimension reference, duplicate measure names, etc.) with golden error outputs.
4. **Compiler: IR → Mondrian XML.** Deterministic. Golden-file tests: for each input `*.yml`, the produced `*.xml` must match.
5. **`saiku lint cubes/`.** Connects to the JDBC source named in the cube, verifies tables and columns referenced in the YAML actually exist. Fails with a table of unknown references.
6. **`saiku infer --source --table`.** JDBC introspection emits starter YAML. Numeric columns default to measures (`sum`), foreign-key-looking columns default to dimensions.
7. **`saiku convert mondrian-to-yaml`.** Parses an existing Mondrian schema and emits YAML. Round-trip test: `convert` → `compile` → diff against original must be semantically equivalent (compare parsed XML trees, not text).
8. **Hot reload.** File watcher on `cubes/` directory rebuilds affected schemas in-process within 2s.
9. **Importers.** `saiku import dbt <path>` consumes `semantic_models` and metrics. `saiku import cube <path>` for Cube.js.
10. **Docs cookbook** covering slowly-changing dimensions, degenerate dims, virtual cubes, role-playing dims, security predicates.
11. **Reference adapter: DuckDB.** Working example schema + `saiku infer` against a DuckDB file. Lives in an `examples/` directory.

**Exit criteria:**
- A new user goes from "Postgres DB" to "working cube in UI" in <10 min via `infer` + one edit.
- `saiku lint` catches every common schema error at edit time.
- Golden-file suite covers YAML → Mondrian XML.
- 3-minute demo video recorded.

---

## Phase 4 — Frontend foundation (3–4 weeks, task-list outline)

**Worktree:** `../saiku-phase-4`.

**Tasks:**

1. **Vite + TypeScript build** in `saiku-ui`. Backbone keeps working; TS is opt-in per file.
2. **Replace the pivot grid with AG Grid Community.** Drop-in in the existing workspace screen. Preserve current drag-drop behaviour on the axis wells.
3. **Monaco editor for MDX and SQL.** Custom language service backed by `/rest/saiku/api/discover` for autocomplete on `[Dimension].[Level]`.
4. **Migrate charts Highcharts → ECharts.** One chart type at a time with visual-regression Playwright tests.
5. **Design tokens** in `tokens.css` (colours, spacing, typography). Introduce dark-mode CSS variables.
6. **Accessibility pass** on touched components: keyboard nav on the grid, ARIA on drag-drop, focus management in dialogs.
7. **Playwright coverage grows.** Every screen we touch gets a smoke test before changes land.

**Exit criteria:**
- Vite HMR <200ms.
- Grid renders 100k rows without lag.
- MDX autocomplete working against live metadata.
- Highcharts fully gone.
- Dark mode shipped.

---

## Phase 5 — Query pipeline (3 weeks, task-list outline)

**Worktree:** `../saiku-phase-5`.

**Tasks:**

1. **Arrow IPC server-side.** Implement a `CellSetFormatter` variant that emits Arrow record batches over HTTP chunked responses.
2. **Arrow client-side.** `apache-arrow` JS bundled into the UI. AG Grid row model fed directly from Arrow vectors.
3. **Async job API.** `POST /queries` returns `{jobId}`. `GET /queries/{jobId}/stream` = SSE with `{status, batches}`. `DELETE /queries/{jobId}` calls `olap4j.Statement.cancel()`.
4. **Virtual-thread executor** (`Executors.newVirtualThreadPerTaskExecutor()`). One thread per in-flight query; no tuning.
5. **Caffeine cache.** Key = `(schemaVersion, mdx, userRole)`. Wired into `ThinQueryService`. Metrics exposed at `/actuator/metrics/saiku.query.cache`.
6. **Query profile drawer** in the UI: generated SQL (from Mondrian's SQL log), parse/plan/SQL/format timings, cache hit/miss.
7. **Cancel button** wired end-to-end with a Playwright test that verifies server-side connection release.
8. **Back-pressure.** Configurable max cells per request; UI offers a Parquet export above the cap.
9. **Parquet export.** `/queries/{jobId}/export?format=parquet` — Arrow → Parquet writer.

**Exit criteria:**
- 1M-cell result streams incrementally into the grid.
- Cancel kills upstream query within 1s.
- Profile drawer on every query.
- Cache hit rate in actuator metrics.
- Playwright: issue → cancel → verify release.

---

## Phase 6 — Frontend rewrite to Svelte (4–6 weeks, task-list outline)

**Worktree:** `../saiku-phase-6`.

**Tasks:**

1. **SvelteKit scaffold** served alongside Backbone. `/ui/` = new, `/legacy/` = old.
2. **OpenAPI spec finalisation** for the Saiku REST API. TypeScript client generated from it (`openapi-typescript`) used by both UIs.
3. **Design system `@saiku/ui`** — Svelte components on Phase 4 tokens. Storybook.
4. **Screen migration in risk order:**
   1. Login + home.
   2. Datasource admin.
   3. Query workspace (vertical slices: basic → calculated members → filters → drill-through).
   4. Dashboards.
   5. Admin / users.
5. **`svelte-dnd-action`** for drag-and-drop. Keyboard + screen-reader by default.
6. **Svelte stores** as the single source of state. Centralise query-workspace state → enables undo/redo.
7. **Mobile responsive read-only** for dashboards.
8. **Feature flag** `saiku serve --ui=next`. Public pre-release from week 2.
9. **Delete `saiku-ui`** once parity reached. Huge net-negative diff.

**Exit criteria:**
- Every old-UI screen has a Svelte equivalent.
- Playwright suite passes with equivalent coverage.
- Lighthouse: >90 perf, >95 a11y.
- Initial-route bundle <200KB gzipped.
- Backbone module deleted.

---

## Phase 7 — Platform features (ongoing menu)

Picked by demand. Each gets its own bite-sized plan before execution. Candidate items:

- Query notebooks (`.saiku.md`).
- Dashboards 2.0 (grid layout, parameters, cross-filter, drill-through).
- Embeddable iframes with signed URLs.
- Comments & mentions (filesystem-backed).
- Git version-history UI.
- Scheduled exports (CSV / Parquet / PDF → email / Slack / S3).
- Alerts on measure thresholds.
- Printable PDF dashboards.
- OIDC / SAML.
- Row-level security hooks wired to YAML `security`.
- Structured JSON audit log.
- First-class multi-tenant.
- AI (opt-in): NL → MDX, auto-explain, schema summarisation, chart suggestion.

Each item's plan lists files to touch, tests to write, and exit criteria. No item is required.

---

## Phase 8 — Ecosystem (ongoing menu)

Also picked by demand. Candidates:

- Apache Calcite evaluation spike; long-horizon Mondrian-replacement investigation.
- MDX-to-SQL transpilers for Snowflake / BigQuery / ClickHouse.
- Generic JDBC + YAML model (no Mondrian).
- Arrow Flight SQL adapter.
- Iceberg REST adapter.
- Cube / Malloy / dbt adapters.
- Fresh docs site (Docusaurus or Astro).
- Examples repo.
- Contributor guide, good-first-issues, monthly community call.
- Plugin SPI stabilisation + registry.
- Browser WASM demo.
- Semantic-release + Sigstore signing.
- LTS branch once stable.

---

## Conventions across all phases

- **One worktree per phase**, branch `phase-N-<slug>`, merged to `development` on completion.
- **Commits are small and frequent.** Each bite-sized task ends in a commit. Never amend a pushed commit.
- **Tests precede implementation** wherever a behaviour can be expressed in a test (superpowers:test-driven-development).
- **Before claiming "done"**, run `mvn verify` + Playwright locally and check the output (superpowers:verification-before-completion).
- **Code review after each phase** via `superpowers:requesting-code-review` before merge.
- **Later phases expand into their own bite-sized plans** before execution. This document stays the map, not the territory.
