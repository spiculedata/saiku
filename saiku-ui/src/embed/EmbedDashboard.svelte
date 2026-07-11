<script lang="ts">
  /*
   * Dashboard renderer for the embed surface — fetches /embed/dashboard/{path}
   * once for the layout, then one /tile/{id}/query per query-backed tile
   * (chart / kpi) running under the owner's data scope.
   *
   * v1 tile dispatch:
   *   text   — single paragraph render (no Markdown parser — keeps the
   *            bundle tight; future PR can swap in `marked` if needed)
   *   chart  — EmbedChart with the tile.chartType as `mode`
   *   kpi    — first row + first numeric column, big number
   *   filter — skipped (filters are a UI thing in the workbench; an
   *            embedded dashboard renders the data as-is for v1)
   *   other  — friendly placeholder so a server-side tile-type addition
   *            doesn't break this bundle
   */
  import { fetchDashboard, fetchDashboardTile, EmbedFetchError, type EmbedFilterOverride } from "./api";
  import type { EmbedDashboardLayout, EmbedDashboardTile, EmbedRow } from "./types";
  import EmbedChart from "./EmbedChart.svelte";
  import EmbedFilterTile from "./EmbedFilterTile.svelte";

  interface Props {
    server: string;
    token: string;
    path: string;
  }

  let { server, token, path }: Props = $props();

  let dashboard = $state<EmbedDashboardLayout | null>(null);
  let error = $state<string | null>(null);
  let loading = $state(false);

  /** tile.id → query result (chart / kpi tiles only). null = pending. */
  let tileData = $state<Record<string, EmbedRow[] | null>>({});

  /** Filter-tile bus. Each filter tile posts its current selection here
   *  (or clears with null); dependent tiles refetch with the merged overrides. */
  let filterOverrides = $state<Record<string, EmbedFilterOverride | null>>({});

  let mergedOverrides = $derived(
    Object.values(filterOverrides).filter((o): o is EmbedFilterOverride => o !== null),
  );

  $effect(() => {
    const s = server.trim();
    const p = path.trim();
    const t = token.trim();
    if (!s || !p) {
      dashboard = null;
      error = null;
      return;
    }
    let cancelled = false;
    loading = true;
    error = null;
    dashboard = null;
    tileData = {};
    fetchDashboard(s, p, t || undefined)
      .then((dash) => {
        if (cancelled) return;
        dashboard = dash;
        loadQueryableTiles(dash, s, p, t, cancelled, []);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        error = friendlyError(e);
      })
      .finally(() => {
        if (!cancelled) loading = false;
      });
    return () => {
      cancelled = true;
    };
  });

  function isQueryable(t: EmbedDashboardTile): boolean {
    return t.type === "chart" || t.type === "kpi";
  }

  /** Kick off one tile fetch per queryable tile — parallel, no cross-tile
   *  dependency. Called both on initial dashboard load and whenever the
   *  filter override set changes. */
  function loadQueryableTiles(
    dash: EmbedDashboardLayout,
    s: string,
    p: string,
    t: string,
    cancelled: boolean,
    overrides: EmbedFilterOverride[],
  ): void {
    for (const tile of dash.layout?.tiles ?? []) {
      if (!isQueryable(tile)) continue;
      tileData = { ...tileData, [tile.id]: null };
      fetchDashboardTile(s, p, tile.id, t || undefined, overrides)
        .then((res) => {
          if (cancelled) return;
          tileData = { ...tileData, [tile.id]: res.data ?? [] };
        })
        .catch(() => {
          if (cancelled) return;
          tileData = { ...tileData, [tile.id]: [] };
        });
    }
  }

  /** Re-fetch every queryable tile whenever the merged override set changes.
   *  We deliberately don't chain this off the initial dashboard-load $effect
   *  above — that one runs once per (server, path, token); this one runs on
   *  every filter selection change once the dashboard is loaded. */
  $effect(() => {
    // Read mergedOverrides so Svelte picks up the dep.
    const overrides = mergedOverrides;
    const s = server.trim();
    const p = path.trim();
    const t = token.trim();
    if (!dashboard || !s || !p) return;
    // Skip the initial "empty overrides" re-fire — the dashboard-load effect
    // already fetched every tile with no overrides.
    if (overrides.length === 0 && !hasEverFilteredRef.current) return;
    hasEverFilteredRef.current = hasEverFilteredRef.current || overrides.length > 0;
    loadQueryableTiles(dashboard, s, p, t, false, overrides);
  });

  const hasEverFilteredRef = { current: false };

  function onFilterChange(tileId: string, override: EmbedFilterOverride | null): void {
    filterOverrides = { ...filterOverrides, [tileId]: override };
  }

  function friendlyError(e: unknown): string {
    if (e instanceof EmbedFetchError) {
      if (e.status === 401) return "This embed is unavailable.";
      return e.body.error ?? `Embed failed (${e.status}).`;
    }
    return "Embed failed to load.";
  }

  /* Map abstract grid cells (cols × rows) to CSS Grid. `cols` defaults
   * to 12 if the dashboard JSON doesn't specify; `h=1` ≈ 60 px matches
   * the SvelteKit workbench's default row height so visual fidelity
   * round-trips. */
  let gridCols = $derived(dashboard?.layout?.cols ?? 12);
  const ROW_HEIGHT_PX = 60;

  /** Single big-number summary for a KPI tile — first row, first
   *  numeric column. Falls back to "-" if the result is empty. */
  function kpiNumber(rows: EmbedRow[] | null | undefined, tile: EmbedDashboardTile): string {
    if (!rows || rows.length === 0) return "-";
    const first = rows[0];
    // Prefer the column named by the KPI definition; otherwise the
    // first numeric column in the row.
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

{#if loading && !dashboard}
  <div class="state">Loading dashboard…</div>
{:else if error}
  <div class="state error" role="alert">{error}</div>
{:else if dashboard}
  <div
    class="grid gap-3 p-3 w-full box-border"
    style="
      grid-template-columns: repeat({gridCols}, minmax(0, 1fr));
      grid-auto-rows: {ROW_HEIGHT_PX}px;
    "
  >
    {#each dashboard.layout.tiles as tile (tile.id)}
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
            {#if tileData[tile.id] === undefined}
              <div class="state muted">Loading…</div>
            {:else if tileData[tile.id]!.length === 0}
              <div class="state muted">No data</div>
            {:else}
              <EmbedChart rows={tileData[tile.id]!} mode={tile.chartType ?? "bar"} />
            {/if}
          {:else if tile.type === "filter"}
            <EmbedFilterTile
              {server}
              {token}
              dashboardPath={path}
              {tile}
              onChange={(o) => onFilterChange(tile.id, o)}
            />
          {:else}
            <div class="state muted">Unsupported tile</div>
          {/if}
        </div>
      </div>
    {/each}
  </div>
{/if}

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
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
  }
</style>
