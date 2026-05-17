<script lang="ts">
  /*
   * 12-column CSS grid that holds the dashboard's tiles. Auto-stacks to a
   * single column below ~768px. Reads from $lib/stores/dashboard so tile
   * mutations refresh without prop-drilling.
   *
   * Tile drag-resize editing lands in task #13; for now tiles render at
   * their persisted (x, y, w, h) positions.
   */

  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import Tile from "$lib/views/dashboard/Tile.svelte";

  interface Props {
    readOnly?: boolean;
  }

  let { readOnly = false }: Props = $props();
</script>

{#if dashboardStore.current}
  {@const dash = dashboardStore.current}
  {#if dash.layout.tiles.length === 0}
    <div class="empty">
      <p>No tiles yet.</p>
      <p class="hint">Use <em>Add tile</em> in the toolbar to start building.</p>
    </div>
  {:else}
    <div
      class="grid"
      style:--cols={dash.layout.cols}
      role="region"
      aria-label="Dashboard tiles"
    >
      {#each dash.layout.tiles as tile (tile.id)}
        <div
          class="cell"
          style:grid-column="{tile.x + 1} / span {tile.w}"
          style:grid-row="{tile.y + 1} / span {tile.h}"
        >
          <Tile {tile} {readOnly} />
        </div>
      {/each}
    </div>
  {/if}
{/if}

<style>
  .grid {
    display: grid;
    /* Fixed N-column grid; rows auto-size to tile h × baseline row height. */
    grid-template-columns: repeat(var(--cols, 12), 1fr);
    grid-auto-rows: minmax(80px, auto);
    gap: 0.5rem;
    flex: 1;
    min-height: 0;
  }
  .cell {
    /* Tiles render their own border / chrome; the cell is just positioning. */
    min-width: 0;
  }
  /* Auto-stack: below ~768px every tile collapses to a single column. */
  @media (max-width: 768px) {
    .grid {
      grid-template-columns: 1fr;
    }
    .cell {
      grid-column: 1 / span 1 !important;
      grid-row: auto / auto !important;
    }
  }
  .empty {
    padding: 3rem 1rem;
    text-align: center;
    color: var(--fg-muted);
    border: 2px dashed var(--border);
    border-radius: 8px;
  }
  .empty p { margin: 0.25rem 0; }
  .empty .hint { font-size: 0.875rem; }
</style>
