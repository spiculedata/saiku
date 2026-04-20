<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";
  import CellsetTable from "$lib/views/CellsetTable.svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import StatsView from "$lib/views/StatsView.svelte";
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
  import { MoreHorizontal, Loader2, XCircle, ChevronDown } from "lucide-svelte";
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
  let moreViewOpen = $state(false);
  const moreViewModes: ViewMode[] = ["stats", "sparkline", "sparkbar"];
  const moreViewLabels: Record<ViewMode, string> = {
    grid: "Grid",
    chart: "Chart",
    stats: "Stats",
    sparkline: "Sparkline",
    sparkbar: "Sparkbar",
  };
  function isMoreMode(m: ViewMode): boolean {
    return (moreViewModes as ViewMode[]).includes(m);
  }
  function pickMoreMode(m: ViewMode) {
    viewMode = m;
    moreViewOpen = false;
  }

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
      let type: "Order" | "Filter" | "TopCount" | "BottomCount" | "Limit" = "Filter";
      let placeholder = "";
      if (id === "filter-order") { type = "Order"; placeholder = "[Measures].[Unit Sales]"; }
      else if (id === "filter-filter") { type = "Filter"; placeholder = "[Measures].[Unit Sales] > 100000"; }
      else if (id === "filter-top") { type = "TopCount"; placeholder = "10, [Measures].[Unit Sales]"; }
      else if (id === "filter-bot") { type = "BottomCount"; placeholder = "10, [Measures].[Unit Sales]"; }
      else if (id === "filter-limit") { type = "Limit"; placeholder = "10"; }
      const sortOrder = model?.axes[axis].sortOrder ?? "ASC";
      axisFilterTarget = { axis, type, expression: placeholder, sort: sortOrder };
      axisFilterOpen = true;
    }
  }

  /** Primary ROWS hierarchy unique name (needed for valid Mondrian MDX — Axis() refs
   *  only work in SELECT, not inside WITH MEMBER). */
  function primaryRowsHier(): { hier: string; deepestLevel: string } | null {
    const model = query.current?.queryModel;
    if (!model) return null;
    const rows = model.axes.ROWS.hierarchies;
    if (rows.length === 0) return null;
    const h = rows[0];
    const lvls = Object.keys(h.levels);
    if (lvls.length === 0) return { hier: h.name, deepestLevel: "" };
    return { hier: h.name, deepestLevel: lvls[lvls.length - 1] };
  }

  function primaryColumnsHier(): string | null {
    const model = query.current?.queryModel;
    if (!model) return null;
    const cols = model.axes.COLUMNS.hierarchies;
    return cols.length > 0 ? cols[0].name : null;
  }

  /** Build the MDX set expression representing the existing ROWS axis. Preserves
   *  any mdx override the user set previously; otherwise expands to the deepest
   *  level members of the primary hierarchy. */
  function rowsAxisSet(): string | null {
    const model = query.current?.queryModel;
    if (!model) return null;
    const axis = model.axes.ROWS;
    if (axis.mdx) return axis.mdx;
    const p = primaryRowsHier();
    if (!p || !p.deepestLevel) return null;
    return `${p.hier}.[${p.deepestLevel}].Members`;
  }

  function onCustomFilterApply(op: string, value: string, value2?: string) {
    customFilterOpen = false;
    const m = customFilterTarget;
    if (!m || !query.current?.queryModel) return;
    const set = rowsAxisSet();
    if (!set) {
      toasts.warning("Drop a hierarchy on ROWS first", "Filter-by-value needs an existing ROWS axis to constrain.");
      return;
    }
    let expr: string;
    if (op === "BETWEEN") expr = `${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2}`;
    else if (op === "NOT BETWEEN") expr = `NOT (${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2})`;
    else expr = `${m.uniqueName} ${op} ${value}`;
    query.current.queryModel.axes.ROWS.mdx = `FILTER(${set}, ${expr})`;
    toasts.success("Filter applied", `${m.caption} ${op} ${value}`);
    void query.run();
  }

  function onFormatPctApply(base: "ROWS" | "COLUMNS" | "GRAND_TOTAL", _scope: "all" | "selected") {
    formatPctOpen = false;
    const t = formatPctTarget;
    if (!t || !query.current?.queryModel) return;
    const calcName = `${t.measure.name} %`;
    let denomTuple: string;
    if (base === "ROWS") {
      const p = primaryRowsHier();
      if (!p) { toasts.warning("No ROWS hierarchy", "Drop a hierarchy onto ROWS before using percent-of-row-total."); return; }
      denomTuple = `([Measures].[${t.measure.name}], ${p.hier}.CurrentMember.Parent)`;
    } else if (base === "COLUMNS") {
      const c = primaryColumnsHier();
      if (!c) { toasts.warning("No COLUMNS hierarchy", "Drop a hierarchy onto COLUMNS before using percent-of-column-total."); return; }
      denomTuple = `([Measures].[${t.measure.name}], ${c}.CurrentMember.Parent)`;
    } else {
      denomTuple = `([Measures].[${t.measure.name}])`;
    }
    const formula = `IIF(${denomTuple} = 0, null, [Measures].[${t.measure.name}] / ${denomTuple})`;
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
    const p = primaryRowsHier();
    if (!p) { toasts.warning("No ROWS hierarchy", "Growth needs a hierarchy on ROWS to reference a previous/first member."); return; }
    const calcName = `${m.name} growth`;
    let prev: string;
    if (basis === "previous") prev = `(${m.uniqueName}, ${p.hier}.CurrentMember.PrevMember)`;
    else if (basis === "first") prev = `(${m.uniqueName}, ${p.hier}.CurrentMember.FirstSibling)`;
    else if (basis === "specific" && ref) prev = `(${m.uniqueName}, ${ref})`;
    else { toasts.warning("Reference missing", "Provide a member unique name for the 'specific' basis."); return; }
    const formula = `IIF(${prev} = 0, null, (${m.uniqueName} - ${prev}) / ${prev})`;
    const next = (query.current.queryModel.calculatedMeasures ?? []).filter(
      (x) => (x as { name?: string }).name !== calcName,
    );
    next.push({ name: calcName, formula, properties: { FORMAT_STRING: "0.00%", SOLVE_ORDER: "300" } });
    query.current.queryModel.calculatedMeasures = next;
    query.addMeasure({ name: calcName, uniqueName: `[Measures].[${calcName}]`, caption: calcName, type: "CALCULATED" });
    toasts.success("Growth calc added", calcName);
  }

  function baseAxisSet(axisLoc: AxisLocation): string | null {
    const model = query.current?.queryModel;
    if (!model) return null;
    const axis = model.axes[axisLoc];
    if (axis.mdx) return axis.mdx;
    const hierarchies = axis.hierarchies;
    if (hierarchies.length === 0) return null;
    const parts: string[] = [];
    for (const h of hierarchies) {
      const lvls = Object.keys(h.levels);
      if (lvls.length === 0) return null;
      parts.push(`${h.name}.[${lvls[lvls.length - 1]}].Members`);
    }
    return parts.length === 1 ? parts[0] : `CROSSJOIN(${parts.join(", ")})`;
  }

  function onAxisFilterSave(expression: string, sort?: string) {
    axisFilterOpen = false;
    const t = axisFilterTarget;
    if (!t || !query.current?.queryModel) return;
    const axis = query.current.queryModel.axes[t.axis];
    if (t.type === "Order") {
      axis.sortOrder = sort ?? "ASC";
      axis.sortEvaluationLiteral = expression || null;
      toasts.success("Sort applied", `${t.axis}: ${sort ?? "ASC"} by ${expression}`);
      void query.run();
      return;
    }
    const base = baseAxisSet(t.axis);
    if (!base) {
      toasts.warning(`${t.axis} is empty`, `Drop a hierarchy onto ${t.axis} before applying an axis MDX expression.`);
      return;
    }
    if (t.type === "TopCount" || t.type === "BottomCount") {
      const fn = t.type.toUpperCase();
      axis.mdx = `${fn}(${base}, ${expression})`;
    } else if (t.type === "Limit") {
      axis.mdx = `HEAD(${base}, ${expression})`;
    } else {
      axis.mdx = `FILTER(${base}, ${expression})`;
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
        e.dataTransfer?.types?.includes("application/x-saiku-measure") ||
        e.dataTransfer?.types?.includes("application/x-saiku-chip")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
    }
  }

  let dragOverAxis = $state<AxisLocation | null>(null);
  let dragOverChipKey = $state<string | null>(null);
  /** Where the chip being dragged came from. Null for sidebar-originated drags
   *  (where any zone is a valid drop). Used to suppress no-op zone highlights
   *  when a chip is dragged over its own axis's empty background. */
  let dragSourceAxis = $state<AxisLocation | null>(null);

  function chipKey(axis: AxisLocation, kind: "hierarchy" | "measure", id: string): string {
    return `${axis}::${kind}::${id}`;
  }
  function onDragEnterAxis(axis: AxisLocation, e: DragEvent) {
    const types = e.dataTransfer?.types;
    if (!types) return;
    const isChipDrag = types.includes("application/x-saiku-chip");
    if (isChipDrag && dragSourceAxis === axis) {
      // Dragging a chip back onto its own axis's empty background is a no-op;
      // don't light up the zone — only chip-to-chip reorder should show feedback.
      return;
    }
    if (types.includes("application/x-saiku-level") ||
        types.includes("application/x-saiku-measure") ||
        isChipDrag) {
      dragOverAxis = axis;
    }
  }
  function onDragLeaveAxis(axis: AxisLocation, e: DragEvent) {
    // Only clear if we truly left the dropzone (not just crossed into a child chip).
    const related = e.relatedTarget as Node | null;
    const zone = e.currentTarget as HTMLElement;
    if (!related || !zone.contains(related)) {
      if (dragOverAxis === axis) dragOverAxis = null;
    }
  }
  function clearDragOver() { dragOverAxis = null; dragOverChipKey = null; dragSourceAxis = null; }

  function onChipDragOver(e: DragEvent, axis: AxisLocation, kind: "hierarchy" | "measure", id: string) {
    if (!e.dataTransfer?.types?.includes("application/x-saiku-chip")) return;
    e.preventDefault();
    e.stopPropagation();
    const key = chipKey(axis, kind, id);
    if (dragOverChipKey !== key) dragOverChipKey = key;
    if (dragOverAxis !== null) dragOverAxis = null;
  }
  function onChipDragEnter(e: DragEvent) {
    // swallow so the zone's ondragenter doesn't re-light the whole dropzone
    if (e.dataTransfer?.types?.includes("application/x-saiku-chip")) {
      e.stopPropagation();
    }
  }

  function onDropAxis(axis: AxisLocation, e: DragEvent) {
    e.preventDefault();
    const chipPayload = e.dataTransfer?.getData("application/x-saiku-chip");
    const levelPayload = e.dataTransfer?.getData("application/x-saiku-level");
    const measurePayload = e.dataTransfer?.getData("application/x-saiku-measure");
    if (chipPayload) {
      // Chip moved between axes. Measures on COLUMNS are axis-locked so we
      // punt on moving them (reorder-within-axis not supported either).
      try {
        const p = JSON.parse(chipPayload) as
          | { kind: "hierarchy"; axis: AxisLocation; name: string }
          | { kind: "measure"; axis: AxisLocation; uniqueName: string };
        if (p.kind === "hierarchy") {
          if (p.axis === axis) return; // no-op, same axis
          query.moveHierarchyToAxis(p.name, axis);
        }
        // measure chips: ignore non-COLUMNS targets; no reorder support yet.
      } catch {
        /* malformed payload — ignore */
      }
    } else if (levelPayload) {
      const drop = JSON.parse(levelPayload);
      query.includeLevel(axis, drop);
    } else if (measurePayload) {
      const m = JSON.parse(measurePayload) as ThinMeasure;
      query.addMeasure(m);
    }
  }

  function onHierChipDragStart(e: DragEvent, axis: AxisLocation, h: ThinHierarchy) {
    const payload = { kind: "hierarchy" as const, axis, name: h.name };
    e.dataTransfer?.setData("application/x-saiku-chip", JSON.stringify(payload));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
    dragSourceAxis = axis;
  }

  function onMeasureChipDragStart(e: DragEvent, m: ThinMeasure) {
    const payload = { kind: "measure" as const, axis: "COLUMNS" as AxisLocation, uniqueName: m.uniqueName };
    e.dataTransfer?.setData("application/x-saiku-chip", JSON.stringify(payload));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
    dragSourceAxis = "COLUMNS";
  }

  /** Drop a dragged chip onto a sibling chip — reorder within axis if same-kind,
   *  otherwise bubble to the zone to handle as a cross-axis move. */
  function onChipDrop(e: DragEvent, targetAxis: AxisLocation, target: { kind: "hierarchy"; name: string } | { kind: "measure"; uniqueName: string }) {
    const chipPayload = e.dataTransfer?.getData("application/x-saiku-chip");
    if (!chipPayload) return; // not a chip drag; let the zone handle it
    const payload = JSON.parse(chipPayload) as
      | { kind: "hierarchy"; axis: AxisLocation; name: string }
      | { kind: "measure"; axis: AxisLocation; uniqueName: string };
    // Only same-axis, same-kind drops are reorders. Otherwise defer to the zone handler.
    if (payload.kind === "hierarchy" && target.kind === "hierarchy" && payload.axis === targetAxis) {
      if (payload.name !== target.name) {
        e.preventDefault();
        e.stopPropagation();
        clearDragOver();
        query.reorderHierarchy(targetAxis, payload.name, target.name);
      }
      return;
    }
    if (payload.kind === "measure" && target.kind === "measure" && targetAxis === "COLUMNS") {
      if (payload.uniqueName !== target.uniqueName) {
        e.preventDefault();
        e.stopPropagation();
        clearDragOver();
        query.reorderMeasure(payload.uniqueName, target.uniqueName);
      }
      return;
    }
    // mismatched kinds or different axis: let the dropzone handle as a move.
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

<svelte:window ondragend={clearDragOver} />

<div class="canvas">
  {#if !selection.cube}
    <div class="canvas__empty">
      <p>{i18n.t("canvas.noCube")}</p>
      <p class="canvas__hint">{i18n.t("canvas.pickPrompt")}</p>
    </div>
  {:else}
    <div class="canvas__body">
    <aside class="dropzones">
      {#each ["COLUMNS", "ROWS"] as const as axis}
        <div
          class={dragOverAxis === axis ? "dropzone is-dragover" : "dropzone"}
          role="region"
          aria-label={axisLabels[axis]}
          ondragover={onDragOver}
          ondragenter={(e) => onDragEnterAxis(axis, e)}
          ondragleave={(e) => onDragLeaveAxis(axis, e)}
          ondrop={(e) => { clearDragOver(); onDropAxis(axis, e); }}
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
                <span
                  class={dragOverChipKey === chipKey("COLUMNS", "measure", m.uniqueName) ? "chip chip--measure is-drop-before" : "chip chip--measure"}
                  draggable="true"
                  ondragstart={(e) => onMeasureChipDragStart(e, m)}
                  ondragenter={onChipDragEnter}
                  ondragover={(e) => onChipDragOver(e, "COLUMNS", "measure", m.uniqueName)}
                  ondrop={(e) => onChipDrop(e, "COLUMNS", { kind: "measure", uniqueName: m.uniqueName })}
                  oncontextmenu={(e) => openMeasureMenu(e, m)}
                >
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
              <span
                class={dragOverChipKey === chipKey(axis, "hierarchy", h.name) ? "chip chip--level is-drop-before" : "chip chip--level"}
                draggable="true"
                ondragstart={(e) => onHierChipDragStart(e, axis, h)}
                ondragenter={onChipDragEnter}
                ondragover={(e) => onChipDragOver(e, axis, "hierarchy", h.name)}
                ondrop={(e) => onChipDrop(e, axis, { kind: "hierarchy", name: h.name })}
                oncontextmenu={(e) => openHierMenu(e, axis, h)}
              >
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
      <div
        class={dragOverAxis === "FILTER" ? "dropzone dropzone--filter is-dragover" : "dropzone dropzone--filter"}
        role="region"
        aria-label="Filter"
        ondragover={onDragOver}
        ondragenter={(e) => onDragEnterAxis("FILTER", e)}
        ondragleave={(e) => onDragLeaveAxis("FILTER", e)}
        ondrop={(e) => { clearDragOver(); onDropAxis("FILTER", e); }}
      >
        <header>
          <span>{axisLabels.FILTER}</span>
          <button type="button" class="dropzone__menu" title="Axis options" onclick={(e) => openAxisMenu(e, "FILTER")}>
            <MoreHorizontal size={14} />
          </button>
        </header>
        <div class="chips">
          {#each query.current?.queryModel?.axes.FILTER.hierarchies ?? [] as h}
            <span
              class={dragOverChipKey === chipKey("FILTER", "hierarchy", h.name) ? "chip chip--level is-drop-before" : "chip chip--level"}
              draggable="true"
              ondragstart={(e) => onHierChipDragStart(e, "FILTER", h)}
              ondragenter={onChipDragEnter}
              ondragover={(e) => onChipDragOver(e, "FILTER", "hierarchy", h.name)}
              ondrop={(e) => onChipDrop(e, "FILTER", { kind: "hierarchy", name: h.name })}
              oncontextmenu={(e) => openHierMenu(e, "FILTER", h)}
            >
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
    </aside>
    <div class="canvas__result">
    <div class="view-toggle" role="tablist" aria-label="Result view">
      <button type="button" role="tab" class:active={viewMode === "grid"} onclick={() => (viewMode = "grid")}>
        {i18n.t("canvas.view.grid")}
      </button>
      <button type="button" role="tab" class:active={viewMode === "chart"} onclick={() => (viewMode = "chart")}>
        {i18n.t("canvas.view.chart")}
      </button>
      <div class="view-more">
        <button
          type="button"
          role="tab"
          class:active={isMoreMode(viewMode)}
          onclick={() => (moreViewOpen = !moreViewOpen)}
          title="Other views"
        >
          <span>{isMoreMode(viewMode) ? moreViewLabels[viewMode] : "More"}</span>
          <ChevronDown size={14} />
        </button>
        {#if moreViewOpen}
          <div class="view-more__menu" role="menu">
            {#each moreViewModes as m}
              <button
                type="button"
                role="menuitem"
                class:active={viewMode === m}
                onclick={() => pickMoreMode(m)}
              >
                {moreViewLabels[m]}
              </button>
            {/each}
          </div>
        {/if}
      </div>
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
    {#if query.running}
      <div class="run-progress" role="status" aria-live="polite">
        <Loader2 class="spin" size={14} />
        <span>Running… ({(query.runningElapsedMs / 1000).toFixed(1)}s)</span>
        <button class="tb-btn tb-btn--ghost tb-btn--sm" onclick={() => query.cancel()} title="Cancel query">
          <XCircle size={14} />
          <span>Cancel</span>
        </button>
      </div>
    {/if}
    <div class="result-host" bind:this={resultHostEl}>
      {#if query.running && !query.result}
        <p class="canvas__hint">{i18n.t("canvas.running")}</p>
      {:else if query.error}
        <p class="callout callout--danger">{query.error}</p>
      {:else if query.result}
        {#if viewMode === "chart"}
          <ChartView result={query.result} type={chartType} options={chartOptions} />
        {:else if viewMode === "stats"}
          <StatsView result={query.result} />
        {:else if viewMode === "sparkline"}
          <CellsetTable result={query.result} spark="line" />
        {:else if viewMode === "sparkbar"}
          <CellsetTable result={query.result} spark="bar" />
        {:else}
          <CellsetTable result={query.result} />
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
  .dropzone.is-dragover {
    border-style: solid;
    border-color: var(--accent);
    background: color-mix(in srgb, var(--accent) 12%, var(--bg-muted));
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--accent) 30%, transparent);
  }
  /* Keep pointer events enabled on chips so chip-to-chip reorder drops still fire. */
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
  .chip.is-drop-before {
    box-shadow: inset 3px 0 0 0 var(--accent), 0 0 0 1px var(--accent);
    border-color: var(--accent);
  }
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
  .view-more { position: relative; display: inline-flex; }
  .view-more > button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .view-more__menu {
    position: absolute;
    top: calc(100% + 4px);
    left: 0;
    min-width: 140px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
    padding: var(--space-1) 0;
    z-index: 20;
    display: flex;
    flex-direction: column;
  }
  .view-more__menu button {
    text-align: left;
    padding: var(--space-1) var(--space-3);
    background: transparent;
    border: none;
    color: var(--fg);
    cursor: pointer;
    font: inherit;
  }
  .view-more__menu button:hover { background: var(--bg-subtle); }
  .view-more__menu button.active { background: var(--accent); color: var(--accent-fg); }
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
  .run-progress {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-2);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg-muted);
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .run-progress :global(.spin) {
    animation: spin 900ms linear infinite;
  }
  .run-progress button {
    margin-left: auto;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    background: transparent;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    color: var(--fg);
    cursor: pointer;
    font: inherit;
  }
  .run-progress button:hover { background: var(--bg-subtle); }
  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
  }
</style>
