<script lang="ts">
  /*
   * Unified filter panel (saiku#996). Docks at the top of the dashboard
   * editor as a compact, collapsible row of pickers — one per registered
   * panel filter. Replaces the per-filter-tile model entirely; filter
   * widgets no longer occupy grid cells.
   *
   * Each picker is a thin card with:
   *   - drag grip (reorder by dragging within the panel)
   *   - level caption header
   *   - single-select dropdown bound to the panel filter's members[]
   *   - inline × to remove the filter from the panel
   *
   * "+ Add filter" reveals an inline form (cube → dim → hier → level
   * pickers) so analysts can register a new filter without leaving the
   * dashboard. Suggested filters from the toolbar's "Suggest filters"
   * action append straight through dashboardStore.addPanelFilter().
   */

  import { ChevronDown, ChevronRight, GripVertical, X } from "lucide-svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { activeFilters, targetKey } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import type { SchemaLike } from "$lib/dashboard/effectiveQuery";
  // issue #924: "affects N of M tiles" hover hint. Compute which tiles a
  // filter narrows (same resolveTarget compat the merge uses) + broadcast
  // it to the grid via the shared hover store.
  import { computeFilterAffinity } from "$lib/dashboard/filterAffinity";
  import { filterAffinityHover } from "$lib/stores/filterAffinity.svelte";
  import {
    ancestorSelections,
    memberDescendsFromAny,
  } from "$lib/dashboard/cascadingFilters";
  // issue #922: cascade widget extracted to a shared picker (saiku#1230).
  // The pure CascadeSelections type is still used to key per-filter state.
  import type { CascadeSelections } from "$lib/dashboard/cascadingFilter";
  import CascadingFilterPicker from "$lib/views/dashboard/widgets/CascadingFilterPicker.svelte";
  import {
    expandDateRange,
    fromDateInputValue,
    toDateInputValue,
  } from "$lib/dashboard/dateRange";
  import {
    listAiCubes,
    type AiCubeSummary,
    type CubeRef,
    type FilterWidget,
    type PanelFilter,
  } from "$lib/api/dashboards";
  // issue #921: Top-N / Bottom-N widget — rank a level by a measure and keep
  // the top/bottom N. Pure resolution in $lib/dashboard/topN; the query runs
  // via the AI Query API (order+limit → TopCount/BottomCount).
  import { executeAiQuery } from "$lib/api/aiQuery";
  import { buildTopNQuery, clampTopN, rankedCaptionsToUniqueNames } from "$lib/dashboard/topN";

  interface Props {
    readOnly?: boolean;
  }

  let { readOnly = false }: Props = $props();

  let panel = $derived(dashboardStore.current?.filterPanel ?? null);
  let collapsed = $derived(panel?.collapsed ?? false);

  /* ----------------------- "add filter" inline form -------------------- */

  let adding = $state(false);
  let addCube = $state<CubeRef | null>(null);
  let addDimension = $state<string>("");
  let addHierarchy = $state<string>("");
  let addLevel = $state<string>("");
  let addWidget = $state<FilterWidget>("single-select");
  let addError = $state<string | null>(null);

  let cubes = $state<AiCubeSummary[]>([]);
  let cubesLoaded = $state(false);

  async function ensureCubesLoaded(): Promise<void> {
    if (cubesLoaded) return;
    try {
      cubes = await listAiCubes();
    } catch {
      cubes = [];
    } finally {
      cubesLoaded = true;
    }
  }

  function openAdd(): void {
    addError = null;
    addCube = null;
    addDimension = "";
    addHierarchy = "";
    addLevel = "";
    addWidget = "single-select";
    adding = true;
    void ensureCubesLoaded();
  }

  function closeAdd(): void {
    adding = false;
  }

  function commitAdd(): void {
    if (!addCube || !addDimension || !addHierarchy || !addLevel) {
      addError = "Pick cube, dimension, hierarchy and level.";
      return;
    }
    const filter: PanelFilter = {
      id: cryptoUuid(),
      widget: addWidget,
      cube: addCube,
      dimension: addDimension,
      hierarchy: addHierarchy,
      level: addLevel,
      members: [],
      // issue #922: a cascading-select needs a config; default to walking
      // from the chosen level down 3 dropdowns. Editable later via the tile
      // editor / future panel-row config.
      ...(addWidget === "cascading-select"
        ? { cascading: { startLevel: addLevel, depth: 3 } }
        : {}),
      // issue #921: a top-n widget needs a config; default to Top 10, measure
      // chosen on the row. It stays inert (selects no members) until a measure
      // is picked.
      ...(addWidget === "top-n" ? { topN: { n: 10, direction: "top" as const } } : {}),
    };
    dashboardStore.addPanelFilter(filter);
    closeAdd();
  }

  function cubeKey(c: CubeRef): string {
    return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
  }

  function handleAddCubeChange(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    const picked = cubes.find((c) => cubeKey(c) === v);
    addCube = picked
      ? {
          connectionName: picked.connectionName,
          catalog: picked.catalog,
          schema: picked.schema,
          cubeName: picked.cubeName,
        }
      : null;
    addDimension = "";
    addHierarchy = "";
    addLevel = "";
    if (addCube) {
      void schemaCache.get(addCube).catch(() => {});
    }
  }

  // Schema-driven dim/hier/level option lookups for the inline add form.
  let addSchema = $derived(() => {
    if (!addCube) return null;
    void schemaCache.version;
    return schemaCache.peek(addCube);
  });

  let addDimensionOptions = $derived(() => {
    const s = addSchema() as { dimensions?: Record<string, { name: string }> } | null;
    if (!s?.dimensions) return [] as string[];
    return Object.values(s.dimensions).map((d) => d.name);
  });

  let addHierarchyOptions = $derived(() => {
    const s = addSchema() as
      | { dimensions?: Record<string, { name: string; hierarchies?: Record<string, { name: string }> }> }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name === addDimension && d.hierarchies) {
        return Object.values(d.hierarchies).map((h) => h.name);
      }
    }
    return [];
  });

  let addLevelOptions = $derived(() => {
    const s = addSchema() as
      | {
          dimensions?: Record<
            string,
            {
              name: string;
              hierarchies?: Record<string, { name: string; levels?: Record<string, { name: string }> }>;
            }
          >;
        }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name !== addDimension || !d.hierarchies) continue;
      for (const h of Object.values(d.hierarchies)) {
        if (h.name === addHierarchy && h.levels) {
          return Object.values(h.levels).map((l) => l.name);
        }
      }
    }
    return [];
  });

  /* --------------------- per-row member catalogues --------------------- */

  interface MemberRowState {
    loading: boolean;
    error: string | null;
    members: { uniqueName: string; caption: string }[] | null;
    key: string;
  }
  let memberCatalogues = $state<Record<string, MemberRowState>>({});

  $effect(() => {
    const filters = panel?.filters ?? [];
    for (const f of filters) {
      if (!f.cube) continue;
      // issue #922: cascading-select runs its own per-level fetch effect
      // below; the flat single-level catalogue isn't used for it.
      if (f.widget === "cascading-select") continue;
      // Prime the schema cache so ancestor-cascade resolution
      // (selectedForPicker below) can compute level depths without
      // hitting /ai/schema synchronously inside the template.
      void schemaCache.get(f.cube).catch(() => {});
      const key = `${cubeKey(f.cube)}|${f.dimension}/${f.hierarchy}/${f.level}`;
      const cat = memberCatalogues[f.id];
      if (cat && cat.key === key) continue;
      void loadMembers(f, key);
    }
  });

  /** Compute the picker's displayed value. A click on the panel
   *  filter's own target wins outright. A click at a *deeper* level
   *  on the same hierarchy (e.g. user clicked Q2 of 1997 on a table
   *  while the panel has a Year picker) cascades up: the click's
   *  unique name is truncated to the panel filter's depth, so the
   *  Year picker shows "1997" without the analyst having to set it
   *  manually. Falls back to the persisted member, then "". */
  function selectedForPicker(f: PanelFilter): string {
    void schemaCache.version;
    const direct = activeFilters.clicks.find(
      (c) =>
        c.filter.dimension === f.dimension &&
        c.filter.hierarchy === f.hierarchy &&
        c.filter.level === f.level,
    );
    if (direct) return direct.filter.members[0] ?? "";

    if (f.cube) {
      const schema = schemaCache.peek(f.cube) as SchemaLike | null;
      const panelIdx = levelIndex(schema, f.dimension, f.hierarchy, f.level);
      if (panelIdx >= 0) {
        for (const c of activeFilters.clicks) {
          if (c.filter.dimension !== f.dimension || c.filter.hierarchy !== f.hierarchy) continue;
          const clickIdx = levelIndex(schema, c.filter.dimension, c.filter.hierarchy, c.filter.level);
          if (clickIdx <= panelIdx) continue;
          const ancestor = truncateUniqueName(c.filter.members[0] ?? "", panelIdx);
          if (ancestor) return ancestor;
        }
      }
    }
    return f.members[0] ?? "";
  }

  function levelIndex(
    schema: SchemaLike | null,
    dim: string,
    hier: string,
    level: string,
  ): number {
    if (!schema?.dimensions) return -1;
    const dimKey = dim.toLowerCase();
    const d = schema.dimensions[dimKey];
    if (!d?.hierarchies) return -1;
    const h = d.hierarchies[hier.toLowerCase()];
    if (!h?.levels) return -1;
    return Object.keys(h.levels).indexOf(level.toLowerCase());
  }

  /** Truncate a Mondrian unique name `[Dim].[Hier].[v1].[v2]…[vk]`
   *  down to the first {@code panelIdx + 1} member segments after the
   *  hierarchy prefix. Returns "" if the name doesn't parse. */
  function truncateUniqueName(uniqueName: string, panelIdx: number): string {
    const segments = uniqueName.match(/\[[^\]]+\]/g);
    if (!segments) return "";
    // 2 segments for the [Dim].[Hier] prefix + (panelIdx + 1) levels under it.
    const keep = 2 + panelIdx + 1;
    if (segments.length < keep) return "";
    return segments.slice(0, keep).join(".");
  }

  /** Build the display option list for one picker. Two passes:
   *
   *   1. **Ancestor restriction** — if any other panel filter / active
   *      click targets a higher level on the same hierarchy AND has a
   *      member selection, filter this picker's options to descendants
   *      of the parent selection. e.g. with Year=1997 active, the
   *      Quarter picker only offers Q1-Q4 of 1997 (not 1998's).
   *   2. **Caption disambiguation** — when two options share the same
   *      caption (the typical FoodMart case where Q1 exists under each
   *      Year), prefix each duplicate with its parent path so the
   *      analyst can tell them apart. Unique captions stay as-is.
   *
   *  Returns null until the underlying members fetch completes. */
  function displayedOptionsFor(
    f: PanelFilter,
  ): { uniqueName: string; caption: string }[] | null {
    const cat = memberCatalogues[f.id];
    if (!cat?.members) return null;
    let opts = cat.members;

    // Pass 1: ancestor restriction.
    if (f.cube) {
      void schemaCache.version;
      const schema = schemaCache.peek(f.cube) as SchemaLike | null;
      const parents = ancestorSelections(activeFilters.all, schema, f.cube, {
        dimension: f.dimension,
        hierarchy: f.hierarchy,
        level: f.level,
        members: f.members ?? [],
      });
      if (parents.length > 0) {
        opts = opts.filter((m) => memberDescendsFromAny(m.uniqueName, parents));
      }
    }

    // Pass 2: caption disambiguation. Count captions; for any that
    // collide, prefix with the parent segment(s) parsed out of the
    // unique name.
    const captionCounts = new Map<string, number>();
    for (const m of opts) {
      captionCounts.set(m.caption, (captionCounts.get(m.caption) ?? 0) + 1);
    }
    return opts.map((m) => {
      if ((captionCounts.get(m.caption) ?? 0) <= 1) return m;
      const segments = m.uniqueName.match(/\[[^\]]+\]/g) ?? [];
      // segments[0] = [Dim], segments[1] = [Hier], then one per level.
      // The leaf is the last; everything between hierarchy prefix and
      // the leaf is parent context.
      const parents = segments.slice(2, -1).map((s) => s.slice(1, -1));
      const parentLabel = parents.join(" / ");
      return {
        uniqueName: m.uniqueName,
        caption: parentLabel ? `${parentLabel} / ${m.caption}` : m.caption,
      };
    });
  }

  async function loadMembers(f: PanelFilter, key: string): Promise<void> {
    if (!f.cube) return;
    memberCatalogues = {
      ...memberCatalogues,
      [f.id]: { loading: true, error: null, members: null, key },
    };
    try {
      const params = new URLSearchParams({
        cubeId: cubeKey(f.cube),
        dimension: f.dimension,
        hierarchy: f.hierarchy,
        level: f.level,
        limit: "500",
      });
      const res = await fetch(`/rest/saiku/api/ai/members/search?${params.toString()}`, {
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const hits = (await res.json()) as { uniqueName: string; caption: string }[];
      memberCatalogues = {
        ...memberCatalogues,
        [f.id]: { loading: false, error: null, members: hits, key },
      };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      memberCatalogues = {
        ...memberCatalogues,
        [f.id]: { loading: false, error: msg, members: [], key },
      };
    }
  }

  function handleMemberChange(filterId: string, e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    commitPanelMembers(filterId, v ? [v] : []);
  }

  /** Date-range pickers carry two HTML date inputs — local UI state
   *  keyed by panel filter id. Persisted member expansion happens via
   *  expandDateRange + commitPanelMembers; the raw date strings stay
   *  client-side so we don't pollute the saved JSON with a UX-only
   *  artefact. */
  let dateRangeState = $state<Record<string, { from: string; to: string }>>({});

  function dateRangeFor(filterId: string): { from: string; to: string } {
    return dateRangeState[filterId] ?? { from: "", to: "" };
  }

  function setDateRange(filterId: string, from: string, to: string): void {
    dateRangeState = { ...dateRangeState, [filterId]: { from, to } };
    const f = dashboardStore.current?.filterPanel?.filters.find((p) => p.id === filterId);
    if (!f) return;
    const cat = memberCatalogues[filterId];
    if (!cat?.members) {
      // Members not loaded yet; bail and let the load $effect re-run
      // with the new state when ready (next dateRange change retries).
      return;
    }
    const fromDate = fromDateInputValue(from);
    const toDate = fromDateInputValue(to);
    const members = expandDateRange(fromDate, toDate, cat.members);
    commitPanelMembers(filterId, members);
  }

  function commitPanelMembers(filterId: string, members: string[]): void {
    dashboardStore.updatePanelFilter(filterId, { members });
    // An explicit panel pick replaces any transient click on the same
    // target — otherwise the click would keep overriding the user's
    // intentional choice and the chip strip would still show the click.
    const f = dashboardStore.current?.filterPanel?.filters.find((p) => p.id === filterId);
    if (!f) return;
    const tkey = `${f.dimension}/${f.hierarchy}/${f.level}`;
    const stale = activeFilters.clicks.find((c) => targetKey(c.filter) === tkey);
    if (stale) activeFilters.clearChip(stale.id);
  }

  /* ===================== issue #921: top-n / bottom-n ====================
   * Rank the level's members by a measure and keep the top/bottom N. The
   * member catalogue (memberCatalogues[f.id]) is already loaded by the effect
   * above; we run the ranking query (order+limit), map the ranked captions to
   * their unique names via that catalogue, then commit them like any pick. */
  interface TopNRowState {
    loading: boolean;
    error: string | null;
    key: string;
  }
  let topNState = $state<Record<string, TopNRowState>>({});

  function topNMeasureOptions(f: PanelFilter): string[] {
    if (!f.cube) return [];
    void schemaCache.version;
    const s = schemaCache.peek(f.cube) as { measures?: Record<string, { name: string }> } | null;
    if (!s?.measures) return [];
    return Object.values(s.measures).map((m) => m.name);
  }

  function setTopN(
    filterId: string,
    partial: Partial<{ measure: string; n: number; direction: "top" | "bottom" }>,
  ): void {
    const f = panel?.filters.find((p) => p.id === filterId);
    const next = { ...(f?.topN ?? {}), ...partial };
    if (partial.n !== undefined) next.n = clampTopN(partial.n);
    dashboardStore.updatePanelFilter(filterId, { topN: next });
  }

  async function resolveTopN(f: PanelFilter, key: string): Promise<void> {
    topNState = { ...topNState, [f.id]: { loading: true, error: null, key } };
    try {
      const tn = f.topN;
      if (!f.cube || !tn?.measure) return;
      const body = buildTopNQuery(
        cubeKey(f.cube),
        f.dimension,
        f.hierarchy,
        f.level,
        tn.measure,
        clampTopN(tn.n),
        tn.direction ?? "top",
      );
      const resp = await executeAiQuery(body, "records");
      if (resp.status !== "SUCCESS" || !resp.metadata?.rows) {
        throw new Error(resp.error ?? `query ${resp.status}`);
      }
      const captions = resp.metadata.rows.map((r) => r.name);
      const cat = memberCatalogues[f.id];
      const uniques = rankedCaptionsToUniqueNames(captions, cat?.members ?? []);
      commitPanelMembers(f.id, uniques);
      topNState = { ...topNState, [f.id]: { loading: false, error: null, key } };
    } catch (e: unknown) {
      topNState = {
        ...topNState,
        [f.id]: { loading: false, error: e instanceof Error ? e.message : String(e), key },
      };
    }
  }

  // Re-resolve a top-n filter when its config or member catalogue changes.
  // Keyed so it runs once per distinct config (no re-fire loop on commit).
  $effect(() => {
    const filters = panel?.filters ?? [];
    for (const f of filters) {
      if (f.widget !== "top-n" || !f.cube) continue;
      const tn = f.topN;
      if (!tn?.measure) continue;
      const cat = memberCatalogues[f.id];
      if (!cat?.members) continue;
      const key = `${cubeKey(f.cube)}|${f.dimension}/${f.hierarchy}/${f.level}|${tn.measure}|${clampTopN(tn.n)}|${tn.direction ?? "top"}|${cat.members.length}`;
      if (topNState[f.id]?.key === key) continue;
      void resolveTopN(f, key);
    }
  });

  /* ===================== issue #922: cascading-select ====================
   * The picker (with its per-level member fetch + dropdown rendering)
   * lives in $lib/views/dashboard/widgets/CascadingFilterPicker.svelte.
   * This panel owns only the per-filter selection chain so collapsing
   * / removing a filter still wipes its in-flight cascade state.
   * Extracted in saiku#1230. The deepest concrete pick from the picker
   * is committed via commitPanelMembers (same merge path used by all
   * other variants). */
  let cascadeState = $state<Record<string, CascadeSelections>>({});

  function cascadeSelections(filterId: string): CascadeSelections {
    return cascadeState[filterId] ?? [];
  }

  function handleCascadeCommit(
    filterId: string,
    next: CascadeSelections,
    members: string[],
  ): void {
    cascadeState = { ...cascadeState, [filterId]: next };
    commitPanelMembers(filterId, members);
  }
  /* ===================== end issue #922 ================================= */

  function handleRemove(id: string): void {
    dashboardStore.removePanelFilter(id);
  }

  function toggleCollapsed(): void {
    if (!panel) return;
    dashboardStore.setPanelCollapsed(!panel.collapsed);
  }

  /* ===================== issue #924: affects-N-tiles hint ================
   * On hover / focus of a filter row, work out which tiles its
   * dim/hier/level resolves against — reusing the exact resolveTarget
   * compat the filter-merge uses, so the hint never lies — and broadcast
   * it so the grid highlights the hits + dims the misses and this row
   * shows an "Affects N of M tiles" badge. Clears on leave/blur. */
  function hoverFilter(f: PanelFilter): void {
    const tiles = dashboardStore.current?.layout.tiles ?? [];
    // Prime any not-yet-cached tile cube so a moment-later re-hover
    // resolves (peek is sync; get is fire-and-forget, ticks schemaCache).
    for (const t of tiles) {
      if (t.cube) void schemaCache.get(t.cube).catch(() => {});
    }
    const affinity = computeFilterAffinity(
      { dimension: f.dimension, hierarchy: f.hierarchy, level: f.level },
      tiles,
      (cube) => schemaCache.peek(cube) as SchemaLike | null,
    );
    filterAffinityHover.set(f.id, affinity);
  }

  function unhoverFilter(): void {
    filterAffinityHover.clear();
  }

  /* --------------------------- drag-reorder ---------------------------- */

  interface DragState {
    fromId: string;
    pointerX: number;
    pointerY: number;
    hoverId: string | null;
  }
  let drag = $state<DragState | null>(null);

  function onPickerPointerDown(e: PointerEvent, id: string): void {
    if (readOnly) return;
    if (e.button !== 0) return;
    const target = e.target as HTMLElement | null;
    if (!target?.closest("[data-drag-handle]")) return;
    drag = { fromId: id, pointerX: e.clientX, pointerY: e.clientY, hoverId: null };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    e.preventDefault();
  }

  function onPickerPointerMove(e: PointerEvent): void {
    if (!drag) return;
    // Project the pointer onto the picker row and find the picker the
    // cursor is over — pure HTML hit-testing keeps this independent of
    // the picker count / shape.
    const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null;
    const target = el?.closest<HTMLElement>("[data-panel-filter-id]");
    const hoverId = target?.dataset.panelFilterId ?? null;
    if (hoverId === drag.hoverId) return;
    drag = { ...drag, hoverId };
  }

  function onPickerPointerUp(e: PointerEvent): void {
    if (!drag) return;
    const { fromId, hoverId } = drag;
    drag = null;
    (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId);
    if (!hoverId || fromId === hoverId) return;
    const ids = (panel?.filters ?? []).map((f) => f.id);
    const fromIdx = ids.indexOf(fromId);
    const toIdx = ids.indexOf(hoverId);
    if (fromIdx === -1 || toIdx === -1) return;
    const next = [...ids];
    const [moved] = next.splice(fromIdx, 1);
    next.splice(toIdx, 0, moved);
    dashboardStore.reorderPanelFilters(next);
  }

  function onPickerPointerCancel(): void {
    drag = null;
  }

  function cryptoUuid(): string {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
</script>

<!-- issue #924: render the panel chrome in edit mode even before a
     filterPanel exists, so "+ Add filter" is always reachable on a fresh
     dashboard (committing the first filter lazily creates the panel via
     ensurePanel). In readOnly we still only show it once filters exist. -->
{#if panel || !readOnly}
  <section class="panel" aria-label="Dashboard filter panel">
    <header class="panel-header">
      <button
        type="button"
        class="collapse-toggle"
        onclick={toggleCollapsed}
        aria-expanded={!collapsed}
        title={collapsed ? "Expand filter panel" : "Collapse filter panel"}
      >
        {#if collapsed}
          <ChevronRight size={14} aria-hidden="true" />
        {:else}
          <ChevronDown size={14} aria-hidden="true" />
        {/if}
        <span class="panel-title">
          Filters
          {#if (panel?.filters.length ?? 0) > 0}
            <span class="count">({panel?.filters.length})</span>
          {/if}
        </span>
      </button>
      {#if !readOnly}
        <button type="button" class="add-btn" onclick={openAdd}>+ Add filter</button>
      {/if}
    </header>
    {#if !collapsed}
      {#if (panel?.filters.length ?? 0) === 0 && !adding}
        <p class="empty">
          No filters yet — click <em>+ Add filter</em> to register one,
          or use <em>Suggest filters</em> in the toolbar.
        </p>
      {/if}
      {#if (panel?.filters.length ?? 0) > 0}
        <!-- svelte-ignore a11y_no_static_element_interactions — pointer events implement drag-to-reorder; keyboard reordering would be a separate UX (kbd shortcuts on focused picker). -->
        <div
          class="picker-row"
          class:picker-row--dragging={drag !== null}
          onpointermove={onPickerPointerMove}
          onpointerup={onPickerPointerUp}
          onpointercancel={onPickerPointerCancel}
        >
          {#each panel?.filters ?? [] as f (f.id)}
            {@const cat = memberCatalogues[f.id]}
            {@const selected = selectedForPicker(f)}
            {@const displayOptions = displayedOptionsFor(f)}
            <!-- svelte-ignore a11y_no_static_element_interactions — pointer events implement drag-to-reorder; the inner Combobox handles keyboard. -->
            <div
              class="picker"
              class:picker--hover={drag?.hoverId === f.id && drag.fromId !== f.id}
              class:picker--source={drag?.fromId === f.id}
              data-panel-filter-id={f.id}
              onpointerdown={(e) => onPickerPointerDown(e, f.id)}
              onmouseenter={() => hoverFilter(f)}
              onmouseleave={unhoverFilter}
              onfocusin={() => hoverFilter(f)}
              onfocusout={unhoverFilter}
            >
              {#if !readOnly}
                <span class="grip" data-drag-handle aria-hidden="true">
                  <GripVertical size={14} />
                </span>
              {/if}
              <span class="picker-label">{f.level}</span>
              <!-- issue #924: how many tiles this filter narrows, shown while hovered. -->
              {#if filterAffinityHover.hoveredFilterId === f.id}
                <span class="affects-badge" aria-live="polite">
                  Affects {filterAffinityHover.affectedCount} of {filterAffinityHover.totalCount} tiles
                </span>
              {/if}
              {#if f.widget === "cascading-select" && f.cube}
                <!-- issue #922: N stacked dropdowns walking the hierarchy.
                     Picker lives in CascadingFilterPicker.svelte; this panel
                     keeps the per-filter selection chain so the deepest
                     pick can flow back through commitPanelMembers. -->
                <CascadingFilterPicker
                  cube={f.cube}
                  dimension={f.dimension}
                  hierarchy={f.hierarchy}
                  startLevel={f.cascading?.startLevel ?? ""}
                  defaultStartLevel={f.level}
                  depth={f.cascading?.depth ?? 0}
                  selections={cascadeSelections(f.id)}
                  {readOnly}
                  onCommit={(next, members) =>
                    handleCascadeCommit(f.id, next, members)}
                />
              {:else if f.widget === "date-range"}
                {@const dr = dateRangeFor(f.id)}
                <span class="date-range">
                  <input
                    type="date"
                    class="picker-date"
                    value={dr.from}
                    disabled={readOnly}
                    aria-label="From"
                    onchange={(e) => setDateRange(f.id, (e.target as HTMLInputElement).value, dr.to)}
                  />
                  <span class="date-sep" aria-hidden="true">→</span>
                  <input
                    type="date"
                    class="picker-date"
                    value={dr.to}
                    disabled={readOnly}
                    aria-label="To"
                    onchange={(e) => setDateRange(f.id, dr.from, (e.target as HTMLInputElement).value)}
                  />
                </span>
              {:else if f.widget === "top-n"}
                <!-- issue #921: rank by a measure, keep top/bottom N. -->
                {@const measures = topNMeasureOptions(f)}
                {@const st = topNState[f.id]}
                <span class="topn">
                  <select
                    class="picker-select"
                    value={f.topN?.direction ?? "top"}
                    disabled={readOnly}
                    aria-label="Direction"
                    onchange={(e) =>
                      setTopN(f.id, { direction: (e.target as HTMLSelectElement).value as "top" | "bottom" })}
                  >
                    <option value="top">Top</option>
                    <option value="bottom">Bottom</option>
                  </select>
                  <input
                    type="number"
                    class="picker-num"
                    min="1"
                    max="1000"
                    value={f.topN?.n ?? 10}
                    disabled={readOnly}
                    aria-label="N"
                    onchange={(e) => setTopN(f.id, { n: Number((e.target as HTMLInputElement).value) })}
                  />
                  <span class="topn-by">by</span>
                  <select
                    class="picker-select"
                    value={f.topN?.measure ?? ""}
                    disabled={readOnly}
                    aria-label="Measure"
                    onchange={(e) => setTopN(f.id, { measure: (e.target as HTMLSelectElement).value })}
                  >
                    <option value="">— measure —</option>
                    {#each measures as m, _im (_im)}
                      <option value={m}>{m}</option>
                    {/each}
                  </select>
                  {#if st?.loading}
                    <span class="topn-state">resolving…</span>
                  {:else if st?.error}
                    <span class="topn-state topn-state--error">{st.error}</span>
                  {:else if (f.members?.length ?? 0) > 0}
                    <span class="topn-state">{f.members.length} member(s)</span>
                  {:else if f.topN?.measure}
                    <span class="topn-state">no matches</span>
                  {/if}
                </span>
              {:else}
                <select
                  class="picker-select"
                  value={selected}
                  disabled={readOnly}
                  onchange={(e) => handleMemberChange(f.id, e)}
                >
                  <option value="">— any —</option>
                  {#if displayOptions}
                    {#each displayOptions as m (m.uniqueName)}
                      <option value={m.uniqueName}>{m.caption}</option>
                    {/each}
                  {:else if !f.cube}
                    <option value="" disabled>(no cube on filter)</option>
                  {:else if cat?.loading}
                    <option value="" disabled>Loading…</option>
                  {:else if cat?.error}
                    <option value="" disabled>Error: {cat.error}</option>
                  {/if}
                </select>
              {/if}
              {#if !readOnly}
                <button
                  type="button"
                  class="picker-remove"
                  onclick={() => handleRemove(f.id)}
                  aria-label="Remove filter"
                  title="Remove filter"
                >
                  <X size={12} />
                </button>
              {/if}
            </div>
          {/each}
        </div>
      {/if}
      {#if adding}
        <form
          class="add-form"
          onsubmit={(e) => {
            e.preventDefault();
            commitAdd();
          }}
        >
          <label class="add-field">
            <span>Cube</span>
            <select onchange={handleAddCubeChange} value={addCube ? cubeKey(addCube) : ""}>
              <option value="">— pick a cube —</option>
              {#each cubes as c (cubeKey(c))}
                <option value={cubeKey(c)}>{c.cubeCaption ?? c.cubeName} ({c.connectionName})</option>
              {/each}
            </select>
          </label>
          <label class="add-field">
            <span>Dimension</span>
            <select bind:value={addDimension} disabled={!addCube}>
              <option value="">— pick —</option>
              {#each addDimensionOptions() as d, _id (_id)}
                <option value={d}>{d}</option>
              {/each}
            </select>
          </label>
          <label class="add-field">
            <span>Hierarchy</span>
            <select bind:value={addHierarchy} disabled={!addDimension}>
              <option value="">— pick —</option>
              {#each addHierarchyOptions() as h, _ih (_ih)}
                <option value={h}>{h}</option>
              {/each}
            </select>
          </label>
          <label class="add-field">
            <span>Level</span>
            <select bind:value={addLevel} disabled={!addHierarchy}>
              <option value="">— pick —</option>
              {#each addLevelOptions() as l, _il (_il)}
                <option value={l}>{l}</option>
              {/each}
            </select>
          </label>
          <label class="add-field">
            <span>Widget</span>
            <select bind:value={addWidget}>
              <option value="single-select">single-select</option>
              <option value="multi-select">multi-select</option>
              <option value="date-range">date-range</option>
              <option value="cascading-select">cascading-select</option>
              <option value="top-n">top-n</option>
            </select>
          </label>
          {#if addError}
            <span class="add-error">{addError}</span>
          {/if}
          <div class="add-actions">
            <button type="button" class="btn" onclick={closeAdd}>Cancel</button>
            <button type="submit" class="btn primary">Add</button>
          </div>
        </form>
      {/if}
    {/if}
  </section>
{/if}

<style>
  .panel {
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    display: flex;
    flex-direction: column;
  }
  .panel-header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.375rem 0.5rem;
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
    font-size: 0.8125rem;
  }
  .collapse-toggle {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    background: transparent;
    border: none;
    padding: 0.125rem 0.25rem;
    cursor: pointer;
    color: var(--fg);
    font-size: inherit;
    flex: 1;
    text-align: left;
  }
  .panel-title {
    font-weight: var(--weight-medium, 500);
  }
  .count {
    color: var(--fg-muted);
    font-weight: 400;
    margin-left: 0.125rem;
  }
  .add-btn {
    padding: 0.25rem 0.5rem;
    border: 1px solid var(--border-strong, var(--border));
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.75rem;
  }
  .empty {
    margin: 0;
    padding: 0.5rem 0.75rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
    font-style: italic;
  }
  .picker-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0.375rem;
    padding: 0.5rem;
    align-items: center;
    touch-action: none;
  }
  .picker {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.25rem 0.375rem;
    border: 1px solid var(--border);
    border-radius: 4px;
    background: var(--bg);
    user-select: none;
  }
  .picker--hover {
    box-shadow: 0 0 0 2px var(--accent, color-mix(in srgb, var(--fg) 30%, transparent));
  }
  .picker--source {
    opacity: 0.45;
  }
  .grip {
    cursor: grab;
    color: var(--fg-muted);
    display: inline-flex;
  }
  .grip:active {
    cursor: grabbing;
  }
  .picker-label {
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    color: var(--fg-muted);
  }
  /* issue #924: "affects N of M tiles" hover badge. */
  .affects-badge {
    font-size: 0.6875rem;
    line-height: 1;
    padding: 0.125rem 0.375rem;
    border-radius: 999px;
    background: var(--accent);
    color: var(--accent-fg, #fff);
    white-space: nowrap;
  }
  .picker-select {
    padding: 0.125rem 0.25rem;
    border: 1px solid var(--border-strong, var(--border));
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
    max-width: 200px;
  }
  .date-range {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
  }
  /* issue #922: stacked cascade dropdowns moved to CascadingFilterPicker
     (saiku#1230); its styles live alongside the markup there. */
  .picker-date {
    padding: 0.125rem 0.25rem;
    border: 1px solid var(--border-strong, var(--border));
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
    font-family: inherit;
  }
  /* issue #921: top-n / bottom-n inline config. */
  .topn {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.25rem;
  }
  .picker-num {
    width: 3.5rem;
    padding: 0.125rem 0.25rem;
    border: 1px solid var(--border-strong, var(--border));
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
    font-family: inherit;
  }
  .topn-by {
    font-size: 0.75rem;
    color: var(--fg-muted);
  }
  .topn-state {
    font-size: 0.75rem;
    color: var(--fg-muted);
  }
  .topn-state--error {
    color: var(--danger);
  }
  .date-sep {
    color: var(--fg-muted);
    font-size: 0.75rem;
  }
  .picker-remove {
    background: transparent;
    border: none;
    cursor: pointer;
    color: var(--fg-muted);
    padding: 0.125rem;
    display: inline-flex;
  }
  .picker-remove:hover {
    color: var(--danger, #c00);
  }
  .add-form {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: 0.5rem;
    padding: 0.5rem;
    border-top: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .add-field {
    display: flex;
    flex-direction: column;
    gap: 0.125rem;
    font-size: 0.75rem;
  }
  .add-field > span:first-child {
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .add-field select {
    padding: 0.25rem 0.375rem;
    border: 1px solid var(--border-strong, var(--border));
    border-radius: 4px;
    background: var(--bg);
    font-size: 0.8125rem;
    min-width: 8rem;
  }
  .add-error {
    color: var(--danger, #c00);
    font-size: 0.75rem;
    align-self: center;
  }
  .add-actions {
    display: flex;
    gap: 0.375rem;
    margin-left: auto;
  }
  .btn {
    padding: 0.25rem 0.625rem;
    border: 1px solid var(--border-strong, var(--border));
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.8125rem;
  }
  .btn.primary {
    background: var(--accent, #5b6cff);
    color: white;
    border-color: var(--accent, #5b6cff);
  }
</style>
