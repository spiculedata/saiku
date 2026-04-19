# Saiku Modernisation Design

**Date:** 2026-04-18
**Status:** Accepted (brainstorm complete; ready for implementation planning per phase)
**Owner:** Tom Barber, with Claude as primary implementer

## Context

Saiku is a long-running open-source BI tool built on Mondrian/XMLA, with a Backbone/jQuery frontend, Spring XML configuration, and a Jackrabbit JCR repository. The codebase works but shows its age: 2012-era dependencies, verbose XML everywhere, and UX that no longer meets modern expectations.

This document captures a staged modernisation plan that:
- Keeps Saiku open source and self-contained (no mandatory SaaS dependencies)
- Preserves Mondrian as the query engine for now, but loosens coupling for future options
- Prioritises low-lift operator and developer experience (single binary, filesystem state, git-friendly config)
- Ships a user-visible improvement at the end of every phase — no multi-month "foundation" work with nothing to show

Execution model: Tom reviews; Claude does most of the implementation. The real constraint is review bandwidth, so phases are sized for small, independently reviewable PRs, gated by a test suite Claude can run autonomously.

## Guiding principles

1. **Tests before refactors.** A green CI with meaningful coverage is non-negotiable before any invasive change.
2. **Each phase ships.** Every phase produces something a user would notice.
3. **Engine-up, not UI-down.** Modernise the pipe before rewriting the UI on top of it.
4. **YAGNI.** No features land for hypothetical future needs. Every capability must be justified by a concrete user or operator pain point.
5. **Open source first.** No required third-party SaaS. AI features are optional and pluggable.
6. **Cut dead weight aggressively.** Pentaho plugin, Jackrabbit, Highcharts, Backbone — all removed when their replacements land.

## Phase 0 — Safety net (2 weeks)

**Goal:** Green CI Claude can trust, so autonomous work between reviews is safe.

**Work:**
- JDK 21 upgrade across all modules; fix deprecations.
- GitHub Actions CI matrix (JDK 21; Linux + macOS), with caching and concurrency cancellation.
- Test inventory + `TESTING.md` documenting current coverage, gaps, and quarantined flakes.
- Testcontainers harness: reusable fixture running Mondrian against the FoodMart schema on H2 or Postgres.
- Playwright smoke test: launch server, log in, open a cube, drag a dim, assert a result.
- Dependency + vulnerability scan; Dependabot/Renovate enabled.
- Spotless code formatter with a pre-commit hook.

**Deliberately not doing:** any refactoring, dependency upgrades beyond JDK, or feature work.

**Exit criteria:**
- `mvn verify` green on CI in under 10 minutes.
- Playwright smoke test green.
- Dependency report committed.
- One command reliably verifies the core query path end-to-end.

## Phase 1 — Build and deploy hygiene (2–3 weeks)

**Goal:** Saiku ships as a single command.

**Work:**
- Spring XML → **Spring Boot 4.0** (latest stable; Spring Framework 7). Big-bang migration, protected by Phase 0's test harness.
- `javax.*` → `jakarta.*` namespace migration throughout.
- Fat JAR with embedded Jetty; drop WAR packaging.
- Picocli (or Spring Boot CLI) entry point: `saiku serve`, `saiku version`, `saiku config validate`. Further subcommands land in later phases.
- Maven BOM module for centralised dependency versions.
- Distroless Docker image, non-root, with healthcheck.
- Kill the `saiku-bi-platform-plugin-p7.1` module; promote any still-useful code or inline at call sites.
- Homebrew tap (optional, cheap win).

**Deliberately not doing:** Jackrabbit removal (Phase 2), UI work, Mondrian changes.

**Exit criteria:**
- `java -jar saiku.jar` starts the server in under 5 seconds.
- `docker run saiku/saiku` works end-to-end.
- One `application.yml` replaces the Spring XML.
- CI publishes JAR and Docker image on every tag.

## Phase 2 — Repo and config: kill Jackrabbit (2 weeks)

**Goal:** Saiku's state lives in a plain folder you can commit to git.

**Filesystem layout:**
```
saiku-data/
  datasources/    # *.yml, one per connection
  schemas/        # *.xml (Mondrian) initially; *.yml once Phase 3 lands
  queries/        # *.saiku.json saved queries
  dashboards/     # *.yml
  users/          # users.yml, roles.yml (dev mode; prod uses OIDC later)
  .meta/          # audit log, search index if needed
```

**Work:**
- `FilesystemDatasourceManager` implementation of `IDatasourceManager`, swappable via config.
- Migration tool: `saiku migrate jcr-to-fs --source old-repo/ --target saiku-data/`.
- Optional git-sync: when `saiku-data/` is inside a git repo, Saiku writes clean commits (author = logged-in user, message = action). Off by default; included in this phase.
- ACL replacement: `roles.yml` with role → path-prefix permissions.
- Delete Jackrabbit and transitive dependencies; measure JAR size drop.

**Deliberately not doing:** YAML semantic layer (Phase 3), multi-tenant redesign, admin UI changes.

