# Changelog

All notable changes to Saiku are documented here. This project follows
[Semantic Versioning](https://semver.org/).

## 4.7.1 — 2026-08-02

Patch release — Cube Designer fixes, a UI theme refresh aligned with Saiku
Cloud, and a demo-mode lockdown.

### Fixed
- **Cube Designer — edit an existing cube.** Opening the designer on a datasource
  that already has a Mondrian schema now loads that schema onto the canvas
  instead of showing a blank one. A new `GET /rest/saiku/admin/cube-designer/
  schema/{dataSourceId}` endpoint resolves the attached catalog (external
  `file:`, classpath `res:`, or repository) to its XML. (saiku#1634)
- **Datasources admin — correct driver/schema/JDBC URL.** Mondrian datasources
  whose inner `Jdbc=` URL carries its own `;`-params (e.g. H2 `;MODE=MySQL`) were
  mis-parsed, showing the catalog file as the driver and a stray param as the
  schema. Parsing is now key-based and order/param tolerant. (saiku#1634)
- **Cube Designer — drag-to-canvas.** Dropping a table now lands it under the
  cursor after the canvas has been panned or zoomed (drop coordinates are mapped
  into flow space). (saiku#1634)
- **UI — flat buttons.** Raw `<button>`s no longer fall back to the browser
  default (a 2px outset border on a grey face); the base reset that Tailwind
  preflight would apply is now in place.

### Changed
- **UI theme aligned with Saiku Cloud.** Adopted the shared three-tier design
  tokens: Saiku-red brand, warm-neutral light / cool-neutral dark surfaces,
  subtle border ramp, and layered dark elevation — replacing the previous
  indigo-accented, harsh-bordered theme.
- **Cube Designer header.** Mode tabs restyled to a segmented group with a
  ringed active tab, matching the Cloud designer.

### Added
- **Demo-mode lockdown (`SAIKU_DEMO`).** On a public demo, visitors can no longer
  create/edit/delete datasources or save a schema from the Cube Designer, so a
  broken connection or schema can't take the shared instance down. Read-only
  actions (Refresh, opening the designer) still work.
- **Bombadil UI fuzz harness** (`saiku-ui/bombadil/`, dev-only, not shipped in
  the runtime) — property-based headless-browser fuzzing of the UI.

## 4.7.0 — 2026-08-01

Feature release.

### Added
- **Visual Cube Designer in the admin panel** — the interactive schema/cube
  designer is now open source and built into Saiku's admin UI
  (`Admin › Datasources › Design cube`). Profile a datasource, lay tables and
  joins out on a canvas, build dimensions/hierarchies/levels and facts/measures,
  and emit Mondrian 4 schemas directly — no hand-written XML. It replaces the
  old text-based schema generator. Backed by new REST endpoints under
  `/rest/saiku/admin/cube-designer/*` (introspect, sample, convert) and a
  faithful Mondrian 3→4 converter (`RolapSchemaUpgrader`).
- **AI-assisted schema authoring (optional)** — with an `ANTHROPIC_API_KEY`
  configured (`saiku.ai.ask.*`), the designer's DimSum assistant can propose
  dimensions, hierarchies and measures conversationally against the profiled
  warehouse. Fails closed (503) when no key is set.
- **App Builder graphical authoring** — theme-token foundation, brand & theme
  inspector, tile inspector with selection + field editing, and ECharts tile
  Trend/Breakdown toggles for building dashboard apps.

### Changed
- Chart & dashboard polish: readable value-axis tick density, legend placement
  below the title band, grid spacing for rotated category labels, and a
  decluttered toolbar with an overflow menu.

## 4.6.4 — 2026-07-30

Security patch release.

### Security
- **Inline-tile embed RLS bypass** — an embed iframe guest could read the row-level-security
  scope from their own token's `saiku.filters` claim and send a crafted client filter override
  that **widened** (added hidden members) or **stripped** the forced RLS filter on an inline
  dashboard tile. The inline path applied the forced filters first and then let client overrides
  remove/replace them. Forced filters are now applied **last and authoritatively**: on every
  forced axis the effective member set is always a subset of the forced set — a client can only
  narrow within the forced scope, never widen, strip, or change the operator. The saved/reference
  tile path was not affected (it uses the fail-closed `forcedFilters` channel).

## 4.6.3 — 2026-07-20

Patch release.

### Fixed
- **Admin datasource save returned 400** — adding or editing a datasource in the admin
  panel failed with `Save failed  /datasources -> 400`, and the datasource list showed
  blank Type/Schema columns. The SvelteKit admin UI posted camelCase field names
  (`name`, `location`, `schemaName`, `type`) that don't exist on the server's
  `DataSourceMapper`; Jackson rejected the first unknown field as a 400. The UI now
  translates to the server's field contract at the API boundary. Editing a datasource
  without retyping the password no longer wipes the stored credential. (saiku#1529)

## 4.6.2 — 2026-07-16

Patch release.

### Added
- **`SAIKU_ADMIN_PASSWORD`** — set the admin password without rebuilding the image.
  The launcher bcrypt-hashes it into a persisted external `<saiku-home>/users.properties`
  (or supply that file yourself), so a rotated password boots without
  `SAIKU_ALLOW_DEFAULT_ADMIN`. Fixes the self-host password-rotation gap — the
  previously-documented `saiku-rotate-admin` command never existed. See
  `dist/README.md` for details.

## 4.6.1 — 2026-07-13

Patch release fixing version reporting and install telemetry.

### Fixed
- **Version reporting** — deployed instances reported their version as `dev` because the
  fat-JAR manifest never stamped `Implementation-Version`, so `getImplementationVersion()`
  was always null. This broke the `/info` version, the update check, and install telemetry
  (every 4.6.0 instance pinged as "dev"). Releases now report their real version.
- **Install telemetry** — the heartbeat counts real releases only: dev/CI/IDE builds are
  skipped client-side and excluded server-side, so the active-install count is accurate.

## 4.6.0 — 2026-07-13

The headline release of the year: **Saiku becomes a semantic layer that AI agents can
query directly** — no MDX, no SQL. Alongside that, a full embedding SDK, a
privacy/governance layer, and a large batch of security hardening. 381 commits since 4.5.2.

### 🧠 The Ossie semantic layer (new)
One semantic model — datasets, fields, metrics, relationships — served to dashboards,
Excel, and AI agents at once.
- **Ossie model support** — describe your data once in a portable YAML/OSI document (or
  generate it from a Mondrian schema) and query it everywhere.
- **Calcite-based SQL adapter** — Saiku plans clean SQL for the model against almost any warehouse.
- **Ontology + AI-context surface** — the model carries synonyms, display names and context
  so agents understand it.
- **Graph traversal + signals endpoints** — walk relationships (e.g. ownership chains) and pull
  risk/screening summaries, not just aggregates.
- **DuckDB/Quack support** with a dialect fix so filtered queries work correctly.

### 🤖 AI & agents
- **Ask in plain English** — natural-language questions translate to a validated query and
  return typed results.
- **Agent Spaces** — named admin-authored personas that scope an ask (system prompt +
  cube/skill allowlists), enforced server-side.
- **Agent Skills** — reusable markdown workflows agents can invoke.
- **SSE streaming** for ask responses (classic + space-scoped).
- **Bring-your-own-LLM adapters** (Anthropic, OpenAI, Azure OpenAI).
- **Agent evaluation framework** — CLI, REST endpoints, a results store and a dashboard for
  scoring agent answers.
- **AI audit log** and safer policy defaults, plus MCP structured-content support.

### 🔌 Embedding SDK
- **React embed SDK**, **web-component embed**, and token-based embedding.
- **Row-level security** (including JWT-driven RLS) and a **PII gate** for embeds.
- **Chart theming**, embed options, and a force-on header for locked-down deployments.
- Embed package renamed to **`@concepttocloud/saiku-embed`** (now on npm).

### 🔒 Privacy & governance
- **PII annotation** on schema fields, with a per-column inspector.
- **k-anonymity** enforcement on results (matrix + query paths).
- AI audit logging and tightened AI data-policy defaults.

### 📊 Charts & UI
- **Combo charts** and **dual-Y-axis**, measure-group tree with dimension applicability,
  richer time filters, and numerous chart-affordance fixes.

### 📈 Install analytics (new)
- **Anonymous, opt-out install telemetry** — counts active installs (not downloads) via a
  daily heartbeat, backed by a zero-cost Cloudflare collector. Disable with
  `SAIKU_TELEMETRY=off` / `DO_NOT_TRACK=1`.

### 🔭 Observability
- Opt-in **OpenTelemetry** bundles (Jetty, Jersey, JDBC, JVM) via the OTel agent.

### 🧪 Demos & content
- New bundled demos: **TPC-DS**, **Flights**, **Pharma**, **Bank showcase**, and a
  **FoodMart embed** demo — Ossie datasources ready to poke on first boot.

### 🛡️ Security
- CVE/dependency fixes across **Jackson**, **Spring + Log4j**, **Vite/DOMPurify**, **Batik**,
  **OpenPDF** (swapped in for iText), and **jsPDF**.

### ⬆️ Dependencies
- **Jetty 12.1.10**, **Log4j 2.26.0**, **Caffeine 3.2.4**, Spotless 3.7.0, slf4j 2.0.18, and
  routine GitHub Actions bumps.

**Artifacts:** fat-jar + dist zip on the [GitHub release](https://github.com/spiculedata/saiku/releases/tag/v4.6.0),
Docker image at `ghcr.io/spiculedata/saiku:4.6.0`, module jars on GitHub Packages,
and `@concepttocloud/saiku-embed` on npm.
