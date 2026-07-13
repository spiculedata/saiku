# Saiku roadmap

_Last reviewed: 2026-07-10_

This roadmap captures the direction of travel for Saiku over the
next 6-12 months. Priorities move; that's the whole point of a
roadmap that's kept honest. See the
[GOVERNANCE](./GOVERNANCE.md) doc for how changes to this file get
proposed.

Everything below is public work in the open-source repository.
Cloud-only operational features (multi-tenant billing, SSO
integrations with SLAs, region deployment) are tracked separately
and don't appear here.

## Currently shipping (Q3 2026)

Features that are landing in the current milestone, referenced by
GitHub issue where one exists.

- **Ossie / Open Semantic Interchange, end-to-end.** DTOs +
  YAML/JSON reader, Calcite adapter, shelf-state query engine, AI
  Query API, MCP tools, natural-language ask. Shipped in July
  2026 as [`bi.saiku.ossie:ossie-core`](https://github.com/spiculedata/ossie)
  + `bi.saiku.ossie:ossie-sql`. Saiku consumes it as an external
  library.
- **AI Query API for both OLAP and Ossie surfaces.** Records +
  matrix formats, async execute, anomaly detection, forecasting,
  natural-language ask. See [`docs/AI-QUERY-API.md`](./docs/AI-QUERY-API.md)
  and [`docs/AI-OSSIE-API.md`](./docs/AI-OSSIE-API.md).
- **dbt / MetricFlow integration.** dbt Core 1.12's
  `target/osi_document.json` is consumed directly. Older dbt
  versions use the MetricFlow-to-Ossie converter in
  [`docs/dbt-hookup/`](./docs/dbt-hookup/).
- **Synonym resolution, custom_extensions passthrough, ontology
  block.** Every OSI spec surface the flights + tpcds reference
  examples exercise now round-trips through Saiku
  ([#1408](https://github.com/spiculedata/saiku/issues/1408),
  [#1409](https://github.com/spiculedata/saiku/issues/1409),
  [#1410](https://github.com/spiculedata/saiku/issues/1410)).

## Next up (Q4 2026)

Features prioritized but not yet actively in flight. Order below
reflects current thinking; order will shift as we learn.

- **Roles-based security for Ossie models
  ([#1393](https://github.com/spiculedata/saiku/issues/1393)).**
  Mondrian-parity per-role WHERE-clause injection driven by
  Spring Security authorities. Load-bearing for any enterprise
  procurement conversation where "the CFO's row-level security
  applies to AI queries too" is a requirement. Design phase
  first (YAML shape, translator hook points, k-anonymity
  interaction), then implementation.
- **Extract the Ossie shelf translator to `bi.saiku.ossie:ossie-sql`
  (saiku#1396 Path 2).** Path 1 (adapter classes) already
  landed. Path 2 disentangles the shelf translator from Saiku-
  specific bindings (`OssieQueryModel`, k-anon filter, PII
  redaction) so the library becomes the reference translator
  for JVM consumers. Real design work.
- **GraphQL API.** Wraps the existing typed AI Query API. Table-
  stakes for embedded and SaaS evaluators. Deferred until an
  actual embedded evaluator asks — we don't want to spend a week
  building it against no user requests.
- **Cube feature-tracker comparison page on saiku.bi.** Mechanical
  content asset for "cube open source alternative" search
  traffic. Not code, but tracked here for planning.

## Investigating (H1 2027)

Directions we've committed to explore but not to build.

- **Ontology-driven schema navigation UI.** The OSI ontology block
  ships knowledge-graph shape (concepts, relationships,
  verbalizations, derived_by). Rendering it as a first-class
  navigation surface alongside the dataset tree would give agents
  and humans an entity-level model view no BI tool has today.
  Depends on a real design pass — not just "render the block."
- **`bi.saiku.ossie:ossie-mondrian` — Mondrian → Ossie adapter
  as a standalone library.** Currently
  [`MondrianToOssieConverter`](./saiku-core/saiku-service/src/main/java/org/saiku/service/schema/ossie/MondrianToOssieConverter.java)
  lives in Saiku because it depends on Mondrian. Extracting it
  would let non-Saiku tools convert Mondrian schemas to OSI
  YAML.
- **Broader warehouse dialect coverage.** Calcite handles many
  dialects; native dialect maps exist for Postgres, H2, HSQLDB,
  MSSQL, MySQL/MariaDB, Oracle. Redshift and Databricks are the
  two most-requested additions.

## Not on the roadmap

Being explicit about what we're deliberately not building matters
as much as what we are.

- **Per-seat pricing.** Flat pricing is a competitive weapon; we
  keep it.
- **Gating existing OSS features behind a Cloud paywall.** The
  no-rug-pull pledge is a governance commitment; see
  [`GOVERNANCE.md`](./GOVERNANCE.md).
- **DAX API.** Power BI is served through SQL / DirectQuery; a
  parallel DAX API is a wrong investment for the audience Saiku
  serves.
- **FedRAMP compliance.** Not the market we serve. Enterprise
  procurement asks for it are politely declined.
- **Chat-driven data-app builder (Cube D3 equivalent).** Cube's
  D3 is a $47M-of-VC product surface. We don't out-Cube Cube on
  chat UX; we serve teams for whom Excel + governed cubes + MCP
  is the right shape.

## How to propose a change

- **Small addition / adjustment:** open a GitHub issue tagged
  `roadmap` describing the ask and the audience. Discussion
  happens on the issue; if consensus favors it we PR it into this
  file.
- **Big directional shift** (removing a whole tier above, changing
  our not-on-roadmap stance): raise it on
  [GitHub Discussions](https://github.com/spiculedata/saiku/discussions)
  first. 72-hour open discussion window per
  [`GOVERNANCE.md`](./GOVERNANCE.md) before any file change.
- **Enterprise-specific ask:** email
  [hello@spicule.co.uk](mailto:hello@spicule.co.uk) with the use
  case. Cloud enterprise contracts can influence prioritization
  transparently; we'll say yes or no in writing.
