<script lang="ts">
  /*
   * Table tile. Renders the tile's effective query as a flat HTML table.
   * Like ChartTile, the data fetch + filter merge lands in task #12 —
   * this component currently shows a typed placeholder so the
   * polymorphic Tile.svelte dispatch is verifiable end-to-end.
   *
   * The real renderer will read the AiQueryResponse `data[]` (records
   * format) and project each row's columns into a thead/tbody pair.
   * Cell-click on a member column emits dashboard:click-filter for the
   * grid to capture.
   */

  import type { DashboardTile } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
  }

  let { tile }: Props = $props();

  let summary = $derived({
    cube: tile.cube ? `${tile.cube.connectionName}/${tile.cube.cubeName}` : "(no cube)",
    queryKind: tile.query?.kind ?? "(no query)",
    queryRef: tile.query?.kind === "reference" ? tile.query.path : "(inline)",
  });
</script>

<div class="table-tile">
  <div class="placeholder">
    <div class="badge">TABLE</div>
    <div class="info">
      <span class="key">cube</span><span class="value">{summary.cube}</span>
      <span class="key">query</span><span class="value">{summary.queryKind}: {summary.queryRef}</span>
    </div>
    <div class="hint">Records-format render lands with task #12 — effective-query builder + filter merge.</div>
  </div>
</div>

<style>
  .table-tile {
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