**Exit criteria:**
- Fresh install creates `saiku-data/` with sample content and works end-to-end.
- Migration tool converts an existing JCR repo with zero data loss.
- JAR size drops by roughly 30–40%.
- Full test suite green, including new filesystem-manager tests.

## Phase 3 — YAML semantic layer (3–4 weeks)

**Goal:** cubes defined in ~20 lines of YAML. Mondrian XML becomes a compile target, not a user-facing artefact.

**Example:**
```yaml
cube: Sales
source:
  connection: warehouse
  table: fact_sales
dimensions:
  - name: Date
    source: date_key → dim_date.id
    levels: [Year, Quarter, Month, Day]
  - name: Product
    source: product_key → dim_product.id
    levels: [Category, Brand, Product]
measures:
  - name: Revenue
    agg: sum(amount)
    format: "$#,##0.00"
  - name: Units
    agg: sum(quantity)
  - name: AvgOrderValue
    expr: Revenue / Orders
security:
  row_filter:
    role: regional_manager
    predicate: "region in ({{user.regions}})"
```

**Work:**
- Schema model as Java records (`CubeDefinition`, `Dimension`, `Level`, `Measure`, `Join`, etc.) — the internal IR.
- YAML parser + validator (Jackson + JSON Schema) with line-accurate error messages.
- Compiler: YAML → Mondrian XML. Deterministic. Golden-file tested.
- `saiku lint cubes/` — introspects JDBC metadata to catch typos in table/column names at edit time.
- `saiku infer --source warehouse --table fact_sales` — best-effort starter YAML from JDBC metadata.
- `saiku convert mondrian-to-yaml schema.xml` — migration path for existing users.
- Hot reload: file watcher on `cubes/` — edit YAML, cube updates in UI within ~2s.
- Import tools: `saiku import dbt ./dbt-project/` for dbt `semantic_models`; equivalent for Cube.js. LookML a stretch goal.
- Docs: cookbook covering slowly-changing dims, degenerate dims, virtual cubes, role-playing dims.

**Deliberately not doing:** replacing Mondrian. This phase is a humane interface on top of it.

**Exit criteria:**
- New user goes from "here's a Postgres DB" to "working cube in the UI" in under 10 minutes using `saiku infer` + one hand-edit.
- `saiku lint` catches every common class of schema error.
- Golden-file test suite for YAML → Mondrian XML.
- Public 3-minute demo video recorded.

## Phase 4 — Frontend foundation (3–4 weeks)

**Goal:** modernise the UI's bones without a rewrite. Bridge phase before Phase 6.

**Work:**
- Vite + TypeScript build. Backbone continues running; TS adopted file-by-file.
- Pivot grid → **AG Grid Community (MIT)**. Perspective.js kept in reserve until Phase 5's Arrow transport.
- Monaco editor for MDX and SQL, with autocomplete driven by live schema metadata.
- Highcharts → **ECharts (Apache 2.0)**. Eliminates the licensing footgun.
- Design tokens in `tokens.css`; dark mode via CSS custom properties.
- Playwright suite expands as screens are touched.
- Accessibility pass on touched components: keyboard nav on grid, ARIA on drag-drop, focus management in dialogs.

**Deliberately not doing:** Svelte rewrite (Phase 6). No redesign. Screens not blocking us stay untouched.

**Exit criteria:**
- `npm run dev` HMR under 200ms.
- Grid renders 100k rows without lag.
- MDX editor autocomplete working against live metadata.
- Highcharts fully removed.
- Dark mode ships.

## Phase 5 — Query pipeline (3 weeks)

**Goal:** large results and long queries stop feeling painful.

**Work:**
- **Arrow IPC** on the wire. Server serialises into record batches; browser consumes via `apache-arrow`. First batch renders while server still producing.
- **Async query execution** via job model:
  - `POST /queries` → `{jobId}` returned immediately.
  - `GET /queries/{jobId}/stream` → SSE with status + Arrow batches.
  - `DELETE /queries/{jobId}` → cancels the underlying Mondrian statement (olap4j `Statement.cancel()`).
- Virtual threads (JDK 21) handle in-flight queries — no pool tuning.
- **Caffeine query cache.** Keys: `(schema version, MDX, user role)`. Size + TTL eviction. Metrics via Actuator.
- **Query profile drawer** in the UI: generated SQL, timing breakdown (parse / plan / SQL / format), cache hit/miss, row counts.
- **Cancel button** in the UI wired end-to-end.
- **Back-pressure:** cap cells per request; UI prompts for an export above the cap.
- **Parquet export path:** `/queries/{jobId}/export?format=parquet`. Trivial on top of Arrow.

**Deliberately not doing:** replace Mondrian (Phase 8 investigation). No MDX dialect changes.

**Exit criteria:**
- 1M-cell result streams incrementally into the grid.
- Cancel kills upstream query within 1s.
- Profile drawer shows SQL + timings on every query.
- Cache hit rate visible in `/actuator/metrics`.
- Playwright covers: issue query → cancel mid-flight → verify server released the connection.

## Phase 6 — Frontend rewrite to Svelte (4–6 weeks)

**Goal:** kill Backbone. Rewrite in SvelteKit, screen by screen, reusing Phase 4 components.

