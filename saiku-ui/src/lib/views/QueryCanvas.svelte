<script lang="ts">
  import { selection } from "$lib/stores/selection.svelte";
</script>

<div class="canvas">
  <div class="dropzones">
    <div class="dropzone dropzone--columns">
      <header>Columns</header>
      <div class="dropzone__body">Drop levels and measures here</div>
    </div>
    <div class="dropzone dropzone--rows">
      <header>Rows</header>
      <div class="dropzone__body">Drop levels and measures here</div>
    </div>
    <div class="dropzone dropzone--filter">
      <header>Filter</header>
      <div class="dropzone__body">Drop filters here</div>
    </div>
  </div>
  <div class="grid-placeholder">
    {#if selection.cube}
      <p>Cube selected: <strong>{selection.cube.caption || selection.cube.name}</strong></p>
      <p class="grid-placeholder__hint">
        Pivot grid (AG Grid) lands in the next slice — drop levels/measures and hit Run to execute the query.
      </p>
    {:else}
      <p>No cube selected.</p>
      <p class="grid-placeholder__hint">
        Pick a cube in the sidebar to start building a query.
      </p>
    {/if}
  </div>
</div>

<style>
  .canvas {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-3);
  }
  .dropzones {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-2);
  }
  .dropzone--filter {
    grid-column: span 2;
  }
  .dropzone {
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    padding: var(--space-2) var(--space-3);
    min-height: 54px;
    background: var(--bg-muted);
  }
  .dropzone header {
    font-size: var(--fs-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: var(--space-1);
  }
  .dropzone__body {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
  }
  .grid-placeholder {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    min-height: 360px;
    color: var(--fg-muted);
    padding: var(--space-5);
  }
  .grid-placeholder__hint {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
  }
</style>
