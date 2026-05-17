<script lang="ts">
  /*
   * Chart tile. Renders the tile's effective query as a chart using
   * ECharts. The effective-query builder (which merges active filters
   * into the base query) lands in task #12 — this component currently
   * shows a typed placeholder describing what will render once the
   * builder is wired in, so the polymorphic Tile.svelte dispatch is
   * verifiable end-to-end.
   */

  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    /** Click-filter callback — kept on the props surface so Tile.svelte
     *  can wire it uniformly across tile types; the placeholder doesn't
     *  fire it until the real ECharts renderer lands in a follow-up. */
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  // onClickFilter is declared on Props for API consistency with TableTile;
  // it gets wired when the ECharts renderer lands in a follow-up.
  let { tile }: Props = $props();

  let summary = $derived({
    chartType: tile.chartType ?? "bar",
    cube: tile.cube ? `${tile.cube.connectionName}/${tile.cube.cubeName}` : "(no cube)",
    queryKind: tile.query?.kind ?? "(no query)",
    queryRef: tile.query?.kind === "reference" ? tile.query.path : "(inline)",
  });
</script>

<div class="chart-tile" data-chart-type={summary.chartType}>
  <div class="placeholder">
    <div class="badge">CHART</div>
    <div class="info">
      <span class="key">type</span><span class="value">{summary.chartType}</span>
      <span class="key">cube</span><span class="value">{summary.cube}</span>
      <span class="key">query</span><span class="value">{summary.queryKind}: {summary.queryRef}</span>
    </div>
    <div class="hint">Data binding lands with task #12 — effective-query builder + filter merge.</div>
  </div>
</div>

<style>
  .chart-tile {
    display: flex;
    height: 100%;
    align-items: center;
    justify-content: center;
    padding: 0.5rem;
  }
  .placeholder {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.5rem;
    text-align: center;
    color: var(--fg-muted);
  }
  .badge {
    font-size: 0.6875rem;
    letter-spacing: 0.08em;
    font-weight: 600;
    padding: 0.125rem 0.5rem;
    border-radius: 999px;
    background: var(--bg-muted, #f3f4f6);
    color: var(--fg-muted);
  }
  .info {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 0.125rem 0.5rem;
    font-size: 0.75rem;
    font-family: monospace;
  }
  .key {
    color: var(--fg-muted);
    text-align: right;
  }
  .value {
    text-align: left;
    word-break: break-all;
  }
  .hint {
    font-size: 0.6875rem;
    font-style: italic;
    color: var(--fg-muted);
  }
</style>
