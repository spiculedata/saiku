# Schema Auto-Generation — Design

**Date:** 2026-04-19
**Status:** Design accepted, ready for implementation plan
**Scope:** Restore and modernise Saiku's "guess a schema, let the user clean it up" workflow, using deterministic JDBC introspection as the skeleton and an optional LLM pass for the polish that users historically did by hand.

---

## Goals

- Turn a JDBC data source into a working Mondrian schema with minimal user effort.
- Keep the user firmly in the driver's seat: every generated element is reviewable, every LLM suggestion is Accept/Reject.
- Work offline / air-gapped with no cloud dependency.
- Support re-runs when the upstream database evolves, without destroying user edits.

## Non-Goals

- Three-way merge of user edits against a regenerated skeleton.
- Detecting column/table *renames* in the upstream DB (drop + add is fine).
- Replacing the existing manual schema editor — this feature complements it.
- Structural reshaping by the LLM (no merging cubes, no inventing joins).

---

## Approach

Two passes, in order:

1. **Deterministic pass** — JDBC introspection + rule-based inference produces a `DraftSchema`. Standard level: stars by FK fan-in, date dimensions with Y/Q/M/D, one-hop snowflakes, numeric non-FK columns → SUM measures.
2. **LLM enrichment pass** — takes the draft + column samples, emits a `SuggestionSet` of reviewable ops (renames, hierarchy proposals, aggregator changes, degenerate-dim promotions, ignores). Never mutates the draft directly.

The LLM pass is optional. A `NoopProvider` runs the same local heuristics (geo naming, `avg_*` patterns, date parts) and emits the same `SuggestionSet` shape, so the feature degrades gracefully on air-gapped deployments.

---

## Architecture

Three layers, matching Saiku's existing split.

### `saiku-service` — `org.saiku.service.schema.generate`

- **`JdbcIntrospector`** — wraps `java.sql.DatabaseMetaData`. Returns a neutral `DbModel` (tables, columns, PK/FK, row-count hints via optimiser stats first, `COUNT(*)` fallback with size guard).
- **`SchemaInferrer`** — pure function `DbModel → DraftSchema`. Runs a fixed pipeline of testable stages (see below). Every inferred element carries a `provenance` tag.
- **`LlmEnricher`** — takes `DraftSchema` + samples, returns `SuggestionSet` via an `LlmProvider` SPI.
- **`LlmProvider` SPI** — one call: `enrich(request) → SuggestionSet`. Adapters: `AnthropicProvider`, `OpenAiProvider`, `OllamaProvider`, `NoopProvider`.
- **`DeltaReconciler`** — compares a new draft against a saved baseline. Marks elements `NEW` / `EXISTING` / `REMOVED_UPSTREAM`.
- **`MondrianSchemaWriter`** — serialises `DraftSchema` + accepted suggestions to Mondrian XML via Mondrian's schema object model (not string templating). Also writes the sidecar baseline JSON.

### `saiku-web` — REST

New resource tree under `/rest/saiku/admin/schema-generator/`:

- `POST /:dataSourceId/start` — kicks off generation, returns a session id.
- `GET /:sessionId/status` — stages: `introspecting`, `inferring`, `enriching`, `ready`.
- `GET /:sessionId/draft` — current draft view model.
- `GET /:sessionId/suggestions` — streamed as LLM pass completes per cube.
- `POST /:sessionId/ops` — apply / reject / manual-edit op; server replays onto draft, returns updated view.
- `POST /:sessionId/save` — validate via Mondrian parser, write XML + sidecar to repository.

### `saiku-ui` — two-pane editor

New admin route `/admin/schema-generator/:dataSourceId`. Svelte. Left pane: editable schema tree with provenance badges. Right pane: grouped suggestions feed (Renames, Hierarchies, Aggregators, Degenerate dims, Ignores) with per-card Accept / Reject / Edit-then-accept and group-level bulk actions.

---

## Deterministic inference pipeline

Each stage is pure and independently golden-file tested.

**1. Classify tables.** FK-out count, FK-in count, column count, row count. Fact candidates = high FK-out + high rows. Dimension candidates = tables pointed at by facts. Neither = "orphan", flagged for LLM pass. Multi-fact allowed — one cube per fact.

**2. Build dimensions.** PK → level key. Single non-PK string column → level `[name]`. One-hop snowflake inlined as a joined second level; deeper snowflakes deferred to LLM pass as suggestions.

**3. Time dimensions.** Any `DATE`/`TIMESTAMP` on a fact becomes a role-playing Time dimension with Year/Quarter/Month/Day levels via Mondrian's `TimeDimension` type. Shared Time dimension emitted once, role-played per column.

