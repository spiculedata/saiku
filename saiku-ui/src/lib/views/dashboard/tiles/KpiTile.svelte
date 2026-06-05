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

  import { onDestroy, onMount } from "svelte";
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
    formatKpi,
    kpiDelta,
    kpiThresholdToken,
    lastAndPriorValues,
    type KpiDelta as KpiDeltaT,
  } from "$lib/dashboard/kpi";
  // Issue #933 — shared loading / error / empty states.
  import TileLoading from "./TileLoading.svelte";
  import TileError from "./TileError.svelte";
  import TileEmpty from "./TileEmpty.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  // #941 share viewer (PR2): render from a prefetched guest response when present.
  import { getShareViewResponse } from "$lib/dashboard/shareViewContext";

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

  let sparkHost = $state<HTMLDivElement | null>(null);
  let spark: echarts.ECharts | null = null;
  let sparkResize: ResizeObserver | null = null;

  onMount(() => {
    if (sparkHost) {
      spark = echarts.init(sparkHost);
      sparkResize = new ResizeObserver(() => spark?.resize());
      sparkResize.observe(sparkHost);
    }
  });

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

  // True when we need the by-time-level query (for sparkline or
  // prior-period delta); false when a single-cell query is enough.
  let wantsSeries = $derived(
    !!kpi.timeLevel && (kpi.sparkline === true || kpi.comparison === "prior-period"),
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
    const active = activeFilters.all;
    // #933 — retry() bumps this to force a refetch with unchanged inputs.
    void retryTick;
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
      // Schema-aware prior-period expansion: when the slicer targets
      // the *same level* as timeLevel (e.g. timeLevel=Quarter, slicer
      // pins Quarter=Q2), enumerating the full series defeats the
      // user's filter intent. Instead, after the members API resolves
      // the level's full ordered list, expand the row to include each
      // slicer member plus its preceding sibling. lastAndPriorValues
      // then computes a real prior-period delta vs the period before
      // the filter (Q2 → vs Q1). Slicers at a *different* level (e.g.
      // Year=1997 with timeLevel=Quarter) fall back to "drop slicer,
      // enumerate full series" — handling Year→Quarter descent needs
      // server-side Descendants() support we don't have yet.
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
            const slicerSet = new Set(needsPriorExpansion.slicer.members);
            const expanded = new Set<string>();
            for (let i = 0; i < allMembers.length; i++) {
              if (slicerSet.has(allMembers[i].uniqueName)) {
                expanded.add(allMembers[i].uniqueName);
                if (i > 0) expanded.add(allMembers[i - 1].uniqueName);
              }
            }
            // Preserve declared order so lastAndPriorValues' last/prior
            // semantics align with the cube's chronological order.
            const ordered = allMembers
              .filter((m) => expanded.has(m.uniqueName))
              .map((m) => m.uniqueName);
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

  let delta = $derived.by<KpiDeltaT | null>(() => {
    if (kpi.comparison === "prior-period") {
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
    if (!spark || !response || response.status !== "SUCCESS") return;
    const values = (response.data ?? []).map((row) => {
      for (const v of Object.values(row)) {
        if (isAiCell(v)) return v.value ?? null;
      }
      return null;
    });
    spark.setOption(
      {
        animation: false,
        grid: { top: 4, right: 4, bottom: 4, left: 4 },
        xAxis: { type: "category", show: false, data: values.map((_, i) => i) },
        yAxis: { type: "value", show: false, scale: true },
        series: [
          {
            type: "line",
            data: values,
            smooth: true,
            showSymbol: false,
            lineStyle: { width: 1.5 },
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
    if (kpi.comparison === "target") return `${pct} vs target`;
    return `${pct} vs prior`;
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
      <div class="value" style={mainColourToken ? `color: var(${mainColourToken});` : ""}>
        {formattedMain}
      </div>
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
      {:else if kpi.comparison === "prior-period" && wantsSeries && valueAndPrior.current != null && valueAndPrior.prior == null}
        <div class="delta delta--empty" title="The selected period has no preceding period in this cube's data.">
          <span>no prior period</span>
        </div>
      {:else if kpi.measureCaption}
        <div class="caption">{kpi.measureCaption}</div>
      {/if}
      {#if kpi.sparkline && wantsSeries}
        <div class="spark" bind:this={sparkHost} aria-hidden="true"></div>
      {/if}
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
    color: var(--fg);
    text-align: center;
  }
  .delta {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    font-size: 0.8125rem;
    color: var(--fg-muted);
  }
  .delta[data-tone="positive"] {
    color: var(--success, #1b8a3a);
  }
  .delta[data-tone="negative"] {
    color: var(--danger, #c00);
  }
  .delta.delta--empty {
    color: var(--fg-muted);
    font-style: italic;
    cursor: help;
  }
  .caption {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .spark {
    width: 100%;
    height: 36px;
    flex-shrink: 0;
  }
  .placeholder {
    padding: 0.75rem 1rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
    text-align: center;
  }
  .placeholder :global(.placeholder__icon) {
    display: inline-block;
    vertical-align: -2px;
    color: var(--fg-subtle);
  }
</style>
