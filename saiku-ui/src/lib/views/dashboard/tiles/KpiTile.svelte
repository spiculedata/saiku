<script lang="ts">
  /*
   * KPI tile (issue #917).
   *
   * Renders a single big number from a one-measure query on the bound
   * cube. Optional features:
   *   - Format selection (number / currency / percent / custom pattern)
   *   - Prior-period comparison: a second-cell delta with ↑/↓ arrow,
   *     coloured by direction.
   *   - Target comparison: delta vs a static target value.
   *   - Sparkline: mini line chart of the configured time level under
   *     the number.
   *   - Threshold-based colouring of the main number using red /
   *     yellow / green bands.
   *
   * Query plumbing mirrors ChartTile / TableTile: an $effect keyed on
   * the active-filter set and resolved cube refetches via the AI Query
   * API. When comparison="prior-period" or sparkline is on, we issue a
   * "by time level" query so both features can read the same series;
   * otherwise a single-cell query is enough.
   */

  import { onDestroy } from "svelte";
  import * as echarts from "echarts";
  import { ArrowDownRight, ArrowUpRight, Minus, Settings2 } from "lucide-svelte";
  import type { CubeRef, DashboardTile, KpiConfig } from "$lib/api/dashboards";
  import {
    executeAiQuery,
    isAiCell,
    type AiCell,
    type AiQueryResponse,
  } from "$lib/api/aiQuery";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import {
    deltaLabelFor,
    isTrailingPartial,
    formatKpi,
    kpiDelta,
    kpiThresholdToken,
    lastAndPriorValues,
    periodLabel,
    type KpiDelta as KpiDeltaT,
  } from "$lib/dashboard/kpi";
  // #992 — year-over-year (same-period-previous-year) expansion.
  import { expandYearOverYearRows } from "$lib/dashboard/kpiYoy";
  import { i18n } from "$lib/stores/i18n.svelte";
  // Issue #933 — shared loading / error / empty states.
  import TileLoading from "./TileLoading.svelte";
  import TileError from "./TileError.svelte";
  import TileEmpty from "./TileEmpty.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  // #941 share viewer (PR2): render from a prefetched guest response when present.
  import { getShareViewResponse } from "$lib/dashboard/shareViewContext";
  // Issue #931 — per-tile auto-refresh: timer wiring + "last updated" indicator.
  import { TileAutoRefresh } from "$lib/dashboard/tileAutoRefresh.svelte";
  import { isAutoRefreshOn } from "$lib/dashboard/autoRefresh";
  import TileRefreshIndicator from "./TileRefreshIndicator.svelte";

  interface Props {
    tile: DashboardTile;
  }

  let { tile }: Props = $props();

  // Non-null only inside the share viewer (getContext at init); see fetch effect.
  // svelte-ignore state_referenced_locally
  const sharedResponse = getShareViewResponse(tile.id);

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);

  // Issue #933 — retry re-fires the deduped fetch effect; empty = a
  // successful query with no rows; hasEffectiveFilters gates the reset.
  let retryTick = $state(0);
  let isEmpty = $derived(
    !!response && response.status === "SUCCESS" && (response.data?.length ?? 0) === 0,
  );
  let hasEffectiveFilters = $derived(
    activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0),
  );
  function retry(): void {
    lastQueryJson = "";
    retryTick++;
  }
  function resetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

  /* --- issue #931: auto-refresh ------------------------------------------
   * Per-tile timer re-fires the existing (filter-aware) KPI fetch on the
   * configured cadence by clearing the dedupe cache + bumping a tick the
   * fetch effect reads (same mechanism as retry()), so the re-run uses the
   * CURRENT active-filter slice. Never auto-refreshes in the share viewer. */
  const auto = new TileAutoRefresh();
  let refreshTick = $state(0);
  function triggerAutoRefresh(): void {
    lastQueryJson = "";
    refreshTick++;
  }
  let autoRefreshOn = $derived(!sharedResponse && isAutoRefreshOn(tile.refreshInterval));
  $effect(() => auto.arm(tile.refreshInterval, triggerAutoRefresh));
  $effect(() => {
    if (autoRefreshOn && auto.lastUpdated > 0) return auto.startHeartbeat();
  });
  /* --- end issue #931 block ---------------------------------------------- */

  let sparkHost = $state<HTMLDivElement | null>(null);
  let spark: echarts.ECharts | null = null;
  let sparkResize: ResizeObserver | null = null;

  // Sparkline ECharts is lazy-initialised in the render $effect below (the host
  // element only exists once data has loaded), so there is no onMount init here.
  onDestroy(() => {
    sparkResize?.disconnect();
    spark?.dispose();
    spark = null;
  });

  // Derived: the KpiConfig with defaults filled in. Lets the renderer
  // treat the config as totally-defined.
  let kpi = $derived<Required<Pick<KpiConfig, "format" | "comparison" | "direction" | "sparkline">> & KpiConfig>({
    format: "number",
    comparison: "none",
    direction: "higher-is-better",
    sparkline: false,
    ...(tile.kpi ?? {}),
  });

  let cube = $derived<CubeRef | null>(tile.cube ?? null);

  // True when we need the by-time-level query (for sparkline or a
  // time-based delta — prior-period or year-over-year); false when a
  // single-cell query is enough.
  let wantsSeries = $derived(
    !!kpi.timeLevel &&
      (kpi.sparkline === true ||
        kpi.comparison === "prior-period" ||
        kpi.comparison === "year-over-year"),
  );

  let lastQueryJson = $state<string>("");

  /** Per-(cube/dim/hier/level) cache of the ordered member list, so the
   *  prior-period expansion below doesn't re-hit /ai/members/search on
   *  every filter tick. Module-scoped so multiple KPI tiles share it. */
  const levelMembersCache = new Map<string, Promise<{ uniqueName: string; caption: string }[]>>();

  function fetchLevelMembers(
    c: CubeRef,
    dimension: string,
    hierarchy: string,
    level: string,
  ): Promise<{ uniqueName: string; caption: string }[]> {
    const cubeId = `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
    const cacheKey = `${cubeId}|${dimension}/${hierarchy}/${level}`;
    const cached = levelMembersCache.get(cacheKey);
    if (cached) return cached;
    const params = new URLSearchParams({
      cubeId,
      dimension,
      hierarchy,
      level,
      limit: "1000",
    });
    const promise = fetch(`/rest/saiku/api/ai/members/search?${params.toString()}`, {
      credentials: "include",
      headers: { Accept: "application/json" },
    })
      .then((r) => (r.ok ? r.json() : []))
      .then((hits: unknown) => (Array.isArray(hits) ? (hits as { uniqueName: string; caption: string }[]) : []))
      .catch(() => [] as { uniqueName: string; caption: string }[]);
    levelMembersCache.set(cacheKey, promise);
    return promise;
  }

  $effect(() => {
    // #941 share viewer: render the prefetched guest response; never fetch live.
    if (sharedResponse) {
      response = sharedResponse;
      loading = false;
      error = null;
      return;
    }
    const measure = kpi.measure;
    const c = cube;
    const series = wantsSeries;
    const tl = kpi.timeLevel;
    const comparison = kpi.comparison;
    const active = activeFilters.all;
    // #933 — retry() bumps this to force a refetch with unchanged inputs.
    void retryTick;
    // #931 — auto-refresh bumps this to re-run with the current filter slice.
    void refreshTick;
    if (!measure || !c) {
      response = null;
      return;
    }

    // Build the request body. Inline KPI tiles don't carry a TileQuery
    // — the cube + measure + (optional) time-level on rows is enough.
    // Slicing comes purely from dashboard-active filters (defaults /
    // widgets / clicks); KPI tiles don't carry per-tile filters of
    // their own — analysts manage the slice via filter widgets.
    //
    // Two Mondrian rules to keep happy here:
    //
    //   1. The WHERE clause is a tuple — at most one filter per
    //      hierarchy. When two active filters target the same hierarchy
    //      at different levels (e.g. Year=1997 *and* Quarter=Q2), keep
    //      the later one (last-wins, same rule mergeFilters uses for
    //      chart/table tiles). Iteration order in activeFilters.all is
    //      defaults → widgets → clicks → so a click on a deeper level
    //      beats a default at a higher level.
    //
    //   2. A hierarchy that's already on rows/columns can't also be a
    //      slicer. When wantsSeries puts the time-level hierarchy on
    //      rows AND a filter targets the same hierarchy, move that
    //      filter's members onto the row axis selection's members[]
    //      and drop it from filters[].
    type AxisSelection = {
      dimension: string;
      hierarchy: string;
      level: string;
      members?: string[];
    };
    type Filter = { dimension: string; hierarchy: string; level: string; members: string[] };
    const byHierarchy = new Map<string, Filter>();
    for (const af of active) {
      const f = af.filter;
      const members = f.members ?? [];
      if (members.length === 0) continue;
      const key = `${f.dimension}/${f.hierarchy}`;
      byHierarchy.set(key, {
        dimension: f.dimension,
        hierarchy: f.hierarchy,
        level: f.level,
        members,
      });
    }
    const rows: AxisSelection[] = [];
    let needsPriorExpansion: { rowHierKey: string; slicer: Filter } | null = null;
    if (series && tl) {
      const rowHierKey = `${tl.dimension}/${tl.hierarchy}`;
      const slicer = byHierarchy.get(rowHierKey);
      rows.push({
        dimension: tl.dimension,
        hierarchy: tl.hierarchy,
        level: tl.level,
      });
      byHierarchy.delete(rowHierKey);
      // Schema-aware time-comparison expansion: when the slicer targets
      // the *same level* as timeLevel (e.g. timeLevel=Quarter, slicer
      // pins Quarter=Q2), enumerating the full series defeats the
      // user's filter intent. Instead, after the members API resolves
      // the level's full ordered list, expand the row to include each
      // slicer member plus its comparison counterpart:
      //   - prior-period (#991): the preceding sibling (Q2 → Q1).
      //   - year-over-year (#992): the same relative position one parent
      //     (year) earlier (Q2.1997 → Q2.1996), via expandYearOverYearRows.
      // lastAndPriorValues then computes a real delta vs that earlier
      // member. Slicers at a *different* level (e.g. Year=1997 with
      // timeLevel=Quarter) fall back to "drop slicer, enumerate full
      // series".
      //
      // saiku#1774 landed the server-side Descendants() support this used to
      // wait on: AiSchemaConverter now accepts axis members at an ancestor
      // level of the axis level and emits Descendants({members}, [Level]).
      // So Year→Quarter descent is now expressible — the fallback below is a
      // remaining gap rather than a blocked one. See saiku#1749, whose
      // period-to-date comparison depends on exactly this.
      if (slicer && slicer.level.toLowerCase() === tl.level.toLowerCase()) {
        needsPriorExpansion = { rowHierKey, slicer };
      }
    }

    loading = true;
    error = null;
    void (async () => {
      try {
        // If we need prior-period expansion, fetch the level's members
        // first, then expand row.members[] before building the query.
        if (needsPriorExpansion && tl && c) {
          const allMembers = await fetchLevelMembers(c, tl.dimension, tl.hierarchy, tl.level);
          if (allMembers.length > 0) {
            const slicerMembers = needsPriorExpansion.slicer.members;
            let ordered: string[];
            if (comparison === "year-over-year") {
              // #992: each pinned member + its same-period-previous-year
              // counterpart, in the cube's chronological declaration order.
              ordered = expandYearOverYearRows(slicerMembers, allMembers);
            } else {
              // #991: each pinned member + its preceding sibling.
              const slicerSet = new Set(slicerMembers);
              const expanded = new Set<string>();
              for (let i = 0; i < allMembers.length; i++) {
                if (slicerSet.has(allMembers[i].uniqueName)) {
                  expanded.add(allMembers[i].uniqueName);
                  if (i > 0) expanded.add(allMembers[i - 1].uniqueName);
                }
              }
              // Preserve declared order so lastAndPriorValues' last/prior
              // semantics align with the cube's chronological order.
              ordered = allMembers
                .filter((m) => expanded.has(m.uniqueName))
                .map((m) => m.uniqueName);
            }
            if (ordered.length > 0 && rows.length > 0) {
              rows[0] = { ...rows[0], members: ordered };
            }
          }
        }

        const body: Record<string, unknown> = {
          cube: c,
          measures: [{ name: measure }],
          rows,
          filters: Array.from(byHierarchy.values()),
        };
        const json = JSON.stringify(body);
        if (json === lastQueryJson) return;
        lastQueryJson = json;
        const r = await executeAiQuery(body, "records");
        response = r;
        if (r.status !== "SUCCESS") error = r.error ?? `Query failed: ${r.status}`;
        else auto.markUpdated(); // #931
      } catch (e: unknown) {
        error = e instanceof Error ? e.message : String(e);
        response = null;
      } finally {
        loading = false;
      }
    })();
  });

  /** The headline number's source value. For "prior-period" comparison
   *  we want the latest period (last row); otherwise the only cell. */
  let valueAndPrior = $derived.by<{ current: number | null; prior: number | null }>(() => {
    if (!response || response.status !== "SUCCESS") return { current: null, prior: null };
    if (wantsSeries) {
      const series = (response.data ?? []).map((row) => {
        // The single measure column — pick whichever value in the row is
        // an AiCell. There's only one per row for a one-measure query.
        for (const v of Object.values(row)) {
          if (isAiCell(v)) return { value: v.value };
        }
        return { value: null };
      });
      // The series is used WHOLE. A partial trailing period is never dropped —
      // its value is real and the tile reports it. Only the comparison is
      // withheld (see `comparable` below).
      return lastAndPriorValues(series);
    }
    // Single-cell query: data has one row with one measure cell.
    const row = response.data?.[0];
    if (!row) return { current: null, prior: null };
    for (const v of Object.values(row)) {
      if (isAiCell(v)) return { current: v.value, prior: null };
    }
    return { current: null, prior: null };
  });

  let mainValue = $derived(valueAndPrior.current);

  /* The author has declared the newest period incomplete. The value stays on
   * screen — it is real — but it is labelled so nobody reads a part-period as a
   * full one, and the comparison against it is withheld. */
  let trailingIsPartial = $derived(
    wantsSeries &&
      response?.status === "SUCCESS" &&
      isTrailingPartial(response.data?.length ?? 0, kpi.partialTrailing),
  );

  /** Caption of the period the headline reports, shown only when it's partial. */
  let periodCaption = $derived.by<string | null>(() => {
    if (!trailingIsPartial) return null;
    const rows = response?.data ?? [];
    const row = rows[rows.length - 1];
    if (!row) return null;
    // The row header (the non-measure cell) is the period's caption.
    for (const [, v] of Object.entries(row)) {
      if (!isAiCell(v) && typeof v === "string" && v.trim()) {
        return periodLabel(v, kpi.timeLevel?.level);
      }
    }
    return null;
  });

  let periodNote = $derived(
    trailingIsPartial
      ? i18n.t(
          "dashboard.kpi.partialPeriod",
          "This period is still incomplete, so it isn't compared against a full one — the comparison would measure the calendar, not the business.",
        )
      : undefined,
  );

  let delta = $derived.by<KpiDeltaT | null>(() => {
    // A part-period measured against a whole one describes the calendar, not
    // the business — so no percentage is offered rather than a misleading one.
    // The value itself is untouched and still on screen.
    if (trailingIsPartial) return null;
    // prior-period and year-over-year share the last/prior series shape —
    // the difference is purely which baseline member the expansion picked.
    if (kpi.comparison === "prior-period" || kpi.comparison === "year-over-year") {
      return kpiDelta(valueAndPrior.current, valueAndPrior.prior, kpi.direction);
    }
    if (kpi.comparison === "target" && kpi.target != null) {
      return kpiDelta(mainValue, kpi.target, kpi.direction);
    }
    return null;
  });

  let mainColourToken = $derived(kpiThresholdToken(mainValue, kpi.thresholds, kpi.direction));

  let formattedMain = $derived(formatKpi(mainValue, kpi.format, kpi.customFormat));

  /** Re-render sparkline whenever the underlying series changes. */
  $effect(() => {
    if (!kpi.sparkline || !wantsSeries) {
      spark?.clear();
      return;
    }
    // Lazy-init here (not onMount): the sparkline host is rendered only once the
    // tile has data + sparkline is on, so at mount time sparkHost is still null.
    // Initialising in this effect — which re-runs when sparkHost binds — is what
    // actually gives the sparkline a canvas.
    if (!spark && sparkHost) {
      spark = echarts.init(sparkHost);
      sparkResize = new ResizeObserver(() => spark?.resize());
      sparkResize.observe(sparkHost);
    }
    if (!spark || !response || response.status !== "SUCCESS") return;
    // Every point is plotted, including a partial trailing period — the chart
    // shows the data as it is.
    const values = (response.data ?? []).map((row) => {
      for (const v of Object.values(row)) {
        if (isAiCell(v)) return v.value ?? null;
      }
      return null;
    });
    // Follow the surrounding theme accent (e.g. an App Builder app's
    // --saiku-app-accent) so the sparkline matches the dashboard's palette
    // instead of ECharts' default blue. Falls back to ECharts' default when no
    // accent var is in scope (plain dashboards keep their existing look).
    let accent: string | undefined;
    try {
      if (sparkHost) {
        const c = getComputedStyle(sparkHost).getPropertyValue("--saiku-app-accent").trim();
        if (c) accent = c;
      }
    } catch {
      /* getComputedStyle can throw on a detached node — ignore */
    }
    spark.setOption(
      {
        animation: false,
        grid: { top: 4, right: 4, bottom: 4, left: 4 },
        xAxis: { type: "category", show: false, data: values.map((_, i) => i) },
        yAxis: { type: "value", show: false, scale: true },
        color: accent ? [accent] : undefined,
        series: [
          {
            type: "line",
            data: values,
            smooth: true,
            showSymbol: false,
            lineStyle: accent ? { width: 1.6, color: accent } : { width: 1.5 },
            areaStyle: accent ? { color: accent, opacity: 0.12 } : undefined,
          },
        ],
        tooltip: { show: false },
      },
      true,
    );
  });

  // Pretty-print the delta ratio for the comparison callout.
  let deltaLabel = $derived(formatDeltaLabel(delta));

  function formatDeltaLabel(d: KpiDeltaT | null): string {
    if (!d || d.ratio == null) return "";
    const pct = new Intl.NumberFormat(undefined, {
      style: "percent",
      maximumFractionDigits: 1,
      signDisplay: "always",
    }).format(d.ratio);
    // An explicit suffix still wins — some cubes need their own vocabulary
    // ("vs last trading day"). Everything else is DERIVED from the comparison
    // and the tile's time level, so the callout can't contradict the grain the
    // tile actually queries (see deltaLabelFor).
    if (kpi.deltaSuffix) {
      return `${pct} ${kpi.deltaSuffix}`;
    }
    const label = deltaLabelFor(kpi.comparison, kpi.timeLevel);
    return `${pct} ${i18n.t(label.key, label.fallback)}`;
  }
</script>

{#if !tile.cube || !kpi.measure}
  <div class="placeholder">
    KPI tile has no measure — open
    <Settings2 size={14} class="placeholder__icon" aria-label="tile settings" />
    to pick a cube + measure.
  </div>
{:else}
  <div class="kpi-tile" data-tone={delta?.tone ?? "flat"}>
    {#if loading && response == null}
      <TileLoading variant="kpi" />
    {:else if error}
      <TileError message={error} onRetry={retry} />
    {:else if isEmpty}
      <TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} />
    {:else}
      <div
        class="value"
        title={periodNote}
        style={mainColourToken ? `color: var(${mainColourToken});` : ""}>
        {formattedMain}
      </div>
      {#if periodCaption}
        <!-- The value above is the newest period's real figure. It is labelled
             partial so nobody reads it as a completed one, and no comparison is
             shown against it. -->
        <div class="period" title={periodNote}>
          {periodCaption} · {i18n.t("dashboard.kpi.partial", "partial")}
        </div>
      {/if}
      {#if delta && delta.ratio != null}
        <div class="delta" data-tone={delta.tone}>
          {#if delta.tone === "positive"}
            <ArrowUpRight size={14} aria-hidden="true" />
          {:else if delta.tone === "negative"}
            <ArrowDownRight size={14} aria-hidden="true" />
          {:else}
            <Minus size={14} aria-hidden="true" />
          {/if}
          <span>{deltaLabel}</span>
        </div>
      {:else if kpi.comparison === "year-over-year" && wantsSeries && valueAndPrior.current != null && valueAndPrior.prior == null}
        <div class="delta delta--empty" title={i18n.t("dashboard.kpi.noLastYear.title", "The selected period has no same-period-previous-year counterpart in this cube's data.")}>
          <span>{i18n.t("dashboard.kpi.noLastYear", "no prior year")}</span>
        </div>
      {:else if kpi.comparison === "prior-period" && wantsSeries && valueAndPrior.current != null && valueAndPrior.prior == null}
        <div class="delta delta--empty" title={i18n.t("dashboard.kpi.noPriorPeriod.title", "The selected period has no preceding period in this cube's data.")}>
          <span>{i18n.t("dashboard.kpi.noPriorPeriod", "no prior period")}</span>
        </div>
      {:else if kpi.measureCaption && !trailingIsPartial}
        <!-- Suppressed while a partial period is flagged: the "Week 52 · partial"
             line already occupies that slot, and the measure caption would just
             repeat the tile's own title underneath it. -->
        <div class="caption">{kpi.measureCaption}</div>
      {/if}
      {#if kpi.sparkline && wantsSeries}
        <div class="w-full h-[36px] shrink-0" bind:this={sparkHost} aria-hidden="true"></div>
      {/if}
    {/if}
    {#if autoRefreshOn && auto.lastUpdated > 0}
      <!-- #931: auto-refresh "last updated" badge + spinning icon. -->
      <div class="refresh-badge">
        <TileRefreshIndicator lastUpdated={auto.lastUpdated} spinning={loading} now={auto.now} />
      </div>
    {/if}
  </div>
{/if}

<style>
.kpi-tile {
    --kpi-red: var(--danger, #c00);
    --kpi-yellow: var(--warning, #c79a00);
    --kpi-green: var(--success, #1b8a3a);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    padding: 0.5rem;
    text-align: center;
    gap: 0.25rem;
    /* container-type lives on the tile (the outer box) so children can
       size with cqi/cqh against the actual tile dimensions. Previously
       this was on .value, which is self-referential — cqi resolves to 0
       on the element that establishes the container, and the browser
       falls back to the viewport, which made the number sized off the
       window rather than the tile (and the contain: inline-size that
       comes with container-type made .value's box width ignore its
       content, shifting it off-centre in the flex column). */
    container-type: size;
  }
  /* Which period the headline is actually reporting. Sits quietly under the
     number — present only when trailing periods are excluded. */
  .period {
    font-size: 0.68rem;
    letter-spacing: 0.04em;
    color: var(--saiku-app-muted, hsl(var(--fg-muted)));
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .value {
    /* clamp scales the number against the tile's inline size (cqi) so
       it stays readable across 2-row and 6-row tiles. width:100% +
       text-align:center keeps the number horizontally centred even
       when the parent's align-items computation gets confused by an
       intrinsic-width child. */
    width: 100%;
    font-size: clamp(1.75rem, 12cqi, 4rem);
    font-weight: 600;
    line-height: 1.1;
    letter-spacing: -0.01em;
    color: hsl(var(--fg));
    text-align: center;
  }
  .delta {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    font-size: 0.8125rem;
    color: hsl(var(--fg-muted));
  }
  .delta[data-tone="positive"] {
    color: var(--success, #1b8a3a);
  }
  .delta[data-tone="negative"] {
    color: var(--danger, #c00);
  }
  .delta.delta--empty {
    color: hsl(var(--fg-muted));
    font-style: italic;
    cursor: help;
  }
  .caption {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  /* #931: auto-refresh badge — top-right of the KPI box, click-through.
     Top (not bottom) so it clears the optional sparkline along the KPI's
     bottom edge. container-type:size on .kpi-tile makes it the containing
     block, so the absolute anchor resolves to the tile. */
  .refresh-badge {
    position: absolute;
    right: 0.25rem;
    top: 0.25rem;
    z-index: 2;
    background: color-mix(in srgb, hsl(var(--bg)) 80%, transparent);
    border-radius: 4px;
    padding: 0.0625rem 0.25rem;
    pointer-events: none;
    max-width: calc(100% - 0.5rem);
  }
  .placeholder {
    padding: 0.75rem 1rem;
    color: hsl(var(--fg-muted));
    font-size: 0.8125rem;
    text-align: center;
  }
  .placeholder :global(.placeholder__icon) {
    display: inline-block;
    vertical-align: -2px;
    color: hsl(var(--fg-subtle));
  }
</style>
