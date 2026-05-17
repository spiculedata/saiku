<script lang="ts">
  /*
   * Polymorphic tile shell. Renders the title-bar frame and dispatches
   * the body to the matching sub-tile component by `tile.type`.
   *
   * Edit / remove buttons live in this shell so every tile type gets
   * them for free. The edit modal is co-located here — keeping the
   * open/closed state per-tile-instance avoids prop-drilling up to the
   * grid and back.
   */

  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import ChartTile from "$lib/views/dashboard/tiles/ChartTile.svelte";
  import TableTile from "$lib/views/dashboard/tiles/TableTile.svelte";
  import TextTile from "$lib/views/dashboard/tiles/TextTile.svelte";
  import FilterTile from "$lib/views/dashboard/tiles/FilterTile.svelte";
  import TileEditorModal from "$lib/views/dashboard/TileEditorModal.svelte";

  interface Props {
    tile: DashboardTile;
    readOnly?: boolean;
  }

  let { tile, readOnly = false }: Props = $props();

  let editorOpen = $state(false);

  function handleEdit(): void {
    if (readOnly) return;
    editorOpen = true;
  }

  function handleRemove(): void {
    if (readOnly) return;
    dashboardStore.removeTile(tile.id);
  }

  /** Click-filter capture from chart / table sub-tiles. Push onto the
   *  active-filter set tagged with this tile's id; the chip bar shows
   *  the new filter and every compatible tile recomputes its effective
   *  query on the activeFilters store's tick. */
  function handleClickFilter(filter: DashboardFilter): void {
    activeFilters.pushClick(filter, tile.id);
  }
</script>

<div class="tile" data-tile-type={tile.type}>
  <header class="tile-header">
    <span class="title">{tile.title ?? defaultTitle(tile)}</span>
    {#if !readOnly}
      <div class="tile-actions">
        <button type="button" class="icon-btn" aria-label="Edit tile" onclick={handleEdit}>⚙</button>
        <button type="button" class="icon-btn" aria-label="Remove tile" onclick={handleRemove}>×</button>
      </div>
    {/if}
  </header>
  <div class="tile-body">
    {#if tile.type === "chart"}
      <ChartTile {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
    {:else if tile.type === "table"}
      <TableTile {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
    {:else if tile.type === "text"}
      <TextTile {tile} />
    {:else if tile.type === "filter"}
      <FilterTile {tile} {readOnly} />
    {:else}
      <div class="unknown">Unknown tile type: {tile.type}</div>
    {/if}
  </div>
</div>

{#if editorOpen}
  <TileEditorModal {tile} onClose={() => (editorOpen = false)} />
{/if}

<script module lang="ts">
  import type { DashboardTile as DT } from "$lib/api/dashboards";

  /** Fallback title when the analyst hasn't set one — keeps the chrome
   *  populated so the tile isn't a mystery rectangle. */
  function defaultTitle(tile: DT): string {
    switch (tile.type) {
      case "chart":
        return tile.chartType ? `${tile.chartType} chart` : "Chart";
      case "table":
        return "Table";
      case "text":
        return "Note";
      case "filter":
        return tile.target?.level ? `Filter: ${tile.target.level}` : "Filter";
      default:
        return tile.type;
    }
  }
</script>

<style>
  .tile {
    display: flex;
    flex-direction: column;
    height: 100%;
    border: 1px solid var(--border, #e5e7eb);
    border-radius: 6px;
    background: var(--bg-tile, #fff);
    overflow: hidden;
  }
  .tile-header {
    display: flex;
    align-items: center;
    padding: 0.375rem 0.5rem;
    border-bottom: 1px solid var(--border, #e5e7eb);
    background: var(--bg-tile-header, #fafafa);
    font-size: 0.8125rem;
  }
  .title {
    font-weight: 500;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tile-actions {
    display: flex;
    gap: 0.25rem;
  }
  .icon-btn {
    border: none;
    background: transparent;
    cursor: pointer;
    color: var(--fg-muted);
    padding: 0 0.25rem;
  }
  .icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }
  .tile-body {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }
  .unknown {
    padding: 0.5rem;
    color: #991b1b;
    font-size: 0.8125rem;
  }
</style>
