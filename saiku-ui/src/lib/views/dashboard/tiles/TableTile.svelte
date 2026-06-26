<script lang="ts">
  /*
   * Table tile. Reads the tile's effective query, fetches via the AI
   * Query API in records format, and renders the result as an HTML
   * table. Row-header columns emit a `clickFilter` event when clicked
   * — Tile.svelte bubbles it up to the grid, which pushes onto
   * activeFilters with the source tile id.
   *
   * Inline tiles only in v1. Reference tiles (where query.kind ===
   * "reference") display a placeholder until the .saiku → AiQueryRequest
   * conversion lands.
   *
   * The fetch runs in an $effect keyed by:
   *   - the tile's id
   *   - the active filter set (so filter changes refire)
   *   - the schema cache version (so the merge re-resolves when a
   *     previously-missing schema arrives)
   */

  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import {
    isAiCell,
    searchMembers,
    type AiCell,
  } from "$lib/api/aiQuery";
  // #1231 — shared fetch state + effect body across ChartTile / TableTile.
  import {
    TileQueryState,
    runTileQueryEffect,
  } from "$lib/hooks/useTileQuery.svelte";
  // #1166: resolve clicked row-header captions to real member unique names.
  import { pickMemberUniqueNameForPath } from "$lib/dashboard/clickFilterMember";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import { type SchemaLike } from "$lib/dashboard/effectiveQuery";
  import {
    inferCubeFromReference,
    inferRowAxesFromReference,
    type RowAxisRef,
  } from "$lib/dashboard/filterSuggestions";
  import type { CubeRef } from "$lib/api/dashboards";
  import { parseFormattedCell } from "$lib/cellset/cellFormat";
  // Issue #930 — right-click a measure cell to drill into its raw fact rows.
  import TileDrillthrough from "./TileDrillthrough.svelte";
  import { drillthroughPosition } from "$lib/dashboard/drillthroughCoord";
  // Issue #919 — per-column conditional formatting (display-only).
  import {
    formatCell,
    cellFormatToStyle,
    type CellFormat,
  } from "$lib/dashboard/conditionalFormat";
  // Issue #920 — opt-in inline sparkline column (display-only). Pure
  // geometry lives in $lib/dashboard/sparkline; this component only
  // collects each row's numeric measure values and renders the SVG.
  import { sparklineGeometry, sparklineBars, numericValues } from "$lib/dashboard/sparkline";
  // Issue #933 — shared loading / error / empty states.
  import TileLoading from "./TileLoading.svelte";
  import TileError from "./TileError.svelte";
  import TileEmpty from "./TileEmpty.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  // #941 share viewer (PR2): render from a prefetched guest response when present.
  import { getShareViewResponse } from "$lib/dashboard/shareViewContext";
  // Issue #931 — per-tile auto-refresh: timer wiring + "last updated" indicator.
  import { isAutoRefreshOn } from "$lib/dashboard/autoRefresh";
  import TileRefreshIndicator from "./TileRefreshIndicator.svelte";

  interface Props {
    tile: DashboardTile;
    /** Called with the dim/hier/level/members context when the user
     *  clicks a row-header cell. Parent (Tile.svelte / grid) pushes the
     *  resulting ActiveFilter onto the active-filter set. */
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  // Non-null only inside the share viewer (getContext at init); see fetch effect.
  // svelte-ignore state_referenced_locally
  const sharedResponse = getShareViewResponse(tile.id);

  // #1231 — shared fetch lifecycle (loading / error / response / dedupe
  // cache / retry tick / refresh tick / auto-refresh) lives on
  // TileQueryState. Local $derived aliases keep the rest of the script +
  // template reading off local names; assignments only happen inside
  // runTileQueryEffect.
  const q = new TileQueryState();
  let response = $derived(q.response);
  let loading = $derived(q.loading);
  let error = $derived(q.error);
  const auto = q.auto;
  function retry(): void {
    q.retry();
  }
  let schema = $state<SchemaLike | null>(null);

  let hasEffectiveFilters = $derived(
    activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0),
  );
  function resetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

  /* --- issue #931: auto-refresh ------------------------------------------
   * Per-tile timer re-fires the existing (filter-aware) fetch on the
   * configured cadence by clearing the dedupe cache + bumping a tick the
   * fetch effect reads (same mechanism as retry()), so the re-run uses the
   * CURRENT effective query. Never auto-refreshes in the share viewer. */
  let autoRefreshOn = $derived(!sharedResponse && isAutoRefreshOn(tile.refreshInterval));
  $effect(() => auto.arm(tile.refreshInterval, () => q.triggerAutoRefresh()));
  $effect(() => {
    if (autoRefreshOn && auto.lastUpdated > 0) return auto.startHeartbeat();
  });
  /* --- end issue #931 block ---------------------------------------------- */

  // Resolved cube — usually tile.cube directly, but reference tiles
  // authored before saiku#878 left tile.cube undefined. Lazy-infer from
  // the saved ThinQuery so the schema-driven filter applicability still
  // works on existing dashboards.
  let resolvedCube = $state<CubeRef | null>(null);
  let inferenceAttempted = $state(false);
  // Row axes lazy-inferred from the saved ThinQuery on reference tiles.
  // clickFilterForCell uses them in place of `tile.query.body.rows`.
  let referenceRowAxes = $state<RowAxisRef[] | null>(null);

  $effect(() => {
    if (tile.cube) resolvedCube = tile.cube;
    // Row axes still need inferring for reference tiles even when the
    // analyst picked the cube explicitly — click-to-filter needs the
    // saved ThinQuery's axes regardless. Skip only when the tile is
    // inline (axes live on the body directly).
    if (tile.query?.kind !== "reference" || inferenceAttempted) return;
    inferenceAttempted = true;
    const refPath = tile.query.path;
    if (!tile.cube) {
      void inferCubeFromReference(refPath).then((inferred) => {
        if (inferred) resolvedCube = inferred;
      });
    }
    void inferRowAxesFromReference(refPath).then((axes) => {
      referenceRowAxes = axes;
    });
  });

  $effect(() => {
    const v = schemaCache.version; // dep
    void v;
    if (!resolvedCube) {
      schema = null;
      return;
    }
    const cached = schemaCache.peek(resolvedCube) as SchemaLike | null;
    if (cached) {
      schema = cached;
    } else {
      void schemaCache.get(resolvedCube).catch(() => {});
    }
  });

  // Recompute the effective query whenever the active filter set or
  // schema changes; refetch on identity change.
  $effect(() => {
    void q.retryTick;
    void q.refreshTick;
    runTileQueryEffect(q, {
      tile,
      activeFilters: activeFilters.all,
      schema,
      sharedResponse: sharedResponse ?? null,
    });
  });

  // Derived: a flat list of column captions (row headers first, then
  // measures) so the table render can iterate by index.
  let columns = $derived<{ caption: string; isRowHeader: boolean }[]>((() => {
    if (!response?.metadata) return [];
    const rowHeader: { caption: string; isRowHeader: boolean }[] = [];
    // The first data row tells us which columns are row-headers (plain
    // strings) vs measures (AiCell). We pick the row-header captions
    // from the first data row's plain-string keys, in insertion order.
    const firstRow = response.data?.[0];
    if (firstRow) {
      for (const key of Object.keys(firstRow)) {
        if (!isAiCell(firstRow[key])) {
          rowHeader.push({ caption: key, isRowHeader: true });
        }
      }
    }
    const measures = (response.metadata.columns ?? []).map((c) => ({
      caption: c.caption,
      isRowHeader: false,
    }));
    return [...rowHeader, ...measures];
  })());

  // Issue #919 — conditional formatting. Per-column the rule engine needs
  // the column's full set of raw numeric values to compute percentile
  // bands and bar widths. Build a caption → values[] index over the
  // current response, using AiCell.value when present (the un-formatted
  // number) and falling back to the plain string for row-header columns
  // (which the engine then coerces / ignores). Recomputed whenever the
  // response changes.
  let columnValues = $derived<Record<string, unknown[]>>((() => {
    const rules = tile.conditionalFormat;
    if (!rules || rules.length === 0) return {};
    const rows = response?.data ?? [];
    const index: Record<string, unknown[]> = {};
    for (const rule of rules) {
      const vals: unknown[] = [];
      for (const row of rows) {
        const cell = row[rule.column];
        vals.push(isAiCell(cell) ? cell.value : cell);
      }
      index[rule.column] = vals;
    }
    return index;
  })());

  /** The un-formatted numeric source for a cell — AiCell.value when the
   *  cell is a measure, else the raw string (engine coerces / ignores). */
  function cellNumericSource(v: AiCell | string | undefined): unknown {
    return isAiCell(v) ? v.value : v;
  }

  /** Compute the conditional-format result for one cell. Returns an empty
   *  CellFormat when no rule targets the column (the common path). */
  function cellFormatFor(column: string, v: AiCell | string | undefined): CellFormat {
    const rules = tile.conditionalFormat;
    if (!rules || rules.length === 0) return {};
    return formatCell(rules, column, cellNumericSource(v), columnValues[column] ?? []);
  }

  // #1166: per-(cube/dim/hier/level/q) cache of the level's members so a
  // repeated click on the same column doesn't re-hit /ai/members/search.
  const clickMemberCache = new Map<string, Promise<{ uniqueName: string; caption: string }[]>>();

  /** Resolve the click-filter context for a row-header cell. The dashboard
   *  level for the click is inferred from the tile's base query — we match
   *  the column caption against the row-axis level captions — and we collect
   *  the row's parent-level captions as the ancestor path so an ambiguous
   *  leaf (e.g. Quarter "Q2", which exists under every Year) can be pinned
   *  to the right member.
   *
   *  #1166: we no longer hand-assemble the member unique name here. The
   *  tile's stored dim/hier may be display aliases (e.g. hierarchy "Products"
   *  where the cube's real hierarchy is "Product"), so a constructed
   *  "[Product].[Products].[Beer]" fails on the server with "Unable to find
   *  a member". Instead the caller asks the server for the level's real
   *  members and matches this caption path against them. */
  function clickFilterContext(
    column: string,
    rowData: Record<string, AiCell | string>,
  ): { dimension: string; hierarchy: string; level: string; captionPath: string[] } | null {
    if (!tile.query) return null;
    let rows: Array<{ dimension: string; hierarchy: string; level: string }> | null = null;
    if (tile.query.kind === "inline") {
      const body = tile.query.body as {
        rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
      };
      rows = body.rows ?? null;
    } else if (tile.query.kind === "reference") {
      rows = referenceRowAxes;
    }
    if (!rows) return null;
    const matchIdx = rows.findIndex((ax) => ax.level === column);
    if (matchIdx === -1) return null;
    const match = rows[matchIdx];

    // Walk the row axes from coarsest to finest, collecting captions for
    // every level in the same dim+hier up to and including the clicked one.
    const captionPath: string[] = [];
    for (let i = 0; i <= matchIdx; i++) {
      const ax = rows[i];
      if (ax.dimension !== match.dimension || ax.hierarchy !== match.hierarchy) continue;
      const cell = rowData[ax.level];
      const cap = typeof cell === "string" ? cell : (cell?.formatted ?? "");
      if (!cap) return null;
      captionPath.push(cap);
    }
    if (captionPath.length === 0) return null;
    return { dimension: match.dimension, hierarchy: match.hierarchy, level: match.level, captionPath };
  }

  function handleCellClick(
    column: string,
    cellValue: string,
    isRowHeader: boolean,
    rowData: Record<string, AiCell | string>,
  ): void {
    if (!isRowHeader || !onClickFilter) return;
    const ctx = clickFilterContext(column, rowData);
    if (!ctx) return;
    const cube = resolvedCube;
    if (!cube) return;

    const { dimension, hierarchy, level, captionPath } = ctx;
    const leaf = captionPath[captionPath.length - 1];
    const cacheKey = `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}|${dimension}/${hierarchy}/${level}|${leaf}`;
    let lookup = clickMemberCache.get(cacheKey);
    if (!lookup) {
      lookup = searchMembers(cube, dimension, hierarchy, level, leaf);
      clickMemberCache.set(cacheKey, lookup);
    }
    void lookup.then((hits) => {
      const uniqueName = pickMemberUniqueNameForPath(hits, captionPath);
      if (!uniqueName) {
        clickMemberCache.delete(cacheKey); // don't cache a miss — allow a retry
        console.warn(
          `[saiku] click-to-filter: no member matched "${captionPath.join(" › ")}" in ${dimension}/${level}`,
        );
        return;
      }
      const filter: DashboardFilter = { dimension, hierarchy, level, members: [uniqueName] };
      onClickFilter?.(filter);
    });
  }

  function renderCell(v: AiCell | string): string {
    if (typeof v === "string") return v;
    return v.formatted ?? "";
  }

  /* ----------------------- sparkline column (#920) -------------------- */

  // Opt-in trailing column that draws a tiny inline trend per row from the
  // row's numeric measure cells. Display-only; the geometry maths lives in
  // $lib/dashboard/sparkline. Needs >= 2 measure columns to be meaningful.
  let sparklineEnabled = $derived(
    tile.type === "table" &&
      (tile.sparkline?.enabled ?? false) &&
      (response?.metadata?.columns?.length ?? 0) >= 2,
  );
  let sparklineType = $derived<"line" | "bar">(tile.sparkline?.type ?? "line");

  /** A row's numeric measure values, in measure-column order. Row-header
   *  columns are excluded — only response.metadata.columns (the measures)
   *  feed the trend. Non-numeric measure cells become null and are dropped
   *  by the geometry helper. */
  function rowMeasureValues(row: Record<string, AiCell | string>): unknown[] {
    const cols = response?.metadata?.columns ?? [];
    return cols.map((c) => {
      const cell = row[c.caption];
      return isAiCell(cell) ? cell.value : cell;
    });
  }

  /** Bar geometry for a row, using the same default viewBox as
   *  sparklineGeometry so the bars line up with the svg's 0 0 100 24 box.
   *  Only the finite measure values feed the bars (non-numeric dropped). */
  function sparklineBarsFor(row: Record<string, AiCell | string>) {
    return sparklineBars(numericValues(rowMeasureValues(row)));
  }

  /* --------------------------- drillthrough (#930) -------------------- */

  let drill = $state<TileDrillthrough | null>(null);

  /** Map a measure caption to its 0-based index among the result's measure
   *  columns (response.metadata.columns) — the column index the backend
   *  drillthrough convention expects. Returns -1 for row-header columns. */
  function measureColumnIndex(caption: string): number {
    const cols = response?.metadata?.columns ?? [];
    return cols.findIndex((c) => c.caption === caption);
  }

  // Right-click a measure cell → drill into the raw fact rows behind it.
  // Verified backend convention is "{col}:{row}", column first. No-op on
  // row-header cells (those keep their left-click click-to-filter) and
  // while the tile has no successful response/queryId yet.
  function handleCellContextMenu(
    e: MouseEvent,
    column: string,
    isRowHeader: boolean,
    rowIndex: number,
  ): void {
    if (isRowHeader) return;
    const queryId = response?.queryId;
    if (!queryId || response?.status !== "SUCCESS") return;
    const colIndex = measureColumnIndex(column);
    const position = drillthroughPosition(colIndex, rowIndex);
    if (!position) return;
    // Only suppress the native menu once we know we're handling the drill.
    e.preventDefault();
    void drill?.open(queryId, position);
  }
