<script lang="ts">
  /*
   * Shared grid renderer for the embed surface. Renders ONE dashboard-style
   * layout ({cols, tiles}) read-only and issues one query per query-backed tile
   * (chart / kpi) through an injected `fetchTile` callback. Filter tiles populate
   * their dropdown through an injected `fetchMembers` callback and post their
   * selection back onto the local filter bus so dependent tiles refetch.
   *
   * The layout shape is identical for a saved dashboard AND for a single App
   * Builder page, so both EmbedDashboard and EmbedApp render through this one
   * component — the ONLY difference is which token-scoped endpoint the injected
   * fetchers call. That keeps the app embed purely presentational over the exact
   * same guarded query/embed path the dashboard embed uses.
   *
   * v1 tile dispatch (unchanged from the original EmbedDashboard):
   *   text   — single paragraph render (no Markdown parser)
   *   chart  — EmbedChart with tile.chartType as `mode`
   *   kpi    — first row + first numeric column, big number
   *   filter — EmbedFilterTile driving the local filter bus
   *   other  — friendly placeholder
   */
  import type { EmbedFilterOverride, EmbedMember } from "./api";
  import type { EmbedDashboardTile, EmbedQueryResponse, EmbedRow } from "./types";
  import EmbedChart from "./EmbedChart.svelte";
  import EmbedFilterTile from "./EmbedFilterTile.svelte";

  interface Props {
    /** {cols, tiles} — a dashboard layout or a single app page's grid. */
    layout: { cols?: number; tiles?: EmbedDashboardTile[] } | null | undefined;
    /** Run one tile's authored query (token-scoped). Overrides ride the same
     *  validated slicer path; the tile body itself is never client-supplied. */
    fetchTile: (tileId: string, overrides: EmbedFilterOverride[]) => Promise<EmbedQueryResponse>;
    /** Populate a filter tile's member dropdown (token-scoped, pinned axis). */
    fetchMembers: (tileId: string, q?: string, limit?: number) => Promise<EmbedMember[]>;
  }

  let { layout, fetchTile, fetchMembers }: Props = $props();

  /** tile.id → query result (chart / kpi tiles only). null = pending. */
  let tileData = $state<Record<string, EmbedRow[] | null>>({});

  /** Filter-tile bus. Each filter tile posts its current selection here (or
   *  clears with null); dependent tiles refetch with the merged overrides. */
  let filterOverrides = $state<Record<string, EmbedFilterOverride | null>>({});

  let mergedOverrides = $derived(
    Object.values(filterOverrides).filter((o): o is EmbedFilterOverride => o !== null),
  );

  const hasEverFilteredRef = { current: false };

  function isQueryable(t: EmbedDashboardTile): boolean {
    return t.type === "chart" || t.type === "kpi";
  }

  /** Kick off one tile fetch per queryable tile — parallel, no cross-tile
   *  dependency. Called on initial layout load and on every filter change. */
  function loadQueryableTiles(
    tiles: EmbedDashboardTile[],
    overrides: EmbedFilterOverride[],
    cancelledRef: { current: boolean },
  ): void {
    for (const tile of tiles) {
      if (!isQueryable(tile)) continue;
      tileData = { ...tileData, [tile.id]: null };
      fetchTile(tile.id, overrides)
        .then((res) => {
          if (cancelledRef.current) return;
          tileData = { ...tileData, [tile.id]: res.data ?? [] };
        })
        .catch(() => {
          if (cancelledRef.current) return;
          tileData = { ...tileData, [tile.id]: [] };
        });
    }
  }

  // Initial load whenever the layout (page) changes. Resets the filter bus so a
  // page switch doesn't carry a prior page's filters or stale tile data.
  $effect(() => {
    const tiles = layout?.tiles ?? [];
    const cancelledRef = { current: false };
    tileData = {};
    filterOverrides = {};
    hasEverFilteredRef.current = false;
    loadQueryableTiles(tiles, [], cancelledRef);
    return () => {
      cancelledRef.current = true;
    };
  });

  // Re-fetch every queryable tile whenever the merged override set changes.
  // Skips the initial "empty overrides" re-fire — the load effect already
  // fetched every tile with no overrides.
  $effect(() => {
    const overrides = mergedOverrides;
    const tiles = layout?.tiles ?? [];
    if (overrides.length === 0 && !hasEverFilteredRef.current) return;
    hasEverFilteredRef.current = hasEverFilteredRef.current || overrides.length > 0;
    loadQueryableTiles(tiles, overrides, { current: false });
  });

  function onFilterChange(tileId: string, override: EmbedFilterOverride | null): void {
    filterOverrides = { ...filterOverrides, [tileId]: override };
  }

  /* Map abstract grid cells (cols × rows) to CSS Grid. `cols` defaults to 12;
   * `h=1` ≈ 60 px matches the workbench default row height. */
  let gridCols = $derived(layout?.cols ?? 12);
  const ROW_HEIGHT_PX = 60;

  /** Single big-number summary for a KPI tile — first row, first numeric
   *  column. Falls back to "-" if the result is empty. */
  function kpiNumber(rows: EmbedRow[] | null | undefined, tile: EmbedDashboardTile): string {
    if (!rows || rows.length === 0) return "-";
    const first = rows[0];
    const namedKey = tile.kpi?.measureCaption ?? tile.kpi?.measure;
    if (namedKey && first[namedKey]?.formatted) return first[namedKey].formatted;
    for (const k of Object.keys(first)) {
      const cell = first[k];
      if (cell?.value !== null && typeof cell?.value === "number") {
        return cell.formatted;
      }
    }
    return "-";
  }
