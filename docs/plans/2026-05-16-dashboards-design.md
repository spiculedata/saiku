# Dashboards — v1 design

**Date:** 2026-05-16
**Status:** design validated, implementation pending
**Author:** brainstorming session with Tom

## Context

The pre-SvelteKit Saiku UI had a dashboards feature: multiple reports / tables / charts arranged in a flexible grid, with filters that pushed across all linked charts. It was a primary differentiator for the product. The Backbone → SvelteKit rewrite dropped it.

This document specifies the v1 shape of the rebuilt feature on the SvelteKit + AI Query API stack. The typed cube schema and validated JSON query shape make several things cheaper than they were in the Backbone era — most notably the per-tile cube compatibility check, which used to fail silently inside Mondrian.

## Shaping decisions

Five choices made in brainstorming. Each one rules out a meaningful chunk of surface area; alternatives are recorded so future readers can see what was off the table and why.

| # | Decision | Alternatives rejected | Reason |
|---|----------|----------------------|--------|
| 1 | **Audience: analyst-built, analyst+internal-viewer-consumed, no embedding** | External / iframe / public read-only | Keeps v1 inside Spring Security; no auth tokens, embed params, or anonymous-viewer plumbing. |
| 2 | **Filter model: global filter bar + click-to-filter** | Global-only; per-tile opt-in; click-only | Hybrid is what analysts actually use. Worth the extra state-tracking cost. |
| 3 | **Tile query binding: hybrid (reference saved query OR inline)** | Reference-only; inline-only | Less-skilled authors pick from a saved-query gallery; power users author inline via `QueryCanvas`. Inline is JSON on disk but never typed by the user. |
| 4 | **Layout: fixed 12-column grid + auto-stack on narrow viewports** | Free-form drag/resize; per-breakpoint layouts | Old Saiku's free-form drag-resize was a recurring source of bugs. Fixed grid gets 95% of value at a fraction of the surface area. |
| 5 | **Cube scope: multi-cube with auto-compatibility** | Single-cube; explicit per-filter target lists | Multi-cube is a real use case; typed schema lets us pre-flight compatibility instead of failing silently at query time. |

## Scope

**In v1**

- New SvelteKit views: `DashboardEditor`, `DashboardViewer` (Viewer = Editor with `readOnly={true}`)
- New JAX-RS resource: `org.saiku.web.rest.resources.DashboardResource` at `/rest/saiku/api/dashboards` — CRUD only
- Persistence: `.saikudash` JSON files in the existing JCR repository, alongside `.saiku` query files
- Tile types: `chart`, `table`, `text` (markdown), `filter` (single-select / multi-select / date-range)
- Filter widgets are themselves grid tiles
- Click-to-filter from `ChartView` / `CellsetTable` cell events
- Auto-stack to single column under ~768px viewport

**Explicitly out of v1**

- Embedding, iframe, public share links, anonymous viewers
- Mobile-specific layouts (separate breakpoint editors)
- KPI tile (single big metric + sparkline)
- Image tile, custom HTML tile
- Live query-linked tiles (edit tile A → tile B rerenders without reload). The hybrid binding's `reference` mode + a reload covers 80% of the value.
- Tile-to-tile drillthrough (uses the existing `/ai/query/{id}/drillthrough` once we wire it; tracked separately)
- Per-tile auto-refresh schedules, alerts, real-time data
- Dashboard versioning beyond what JCR provides for free

## Architecture overview

```
┌─────────────────────────────────────────────────────────────┐
│  saiku-ui/src/lib/views/dashboard/  (SvelteKit)             │
│                                                             │
│   DashboardEditor.svelte ──┬── DashboardToolbar             │
│                            ├── DashboardFilterBar           │
│                            └── DashboardGrid                │
│                                  └── Tile (× N)             │
│                                       ├── ChartTile         │
│                                       ├── TableTile         │
│                                       ├── TextTile          │
│                                       └── FilterTile        │
│                                                             │
│   Stores: dashboardStore, activeFiltersStore, schemaCache   │
└────────┬────────────────────────────────────┬───────────────┘
         │                                    │
         ▼                                    ▼
  /rest/saiku/api/dashboards (NEW)    /rest/saiku/api/ai/query (existing)
  CRUD against JCR repo               Tile queries flow through unchanged
```

The dashboard layer is a **thin layout layer** on top of the AI Query API. Tiles query through `/ai/query` exactly as the workspace does; the dashboard frontend rewrites each tile's `AiQueryRequest` with merged active filters before posting. **No new backend query engine.** No backend filter resolution either — the client merges and POSTs.

