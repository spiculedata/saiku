<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";
  import CellsetTable from "$lib/views/CellsetTable.svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import { CHART_TYPES, DEFAULT_CHART_OPTIONS, type ChartType, type ChartOptions } from "$lib/views/chartTypes";
  import SelectionsModal from "$lib/modals/SelectionsModal.svelte";
  import DrillthroughModal from "$lib/modals/DrillthroughModal.svelte";
  import DrillthroughResultModal from "$lib/modals/DrillthroughResultModal.svelte";
  import ChartEditorModal from "$lib/modals/ChartEditorModal.svelte";
  import { listLevelMembers, listRootMembers, type SaikuMember } from "$lib/api/discover";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { drillthrough as fetchDrillthrough, type QueryResult } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  type ViewMode = "grid" | "chart";
  let viewMode = $state<ViewMode>("grid");
  let chartType = $state<ChartType>("bar");
  let chartOptions = $state<ChartOptions>({ ...DEFAULT_CHART_OPTIONS });
  let chartEditorOpen = $state(false);

  function chartGroups(): { name: string; items: typeof CHART_TYPES }[] {
    const map = new Map<string, typeof CHART_TYPES>();
    for (const c of CHART_TYPES) {
      const arr = map.get(c.group) ?? [];
      arr.push(c);
      map.set(c.group, arr);
    }
    return Array.from(map.entries()).map(([name, items]) => ({ name, items }));
  }

  // Drillthrough modal state
  let drillModalOpen = $state(false);
  let drillResultOpen = $state(false);
  let drillResult = $state<QueryResult | null>(null);
  let drillPosition = $state<string | null>(null);

  let selectionsOpen = $state(false);
  let selectionsTarget = $state<{ axis: AxisLocation; hierarchyName: string; hierarchyCaption: string; levelName: string } | null>(null);
  let selectionsMembers = $state<SaikuMember[]>([]);
  let selectionsLoading = $state(false);
  let selectionsInitial = $state<{ uniqueNames: string[]; type: "INCLUSION" | "EXCLUSION" }>({
    uniqueNames: [],
    type: "INCLUSION",
  });

  const axisLabels = $derived<Record<AxisLocation, string>>({
    COLUMNS: i18n.t("canvas.columns"),
    ROWS: i18n.t("canvas.rows"),
    FILTER: i18n.t("canvas.filter"),
    PAGES: "Pages",
  });

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
    const existing = query.getLevelSelection(hier.name, levelName);
    selectionsInitial = { uniqueNames: existing.memberUniqueNames, type: existing.type };
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
        hier.name,
        levelName,
      );
    } catch (err) {
      try {
        selectionsMembers = await listRootMembers(
          session.current.username,
          selection.cube,
          hier.name,
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

  let resultHostEl = $state<HTMLDivElement | null>(null);

  $effect(() => {
    const el = resultHostEl;
    if (!el) return;
    const handler = (ev: Event) => {
      const ce = ev as CustomEvent<{
        axis: AxisLocation;
        hierarchyName: string;
        hierarchyCaption: string;
        levelName: string;
        members: SaikuMember[];
      }>;
      const d = ce.detail;
      if (!d) return;
      selectionsTarget = {
        axis: d.axis,
        hierarchyName: d.hierarchyName,
        hierarchyCaption: d.hierarchyCaption,
        levelName: d.levelName,
      };
      const existing = query.getLevelSelection(d.hierarchyName, d.levelName);
      selectionsInitial = { uniqueNames: existing.memberUniqueNames, type: existing.type };
      selectionsMembers = d.members;
      selectionsOpen = true;
    };
    el.addEventListener("saiku-filter-level", handler);

    const drillHandler = (ev: Event) => {
      const ce = ev as CustomEvent<{ row: number; col: number }>;
      if (!ce.detail) return;
      drillPosition = `${ce.detail.row}:${ce.detail.col}`;
      drillModalOpen = true;
    };
    el.addEventListener("saiku-drillthrough", drillHandler);

    return () => {
      el.removeEventListener("saiku-filter-level", handler);
      el.removeEventListener("saiku-drillthrough", drillHandler);
    };
  });

  async function runDrillthrough(opts: { dimensions: string[]; measures: string[]; maxRows: number }) {
    drillModalOpen = false;
    if (!query.current) return;
    drillResult = null;
    drillResultOpen = true;
    try {
      const returns = [...opts.dimensions, ...opts.measures];
      // NOTE: legacy passed a per-axis cell-position here to narrow the drillthrough.
      // The new UI only knows the visual (row, col) of the clicked data cell; sending
      // that as `position` trips an IOOB on the backend for non-trivial cellsets, so we
      // currently drill through the whole cellset and let the user filter by `returns`.
      drillResult = await fetchDrillthrough(query.current.name, {
        maxRows: opts.maxRows,
        returns,
      });
    } catch (err) {
      drillResultOpen = false;
      toasts.danger("Drillthrough failed", err instanceof Error ? err.message : String(err));
    }
  }

  let cubeMetadata = $state<{ dimensions: import("$lib/api/discover").SaikuDimension[]; measures: import("$lib/api/discover").SaikuMeasure[] } | null>(null);
  $effect(() => {
    const cube = selection.cube;
    if (!cube || !session.current) {
      cubeMetadata = null;
      return;
    }
    datasources.metadata(session.current.username, cube).then((md) => (cubeMetadata = md)).catch(() => {});
  });

  async function onSelectionsSave(uniqueNames: string[], type: "INCLUSION" | "EXCLUSION") {
    if (!selectionsTarget) return;
    query.setLevelSelection(
      selectionsTarget.hierarchyName,
      selectionsTarget.levelName,
      uniqueNames,
      type,
    );
    selectionsOpen = false;
    toasts.success(i18n.t("toast.saved"), `${uniqueNames.length} selection(s) applied`);
    if (query.hasRunnableShape()) await query.run();
  }
</script>

<div class="canvas">
  {#if !selection.cube}
    <div class="canvas__empty">
      <p>{i18n.t("canvas.noCube")}</p>
      <p class="canvas__hint">{i18n.t("canvas.pickPrompt")}</p>
    </div>
  {:else}
    <div class="canvas__body">
    <aside class="dropzones">
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
            <span class="chip chip--level">
              <button
                type="button"
                class="chip__label"
                onclick={() => openSelections("FILTER", h)}
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
          {#if (query.current?.queryModel?.axes.FILTER.hierarchies.length ?? 0) === 0}
            <span class="chips__empty">{i18n.t("canvas.dropFilters")}</span>
          {/if}
        </div>
      </div>
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
              <span class="chips__empty">
                {axis === "COLUMNS" ? i18n.t("canvas.dropLevelsMeasures") : i18n.t("canvas.dropLevels")}
              </span>
            {/if}
          </div>
        </div>
      {/each}
    </aside>
    <div class="canvas__result">
    <div class="view-toggle" role="tablist" aria-label="Result view">
      <button type="button" role="tab" class:active={viewMode === "grid"} onclick={() => (viewMode = "grid")}>
        {i18n.t("canvas.view.grid")}
      </button>
      <button type="button" role="tab" class:active={viewMode === "chart"} onclick={() => (viewMode = "chart")}>
        {i18n.t("canvas.view.chart")}
      </button>
      {#if viewMode === "chart"}
        <label class="chart-pick">
          <span class="sr-only">Chart type</span>
          <select bind:value={chartType}>
            {#each chartGroups() as group}
              <optgroup label={group.name}>
                {#each group.items as c}
                  <option value={c.id}>{c.label}</option>
                {/each}
              </optgroup>
            {/each}
          </select>
        </label>
        <button type="button" class="chart-edit" title="Chart editor" onclick={() => (chartEditorOpen = true)}>⚙</button>
      {/if}
    </div>
    <div class="result-host" bind:this={resultHostEl}>
      {#if query.running}
        <p class="canvas__hint">{i18n.t("canvas.running")}</p>
      {:else if query.error}
        <p class="callout callout--danger">{query.error}</p>
      {:else if query.result}
        {#if viewMode === "grid"}
          <CellsetTable result={query.result} />
        {:else}
          <ChartView result={query.result} type={chartType} options={chartOptions} />
        {/if}
      {:else}
        <p class="canvas__hint">{i18n.t("canvas.buildPrompt")}</p>
      {/if}
    </div>
    </div>
    </div>
  {/if}
</div>

{#if selectionsTarget}
  <SelectionsModal
    levelCaption={selectionsTarget.hierarchyCaption + " › " + selectionsTarget.levelName}
    available={selectionsMembers}
    initialSelected={selectionsInitial.uniqueNames}
    initialType={selectionsInitial.type}
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

<DrillthroughModal
  dimensions={cubeMetadata?.dimensions ?? []}
  measures={cubeMetadata?.measures ?? []}
  maxRows={1000}
  open={drillModalOpen}
  onRun={runDrillthrough}
  onExportCsv={(opts) => {
    drillModalOpen = false;
    if (!query.current) return;
    const params = new URLSearchParams();
    params.set("maxrows", "10000");
    if (drillPosition) params.set("position", drillPosition);
    const returns = [...opts.dimensions, ...opts.measures];
    if (returns.length) params.set("returns", returns.join(","));
    window.open(`/rest/saiku/api/query/${encodeURIComponent(query.current.name)}/drillthrough/export/csv?${params.toString()}`, "_blank");
  }}
  onCancel={() => (drillModalOpen = false)}
/>

<DrillthroughResultModal
  result={drillResult}
  open={drillResultOpen}
  onClose={() => (drillResultOpen = false)}
/>

<ChartEditorModal
  initial={chartOptions}
  open={chartEditorOpen}
  onSave={(next) => { chartOptions = next; chartEditorOpen = false; }}
  onCancel={() => (chartEditorOpen = false)}
/>

<style>
  .canvas {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    padding: var(--space-3);
    overflow: hidden;
  }
  .canvas__body {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 260px 1fr;
    gap: var(--space-3);
    overflow: hidden;
  }
  .canvas__result {
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    overflow: hidden;
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
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    overflow-y: auto;
    padding-right: var(--space-1);
  }
  .dropzone {
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    padding: var(--space-2) var(--space-3);
    min-height: 54px;
    background: var(--bg-muted);
  }
  .dropzone--filter { background: var(--bg-subtle); }
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
  .result-host { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
  .result-host :global(.runtime) { flex: 0 0 auto; }
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
  .chart-edit {
    margin-left: auto;
    background: transparent;
    color: var(--fg-muted);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    cursor: pointer;
    padding: 2px 8px;
  }
  .chart-edit:hover { background: var(--bg-subtle); color: var(--fg); }
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