**Why Svelte:** compiler-based, tiny runtime, right-sized for a data-heavy drag-and-drop app. React is 3× the bundle and ceremony; Solid is close but has a smaller ecosystem.

**Work:**
- SvelteKit scaffold served alongside Backbone from the same JAR. `/ui/` = new; `/legacy/` = old.
- OpenAPI spec finalised this phase; TypeScript client generated from it and shared across both UIs.
- Migration order, low-risk first:
  1. Login + home.
  2. Datasource admin.
  3. Query workspace (biggest surface). Port in vertical slices: basic drag-drop first, then calculated members, filters, drill-through.
  4. Dashboards.
  5. Admin/users.
- Svelte design system (`@saiku/ui`) on Phase 4 tokens. Storybook for visual review. Dark mode baked in.
- `svelte-dnd-action` for drag-and-drop. Keyboard and screen-reader accessible for free.
- State via Svelte stores. No Redux. Centralisation enables undo/redo.
- Responsive read-only view for mobile (dashboards only; no authoring on phones).
- Pre-release behind `saiku serve --ui=next` from week 2 for early-adopter feedback.
- Delete `saiku-ui` (Backbone) once parity is reached.

**Deliberately not doing:** add new features. Strict parity rule — new features go in Phase 7.

**Exit criteria:**
- Every screen from the old UI has a Svelte equivalent.
- Playwright suite passes on the new UI with equivalent coverage.
- Lighthouse: >90 performance, >95 accessibility.
- Initial route bundle <200KB gzipped.
- `saiku-ui` module deleted.

## Phase 7 — Platform features (ongoing menu)

Independent, 1–3 weeks each. Pick by demand.

**Authoring and collaboration**
- Query notebooks (MDX + markdown + charts as `.saiku.md` files).
- Dashboards 2.0: grid layout, parameters, cross-filtering, drill-through.
- Embeddable iframes with signed URLs.
- Comments + mentions (backed by filesystem, git-blame-friendly).
- Version history UI surfacing git diffs of schemas and queries.

**Delivery**
- Scheduled exports (CSV / Parquet / PDF to email / Slack / S3 / webhook).
- Alerts on measure thresholds.
- Printable PDF dashboards.

**Auth and ops**
- OIDC / SAML out of the box; delete the homegrown auth.
- Row-level security hooks wired to the YAML `security` block.
- Structured JSON audit log to stdout or a sink.
- First-class multi-tenant: one install, isolated workspaces.

**AI (optional, pluggable)**
- NL → MDX via Claude tool-use, grounded in cube metadata.
- Auto-explain: "what drove the March spike?" walks drill-downs and summarises.
- Schema summarisation for onboarding.
- Chart suggestion from result shape.
- All optional. No API key ever required to run Saiku.

## Phase 8 — Ecosystem (ongoing menu)

**Query engine**
- Apache Calcite spike: can we retire Mondrian long-term? 6+ month investigation if we commit. Would live alongside Mondrian for a long time.
- MDX-to-SQL transpiler for Snowflake / BigQuery / ClickHouse where Mondrian's generated SQL underperforms.

**Datasources (as YAML semantic-layer adapters)**
- DuckDB (treated as the reference implementation — a separate near-term workstream).
- Generic JDBC + YAML model, no Mondrian required for simple cases.
- Arrow Flight SQL.
- Iceberg REST catalog.
- Cube semantic layer passthrough.
- Malloy compiler integration.
- dbt `semantic_models` consumption.

**Developer ecosystem**
- Fresh docs site (Docusaurus or Astro) with runnable examples.
- Examples repo with real datasets and cubes.
- Contributor guide, good-first-issue triage, monthly community call.
- Plugin SPI stabilisation and a plugin registry.
- Browser WASM demo on the website.

**Release engineering**
- Semantic-release automation.
- Signed releases (Sigstore).
- Long-term support branch once stabilised.

## Timeline summary

| Phase | Weeks | Ships |
|-------|-------|-------|
| 0 | 2 | CI, tests, JDK 21 |
| 1 | 2–3 | Spring Boot 4, fat JAR, CLI, Docker |
| 2 | 2 | Filesystem repo; Jackrabbit gone |
| 3 | 3–4 | YAML semantic layer; `saiku infer`/`lint` |
| 4 | 3–4 | Vite/TS, AG Grid, Monaco, ECharts |
| 5 | 3 | Arrow transport, async queries, cache |
| 6 | 4–6 | Svelte rewrite; Backbone gone |
| 7 | ongoing | Notebooks, embed, auth, AI, etc. |
| 8 | ongoing | Calcite, adapters, docs, community |

Sequenced core work (Phases 0–6) lands in roughly **20–24 weeks**. Phases 7 and 8 are menus worked through by demand after the core modernisation.

## Open decisions to revisit

- DuckDB integration treated as the reference Phase 3 YAML-layer example; may want its own mini-plan.
- Whether to release under a new major version (2.0) when Phase 6 completes, to signal the break from the legacy UI.
- Whether to stand up a docs site (Phase 8) earlier, to capture early adopters of Phases 2–3.