## Data model

Single JSON file per dashboard (`.saikudash`):

```jsonc
{
  "id": "uuid-v4",
  "name": "Q4 Sales Review",
  "version": 1,                        // schema version (not user-visible)
  "layout": {
    "cols": 12,                        // fixed; rows grow as tiles are added
    "tiles": [
      {
        "id": "tile-a",
        "x": 0, "y": 0, "w": 6, "h": 4,
        "type": "chart",
        "title": "Sales by quarter",
        "cube": { "connectionName": "...", "catalog": "...",
                  "schema": "...", "cubeName": "Sales" },
        "query": {
          "kind": "reference",         // | "inline"
          "path": "/queries/sales-by-quarter.saiku"
        },
        "chartType": "bar"
      },
      {
        "id": "tile-b", "x": 6, "y": 0, "w": 6, "h": 4,
        "type": "table",
        "cube": { ... },
        "query": { "kind": "inline", "body": { /* AiQueryRequest */ } }
      },
      {
        "id": "tile-c", "x": 0, "y": 4, "w": 12, "h": 1,
        "type": "filter",
        "target": { "dimension": "Time", "hierarchy": "Time", "level": "Year" },
        "widget": "multi-select"        // | "single-select" | "date-range"
      }
    ]
  },
  "filters": [                          // saved default filter values
    { "dimension": "Time", "hierarchy": "Time", "level": "Year",
      "members": ["[Time].[Time].[1997]"] }
  ]
}
```

Key shapes:
- **`cube` is per-tile**, not per-dashboard. Multi-cube is supported by construction.
- **`query` is a discriminated union** (`kind: "reference" | "inline"`). Reference points to an existing `.saiku` file; inline embeds the full `AiQueryRequest` shape.
- **Filter widgets are tiles** with `type: "filter"`. They occupy grid cells like everything else.
- **`filters[]` at the dashboard root** carries default values applied on load.

## REST surface (`DashboardResource`)

```
GET    /rest/saiku/api/dashboards               → tree listing under the repo
GET    /rest/saiku/api/dashboards/{path}        → load
POST   /rest/saiku/api/dashboards/{path}        → save (create or overwrite)
DELETE /rest/saiku/api/dashboards/{path}        → delete
```

Implementation goes through the existing `BasicRepositoryResource2` JCR plumbing — same backend, same auth, same path conventions as `.saiku` query files. **No new permission model.**

## Stores & state model

```ts
dashboardStore:    Writable<Dashboard>           // the loaded dashboard
activeFiltersStore: Derived<ActiveFilter[]>      // defaults ∪ widget ∪ click
schemaCache:       Writable<Map<cubeKey, AiSchema>>  // lazy, per-tile-cube

type ActiveFilter = {
  id: string
  source: 'default' | 'widget' | { tileId: string }
  dimension: string
  hierarchy: string
  level: string
  members: string[]      // MDX unique names; empty = "any"
}
```

For each tile, a derived store computes an **effective query**:

```
effectiveQueryFor(tile, activeFilters, schemaCache) → AiQueryRequest
```

**Tiles are independent subscribers.** When `activeFiltersStore` fires:
- Each tile recomputes its effective query.
- If unchanged → no refetch.
- If changed → POST `/ai/query` for that tile only.
- A slow tile doesn't block others; a failing tile shows an error in its own frame.

No master orchestrator; the alternative (batched refetch) was considered and rejected as premature.

## Filter propagation logic

### Applicability check

For an `ActiveFilter` `F` to apply to tile `T`:
1. `T.cube`'s `AiSchema` has a dimension matching `F.dimension` (alias-aware via existing `dimensionAliases` map).
2. That dimension has a hierarchy matching `F.hierarchy`.
3. That hierarchy has a level matching `F.level`.

If any of those fail → silently inapplicable (per decision 5). **The editor surfaces this visibly:** when you create a filter widget, every existing tile gets a small badge — green check (will apply) or gray dash (incompatible cube). Authors see the auto-skip *before* runtime.

### Merge logic

For each applicable filter:
- If the tile's existing `filters[]` already has one for that hierarchy → **replace it entirely** (widget filter wins over baked-in default).
- Otherwise → **append**.

### Axis-reuse conflict

