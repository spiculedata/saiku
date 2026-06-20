<script lang="ts">
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import type { AxisLocation, ThinHierarchy, ThinMeasure } from "$lib/api/query";

  /** UI-only zone identifier. The backend {@link AxisLocation} enum
   *  (COLUMNS / ROWS / PAGES / FILTER) is what actually round-trips to
   *  the server; "MEASURES" is a SvelteKit-only sentinel for the
   *  dedicated measures panel restored in saiku#1018. Internally the
   *  model still stores measures in `details.measures` and the MDX
   *  emitter still places them on `details.axis = COLUMNS`, so nothing
   *  changes server-side — this is purely about laying out the chip
   *  drop targets the way users from the Backbone-era UI expect. */
  type ZoneId = AxisLocation | "MEASURES";
  import CellsetTable from "$lib/views/CellsetTable.svelte";
  import ChartView from "$lib/views/ChartView.svelte";
  import StatsView from "$lib/views/StatsView.svelte";
  import { CHART_TYPES } from "$lib/views/chartTypes";
  import { parseCellset } from "$lib/views/cellsetUtils";
  import type { ViewMode } from "$lib/stores/query.svelte";
  import SelectionsModal from "$lib/modals/SelectionsModal.svelte";
  import DrillthroughModal from "$lib/modals/DrillthroughModal.svelte";
  import DrillthroughResultModal from "$lib/modals/DrillthroughResultModal.svelte";
  import ChartEditorModal from "$lib/modals/ChartEditorModal.svelte";
  import CustomFilterModal from "$lib/modals/CustomFilterModal.svelte";
  import FormatAsPercentageModal from "$lib/modals/FormatAsPercentageModal.svelte";
  import GrowthModal from "$lib/modals/GrowthModal.svelte";
  import FilterModal from "$lib/modals/FilterModal.svelte";
  import OrderModal from "$lib/modals/OrderModal.svelte";
  import TopBottomCountModal from "$lib/modals/TopBottomCountModal.svelte";
  import LimitModal from "$lib/modals/LimitModal.svelte";
  import DateFilterModal from "$lib/modals/DateFilterModal.svelte";
  import { isTimeHierarchy } from "$lib/modals/dateFilterMdx";
  import ContextMenu from "$lib/components/ContextMenu.svelte";
  type ContextMenuItem = { id: string; label: string; disabled?: boolean; danger?: boolean; sep?: boolean };
  import { MoreHorizontal, Loader2, XCircle, ChevronDown, Settings, Sparkles } from "lucide-svelte";
  import EmptyState from "$lib/components/EmptyState.svelte";
  import { listLevelMembers, listRootMembers, type SaikuMember } from "$lib/api/discover";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { drillthrough as fetchDrillthrough, type QueryResult } from "$lib/api/query";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";

  /** Issue #912 — when {@code embedded} is true the canvas is mounted
   *  inside the dashboard tile editor rather than the workspace. The host
   *  (TileEditorModal) owns the query-store lifecycle: it seeds the model
   *  from the tile's inline body and commits it back on Save. So in
   *  embedded mode we suppress the workspace-only auto-init effect that
   *  re-`initFor`s (and thereby wipes) the model whenever
   *  {@link selection.cube} changes, and clears it when no cube is
   *  selected. Everything else — drop zones, view toggle, result pane,
   *  context menus — is reused verbatim. */
  interface Props {
    embedded?: boolean;
  }
  let { embedded = false }: Props = $props();

  let moreViewOpen = $state(false);

  // saiku#1233 — consolidated open/target state for the modal cluster.
  // `null` means closed; a non-null value means open with that target.
  // chartEditor is bool-only (no per-open target). One source of truth
  // beats six independent open/target pairs scattered through this
  // file — see closeModal() for the close-and-clear sweep.
  type AxisFilterType = "Order" | "Filter" | "TopCount" | "BottomCount" | "Limit";
  interface ModalState {
    chartEditor: boolean;
    customFilter: ThinMeasure | null;
    formatPct: { measure: ThinMeasure } | null;
    growth: ThinMeasure | null;
    dateFilter: {
      axis: AxisLocation;
      hierarchyName: string;
      hierarchyCaption: string;
      levelName: string;
    } | null;
    axisFilter: {
      axis: AxisLocation;
      type: AxisFilterType;
      expression: string;
      sort: string;
    } | null;
  }
  let modals = $state<ModalState>({
    chartEditor: false,
    customFilter: null,
    formatPct: null,
    growth: null,
    dateFilter: null,
    axisFilter: null,
  });
  function closeModal(k: keyof ModalState): void {
    if (k === "chartEditor") modals.chartEditor = false;
    else modals[k] = null;
  }

  // Column-category labels from the latest cellset — passed into the
  // Chart Editor so the per-series Left/Right/Auto picker can list
  // measure names. Empty when there's no result yet; the editor's
  // picker section hides itself in that case.
  const chartSeriesNames = $derived.by((): string[] => {
    const r = query.result;
    if (!r || !Array.isArray(r.cellset) || r.cellset.length === 0) return [];
    try {
      const parsed = parseCellset(r);
      return parsed.columnCategories.slice();
    } catch {
      return [];
    }
  });
  const moreViewModes: ViewMode[] = ["stats", "sparkline", "sparkbar"];
  function viewModeLabel(m: ViewMode): string {
    return i18n.t(`canvas.view.${m}`);
  }
  function isMoreMode(m: ViewMode): boolean {
    return (moreViewModes as ViewMode[]).includes(m);
  }
  function pickMoreMode(m: ViewMode) {
    query.viewMode = m;
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
    kind: "measure" | "hierarchy" | "axis" | "measures-panel" | null;
    axis: AxisLocation | null;
    measure: ThinMeasure | null;
    hierarchy: ThinHierarchy | null;
  }
  let menu = $state<MenuCtx>({ open: false, x: 0, y: 0, items: [], kind: null, axis: null, measure: null, hierarchy: null });


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
        { id: "filter", label: i18n.t("canvas.menu.filterValue") },
        { id: "format-pct", label: i18n.t("canvas.menu.formatPercent") },
        { id: "growth", label: i18n.t("canvas.menu.growthCalc") },
        { id: "_sep", sep: true, label: "" },
        { id: "remove", label: i18n.t("canvas.menu.removeMeasure"), danger: true },
      ],
    };
  }

  function openHierMenu(e: MouseEvent, axis: AxisLocation, h: ThinHierarchy) {
    e.preventDefault();
    e.stopPropagation();
    // When the chip carries more than one level, surface a per-level
    // removal item so users can drop "Quarter" without losing "Year".
    // The flat-menu approach (vs a submenu) keeps ContextMenu.svelte
    // simple; a hierarchy with >5 levels would benefit from a submenu,
    // but those are rare enough that this trade-off is fine for now.
    const levelNames = Object.keys(h.levels);
    // saiku#1221 Phase 4: skip the Selections detour for time-typed chips.
    // If the dimension or any level is Mondrian-native time-typed, surface
    // "Date filter…" as a primary item directly on the hierarchy menu.
    // Selections is still available for users who want the member picker
    // (e.g. "just Q1 + Q3"), but it's no longer the only entry point.
    const isTime = isHierarchyTimeTyped(h);
    const items: ContextMenuItem[] = [];
    if (isTime) {
      items.push({ id: "dateFilter", label: i18n.t("canvas.menu.dateFilter") });
    }
    items.push({ id: "selections", label: i18n.t("canvas.menu.editSelections") });
    items.push({ id: "_sep1", sep: true, label: "" });
    if (levelNames.length > 1) {
      for (const levelName of levelNames) {
        items.push({
          id: `removeLevel:${levelName}`,
          label: `${i18n.t("canvas.menu.removeLevel")}: ${levelName}`,
        });
      }
      items.push({ id: "_sep2", sep: true, label: "" });
    }
    items.push({ id: "remove", label: i18n.t("canvas.menu.removeHierarchy"), danger: true });
    menu = {
      open: true,
      x: e.clientX, y: e.clientY,
      kind: "hierarchy",
      axis, measure: null, hierarchy: h,
      items,
    };
  }

  /** True if the hierarchy's dimension is Mondrian-typed TIME or any of its
   *  levels carries a {@code levelType="Time*"} annotation. Used by the
   *  context-menu builder to surface "Date filter…" as a primary action. */
  function isHierarchyTimeTyped(h: ThinHierarchy): boolean {
    for (const d of cubeMetadata?.dimensions ?? []) {
      for (const hh of d.hierarchies ?? []) {
        if (hh.uniqueName === h.name || hh.name === h.name) {
          if (d.dimensionType === "TIME") return true;
          return !!hh.levels?.some((l) => !!l.levelType && l.levelType.startsWith("Time"));
        }
      }
    }
    return false;
  }

  function openMeasuresMenu(e: MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    // Mirrors the legacy Backbone "Position" picker. Reads the current
    // (axis, location) so the matching item can show a check (or just
    // be implied — the previous-selected item is the no-op).
    menu = {
      open: true,
      x: e.clientX, y: e.clientY,
      kind: "measures-panel",
      axis: null, measure: null, hierarchy: null,
      items: [
        { id: "_hdr", label: i18n.t("canvas.menu.position"), disabled: true },
        { id: "_sep1", sep: true, label: "" },
        { id: "pos:BOTTOM_COLUMNS", label: i18n.t("canvas.menu.position.colsMeasures") },
        { id: "pos:TOP_COLUMNS", label: i18n.t("canvas.menu.position.measuresCols") },
        { id: "pos:BOTTOM_ROWS", label: i18n.t("canvas.menu.position.rowsMeasures") },
        { id: "pos:TOP_ROWS", label: i18n.t("canvas.menu.position.measuresRows") },
        { id: "_sep2", sep: true, label: "" },
        { id: "pos:reset", label: i18n.t("canvas.menu.position.reset") },
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
        { id: "filter-order", label: i18n.t("canvas.menu.orderMdx") },
        { id: "filter-filter", label: i18n.t("canvas.menu.filterMdx") },
        { id: "filter-top", label: i18n.t("canvas.menu.topCount") },
        { id: "filter-bot", label: i18n.t("canvas.menu.bottomCount") },
        { id: "filter-limit", label: i18n.t("canvas.menu.limit") },
      ],
    };
  }

  function onMenuPick(id: string) {
    const m = menu;
    menu = { ...m, open: false };
    if (!id) return;
    if (m.kind === "measure" && m.measure) {
      if (id === "filter") {
        modals.customFilter = m.measure;
      } else if (id === "format-pct") {
        modals.formatPct = { measure: m.measure };
      } else if (id === "growth") {
        modals.growth = m.measure;
      } else if (id === "remove") {
        query.removeMeasure(m.measure.uniqueName);
      }
      return;
    }
    if (m.kind === "hierarchy" && m.axis && m.hierarchy) {
      if (id === "selections") openSelections(m.axis, m.hierarchy);
      else if (id === "dateFilter") openDateFilterDirect(m.axis, m.hierarchy);
      else if (id === "remove") query.removeHierarchy(m.hierarchy.name);
      else if (id.startsWith("removeLevel:")) {
        const levelName = id.substring("removeLevel:".length);
        query.removeLevel(m.hierarchy.name, levelName);
        if (query.hasRunnableShape()) void query.run();
      }
      return;
    }
    if (m.kind === "measures-panel") {
      if (!id.startsWith("pos:")) return;
      const arg = id.substring("pos:".length);
      if (arg === "reset") {
        query.setMeasuresPlacement("COLUMNS", "BOTTOM");
      } else {
        // BOTTOM_COLUMNS | TOP_COLUMNS | BOTTOM_ROWS | TOP_ROWS
        const [location, axis] = arg.split("_") as ["TOP" | "BOTTOM", "COLUMNS" | "ROWS"];
        query.setMeasuresPlacement(axis, location);
      }
      if (query.hasRunnableShape()) void query.run();
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
      modals.axisFilter = { axis, type, expression: placeholder, sort: sortOrder };
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
    closeModal("customFilter");
    const m = modals.customFilter;
    if (!m || !query.current?.queryModel) return;
    const set = rowsAxisSet();
    if (!set) {
      toasts.warning(i18n.t("toast.dropRowsFirst"), i18n.t("toast.dropRowsFirst.body"));
      return;
    }
    let expr: string;
    if (op === "BETWEEN") expr = `${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2}`;
    else if (op === "NOT BETWEEN") expr = `NOT (${m.uniqueName} >= ${value} AND ${m.uniqueName} <= ${value2})`;
    else expr = `${m.uniqueName} ${op} ${value}`;
    query.current.queryModel.axes.ROWS.mdx = `FILTER(${set}, ${expr})`;
    toasts.success(i18n.t("toast.filterApplied"), `${m.caption} ${op} ${value}`);
    void query.run();
  }

  function onFormatPctApply(base: "ROWS" | "COLUMNS" | "GRAND_TOTAL", _scope: "all" | "selected") {
    closeModal("formatPct");
    const t = modals.formatPct;
    if (!t || !query.current?.queryModel) return;
    const calcName = `${t.measure.name} %`;
    let denomTuple: string;
    if (base === "ROWS") {
      const p = primaryRowsHier();
      if (!p) { toasts.warning(i18n.t("toast.noRowsHier"), i18n.t("toast.noRowsHier.pct")); return; }
      denomTuple = `([Measures].[${t.measure.name}], ${p.hier}.CurrentMember.Parent)`;
    } else if (base === "COLUMNS") {
      const c = primaryColumnsHier();
      if (!c) { toasts.warning(i18n.t("toast.noColsHier"), i18n.t("toast.noColsHier.pct")); return; }
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
    toasts.success(i18n.t("toast.formatPct"), calcName);
  }

  function onGrowthApply(basis: string, ref?: string) {
    closeModal("growth");
    const m = modals.growth;
    if (!m || !query.current?.queryModel) return;
    const p = primaryRowsHier();
    if (!p) { toasts.warning(i18n.t("toast.noRowsHier"), i18n.t("toast.noRowsHier.growth")); return; }
    const calcName = `${m.name} growth`;
    let prev: string;
    if (basis === "previous") prev = `(${m.uniqueName}, ${p.hier}.CurrentMember.PrevMember)`;
    else if (basis === "first") prev = `(${m.uniqueName}, ${p.hier}.CurrentMember.FirstSibling)`;
    else if (basis === "specific" && ref) prev = `(${m.uniqueName}, ${ref})`;
    else { toasts.warning(i18n.t("toast.refMissing"), i18n.t("toast.refMissing.body")); return; }
    const formula = `IIF(${prev} = 0, null, (${m.uniqueName} - ${prev}) / ${prev})`;
    const next = (query.current.queryModel.calculatedMeasures ?? []).filter(
      (x) => (x as { name?: string }).name !== calcName,
    );
    next.push({ name: calcName, formula, properties: { FORMAT_STRING: "0.00%", SOLVE_ORDER: "300" } });
    query.current.queryModel.calculatedMeasures = next;
    query.addMeasure({ name: calcName, uniqueName: `[Measures].[${calcName}]`, caption: calcName, type: "CALCULATED" });
    toasts.success(i18n.t("toast.growthAdded"), calcName);
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
    closeModal("axisFilter");
    const t = modals.axisFilter;
    if (!t || !query.current?.queryModel) return;
    const axis = query.current.queryModel.axes[t.axis];
    if (t.type === "Order") {
      axis.sortOrder = sort ?? "ASC";
      axis.sortEvaluationLiteral = expression || null;
      toasts.success(i18n.t("toast.sortApplied"), `${t.axis}: ${sort ?? "ASC"} by ${expression}`);
      void query.run();
      return;
    }
    const base = baseAxisSet(t.axis);
    if (!base) {
      toasts.warning(`${t.axis} ${i18n.t("toast.axisEmpty")}`, i18n.t("toast.axisEmpty.body").replace("{axis}", t.axis));
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
    toasts.success(i18n.t("toast.axisExprApplied"), `${t.type} on ${t.axis}`);
    void query.run();
  }

  function onDateFilterApply(mdx: string) {
    closeModal("dateFilter");
    const t = modals.dateFilter;
    if (!t || !query.current?.queryModel) return;
    const axis = query.current.queryModel.axes[t.axis];
    const hadExisting = !!axis.mdx;
    axis.mdx = mdx;
    if (hadExisting) {
      toasts.warning(i18n.t("toast.dateFilter"), i18n.t("toast.dateFilter.replaced"));
    } else {
      toasts.success(i18n.t("toast.dateFilter"), i18n.t("toast.dateFilter.body"));
    }
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

  const axisLabels = $derived<Record<ZoneId, string>>({
    COLUMNS: i18n.t("canvas.columns"),
    ROWS: i18n.t("canvas.rows"),
    FILTER: i18n.t("canvas.filter"),
    PAGES: i18n.t("canvas.pages"),
    MEASURES: i18n.t("canvas.measures"),
  });

  $effect(() => {
    // #912: in the embedded (tile-editor) host the modal owns the query
    // lifecycle — seeding the model from the tile body and committing it
    // back on Save. Re-`initFor`ing here would clobber that seeded model
    // the instant the cube is mirrored into `selection`, so opt out.
    if (embedded) return;
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

  let dragOverAxis = $state<ZoneId | null>(null);
  let dragOverChipKey = $state<string | null>(null);
  // Whether the pointer is below the target chip's mid-line (drop-after)
  // or above (drop-before). Drives the visual drop-line indicator AND
  // the reorderHierarchy insert-position decision in onChipDrop.
  let dragOverChipAfter = $state<boolean>(false);
  /** Where the chip being dragged came from. Null for sidebar-originated drags
   *  (where any zone is a valid drop). Used to suppress no-op zone highlights
   *  when a chip is dragged over its own axis's empty background. */
  let dragSourceAxis = $state<ZoneId | null>(null);

  function chipKey(axis: ZoneId, kind: "hierarchy" | "measure", id: string): string {
    return `${axis}::${kind}::${id}`;
  }
  function onDragEnterAxis(axis: ZoneId, e: DragEvent) {
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
  function onDragLeaveAxis(axis: ZoneId, e: DragEvent) {
    // Only clear if we truly left the dropzone (not just crossed into a child chip).
    const related = e.relatedTarget as Node | null;
    const zone = e.currentTarget as HTMLElement;
    if (!related || !zone.contains(related)) {
      if (dragOverAxis === axis) dragOverAxis = null;
    }
  }
  function clearDragOver() { dragOverAxis = null; dragOverChipKey = null; dragOverChipAfter = false; dragSourceAxis = null; }

  function onChipDragOver(e: DragEvent, axis: ZoneId, kind: "hierarchy" | "measure", id: string) {
    if (!e.dataTransfer?.types?.includes("application/x-saiku-chip")) return;
    e.preventDefault();
    e.stopPropagation();
    const key = chipKey(axis, kind, id);
    // Pick before/after based on mouse Y vs chip mid-point. Without this,
    // every drop is "before target", which means dragging the FIRST chip
    // onto the second produces no change (source already comes before
    // target after the splice). With before/after detection the
    // interaction reads symmetrically — drop above the mid-line → before,
    // drop below → after.
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const after = e.clientY > rect.top + rect.height / 2;
    if (dragOverChipKey !== key || dragOverChipAfter !== after) {
      dragOverChipKey = key;
      dragOverChipAfter = after;
    }
    if (dragOverAxis !== null) dragOverAxis = null;
  }
  function onChipDragEnter(e: DragEvent) {
    // swallow so the zone's ondragenter doesn't re-light the whole dropzone
    if (e.dataTransfer?.types?.includes("application/x-saiku-chip")) {
      e.stopPropagation();
    }
  }

  function onDropAxis(axis: ZoneId, e: DragEvent) {
    e.preventDefault();
    const chipPayload = e.dataTransfer?.getData("application/x-saiku-chip");
    const levelPayload = e.dataTransfer?.getData("application/x-saiku-level");
    const measurePayload = e.dataTransfer?.getData("application/x-saiku-measure");
    if (chipPayload) {
      try {
        const p = JSON.parse(chipPayload) as
          | { kind: "hierarchy"; axis: ZoneId; name: string }
          | { kind: "measure"; axis: ZoneId; uniqueName: string };
        if (p.kind === "hierarchy") {
          if (axis === "MEASURES") return; // levels can't live in MEASURES
          if (p.axis === axis) return; // no-op, same axis
          query.moveHierarchyToAxis(p.name, axis);
        }
        // measure chips: stay in the MEASURES panel; reorder handled by onChipDrop.
      } catch {
        /* malformed payload — ignore */
      }
    } else if (levelPayload) {
      if (axis === "MEASURES") return; // levels can't be dropped on the measures panel
      const drop = JSON.parse(levelPayload);
      query.includeLevel(axis, drop);
    } else if (measurePayload) {
      // Both MEASURES (new home) and COLUMNS (muscle memory from the
      // old "drop measures with levels" pattern) accept measure drops;
      // either way addMeasure stashes the measure in details.measures
      // which the MDX emitter will project onto details.axis (= COLUMNS).
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
    const payload = { kind: "measure" as const, axis: "MEASURES" as ZoneId, uniqueName: m.uniqueName };
    e.dataTransfer?.setData("application/x-saiku-chip", JSON.stringify(payload));
    if (e.dataTransfer) e.dataTransfer.effectAllowed = "move";
    dragSourceAxis = "MEASURES";
  }

  /** Drop a dragged chip onto a sibling chip — reorder within axis if same-kind,
   *  otherwise bubble to the zone to handle as a cross-axis move. */
  function onChipDrop(e: DragEvent, targetAxis: ZoneId, target: { kind: "hierarchy"; name: string } | { kind: "measure"; uniqueName: string }) {
    const chipPayload = e.dataTransfer?.getData("application/x-saiku-chip");
    if (!chipPayload) return; // not a chip drag; let the zone handle it
    const payload = JSON.parse(chipPayload) as
      | { kind: "hierarchy"; axis: ZoneId; name: string }
      | { kind: "measure"; axis: ZoneId; uniqueName: string };
    // Only same-axis, same-kind drops are reorders. Otherwise defer to the zone handler.
    if (payload.kind === "hierarchy" && target.kind === "hierarchy" && payload.axis === targetAxis) {
      if (payload.name !== target.name && targetAxis !== "MEASURES") {
        e.preventDefault();
        e.stopPropagation();
        // Decide insert position based on the drag-over Y signal captured
        // in onChipDragOver. dropAfter → splice just after the target;
        // dropBefore → splice just before. reorderHierarchy takes a
        // targetName that the source lands BEFORE (or null = end), so
        // "after target" means we pass the name of the chip AFTER target
        // (or null when target is the last).
        const dropAfter = dragOverChipAfter;
        const list = query.current?.queryModel?.axes[targetAxis].hierarchies ?? [];
        let insertBefore: string | null = target.name;
        if (dropAfter) {
          const tIdx = list.findIndex((h) => h.name === target.name);
          insertBefore = tIdx >= 0 && tIdx + 1 < list.length ? list[tIdx + 1].name : null;
        }
        clearDragOver();
        query.reorderHierarchy(targetAxis, payload.name, insertBefore);
      }
      return;
    }
    if (payload.kind === "measure" && target.kind === "measure" && targetAxis === "MEASURES") {
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

  /** Open the date-filter modal directly on a hierarchy chip, skipping the
   *  SelectionsModal detour. Used for time-typed chips (saiku#1221 Phase 4)
   *  where browsing members first is rarely what the user wants. Picks the
   *  deepest declared level on the chip (leaf grain) so the modal opens on
   *  the chip's effective grain, not just the first level. */
  function openDateFilterDirect(axis: AxisLocation, hier: ThinHierarchy) {
    if (!selection.cube) return;
    const levelNames = Object.keys(hier.levels);
    const levelName = levelNames[levelNames.length - 1] ?? levelNames[0];
    if (!levelName) return;
    modals.dateFilter = {
      axis,
      hierarchyName: hier.name,
      hierarchyCaption: hier.caption ?? hier.name,
      levelName,
    };
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
          i18n.t("toast.loadMembersFailed"),
          err2 instanceof Error ? err2.message : String(err2),
        );
      }
    } finally {
      selectionsLoading = false;
    }
  }

  // #1176: collapse the query-builder above the result when the canvas body
  // is too narrow to show both side-by-side, so the result/chart gets the
  // full width instead of being pushed off-screen. Container-width based
  // (ResizeObserver), mirroring the dashboard grid's responsive stack.
  let bodyEl = $state<HTMLDivElement | null>(null);
  let bodyWidth = $state(0);
  const BODY_STACK_PX = 640;
  let bodyNarrow = $derived(bodyWidth > 0 && bodyWidth < BODY_STACK_PX);

  $effect(() => {
    const el = bodyEl;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      bodyWidth = entries[0]?.contentRect.width ?? el.clientWidth;
    });
    ro.observe(el);
    bodyWidth = el.clientWidth;
    return () => ro.disconnect();
  });

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

    const openDrillFromToolbar = () => {
      drillPosition = null;
      drillModalOpen = true;
    };
    window.addEventListener("saiku-open-drillthrough", openDrillFromToolbar);

    return () => {
      el.removeEventListener("saiku-filter-level", handler);
      el.removeEventListener("saiku-drillthrough", drillHandler);
      window.removeEventListener("saiku-open-drillthrough", openDrillFromToolbar);
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
      toasts.danger(i18n.t("toast.drillFailed"), err instanceof Error ? err.message : String(err));
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
    toasts.success(i18n.t("toast.saved"), i18n.t("toast.selectionsApplied").replace("{n}", String(uniqueNames.length)));
    if (query.hasRunnableShape()) await query.run();
  }
</script>

<svelte:window ondragend={clearDragOver} />

<div class="canvas">
  {#if !selection.cube}
    <div class="canvas__empty">
      <p>{i18n.t("canvas.noCube")}</p>
      <p class="text-fg-subtle text-sm m-0">{i18n.t("canvas.pickPrompt")}</p>
    </div>
  {:else}
    <div
      bind:this={bodyEl}
      class="canvas__body"
      class:canvas__body--mdx={query.current?.type === "MDX"}
      class:canvas__body--narrow={bodyNarrow && query.current?.type !== "MDX"}
    >
    {#if query.current?.type !== "MDX"}
    <aside class="flex flex-col gap-2 overflow-y-auto pr-1">
      <!-- Dedicated MEASURES panel (restored from the pre-rewrite UI).
           Sits above COLUMNS/ROWS so the chip stack visually separates
           the projection (measures) from the axes (levels). Drops here
           route to addMeasure, which stashes the measure in
           details.measures; details.axis remains "COLUMNS" server-side
           so the MDX emission is unchanged. -->
      <div
        class={dragOverAxis === "MEASURES" ? "dropzone dropzone--measures is-dragover" : "dropzone dropzone--measures"}
        role="region"
        aria-label={axisLabels.MEASURES}
        ondragover={onDragOver}
        ondragenter={(e) => onDragEnterAxis("MEASURES", e)}
        ondragleave={(e) => onDragLeaveAxis("MEASURES", e)}
        ondrop={(e) => { clearDragOver(); onDropAxis("MEASURES", e); }}
      >
        <header>
          <span>{axisLabels.MEASURES}</span>
          <button type="button" class="dropzone__menu" title={i18n.t("canvas.menu.position")} aria-label={i18n.t("canvas.menu.position")} onclick={openMeasuresMenu}>
            <MoreHorizontal size={14} />
          </button>
        </header>
        <div class="chips">
          {#each query.current?.queryModel?.details.measures ?? [] as m}
            <!-- svelte-ignore a11y_no_static_element_interactions — drag/context-menu are mouse affordances; the inner buttons (label, close) handle keyboard accessibility. -->
            <span
              class={dragOverChipKey === chipKey("MEASURES", "measure", m.uniqueName) ? "chip chip--measure is-drop-before" : "chip chip--measure"}
              draggable="true"
              ondragstart={(e) => onMeasureChipDragStart(e, m)}
              ondragenter={onChipDragEnter}
              ondragover={(e) => onChipDragOver(e, "MEASURES", "measure", m.uniqueName)}
              ondrop={(e) => onChipDrop(e, "MEASURES", { kind: "measure", uniqueName: m.uniqueName })}
              oncontextmenu={(e) => openMeasureMenu(e, m)}
            >
              <span class="chip__label" title={i18n.t("canvas.chip.rightClickHint")}>
                Σ {m.caption || m.name}
              </span>
              <button
                type="button"
                class="chip__x"
                title={i18n.t("canvas.menu.removeMeasure")}
                aria-label="{i18n.t('canvas.menu.removeMeasure')} {m.caption || m.name}"
                onclick={() => removeMeasure(m.uniqueName)}
              >×</button>
            </span>
          {/each}
          {#if (query.current?.queryModel?.details.measures.length ?? 0) === 0}
            <span class="chips__empty">{i18n.t("canvas.dropMeasures")}</span>
          {/if}
        </div>
      </div>
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
            <button type="button" class="dropzone__menu" title={i18n.t("canvas.axisOptions")} aria-label={i18n.t("canvas.axisOptions")} onclick={(e) => openAxisMenu(e, axis)}>
              <MoreHorizontal size={14} />
            </button>
          </header>
          <div class="chips">
            {#each query.current?.queryModel?.axes[axis].hierarchies ?? [] as h}
              {@const levelNames = Object.keys(h.levels)}
              <!-- svelte-ignore a11y_no_static_element_interactions — drag/context-menu are mouse affordances; the inner buttons (label, close) handle keyboard accessibility. -->
              <span
                class={"chip chip--level " +
                  (dragOverChipKey === chipKey(axis, "hierarchy", h.name)
                    ? (dragOverChipAfter ? "is-drop-after" : "is-drop-before")
                    : "")}
                draggable="true"
                ondragstart={(e) => onHierChipDragStart(e, axis, h)}
                ondragenter={onChipDragEnter}
                ondragover={(e) => onChipDragOver(e, axis, "hierarchy", h.name)}
                ondrop={(e) => onChipDrop(e, axis, { kind: "hierarchy", name: h.name })}
                oncontextmenu={(e) => openHierMenu(e, axis, h)}
              >
                <!-- Dim caption opens the selections modal. When the chip
                     carries multiple levels we follow with per-level pills
                     so each can be removed individually without losing the
                     whole hierarchy. -->
                <button
                  type="button"
                  class="chip__label"
                  onclick={() => openSelections(axis, h)}
                  title={i18n.t("canvas.menu.editSelections")}
                >
                  {h.caption || h.name}
                </button>
                {#each levelNames as lvl, i}
                  <span class="chip__lvl-sep">{i === 0 ? "›" : ","}</span>
                  <span class="chip__lvl">
                    {lvl}{#if levelNames.length > 1}<button
                      type="button"
                      class="chip__lvl-x"
                      aria-label="{i18n.t('canvas.menu.removeLevel')} {lvl}"
                      title="{i18n.t('canvas.menu.removeLevel')}: {lvl}"
                      onclick={(e) => {
                        e.stopPropagation();
                        query.removeLevel(h.name, lvl);
                        if (query.hasRunnableShape()) void query.run();
                      }}
                    >×</button>{/if}
                  </span>
                {/each}
                <button
                  type="button"
                  class="chip__x"
                  aria-label="{i18n.t('canvas.menu.removeHierarchy')} {h.caption || h.name}"
                  onclick={() => removeHier(h.name)}
                >×</button>
              </span>
            {/each}
            {#if (query.current?.queryModel?.axes[axis].hierarchies.length ?? 0) === 0}
              <span class="chips__empty">{i18n.t("canvas.dropLevels")}</span>
            {/if}
          </div>
        </div>
      {/each}
      <div
        class={dragOverAxis === "FILTER" ? "dropzone dropzone--filter is-dragover" : "dropzone dropzone--filter"}
        role="region"
        aria-label={i18n.t("canvas.filter")}
        ondragover={onDragOver}
        ondragenter={(e) => onDragEnterAxis("FILTER", e)}
        ondragleave={(e) => onDragLeaveAxis("FILTER", e)}
        ondrop={(e) => { clearDragOver(); onDropAxis("FILTER", e); }}
      >
        <header>
          <span>{axisLabels.FILTER}</span>
          <button type="button" class="dropzone__menu" title={i18n.t("canvas.axisOptions")} aria-label={i18n.t("canvas.axisOptions")} onclick={(e) => openAxisMenu(e, "FILTER")}>
            <MoreHorizontal size={14} />
          </button>
        </header>
        <div class="chips">
          {#each query.current?.queryModel?.axes.FILTER.hierarchies ?? [] as h}
            <!-- svelte-ignore a11y_no_static_element_interactions — drag/context-menu are mouse affordances; the inner buttons (label, close) handle keyboard accessibility. -->
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
                title={i18n.t("canvas.menu.editSelections")}
              >
                {hierChipLabel(h)}
              </button>
              <button
                type="button"
                class="chip__x"
                aria-label="{i18n.t('canvas.menu.removeHierarchy')} {h.caption || h.name}"
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
    {/if}
    <div class="min-w-0 min-h-0 flex flex-col gap-2 overflow-hidden">
    {#if query.current?.type === "MDX"}
      <div class="canvas__mdx-banner" role="status" title={i18n.t("canvas.mdxBannerText")}>
        <span class="canvas__mdx-badge">MDX</span>
        <span class="min-w-0 overflow-hidden text-ellipsis">{i18n.t("canvas.mdxBannerShort")}</span>
      </div>
    {/if}
    <div class="view-toggle" role="tablist" aria-label={i18n.t("canvas.resultView")}>
      <button type="button" role="tab" class:active={query.viewMode === "grid"} onclick={() => (query.viewMode = "grid")}>
        {i18n.t("canvas.view.grid")}
      </button>
      <button type="button" role="tab" class:active={query.viewMode === "chart"} onclick={() => (query.viewMode = "chart")}>
        {i18n.t("canvas.view.chart")}
      </button>
      <div class="view-more">
        <button
          type="button"
          role="tab"
          class:active={isMoreMode(query.viewMode)}
          onclick={() => (moreViewOpen = !moreViewOpen)}
          title={i18n.t("canvas.view.other")}
        >
          <span>{isMoreMode(query.viewMode) ? viewModeLabel(query.viewMode) : i18n.t("canvas.view.more")}</span>
          <ChevronDown size={14} />
        </button>
        {#if moreViewOpen}
          <div class="view-more__menu" role="menu">
            {#each moreViewModes as m}
              <button
                type="button"
                role="menuitem"
                class:active={query.viewMode === m}
                onclick={() => pickMoreMode(m)}
              >
                {viewModeLabel(m)}
              </button>
            {/each}
          </div>
        {/if}
      </div>
      {#if query.viewMode === "chart"}
        <label class="chart-pick">
          <span class="sr-only">{i18n.t("canvas.chartType")}</span>
          <select bind:value={query.chartType}>
            {#each chartGroups() as group}
              <optgroup label={group.name}>
                {#each group.items as c}
                  <option value={c.id}>{c.label}</option>
                {/each}
              </optgroup>
            {/each}
          </select>
        </label>
        <button type="button" class="tb-btn tb-btn--ghost" title={i18n.t("modal.chart.title")} aria-label={i18n.t("a11y.editChartOptions")} onclick={() => (modals.chartEditor = true)}>
          <Settings size={18} />
        </button>
      {/if}
    </div>
    {#if query.running}
      <div class="run-progress" role="status" aria-live="polite">
        <Loader2 class="spin" size={14} />
        <span>{i18n.t("canvas.running.short")} ({(query.runningElapsedMs / 1000).toFixed(1)}s)</span>
        <button class="tb-btn tb-btn--ghost tb-btn--sm" onclick={() => query.cancel()} title={i18n.t("canvas.cancelQuery")}>
          <XCircle size={14} />
          <span>{i18n.t("modal.cancel")}</span>
        </button>
      </div>
    {/if}
    <div class="result-host" bind:this={resultHostEl}>
      {#if query.running && !query.result}
        <p class="text-fg-subtle text-sm m-0">{i18n.t("canvas.running")}</p>
      {:else if query.error}
        <div class="callout callout--danger" role="alert">
          <p class="callout__text">{query.error}</p>
          {#if query.errorDetail}
            <details class="callout__details">
              <summary>{i18n.t("canvas.error.showDetails")}</summary>
              <pre class="callout__detail-text">{query.errorDetail}</pre>
            </details>
          {/if}
        </div>
      {:else if query.result}
        {#if query.viewMode === "chart"}
          <ChartView result={query.result} type={query.chartType} options={query.chartOptions} />
        {:else if query.viewMode === "stats"}
          <StatsView result={query.result} />
        {:else if query.viewMode === "sparkline"}
          <CellsetTable result={query.result} spark="line" />
        {:else if query.viewMode === "sparkbar"}
          <CellsetTable result={query.result} spark="bar" />
        {:else}
          <CellsetTable result={query.result} />
        {/if}
      {:else}
        <EmptyState
          icon={Sparkles}
          title="Build a query"
          description={i18n.t("canvas.buildPrompt")}
        />
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
    showDateFilter={(() => {
      // Resolve the hierarchy's Mondrian-native type from cubeMetadata. This
      // beats the legacy caption substring match — translations and renamed
      // hierarchies no longer hide the date filter button (saiku#1221).
      const hierName = selectionsTarget.hierarchyName;
      let dimensionType: string | undefined;
      let levels: { levelType?: string }[] | undefined;
      for (const d of cubeMetadata?.dimensions ?? []) {
        for (const h of d.hierarchies ?? []) {
          if (h.uniqueName === hierName || h.name === hierName) {
            dimensionType = d.dimensionType;
            levels = h.levels;
            break;
          }
        }
        if (dimensionType !== undefined || levels) break;
      }
      return isTimeHierarchy(dimensionType, levels, selectionsTarget.hierarchyCaption);
    })()}
    onOpenDateFilter={() => {
      // Hand off the currently-open Selections target to the date-filter
      // modal. Closing Selections first avoids stacked overlays.
      if (!selectionsTarget) return;
      modals.dateFilter = { ...selectionsTarget };
      selectionsOpen = false;
    }}
    onCancel={() => (selectionsOpen = false)}
  />
{/if}

{#if selectionsLoading && selectionsOpen}
  <p class="callout">{i18n.t("canvas.loadingMembers")}</p>
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
  initial={query.chartOptions}
  chartType={query.chartType}
  seriesNames={chartSeriesNames}
  open={modals.chartEditor}
  onSave={(next) => { query.chartOptions = next; modals.chartEditor = false; }}
  onCancel={() => (modals.chartEditor = false)}
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
  measureCaption={modals.customFilter?.caption ?? modals.customFilter?.name ?? ""}
  open={!!modals.customFilter}
  onApply={onCustomFilterApply}
  onCancel={() => (closeModal("customFilter"))}
/>

<FormatAsPercentageModal
  defaultAxis="COLUMNS"
  scope="all"
  open={!!modals.formatPct}
  onApply={onFormatPctApply}
  onCancel={() => (closeModal("formatPct"))}
/>

<GrowthModal
  open={!!modals.growth}
  onApply={onGrowthApply}
  onCancel={() => (closeModal("growth"))}
/>

{#if modals.dateFilter}
  {@const dfHierLevels = (() => {
    // Resolve the sibling levels of the chip — needed for the Compare tab
    // to find the right ParallelPeriod anchor (saiku#1221 Phase 2).
    const hierName = modals.dateFilter.hierarchyName;
    for (const d of cubeMetadata?.dimensions ?? []) {
      for (const h of d.hierarchies ?? []) {
        if (h.uniqueName === hierName || h.name === hierName) return h.levels;
      }
    }
    return undefined;
  })()}
  {@const dfLevelType = (() => {
    const target = `${modals.dateFilter.hierarchyName}.[${modals.dateFilter.levelName}]`;
    return dfHierLevels?.find((l) => l.uniqueName === target)?.levelType;
  })()}
  <DateFilterModal
    open={!!modals.dateFilter}
    hierarchyCaption={modals.dateFilter.hierarchyCaption}
    hierarchyName={modals.dateFilter.hierarchyName}
    levelName={`${modals.dateFilter.hierarchyName}.[${modals.dateFilter.levelName}]`}
    levelType={dfLevelType}
    hierarchyLevels={dfHierLevels}
    timeCalcs={selection.cube?.timeCalcs}
    onAddCalcMeasure={(name) => {
      // saiku#1221 Phase 3: TimeCalc click → splice the calc's
      // [Measures] reference into the active query. Keeps the modal
      // open so users can stack multiple calcs.
      query.addMeasure({
        name,
        caption: name,
        uniqueName: `[Measures].[${name}]`,
        type: "EXACT",
      });
    }}
    onApply={onDateFilterApply}
    onCancel={() => (closeModal("dateFilter"))}
  />
{/if}

{#if modals.axisFilter && modals.axisFilter.type === "Order"}
  <OrderModal
    axis={modals.axisFilter.axis}
    measures={cubeMetadata?.measures ?? []}
    initialMeasure={modals.axisFilter.expression.startsWith("[") ? modals.axisFilter.expression : ""}
    initialSort={modals.axisFilter.sort as "ASC" | "BASC" | "DESC" | "BDESC"}
    open={!!modals.axisFilter}
    onSave={(measure, sort) => onAxisFilterSave(measure, sort)}
    onCancel={() => (closeModal("axisFilter"))}
  />
{:else if modals.axisFilter && (modals.axisFilter.type === "TopCount" || modals.axisFilter.type === "BottomCount")}
  <TopBottomCountModal
    axis={modals.axisFilter.axis}
    variant={modals.axisFilter.type === "TopCount" ? "top" : "bottom"}
    measures={cubeMetadata?.measures ?? []}
    initialCount={10}
    initialMeasure={cubeMetadata?.measures[0]?.uniqueName ?? ""}
    open={!!modals.axisFilter}
    onSave={(expression) => onAxisFilterSave(expression)}
    onCancel={() => (closeModal("axisFilter"))}
  />
{:else if modals.axisFilter && modals.axisFilter.type === "Limit"}
  <LimitModal
    axis={modals.axisFilter.axis}
    initialCount={10}
    open={!!modals.axisFilter}
    onSave={(count) => onAxisFilterSave(count)}
    onCancel={() => (closeModal("axisFilter"))}
  />
{:else if modals.axisFilter && modals.axisFilter.type === "Filter"}
  <FilterModal
    axis={modals.axisFilter.axis}
    expression={modals.axisFilter.expression}
    open={!!modals.axisFilter}
    onSave={(expression) => onAxisFilterSave(expression)}
    onCancel={() => (closeModal("axisFilter"))}
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
    /* minmax(0, 1fr) (not bare 1fr) so the result column can shrink BELOW
       its content's min-width instead of forcing the whole canvas wider than
       the viewport — that overflow is what pushed the result/chart off-screen
       at narrow widths (#1176). */
    grid-template-columns: 260px minmax(0, 1fr);
    gap: var(--space-3);
    overflow: hidden;
  }
  /* #1176: when the body is too narrow to show builder + result side by
     side, stack the builder above the result (single column) and let the
     body scroll, so the result/chart gets the full width. Container-width
     driven (.canvas__body--narrow toggled by a ResizeObserver). */
  .canvas__body--narrow {
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    overflow-x: hidden;
  }
  /* MDX mode: the dropzones aside is `{#if}`-hidden, so a 260px 1fr
     grid would place the result in column 1 (260px) and leave column 2
     empty. Collapse to a single 1fr column so the result eats the
     entire freed-up horizontal space. */
  .canvas__body--mdx {
    grid-template-columns: 1fr;
  }
  .canvas__mdx-banner {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 2px 8px 2px 2px;
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 999px;
    font-size: 0.75rem;
    color: var(--fg-muted);
    align-self: flex-start;
    max-width: 100%;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .canvas__mdx-badge {
    background: var(--accent);
    color: white;
    border-radius: 999px;
    padding: 1px 7px;
    font-size: 0.7rem;
    font-weight: 600;
    letter-spacing: 0.04em;
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
  .dropzone {
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-md);
    padding: var(--space-2) var(--space-3);
    min-height: 54px;
    background: var(--bg-muted);
  }
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
    font-weight: var(--weight-semibold);
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
  .chips__empty {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 32px;
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
    font-style: italic;
  }
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
  .chip:hover { background: var(--bg-subtle); }
  .chip.is-drop-before {
    box-shadow: inset 0 3px 0 0 var(--accent), 0 0 0 1px var(--accent);
    border-color: var(--accent);
  }
  .chip.is-drop-after {
    box-shadow: inset 0 -3px 0 0 var(--accent), 0 0 0 1px var(--accent);
    border-color: var(--accent);
  }
  /* Per-level chunks inside a multi-level hierarchy chip. Goal: stay
     visually identical to a single-level chip's "Dim › Level" form —
     no extra chrome, no background tags, just an inline list. Each
     level gets a tiny × that only fades in on chip-hover, so the
     resting state is uncluttered. */
  .chip__lvl-sep {
    color: var(--fg-subtle);
    padding: 0 4px;
    user-select: none;
    font-size: var(--fs-xs);
  }
  .chip__lvl {
    color: var(--fg);
    padding: 2px 0;
    font-size: var(--fs-xs);
    white-space: nowrap;
  }
  .chip__lvl-x {
    background: transparent;
    color: var(--fg-subtle);
    border: 0;
    padding: 0 4px;
    margin-left: 2px;
    font-size: 12px;
    line-height: 1;
    cursor: pointer;
    border-radius: var(--radius-sm);
    opacity: 0;
    transition: opacity 80ms ease;
  }
  .chip:hover .chip__lvl-x,
  .chip:focus-within .chip__lvl-x {
    opacity: 0.7;
  }
  .chip__lvl-x:hover {
    opacity: 1 !important;
    color: var(--danger);
    background: var(--bg-hover);
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
    /* #1176: wrap the Grid/Chart/More + chart-type controls instead of
       overflowing the result column at narrow widths. */
    flex-wrap: wrap;
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
