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
    executeAiQuery,
    executeSavedQuery,
    isAiCell,
    type AiQueryResponse,
    type AiCell,
  } from "$lib/api/aiQuery";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    effectiveQueryFor,
    applicableSavedFilters,
    type SchemaLike,
  } from "$lib/dashboard/effectiveQuery";
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
  // Issue #933 — shared loading / error / empty states.
  import TileLoading from "./TileLoading.svelte";
  import TileError from "./TileError.svelte";
  import TileEmpty from "./TileEmpty.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";

  interface Props {
    tile: DashboardTile;
    /** Called with the dim/hier/level/members context when the user
     *  clicks a row-header cell. Parent (Tile.svelte / grid) pushes the
     *  resulting ActiveFilter onto the active-filter set. */
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);
  let schema = $state<SchemaLike | null>(null);

  // Issue #933 — retry re-fires the deduped fetch effect; hasEffectiveFilters
  // gates the empty-state "Reset filters" affordance.
  let retryTick = $state(0);
  let hasEffectiveFilters = $derived(
    activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0),
  );
  function retry(): void {
    lastQueryJson = "";
    retryTick++;
  }
  function resetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

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
  let lastQueryJson = $state<string>("");

  $effect(() => {
    const tileQuery = tile.query;
    const active = activeFilters.all;
    const s = schema;
    void s;
    // #933 — retry() bumps this to force a refetch with unchanged inputs.
    void retryTick;
    if (!tileQuery) return;

    if (tileQuery.kind === "reference") {
      // Reference tile: server loads the saved ThinQuery, merges any
      // applicable dashboard filters onto it via ThinQueryFilterMerge,
      // then runs it. Filter applicability is checked client-side first
      // against the tile's cube schema so we don't ship filters the
      // server would have to drop anyway.
      const refFilters = applicableSavedFilters(schema, active);
      const key = `ref:${tileQuery.path}|${JSON.stringify(refFilters)}`;
      if (key === lastQueryJson) return;
      lastQueryJson = key;
      loading = true;
      error = null;
      void (async () => {
        try {
          const r = await executeSavedQuery(
            tileQuery.path,
            refFilters.map((f) => ({
              dimension: f.dimension,
              hierarchy: f.hierarchy,
              level: f.level,
              members: f.members ?? [],
            })),
          );
          response = r;
          if (r.status !== "SUCCESS") error = r.error ?? `Query failed: ${r.status}`;
        } catch (e: unknown) {
          error = e instanceof Error ? e.message : String(e);
          response = null;
        } finally {
          loading = false;
        }
      })();
      return;
    }

    // Inline tile: merge active filters into the base body via the
    // effective-query builder, then POST to /ai/query.
    const effective = effectiveQueryFor(tile, active, schema);
    if (!effective) return;
    const json = JSON.stringify(effective);
    if (json === lastQueryJson) return; // no-op; avoid duplicate fetches
    lastQueryJson = json;

    loading = true;
    error = null;
    void (async () => {
      try {
        const r = await executeAiQuery(effective, "records");
        response = r;
        if (r.status !== "SUCCESS") {
          error = r.error ?? `Query failed: ${r.status}`;
        }
      } catch (e: unknown) {
        error = e instanceof Error ? e.message : String(e);
        response = null;
      } finally {
        loading = false;
      }
    })();
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

  /** Build the click-filter context for a row-header cell. The dashboard
   *  level for the click is inferred from the tile's base query — we
   *  match the column caption against the row-axis level captions —
   *  and the member ref is constructed as a full MDX unique name from
   *  the row's parent-level captions so Mondrian can resolve an
   *  ambiguous leaf (e.g. Quarter "Q2" which exists under every Year).
   *
   *  Without the parent path, the server-side resolver fails with
   *  "Unable to find a member with name [Q2]" because Q2 of 1997 and
   *  Q2 of 1998 collide. */
  function clickFilterForCell(
    column: string,
    cellValue: string,
    rowData: Record<string, AiCell | string>,
  ): DashboardFilter | null {
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

    // Walk the row axes from coarsest to finest, collecting captions
    // for every level that sits in the same dim+hier as the clicked
    // column up to and including the clicked level. Those captions
    // become the dotted path in the constructed unique name.
    const pathSegments: string[] = [];
    for (let i = 0; i <= matchIdx; i++) {
      const ax = rows[i];
      if (ax.dimension !== match.dimension || ax.hierarchy !== match.hierarchy) continue;
      const cell = rowData[ax.level];
      const cap = typeof cell === "string" ? cell : (cell?.formatted ?? "");
      if (!cap) return null;
      pathSegments.push(cap);
    }
    // Mondrian unique-name format: [<dim>].[<hier>].[v1].[v2]…[vk].
    // Captions wrapped in brackets verbatim; Saiku's existing schemas
    // use bracket-quoted segments so spaces / dots are tolerated.
    const uniqueName = `[${match.dimension}].[${match.hierarchy}].${pathSegments
      .map((s) => `[${s}]`)
      .join(".")}`;
    return {
      dimension: match.dimension,
      hierarchy: match.hierarchy,
      level: match.level,
      members: [uniqueName],
    };
  }

  function handleCellClick(
    column: string,
    cellValue: string,
    isRowHeader: boolean,
    rowData: Record<string, AiCell | string>,
  ): void {
    if (!isRowHeader) return;
    const filter = clickFilterForCell(column, cellValue, rowData);
    if (!filter) return;
    onClickFilter?.(filter);
  }

  function renderCell(v: AiCell | string): string {
    if (typeof v === "string") return v;
    return v.formatted ?? "";
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
  <div class="placeholder">Tile has no query binding — open ⚙ to set one.</div>
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
              {#each columns as col (col.caption)}
                <th class:row-header={col.isRowHeader}>{col.caption}</th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each rows as row, i (i)}
              <tr>
                {#each columns as col (col.caption)}
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
                    {#if cf.icon}<span class="cf-icon" aria-hidden="true">{cf.icon}</span>{/if}{fmt.display}
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    {/if}
  </div>
{/if}

<TileDrillthrough bind:this={drill} cube={resolvedCube} />

<style>
  .table-tile {
    height: 100%;
    overflow: auto;
    padding: 0.25rem 0.5rem;
  }
  .placeholder {
    padding: 1rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
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
  .cf-icon {
    display: inline-block;
    margin-right: 0.25rem;
    font-weight: var(--weight-semibold);
  }
</style>