</script>

{#if !tile.query}
  <div class="p-4 text-fg-muted text-sm">Tile has no query binding — open ⚙ to set one.</div>
{:else}
  <div class="table-tile" role="region" aria-label="Table tile">
    {#if loading && !response}
      <TileLoading variant="table" />
    {:else if error}
      <TileError message={error} onRetry={retry} />
    {:else if response && response.status === "SUCCESS"}
      {@const rows = response.data ?? []}
      {#if rows.length === 0}
        <TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} />
      {:else}
        <table>
          <thead>
            <tr>
              {#each columns as col, ci (ci)}
                <th class:row-header={col.isRowHeader}>{col.caption}</th>
              {/each}
              {#if sparklineEnabled}
                <th class="spark-header">Trend</th>
              {/if}
            </tr>
          </thead>
          <tbody>
            {#each rows as row, i (i)}
              <tr>
                {#each columns as col, ci (ci)}
                  {@const v = row[col.caption]}
                  {@const fmt = parseFormattedCell(renderCell(v))}
                  {@const cf = cellFormatFor(col.caption, v)}
                  {@const cfStyle = cellFormatToStyle(cf)}
                  <!-- svelte-ignore a11y_no_static_element_interactions
                       Right-click drillthrough (#930) is a power-user
                       shortcut on measure cells; the kebab/overflow drill
                       affordances on the tile remain the keyboard-accessible
                       path, so handling contextmenu on a plain <td> adds no
                       a11y regression. Row-header cells keep their existing
                       button role + Enter/Space keyboard activation. -->
                  <td
                    class:row-header={col.isRowHeader}
                    class:clickable={col.isRowHeader}
                    style={[fmt.color && !cf.color ? `color: ${fmt.color}` : undefined, cfStyle]
                      .filter(Boolean)
                      .join("; ") || undefined}
                    onclick={() => handleCellClick(col.caption, fmt.display, col.isRowHeader, row)}
                    oncontextmenu={(e) =>
                      handleCellContextMenu(e, col.caption, col.isRowHeader, i)}
                    role={col.isRowHeader ? "button" : undefined}
                    tabindex={col.isRowHeader ? 0 : undefined}
                    onkeydown={(e) => {
                      if (col.isRowHeader && (e.key === "Enter" || e.key === " ")) {
                        e.preventDefault();
                        handleCellClick(col.caption, fmt.display, col.isRowHeader, row);
                      }
                    }}
                  >
                    {#if cf.icon}<span class="inline-block mr-1 font-semibold" aria-hidden="true">{cf.icon}</span>{/if}{fmt.display}
                  </td>
                {/each}
                {#if sparklineEnabled}
                  {@const g = sparklineGeometry(rowMeasureValues(row))}
                  <td class="spark-cell">
                    {#if g.renderable}
                      <svg
                        class="spark"
                        viewBox={`0 0 ${g.width} ${g.height}`}
                        width={g.width}
                        height={g.height}
                        preserveAspectRatio="none"
                        role="img"
                        aria-label={`Trend across ${g.points.length} measures, min ${g.min}, max ${g.max}`}
                      >
                        {#if sparklineType === "bar"}
                          {#each sparklineBarsFor(row) as bar (bar.x)}
                            <rect
                              x={bar.x}
                              y={bar.y}
                              width={bar.width}
                              height={bar.height}
                              fill="var(--accent)"
                            />
                          {/each}
                        {:else}
                          <polyline
                            points={g.polyline}
                            fill="none"
                            stroke="var(--accent)"
                            stroke-width="1.25"
                            stroke-linejoin="round"
                            stroke-linecap="round"
                            vector-effect="non-scaling-stroke"
                          />
                          {#if g.last}
                            <circle cx={g.last.x} cy={g.last.y} r="1.5" fill="var(--accent)" />
                          {/if}
                        {/if}
                      </svg>
                    {:else}
                      <span class="text-fg-muted" aria-hidden="true">—</span>
                    {/if}
                  </td>
                {/if}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    {/if}
    {#if autoRefreshOn && auto.lastUpdated > 0}
      <!-- #931: auto-refresh "last updated" badge + spinning icon. -->
      <div class="refresh-badge">
        <TileRefreshIndicator lastUpdated={auto.lastUpdated} spinning={loading} now={auto.now} />
      </div>
    {/if}
  </div>
{/if}

<TileDrillthrough bind:this={drill} cube={resolvedCube} />

<style>
.table-tile {
    height: 100%;
    overflow: auto;
    padding: 0.25rem 0.5rem;
    /* #931: anchor the auto-refresh badge to the tile box. */
    position: relative;
  }
  /* #931: auto-refresh badge — pinned bottom-right, sticky so it stays put
     while the table body scrolls; click-through so it never blocks cells. */
  .refresh-badge {
    position: sticky;
    bottom: 0.25rem;
    float: right;
    margin-right: 0.25rem;
    z-index: 2;
    background: color-mix(in srgb, var(--bg) 80%, transparent);
    border-radius: 4px;
    padding: 0.0625rem 0.25rem;
    pointer-events: none;
    max-width: calc(100% - 0.5rem);
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.8125rem;
  }
  th, td {
    padding: 0.25rem 0.5rem;
    text-align: right;
    border-bottom: 1px solid var(--border);
  }
  th {
    background: var(--bg-muted);
    font-weight: var(--weight-semibold);
    position: sticky;
    top: 0;
    z-index: 1;
  }
  th.row-header, td.row-header {
    text-align: left;
  }
  td.clickable {
    cursor: pointer;
  }
  td.clickable:hover {
    background: var(--bg-subtle);
  }
  td.clickable:focus {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
  /* Issue #919 — conditional-format icon glyph prepended to a cell. */
  /* Issue #920 — inline sparkline column. */
  th.spark-header {
    text-align: center;
  }
  td.spark-cell {
    text-align: center;
    padding: 0.125rem 0.5rem;
    white-space: nowrap;
  }
  svg.spark {
    display: block;
    width: 64px;
    height: 18px;
    color: var(--accent);
  }
</style>