saiku#784's rule (a filter hierarchy already on the tile's `rows`/`columns` axis is rejected) still applies. The editor pre-checks at filter-creation time and surfaces a yellow "this filter would conflict with tile X's row axis" warning instead of letting the converter reject at runtime.

### Click-filter semantics

- A click on a chart bar / table cell tags `ActiveFilter` with `source: { tileId: <source> }`.
- Click filters **compose with** widget/default filters (additive, not replacing).
- For the same hierarchy: **click wins over widget** (most recent intent). The filter chip shows both states.
- Clearing: explicit chip-X, or click-the-same-value-again (toggle).
- Switching to a different dashboard wipes click-filters; widget filters and defaults survive.

## Tile types in detail

### ChartTile

- Wraps existing `ChartView` with `effectiveQuery` input.
- `chartType` per tile (from existing `chartTypes.ts`).
- Edit affordance ⚙ → `TileEditorModal` with chart type + query controls; query mode edits route through `QueryCanvas`.
- Cell click → emits `dashboard:click-filter` to parent.

### TableTile

- Wraps existing `CellsetTable`. Same edit affordance as ChartTile minus chart-type field.
- Already emits cell-click events; same wiring to click-filter capture.

### TextTile

- Stores markdown in `tile.text`; renders with the existing markdown lib (confirm during impl).
- **Hardening:** marked + DOMPurify, no raw HTML, no scripts. Threat model = malicious analyst in a shared repo; surface is cheap to lock down.
- No query, no filters, no events.

### FilterTile

- Sub-types via `widget`: `single-select` | `multi-select` | `date-range`.
- Member source: calls `/rest/saiku/api/ai/members/search` against the targeted level. Cached per cube+level.
- Cube choice when multiple compatible: **first compatible cube in the dashboard, sorted by tile id** (deterministic).
- Selection → pushes `ActiveFilter { source: 'widget' }`. Empty selection → removes the filter.

### Add-tile flow

1. Pick type → Chart / Table / Text / Filter.
2. For chart/table → cube picker → query mode (reference vs inline).
3. For filter → cube picker → dim/hier/level → widget sub-type.
4. Lands at the first free `(x,y)` at row N+1.

## Testing strategy

**Unit (vitest)**

- `dashboardStore` round-trip; tile add/remove; layout mutations preserve other tiles.
- `activeFiltersStore` merge: default + widget + click precedence; toggle clears.
- Applicability checker: 2×2 matrix of (filter, tile cube schema); alias resolution; axis-reuse conflict case.
- Effective-query builder: `(tile, activeFilters, schema) → AiQueryRequest`; merge replace/append; no mutation of the source tile object.

**Component (vitest + @testing-library/svelte)**

- `Tile.svelte` polymorphism by `tile.type`; no edit affordances when `readOnly={true}`.
- `FilterTile` selection emits the expected `ActiveFilter` shape.
- `DashboardGrid` auto-stacks below breakpoint.

**Backend (JUnit)**

- `DashboardResource` CRUD against a temp JCR; Jackson roundtrip with no field loss on union types (`query.kind`).
- Permissions inherit from `BasicRepositoryResource2`'s existing tests.

**Live integration (`saiku-launcher/test-ai-live.sh`)**

- Create → fetch → mutate (add filter widget) → save → re-fetch → assert filter applied to each compatible tile's effective query (verified by replaying the merged query and matching row count).

**XSS regression**

- `TextTile` harness with `<script>`, `<img onerror=>`, raw HTML; assert all stripped.

## Open follow-ups (post-v1)

- KPI tile (single metric + sparkline) — most-requested deferral.
- Tile-to-tile drillthrough — uses existing `/ai/query/{id}/drillthrough`; needs UX for "open detail panel from this cell".
- Live query-linked tiles (instant rerender on shared-query edit).
- Embed mode (public share, iframe, anonymous viewer).
- Per-breakpoint layouts (separate mobile dashboard).
- Per-tile auto-refresh schedules.

## Implementation plan

Out of scope for this design doc. Will land as a separate `docs/plans/2026-05-XX-dashboards-implementation.md` produced via `superpowers:writing-plans` ahead of any code.

## References

- AI Query API contract: `docs/AI-QUERY-API.md`, `[[pages/decisions/ai-query-api-contract]]`
- saiku#784 (axis-reuse filter validation): handled in `AiSchemaConverter`
- Cube schema + alias maps: `AiSchema`, `dimensionAliases`, `levelAliases`
- Existing repo storage pattern: `BasicRepositoryResource2`, `.saiku` files
