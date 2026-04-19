<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";
  import CellsetTable from "$lib/views/CellsetTable.svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import StatsView from "$lib/views/StatsView.svelte";
  import SparklineView from "$lib/views/SparklineView.svelte";
  import { CHART_TYPES, DEFAULT_CHART_OPTIONS, type ChartType, type ChartOptions } from "$lib/views/chartTypes";
  import SelectionsModal from "$lib/modals/SelectionsModal.svelte";
  import DrillthroughModal from "$lib/modals/DrillthroughModal.svelte";
  import DrillthroughResultModal from "$lib/modals/DrillthroughResultModal.svelte";
  import ChartEditorModal from "$lib/modals/ChartEditorModal.svelte";
  import CustomFilterModal from "$lib/modals/CustomFilterModal.svelte";
  import FormatAsPercentageModal from "$lib/modals/FormatAsPercentageModal.svelte";
  import GrowthModal from "$lib/modals/GrowthModal.svelte";
  import FilterModal from "$lib/modals/FilterModal.svelte";
  import ContextMenu from "$lib/components/ContextMenu.svelte";
  type ContextMenuItem = { id: string; label: string; disabled?: boolean; danger?: boolean; sep?: boolean };
  import { MoreHorizontal } from "lucide-svelte";
  import { listLevelMembers, listRootMembers, type SaikuMember } from "$lib/api/discover";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { drillthrough as fetchDrillthrough, type QueryResult } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  type ViewMode = "grid" | "chart" | "stats" | "sparkline" | "sparkbar";
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

  // --- Context menu state + downstream modals ---
  interface MenuCtx {
    open: boolean;
    x: number;
    y: number;
    items: ContextMenuItem[];
    // payload for the action handler
    kind: "measure" | "hierarchy" | "axis" | null;
    axis: AxisLocation | null;
    measure: ThinMeasure | null;
    hierarchy: ThinHierarchy | null;
  }
  let menu = $state<MenuCtx>({ open: false, x: 0, y: 0, items: [], kind: null, axis: null, measure: null, hierarchy: null });

  let customFilterOpen = $state(false);
  let customFilterTarget = $state<ThinMeasure | null>(null);
  let formatPctOpen = $state(false);
  let formatPctTarget = $state<{ measure: ThinMeasure } | null>(null);
  let growthOpen = $state(false);
  let growthTarget = $state<ThinMeasure | null>(null);

  let axisFilterOpen = $state(false);
  let axisFilterTarget = $state<{ axis: AxisLocation; type: "Order" | "Filter" | "TopCount" | "BottomCount" | "Limit"; expression: string; sort: string } | null>(null);

  function openMeasureMenu(e: MouseEvent, m: ThinMeasure) {
    e.preventDefault();
    e.stopPropagation();
    menu = {
      open: true,
      x: e.clientX, y: e.clientY,
      kind: "measure",
      axis: "COLUMNS",
      measure: m,
      hierarchy: null,
      items: [
        { id: "filter", label: "Filter by value…" },
        { id: "format-pct", label: "Format as percentage…" },
        { id: "growth", label: "Growth calculation…" },
        { id: "_sep", sep: true, label: "" },
        { id: "remove", label: "Remove measure", danger: true },
      ],
    };
  }

  function openHierMenu(e: MouseEvent, axis: AxisLocation, h: ThinHierarchy) {
    e.preventDefault();
    e.stopPropagation();
    menu = {
      open: true,
      x: e.clientX, y: e.clientY,
      kind: "hierarchy",
      axis, measure: null, hierarchy: h,
      items: [
        { id: "selections", label: "Edit selections…" },
        { id: "_sep", sep: true, label: "" },
        { id: "remove", label: "Remove hierarchy", danger: true },
      ],
    };
  }

  function openAxisMenu(e: MouseEvent, axis: AxisLocation) {
    e.preventDefault();
    e.stopPropagation();
    menu = {
      open: true,
      x: e.clientX, y: e.clientY,
      kind: "axis",
      axis, measure: null, hierarchy: null,
      items: [
        { id: "filter-order", label: "Order (custom MDX)…" },
        { id: "filter-filter", label: "Filter (custom MDX)…" },
        { id: "filter-top", label: "Top count…" },
        { id: "filter-bot", label: "Bottom count…" },
        { id: "filter-limit", label: "Limit…" },
      ],
    };
  }

  function onMenuPick(id: string) {
    const m = menu;
    menu = { ...m, open: false };
    if (!id) return;
    if (m.kind === "measure" && m.measure) {
      if (id === "filter") {
        customFilterTarget = m.measure;
        customFilterOpen = true;
      } else if (id === "format-pct") {
        formatPctTarget = { measure: m.measure };
        formatPctOpen = true;
      } else if (id === "growth") {
        growthTarget = m.measure;
        growthOpen = true;
      } else if (id === "remove") {
        query.removeMeasure(m.measure.uniqueName);
      }
      return;
    }
    if (m.kind === "hierarchy" && m.axis && m.hierarchy) {
      if (id === "selections") openSelections(m.axis, m.hierarchy);
      else if (id === "remove") query.removeHierarchy(m.hierarchy.name);
      return;
    }
    if (m.kind === "axis" && m.axis) {
      const axis = m.axis;
      const model = query.current?.queryModel;
      const existing = model?.axes[axis].mdx ?? "";
      let type: "Order" | "Filter" | "TopCount" | "BottomCount" | "Limit" = "Filter";
      if (id === "filter-order") type = "Order";
      else if (id === "filter-filter") type = "Filter";
      else if (id === "filter-top") type = "TopCount";
      else if (id === "filter-bot") type = "BottomCount";
      else if (id === "filter-limit") type = "Limit";
      axisFilterTarget = { axis, type, expression: existing, sort: "ASC" };
      axisFilterOpen = true;
    }
  }

  function onCustomFilterApply(op: string, value: string, value2?: string) {
    customFilterOpen = false;
    const m = customFilterTarget;
    if (!m || !query.current?.queryModel) return;
    let expr = `${m.uniqueName} ${op} ${value}`;
    if (op === "BETWEEN") expr = `${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2}`;
    else if (op === "NOT BETWEEN") expr = `NOT (${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2})`;
    const axis = query.current.queryModel.axes.ROWS;
    axis.mdx = axis.mdx
      ? `FILTER(${axis.mdx}, ${expr})`
      : `FILTER({[Measures].CurrentMember}, ${expr})`;
    toasts.success("Filter applied", `${m.caption} ${op} ${value}`);
    void query.run();
  }

  function onFormatPctApply(axis: "ROWS" | "COLUMNS" | "GRAND_TOTAL", _scope: "all" | "selected") {
    formatPctOpen = false;
    const t = formatPctTarget;
    if (!t || !query.current?.queryModel) return;
    const calcName = `${t.measure.name} %`;
    const ref = axis === "ROWS"
      ? "Axis(1).Item(0).Item(0).Dimension.CurrentMember.Parent"
      : axis === "COLUMNS"
        ? "Axis(0).Item(0).Item(0).Dimension.CurrentMember.Parent"
        : "[All]";
    const denom = `([Measures].[${t.measure.name}], ${ref})`;
    const formula = `IIF(${denom} = 0, null, [Measures].[${t.measure.name}] / ${denom})`;
    const next = (query.current.queryModel.calculatedMeasures ?? []).filter(
      (x) => (x as { name?: string }).name !== calcName,
    );
    next.push({ name: calcName, formula, properties: { FORMAT_STRING: "0.00%", SOLVE_ORDER: "200" } });
    query.current.queryModel.calculatedMeasures = next;
    query.addMeasure({ name: calcName, uniqueName: `[Measures].[${calcName}]`, caption: calcName, type: "CALCULATED" });
    toasts.success("Formatted as %", calcName);
  }

  function onGrowthApply(basis: string, ref?: string) {
    growthOpen = false;
    const m = growthTarget;
    if (!m || !query.current?.queryModel) return;
    const calcName = `${m.name} growth`;
    let prev: string;
    if (basis === "previous") prev = `(${m.uniqueName}, Axis(1).Item(0).Item(0).PrevMember)`;
    else if (basis === "first") prev = `(${m.uniqueName}, Axis(1).Item(0).Item(0).FirstSibling)`;
    else prev = `(${m.uniqueName}, ${ref ?? ""})`;
    const formula = `(${m.uniqueName} - ${prev}) / ${prev}`;
    const next = (query.current.queryModel.calculatedMeasures ?? []).filter(
      (x) => (x as { name?: string }).name !== calcName,
    );
    next.push({ name: calcName, formula, properties: { FORMAT_STRING: "0.00%", SOLVE_ORDER: "300" } });
    query.current.queryModel.calculatedMeasures = next;
    query.addMeasure({ name: calcName, uniqueName: `[Measures].[${calcName}]`, caption: calcName, type: "CALCULATED" });
    toasts.success("Growth calc added", calcName);
  }

  function onAxisFilterSave(expression: string, sort?: string) {
    axisFilterOpen = false;
    const t = axisFilterTarget;
    if (!t || !query.current?.queryModel) return;
    const axis = query.current.queryModel.axes[t.axis];
    if (t.type === "Order") {
      axis.sortOrder = sort ?? "ASC";
      axis.sortEvaluationLiteral = expression || null;
    } else if (t.type === "TopCount" || t.type === "BottomCount") {
      const fn = t.type.toUpperCase();
      axis.mdx = axis.mdx ? `${fn}(${axis.mdx}, ${expression})` : null;
      if (!axis.mdx) {
        toasts.warning("No axis set", `Drop a hierarchy onto ${t.axis} first.`);
        return;
      }
    } else if (t.type === "Limit") {
      axis.mdx = axis.mdx ? `HEAD(${axis.mdx}, ${expression})` : null;
    } else {
      axis.mdx = axis.mdx ? `FILTER(${axis.mdx}, ${expression})` : `FILTER({}, ${expression})`;
    }
    toasts.success("Axis expression applied", `${t.type} on ${t.axis}`);
    void query.run();
  }

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
        <header>
          <span>{axisLabels.FILTER}</span>
          <button type="button" class="dropzone__menu" title="Axis options" onclick={(e) => openAxisMenu(e, "FILTER")}>
            <MoreHorizontal size={14} />
          </button>
        </header>
        <div class="chips">
          {#each query.current?.queryModel?.axes.FILTER.hierarchies ?? [] as h}
            <span class="chip chip--level" oncontextmenu={(e) => openHierMenu(e, "FILTER", h)}>
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
          <header>
            <span>{axisLabels[axis]}</span>
            <button type="button" class="dropzone__menu" title="Axis options" onclick={(e) => openAxisMenu(e, axis)}>
              <MoreHorizontal size={14} />
            </button>
          </header>
          <div class="chips">
            {#if axis === "COLUMNS" && query.current}
              {#each query.current.queryModel?.details.measures ?? [] as m}
                <span class="chip chip--measure" oncontextmenu={(e) => openMeasureMenu(e, m)}>
                  <span class="chip__label" title="Right-click for options">
                    Σ {m.caption || m.name}
                  </span>
                  <button
                    type="button"
                    class="chip__x"
                    title="Remove measure"
                    aria-label="Remove {m.caption || m.name}"
                    onclick={() => removeMeasure(m.uniqueName)}
                  >×</button>
                </span>
              {/each}
            {/if}
            {#each query.current?.queryModel?.axes[axis].hierarchies ?? [] as h}
              <span class="chip chip--level" oncontextmenu={(e) => openHierMenu(e, axis, h)}>
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
      <button type="button" role="tab" class:active={viewMode === "stats"} onclick={() => (viewMode = "stats")}>
        Stats
      </button>
      <button type="button" role="tab" class:active={viewMode === "sparkline"} onclick={() => (viewMode = "sparkline")}>
        Sparkline
      </button>
      <button type="button" role="tab" class:active={viewMode === "sparkbar"} onclick={() => (viewMode = "sparkbar")}>
        Sparkbar
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
        {:else if viewMode === "chart"}
          <ChartView result={query.result} type={chartType} options={chartOptions} />
        {:else if viewMode === "stats"}
          <StatsView result={query.result} />
        {:else if viewMode === "sparkline"}
          <SparklineView result={query.result} mode="line" />
        {:else if viewMode === "sparkbar"}
          <SparklineView result={query.result} mode="bar" />
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

<ContextMenu
  open={menu.open}
  x={menu.x}
  y={menu.y}
  items={menu.items}
  onPick={onMenuPick}
  onClose={() => (menu = { ...menu, open: false })}
/>

<CustomFilterModal
  measureCaption={customFilterTarget?.caption ?? customFilterTarget?.name ?? ""}
  open={customFilterOpen}
  onApply={onCustomFilterApply}
  onCancel={() => (customFilterOpen = false)}
/>

<FormatAsPercentageModal
  defaultAxis="COLUMNS"
  scope="all"
  open={formatPctOpen}
  onApply={onFormatPctApply}
  onCancel={() => (formatPctOpen = false)}
/>

<GrowthModal
  open={growthOpen}
  onApply={onGrowthApply}
  onCancel={() => (growthOpen = false)}
/>

{#if axisFilterTarget}
  <FilterModal
    axis={axisFilterTarget.axis}
    expressionType={axisFilterTarget.type}
    expression={axisFilterTarget.expression}
    sortFunction={axisFilterTarget.sort as "ASC" | "BASC" | "DESC" | "BDESC"}
    open={axisFilterOpen}
    onSave={onAxisFilterSave}
    onCancel={() => (axisFilterOpen = false)}
  />
{/if}

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
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: var(--fs-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-bottom: var(--space-1);
  }
  .dropzone__menu {
    background: transparent;
    border: 0;
    color: var(--fg-subtle);
    cursor: pointer;
    padding: 2px 4px;
    border-radius: 3px;
    display: inline-flex;
    align-items: center;
    opacity: 0;
    transition: opacity 120ms ease;
  }
  .dropzone:hover .dropzone__menu { opacity: 1; }
  .dropzone__menu:hover { background: var(--bg-subtle); color: var(--fg); }
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
