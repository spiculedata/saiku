# Saiku Dashboards — User Guide

A dashboard is a saved, shareable canvas of **tiles** — charts, tables, KPIs,
filters, text and images — laid out on a grid over your cube data. This guide
walks through building one, making it interactive, and sharing it.

> Looking to *embed* a dashboard in your own app instead? See the
> `<saiku-embed>` web component in [`../saiku-ui/src/embed/README.md`](../saiku-ui/src/embed/README.md).
> To query cubes programmatically (no UI), see [`AI-QUERY-API.md`](AI-QUERY-API.md).

---

## 1. Concepts

| Term | What it is |
| --- | --- |
| **Dashboard** | A `.saikudash` document: a grid layout of tiles + a filter panel. |
| **Tile** | One cell of content — chart / table / KPI / text / image / filter. |
| **Catalogue** | The browse-and-open index of all dashboards, organised into folders. |
| **Filter panel** | A docked row of filters at the top that slice every compatible tile at once. |

Editing is **mode-based**: open a dashboard read-only to view it, or switch to
**edit mode** (the toolbar) to add/move/configure tiles. Read-only viewers never
see the authoring controls.

---

## 2. Create a dashboard

1. Open the **Dashboards** catalogue (top nav).
2. **New dashboard** → choose **Blank** or **start from a template** (a couple of
   ready-made starter layouts so you're not facing an empty grid), give it a
   **name**, and pick the **folder** to save into.
3. You land in the editor with an empty grid and the **Add tile** menu.

**Folders.** The catalogue is a folder tree — create, rename, and drag
dashboards between folders to keep things tidy. Pin the dashboards you open most
so they're one click away.

---

## 3. Tiles

Use **Add tile** to drop any of these onto the grid; drag a tile's corner to
resize, drag its header to move it.

| Tile | Use it for |
| --- | --- |
| **Chart** | Visualise a query — 16 chart types (bars, lines, pie/donut, treemap, sunburst, heatmap, radar, scatter, bubble, waterfall, map). |
| **Table** | A cross-tab grid, with optional per-column conditional formatting + inline sparklines. |
| **KPI** | A single headline number with a comparison (vs last year / prior period / target). |
| **Text** | Markdown-ish notes, headings, captions. |
| **Image** | A logo or graphic, by URL or upload. |
| **Filter** | A picker that slices other tiles (these live in the filter panel — see §7). |

Every data tile shows clean **loading / error / empty** states, and an empty
result tells you whether it's empty *because of a filter* (with a reset).

---

## 4. Chart tiles

### Bind data
A chart tile runs a query against one cube. Bind it either to a **saved query**
(pick a `.saiku` from the repository) or build an **inline** query (cube +
measures + row/column levels). Pick the **chart type** from the type menu.

### Chart options
Open a chart tile's **chart-options** panel (the ⚙ control) for everything
below. All are optional — a fresh tile renders with sensible defaults.

- **Titles, axis labels, legend** — show/hide + position the legend.
- **Dual axis** — auto-split a small-magnitude series onto a second y-axis so it
  isn't crushed; override **per series** (Left / Right / Auto).
- **Colours** — pick a named palette, or override the colour of an individual
  series. Honors the global colour-blind-safe mode.
- **Number format** — prefix/suffix (e.g. `$`, `%`), decimals, thousands
  separators, and large-number abbreviation (`1.5k`, `2.4M`) on the value axis,
  tooltips and data labels.
- **Reference lines & bands** — draw threshold/target lines (`markLine`) and
  shaded ranges (`markArea`) across cartesian charts.
- **Conditional formatting** — recolour **bars** by value with first-match-wins
  threshold rules (e.g. red below target, green above) + a fallback colour.
- **Combo (series type)** — give each series its own type, e.g. revenue as
  **bars** and growth-rate as a **line** on the same canvas (cartesian; composes
  with dual axis).
- **Trend lines** — overlay a linear / moving-average / weighted-MA trend.
- **Sort & Top-N** — sort the category axis and trim to the top N, client-side
  (no re-query).

### Special chart types
- **Small multiples** — a pie/donut/treemap/sunburst with several measures
  renders as a grid of mini-charts, one per measure, so each stays readable.
- **Map (choropleth)** — colours world countries by a measure; place names come
  from the row hierarchy (common aliases like *USA* / *UK* are matched
  automatically).

### AI overlays (time-series charts)
- **Anomaly detection** — flag outliers along a time axis (z-score or MAD); the
  flagged points get markers with a score/expected tooltip.
- **Forecast** — project a horizon of future points with a confidence band
  (dashed continuation). Both are server-side, no LLM. See the API contracts in
  [`AI-QUERY-API.md`](AI-QUERY-API.md) (Step 7).

---

## 5. Table & KPI tiles

**Table tiles** support **per-column conditional formatting** (colour scales /
threshold bands) and inline **sparklines** (a tiny trend chart in a column).

**KPI tiles** show one big number plus a comparison badge: **vs last year**, **vs
prior period**, or **vs a target** — with a clear "no prior data" state when the
comparison can't be computed.

---

## 6. Filters

Filters live in the **filter panel** docked at the top of the dashboard. A panel
filter slices **every compatible tile** at once (a tile is compatible when the
filter's dimension/hierarchy/level resolves in that tile's cube).

Filter widgets:
- **Single-select** / **multi-select** — pick one or many members.
- **Date range** — relative or explicit ranges on a time level.
- **Cascading select** — walk a hierarchy level by level (pick a parent, the next
  dropdown shows only its children).
- **Top-N** — rank a level's members by a measure and keep the top (or bottom) N.

When you hover a panel filter, the grid shows **"affects N of M tiles"** with a
preview ring on the tiles it will slice, so there are no surprises.

---

## 7. Make it interactive

- **Click-to-filter** — click a bar/point/slice to filter the rest of the
  dashboard to that member (a removable chip appears).
- **Cross-filter (brush)** — on an opted-in chart tile, drag to rubber-band a
  range of categories; the *other* tiles narrow to those members while the
  source keeps full context. Press **Esc** (or click off the selection) to clear.
  Turn it on per tile in the chart tile editor.
- **Drill-down** — expand a hierarchy (Year → Quarter → Month) without leaving
  the chart.
- **Drill-through** — right-click a data point to see the **raw fact rows** behind
  that cell, and **export them to CSV**. (Raw-row access can be gated by role /
  policy.)

Clear all transient (click/cross) filters from the filter bar; panel selections
persist with the dashboard.

---

## 8. Layout & editing

- **Grid** — 12-column responsive grid; drag to move, drag a corner to resize.
- **Multi-select** — Ctrl/Cmd-click (or shift-click to extend) tiles, then use the
  **bulk actions** bar to duplicate or delete several at once.
- **Undo / redo** — full edit history with keyboard shortcuts.
- **Responsive** — on narrow screens tiles auto-stack and the toolbar collapses
  into a menu, so a dashboard stays usable on a phone.

---

## 9. Refresh, export, share

- **Auto-refresh** — set a per-tile cadence (e.g. every 1 / 5 / 15 min); the tile
  re-runs its filter-aware query on that interval and pauses while the tab is
  hidden. A "last updated" badge shows freshness.
- **Export** — export the whole dashboard to **PDF** or **PNG** from the toolbar.
- **Share** — mint a read-only **share link** so people can view a dashboard
  without an account, scoped to that dashboard.
- **Collaborate** — leave **comments** on a tile, and browse the dashboard's
  **version history** to see (and restore) earlier states.

---

## 10. Tips

- Start from a **template** to get a sensible layout, then swap the queries.
- Use **dual axis + combo** together for the classic "bars + trend line" KPI
  chart.
- **Conditional formatting** + a **reference line** at the target makes
  "are we above/below plan?" readable at a glance.
- Keep **drill-through** for the analysts (it exposes raw rows); give everyone
  else aggregate charts + drill-*down*.

---

*This guide covers the shipped dashboard feature set as of Saiku 4.x. The
underlying chart engine is shared between the workspace (analysis view) and
dashboard tiles, so chart options behave the same in both.*
