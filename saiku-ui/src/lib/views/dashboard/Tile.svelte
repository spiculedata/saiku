<script lang="ts">
  /*
   * Polymorphic tile shell. Renders a frame with a title bar and dispatches
   * the body to the matching sub-tile component (Chart / Table / Text /
   * Filter). Sub-tiles land in task #11; this scaffold prints the tile
   * metadata so the grid layout is verifiable end-to-end before the
   * substance arrives.
   */

  import type { DashboardTile } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    readOnly?: boolean;
  }

  let { tile, readOnly = false }: Props = $props();
</script>

<div class="tile" data-tile-type={tile.type}>
  <header class="tile-header">
    <span class="title">{tile.title ?? tile.type}</span>
    {#if !readOnly}
      <div class="tile-actions">
        <button type="button" class="icon-btn" aria-label="Edit tile" disabled>⚙</button>
        <button type="button" class="icon-btn" aria-label="Remove tile" disabled>×</button>
      </div>
    {/if}
  </header>
  <div class="tile-body">
    <!-- Scaffold: real sub-tile components land in task #11. -->
    <pre class="placeholder">type: {tile.type}
id: {tile.id}
position: ({tile.x}, {tile.y}) {tile.w}×{tile.h}</pre>
  </div>
</div>

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
    padding: 0.5rem;
    overflow: auto;
  }
  .placeholder {
    font-family: monospace;
    font-size: 0.75rem;
    color: var(--fg-muted);
    margin: 0;
    white-space: pre-wrap;
  }
</style>