</script>

<div
  class="grid gap-3 p-3 w-full box-border"
  style="
    grid-template-columns: repeat({gridCols}, minmax(0, 1fr));
    grid-auto-rows: {ROW_HEIGHT_PX}px;
  "
>
  {#each layout?.tiles ?? [] as tile (tile.id)}
    <div
      class="tile"
      style="
        grid-column: {tile.x + 1} / span {tile.w};
        grid-row: {tile.y + 1} / span {tile.h};
      "
    >
      {#if tile.title}
        <header>{tile.title}</header>
      {/if}
      <div class="body">
        {#if tile.type === "text"}
          <p>{tile.text ?? ""}</p>
        {:else if tile.type === "kpi"}
          <div class="kpi">{kpiNumber(tileData[tile.id], tile)}</div>
        {:else if tile.type === "chart"}
          {#if tileData[tile.id] === undefined || tileData[tile.id] === null}
            <div class="state muted">Loading…</div>
          {:else if tileData[tile.id]!.length === 0}
            <div class="state muted">No data</div>
          {:else}
            <EmbedChart rows={tileData[tile.id]!} mode={tile.chartType ?? "bar"} />
          {/if}
        {:else if tile.type === "filter"}
          <EmbedFilterTile
            {tile}
            fetchMembers={(q, limit) => fetchMembers(tile.id, q, limit)}
            onChange={(o) => onFilterChange(tile.id, o)}
          />
        {:else}
          <div class="state muted">Unsupported tile</div>
        {/if}
      </div>
    </div>
  {/each}
</div>

<style>
  .tile {
    background: var(--saiku-embed-tile-bg, #ffffff);
    border: 1px solid var(--saiku-embed-border, #e5e7eb);
    border-radius: 8px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    min-height: 0; /* let chart fill, not push */
  }
  .tile header {
    padding: 8px 12px;
    font-weight: 600;
    font-size: 13px;
    border-bottom: 1px solid var(--saiku-embed-border, #e5e7eb);
    color: var(--saiku-embed-fg, #1f2937);
    font-family: system-ui, sans-serif;
  }
  .tile .body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }
  .tile .body p {
    padding: 8px 12px;
    margin: 0;
    font-family: system-ui, sans-serif;
    font-size: 13px;
    white-space: pre-wrap;
    color: var(--saiku-embed-fg, #1f2937);
  }
  .kpi {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 32px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    color: var(--saiku-embed-fg, #1f2937);
    font-family: system-ui, sans-serif;
  }
  .state {
    padding: 12px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
</style>
