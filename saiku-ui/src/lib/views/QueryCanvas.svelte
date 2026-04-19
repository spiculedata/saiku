<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";
  import CellsetTable from "$lib/views/CellsetTable.svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import { CHART_TYPES, type ChartType } from "$lib/views/chartTypes";
  import SelectionsModal from "$lib/modals/SelectionsModal.svelte";
  import { listLevelMembers, listRootMembers, type SaikuMember } from "$lib/api/discover";
  import { toasts } from "$lib/stores/toasts.svelte";

  type ViewMode = "grid" | "chart";
  let viewMode = $state<ViewMode>("grid");
  let chartType = $state<ChartType>("bar");

  let selectionsOpen = $state(false);
  let selectionsTarget = $state<{ axis: AxisLocation; hierarchyName: string; hierarchyCaption: string; levelName: string } | null>(null);
  let selectionsMembers = $state<SaikuMember[]>([]);
  let selectionsLoading = $state(false);

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

  async function openSelections(axis: AxisLocation, hier: ThinHierarchy) {
    if (!selection.cube || !session.current) return;
    const levelName = Object.keys(hier.levels)[0];
    if (!levelName) return;
    selectionsTarget = {
      axis,
      hierarchyName: hier.name,
      hierarchyCaption: hier.caption ?? hier.name,
      levelName,
    };
    selectionsOpen = true;
    selectionsLoading = true;
    selectionsMembers = [];
    try {
      // The dimension-name path requires just the dimension token from the hierarchy unique name.
      const dimension = hier.name.split(".")[0]?.replace(/[[\]]/g, "") ?? hier.name;
      selectionsMembers = await listLevelMembers(
        session.current.username,
        selection.cube,
        dimension,
        hier.uniqueName ?? hier.name,
        levelName,
      );
    } catch (err) {
      try {
        selectionsMembers = await listRootMembers(
          session.current.username,
          selection.cube,
          hier.uniqueName ?? hier.name,
        );
      } catch (err2) {
        toasts.danger(
          "Could not load members",
          err2 instanceof Error ? err2.message : String(err2),
        );
      }
    } finally {
      selectionsLoading = false;
    }
  }

  function onSelectionsSave(_uniqueNames: string[], _type: "INCLUSION" | "EXCLUSION") {
    // Wire into query.updateSelections() when the selection
    // shape lands on the query store.
    selectionsOpen = false;
    toasts.info("Selections", "Applied (selection persistence lands when the query JSON schema is extended).");
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
              <span class="chip chip--level">
                <button
                  type="button"
                  class="chip__label"
                  onclick={() => openSelections(axis, h)}
                  title="Edit selections"
                >
                  {hierChipLabel(h)}
                </button>
                <button
                  type="button"
                  class="chip__x"
                  aria-label="Remove {h.caption || h.name}"
                  onclick={() => removeHier(h.name)}
                >×</button>
              </span>
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

    <div class="view-toggle" role="tablist" aria-label="Result view">
      <button type="button" role="tab" class:active={viewMode === "grid"} onclick={() => (viewMode = "grid")}>
        Grid
      </button>
      <button type="button" role="tab" class:active={viewMode === "chart"} onclick={() => (viewMode = "chart")}>
        Chart
      </button>
      {#if viewMode === "chart"}
        <label class="chart-pick">
          <span class="sr-only">Chart type</span>
          <select bind:value={chartType}>
            {#each CHART_TYPES as c}
              <option value={c.id}>{c.label}</option>
            {/each}
          </select>
        </label>
      {/if}
    </div>
    <div class="grid-host">
      {#if query.running}
        <p class="canvas__hint">Running query…</p>
      {:else if query.error}
        <p class="callout callout--danger">{query.error}</p>
      {:else if query.result}
        {#if viewMode === "grid"}
          <CellsetTable result={query.result} />
        {:else}
          <ChartView result={query.result} type={chartType} />
        {/if}
      {:else}
        <p class="canvas__hint">
          Drop levels onto <strong>Rows</strong>/<strong>Columns</strong> and measures onto <strong>Columns</strong>, then hit <strong>Run</strong>.
        </p>
      {/if}
    </div>
  {/if}
</div>

{#if selectionsTarget}
  <SelectionsModal
    levelCaption={selectionsTarget.hierarchyCaption + " › " + selectionsTarget.levelName}
    available={selectionsMembers}
    initialSelected={[]}
    initialType="INCLUSION"
    open={selectionsOpen}
    onSave={onSelectionsSave}
    onOpenDateFilter={() => {
      selectionsOpen = false;
      toasts.info("Date filter", "Date filter modal wiring ships in a later slice.");
    }}
    onCancel={() => (selectionsOpen = false)}
  />
{/if}

{#if selectionsLoading && selectionsOpen}
  <p class="callout">Loading members…</p>
{/if}

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
    padding: 0;
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    font-size: var(--fs-xs);
    overflow: hidden;
  }
  .chip--measure { color: var(--accent); padding: 2px var(--space-2); cursor: pointer; }
  .chip:hover { background: var(--bg-subtle); }
  .chip__label {
    background: transparent;
    color: inherit;
    border: 0;
    padding: 2px var(--space-2);
    cursor: pointer;
    font: inherit;
  }
  .chip__x {
    background: transparent;
    color: var(--fg-subtle);
    border: 0;
    border-left: 1px solid var(--border);
    padding: 0 var(--space-1);
    font-size: 14px;
    line-height: 1;
    cursor: pointer;
  }
  .chip__x:hover { color: var(--danger); background: var(--bg-muted); }
  .grid-host { flex: 1; min-height: 260px; }
  .view-toggle {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) 0;
  }
  .view-toggle button {
    padding: var(--space-1) var(--space-3);
    background: transparent;
    border: 1px solid var(--border-strong);
    color: var(--fg-muted);
    border-radius: var(--radius-sm);
    cursor: pointer;
    font: inherit;
  }
  .view-toggle button.active {
    background: var(--accent);
    color: var(--accent-fg);
    border-color: var(--accent);
  }
  .chart-pick select {
    padding: var(--space-1) var(--space-2);
    background: var(--bg);
    color: var(--fg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
</style>