**4. Measures.** Non-FK numeric fact columns → `<Measure aggregator="sum">`. An implicit `[Measures].[Fact Count]` using `count(*)` is always added. Aggregator heuristics beyond SUM deferred to LLM pass.

**5. Emit `DraftSchema`.** In-memory object graph. Every element carries `provenance` (`rule:fact-by-fk-fanin`, `rule:date-column`, …) so the UI can explain *why* something was generated.

Deferred to LLM pass deliberately: degenerate dimensions, aggregator variety beyond SUM, multi-hop snowflakes, orphan-table decisions.

---

## LLM enrichment

### Input bundle (compact JSON)

- Draft schema.
- Per column: name, type, nullability, distinct-count estimate, up to ~5 sample values.
- PII filter: name-pattern deny-list (`*ssn*`, `*password*`, `*email*`, …) plus admin override. Columns matching the deny-list send metadata only, no samples.
- Payload caps; large schemas chunked by cube.

### Suggestion ops

- `RenameOp` — caption + description; preserves underlying identifier.
- `HierarchyOp` — multi-level hierarchy proposal with confidence and source columns.
- `AggregatorOp` — change measure aggregator with one-line rationale.
- `DegenerateDimOp` — promote categorical fact column to degenerate dimension.
- `IgnoreOp` — suggest dropping an orphan table or noisy column.

### Determinism & failure

Low temperature, seeded where supported, schema-constrained output (JSON schema / tool-use). Malformed output → retry N times, then fall back to `NoopProvider` for that request and flag it in the response. `NoopProvider` runs local heuristics and emits the same `SuggestionSet` shape so the UI never branches on provider availability.

---

## UI flow

- **Left pane:** schema tree. Click a node → side-drawer form. Raw XML is hidden behind an "Edit XML" escape hatch per cube.
- **Right pane:** suggestions grouped by op type. Cards show affected node, before → after preview, confidence, rationale. Accept / Reject / Edit-then-accept. Bulk: "Accept all renames with confidence ≥ high".
- **State model:** draft lives server-side keyed by session id. UI sends ops, server replays onto draft, returns updated view. Op log makes undo trivial and survives tab close.
- **Progress:** async pipeline with streamed per-cube suggestions. Stages surfaced in UI: `Introspecting… Inferring… Enriching…`.
- **Commit:** "Save schema" validates via Mondrian's parser before write; errors surface inline against the offending node. On save: XML + `<schema-name>.generated.json` sidecar (baseline draft + op log) written to repository.

---

## Entry points

Both provided; build in order:

1. **From existing data source** *(MVP)* — "Generate schema" button on a data source with no schema (or a placeholder). Reuses creds, drivers, pooling.
2. **New data source with auto-schema** *(follow-up)* — standalone wizard: JDBC URL + creds → generate → save as new data source or discard.

Same underlying generator service; entry #2 is UI plumbing on top of a working entry #1.

---

## Re-run & drift

Re-run = generate against the delta. Same pipeline, narrower input.

1. Re-introspect → new `DbModel`.
2. `SchemaInferrer` produces a fresh `DraftSchema`.
3. `DeltaReconciler` diffs new vs baseline sidecar by **stable identifiers** (table name, column name, FK signature — never captions):
   - in new, not in baseline → `NEW`
   - in both → `EXISTING` (skipped; user edits preserved)
   - in baseline, not in new → `REMOVED_UPSTREAM` (warning suggestion: keep reference or drop?)
4. LLM enrichment runs only on `NEW` elements.
5. UI opens with existing schema loaded on the left and only delta-derived suggestions on the right.

Hand-written schemas (no sidecar) are still re-generatable — everything comes through as `NEW` against their live schema as context.

---

## Testing strategy

- **Stage-level golden files** for `SchemaInferrer` against representative fixtures: Foodmart (classic star), a snowflake, a multi-fact warehouse, a wide flat table, and an ambiguous-fact schema.
- **`NoopProvider` only** for enrichment unit tests — deterministic, offline, hermetic.
- **Contract tests** for real `LlmProvider` adapters: assert response-schema conformance, not content. Run opt-in (skipped without creds).
- **End-to-end** one happy path: Foodmart → generate → accept-all → save → open saved schema in Saiku and run a known MDX query.
- **Delta tests**: regenerate against a mutated `DbModel` fixture (add column, drop column, add table) and assert the expected `NEW` / `EXISTING` / `REMOVED_UPSTREAM` classification.

---

## Open questions / future work

- Multi-hop snowflake handling beyond one level — currently deferred to LLM suggestion; may want a deterministic rule once we see real-world shapes.
- Schema-level ACL / visibility suggestions — out of scope for v1.
- Incremental LLM re-enrichment when the user rejects many same-type suggestions (learn a per-project preference) — future.
