# Phase 7 — Platform features (menu)

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans when picking an item off this menu.

**Goal:** Independent features built on top of the modernised stack. Not a phase with a fixed scope — a menu of tickets picked by user demand. Each item gets expanded into its own bite-sized plan before execution.

**Base branch:** `development` (post Phase 6 merge).

**Effort:** each item = 1–3 focused sessions. Pick based on which users are shouting loudest.

---

## Menu

### Authoring & collaboration
- **Query notebooks** — MDX + markdown + chart cells, saved as `.saiku.md` files. Jupyter-for-cubes UX.
- **Dashboards 2.0** — grid layout, parameters, cross-filter between tiles, drill-through.
- **Embeddable iframes** — signed-URL auth, one-line `<iframe src="…/embed/query/abc123?token=…">`. Post-message API for host → embed.
- **Comments + @mentions** — threaded comments on query cells. Files on disk; git-blame-friendly.
- **Version history UI** — surface `git log` diffs of schemas, queries, dashboards directly in Saiku.

### Delivery
- **Scheduled exports** — cron-like scheduler. Outputs: CSV / Parquet / PDF / XLSX. Destinations: email (SMTP), Slack (webhook), S3, arbitrary webhook.
- **Alerts** — threshold rules on measures ("revenue < 80% of 7-day avg"). Notify via the scheduled-export destinations.
- **Printable PDF dashboards** — one-click render. Clean layout, page breaks, footer with timestamp + filter summary.

### Auth & ops
- **OIDC / SAML** — drop the in-memory users.properties for a real SSO. Spring Security OAuth2 Client + SAML2 starters.
- **Row-level security** — wire the YAML `security` block (Phase 3) to the query-time `SecurityAwareConnectionManager`.
- **Audit log** — structured JSON events to stdout (or a sink). Every query, every config change, every login.
- **Multi-tenant** — one deployment, many isolated workspaces, shared auth. Per-workspace data dir, per-workspace ACL.

### AI (opt-in, pluggable)
- **NL → MDX** — Claude tool-use call with the cube metadata as tools. User types "sales by region last quarter", gets MDX + results.
- **Auto-explain** — "what drove the March spike?" walks drill-downs, summarises findings.
- **Schema summariser** — for onboarding to a new cube, produce a human-readable overview.
- **Chart suggestion** — given result shape, recommend chart type (line for time-series, stacked-bar for part-of-whole, etc.).

All AI features require a pluggable `LlmProvider` abstraction. Built-in providers: Anthropic, OpenAI, local Ollama. **No API key required to run Saiku itself.**

---

## Per-item process

Before picking any item:
1. Confirm with stakeholders the item is still the priority.
2. Write a bite-sized plan: `docs/plans/YYYY-MM-DD-phase-7-<item>.md`.
3. Create a worktree: `../saiku-phase-7-<item>`.
4. Execute per `superpowers:executing-plans`.
5. Ship one item per PR.

## Acceptance criteria (per item)

Every Phase 7 item must:
- Have documentation (README section + 1-page guide for non-obvious items).
- Have tests (unit + at least one Playwright/integration).
- Be off by default if it introduces security surface (OIDC, multi-tenant, AI) — user opts in via config.
- Not break any existing phase 0–6 behaviour.

## Not in scope for Phase 7

- New query engine (that's Phase 8 Calcite).
- New frontend framework (Svelte is set by Phase 6).
- Breaking changes to the YAML semantic layer (additive only).
