# Changelog

All notable changes to Saiku are documented here. This project follows
[Semantic Versioning](https://semver.org/).

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
