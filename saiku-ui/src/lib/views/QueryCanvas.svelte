<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";
  import CellsetTable from "$lib/views/CellsetTable.svelte";

  const axisLabels: Record<AxisLocation, string> = {
    COLUMNS: "Columns",
    ROWS: "Rows",
    FILTER: "Filter",
    PAGES: "Pages",
  };

  $effect(() => {
    if (selection.cube && (!query.current || query.current.cube.uniqueName !== selection.cube.uniqueName)) {
      query.initFor(selection.cube);
    }
    if (!selection.cube) {
      query.reset();
    }
  });

  function onDragOver(e: DragEvent) {
    if (e.dataTransfer?.types?.includes("application/x-saiku-level") ||
        e.dataTransfer?.types?.includes("application/x-saiku-measure")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
    }
  }

  function onDropAxis(axis: AxisLocation, e: DragEvent) {
    e.preventDefault();
    const levelPayload = e.dataTransfer?.getData("application/x-saiku-level");
    const measurePayload = e.dataTransfer?.getData("application/x-saiku-measure");
    if (levelPayload) {
      const drop = JSON.parse(levelPayload);
      query.includeLevel(axis, drop);
    } else if (measurePayload) {
      const m = JSON.parse(measurePayload) as ThinMeasure;
      query.addMeasure(m);
    }
  }

  function hierChipLabel(h: ThinHierarchy): string {
    const levelNames = Object.keys(h.levels);
    const head = h.caption || h.name;
    if (levelNames.length === 0) return head;
    if (levelNames.length === 1) return `${head} › ${levelNames[0]}`;
    return `${head} (${levelNames.length} levels)`;
  }

  function removeHier(name: string) {
    query.removeHierarchy(name);
  }

  function removeMeasure(uniqueName: string) {
    query.removeMeasure(uniqueName);
  }
</script>

<div class="canvas">
  {#if !selection.cube}
    <div class="canvas__empty">
      <p>No cube selected.</p>
      <p class="canvas__hint">Pick a cube in the sidebar to start building a query.</p>
    </div>
  {:else}
    <div class="dropzones">
      {#each ["COLUMNS", "ROWS"] as const as axis}
        <div
          class="dropzone"
          role="region"
          aria-label={axisLabels[axis]}
          ondragover={onDragOver}
          ondrop={(e) => onDropAxis(axis, e)}
        >
          <header>{axisLabels[axis]}</header>
          <div class="chips">
            {#if axis === "COLUMNS" && query.current}
              {#each query.current.queryModel?.details.measures ?? [] as m}
                <button type="button" class="chip chip--measure" onclick={() => removeMeasure(m.uniqueName)} title="Remove measure">
                  Σ {m.caption || m.name}
                  <span class="chip__x">×</span>
                </button>
              {/each}
            {/if}
            {#each query.current?.queryModel?.axes[axis].hierarchies ?? [] as h}
              <button type="button" class="chip chip--level" onclick={() => removeHier(h.name)} title="Remove">
                {hierChipLabel(h)}
                <span class="chip__x">×</span>
              </button>
            {/each}
            {#if (query.current?.queryModel?.axes[axis].hierarchies.length ?? 0) === 0
              && !(axis === "COLUMNS" && (query.current?.queryModel?.details.measures.length ?? 0) > 0)}
              <span class="chips__empty">Drop levels {axis === "COLUMNS" ? "or measures" : ""} here</span>
            {/if}
          </div>
        </div>
      {/each}
      <div
        class="dropzone dropzone--filter"
        role="region"
        aria-label="Filter"
        ondragover={onDragOver}
        ondrop={(e) => onDropAxis("FILTER", e)}
      >
        <header>{axisLabels.FILTER}</header>
        <div class="chips">
          {#each query.current?.queryModel?.axes.FILTER.hierarchies ?? [] as h}
            <button type="button" class="chip chip--level" onclick={() => removeHier(h.name)}>
              {hierChipLabel(h)}
              <span class="chip__x">×</span>
            </button>
          {/each}
          {#if (query.current?.queryModel?.axes.FILTER.hierarchies.length ?? 0) === 0}
            <span class="chips__empty">Drop filters here</span>
          {/if}
        </div>
      </div>
    </div>

    <div class="grid-host">
      {#if query.running}
        <p class="canvas__hint">Running query…</p>
      {:else if query.error}
        <p class="callout callout--danger">{query.error}</p>
      {:else if query.result}
        <CellsetTable result={query.result} />
      {:else}
        <p class="canvas__hint">
          Drop levels onto <strong>Rows</strong>/<strong>Columns</strong> and measures onto <strong>Columns</strong>, then hit <strong>Run</strong>.
        </p>
      {/if}
    </div>
  {/if}
</div>

<style>
  .canvas {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    padding: var(--space-3);
  }
  .canvas__empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    min-height: 360px;
    color: var(--fg-muted);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
  }
  .canvas__hint { color: var(--fg-subtle); font-size: var(--fs-sm); margin: 0; }
  .dropzones {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-2);
  }
  .dropzone--filter { grid-column: span 2; }
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
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-1);
  }
  .chips__empty { color: var(--fg-subtle); font-size: var(--fs-sm); }
  .chip {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: 2px var(--space-2);
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font-size: var(--fs-xs);
    cursor: pointer;
  }
  .chip--measure { color: var(--accent); }
  .chip:hover { background: var(--bg-subtle); }
  .chip__x { color: var(--fg-subtle); font-size: 14px; line-height: 1; }
  .grid-host { flex: 1; min-height: 260px; }
</style>
