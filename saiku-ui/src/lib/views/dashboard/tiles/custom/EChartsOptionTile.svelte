<script lang="ts">
  /*
   * Custom tile renderer: `echarts-option` (App Builder Phase 2, saiku#1441).
   *
   * The app author supplies a declarative ECharts `option` object (NO code) on
   * `tile.custom.options`. It is validated against the safe subset in
   * $lib/dashboard/custom/echartsOption BEFORE it ever reaches setOption; an
   * invalid option renders an inline placeholder instead of a chart.
   *
   * Data + query reuse: this tile fetches through the SAME shared hook the
   * built-in chart tile uses (TileQueryState + runTileQueryEffect), so filters,
   * dedupe, retry and auto-refresh all behave identically. The response is
   * projected with projectFromAiQueryResponse (the same helper the chart tile
   * feeds ECharts), then merged into the author's option and rendered on a
   * shared echarts instance.
   */

  import { onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { DashboardTile, DashboardFilter, CubeRef } from "$lib/api/dashboards";
  import { TileQueryState, runTileQueryEffect } from "$lib/hooks/useTileQuery.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import { type SchemaLike } from "$lib/dashboard/effectiveQuery";
  import { projectFromAiQueryResponse } from "$lib/dashboard/chartOptions";
  import {
    validateEchartsOption,
    applyDataToEchartsOption,
    type EChartsDataProjection,
  } from "$lib/dashboard/custom/echartsOption";
  import { searchMembers } from "$lib/api/aiQuery";
  import { pickMemberUniqueName } from "$lib/dashboard/clickFilterMember";
  import { theme } from "$lib/stores/theme.svelte";
  // The author option is layered over a themed baseline so anything it doesn't
  // state (title colour, axis colours, series palette) follows the app theme.
  import {
    appEchartsBase,
    readAppBrandTokens,
    resolveChartTokensFor,
    withAppEchartsDefaults,
  } from "$lib/dashboard/appChartTheme";
  import { getAppThemeSignature } from "$lib/views/app/appThemeContext";
  import { applyValueAxisFormat } from "$lib/dashboard/custom/valueAxisFormat";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import TileLoading from "../TileLoading.svelte";
  import TileError from "../TileError.svelte";
  import TileEmpty from "../TileEmpty.svelte";

  interface Props {
    tile: DashboardTile;
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  // Null outside an App Builder app. getContext must run at init.
  const appThemeSignature = getAppThemeSignature();

  // Trend/Breakdown toggle (opt-in via tile.custom.trendBreakdown). "trend"
  // keeps the author's line series; "breakdown" swaps them to bars over the
  // same query — a display-only mode switch, no re-query.
  /** Fallback "current period" marker colour, used outside a themed app. */
  const ACCENT_LAST_FALLBACK = "#c85a3a";
  let showToggle = $derived(!!tile.custom?.trendBreakdown);
  let emphasizeLast = $derived(!!tile.custom?.emphasizeLast);
  let mode = $state<"trend" | "breakdown">("trend");

  // Whether the author has supplied any option at all — distinguishes a
  // brand-new / unconfigured tile from a genuinely invalid one.
  let hasOption = $derived(
    !!tile.custom?.options && Object.keys(tile.custom.options).length > 0,
  );
  // Validate the author option (fail-closed). Recomputes when the tile's saved
  // options change (editor save).
  let validation = $derived(validateEchartsOption(tile.custom?.options));

  let host = $state<HTMLDivElement | null>(null);
  // $state.raw so the render effect re-fires the moment init completes without
  // Svelte proxying ECharts' internal state (mirrors ChartTile).
  let chart = $state.raw<echarts.ECharts | null>(null);
  let resizeObserver: ResizeObserver | null = null;

  const q = new TileQueryState();
  let response = $derived(q.response);
  let loading = $derived(q.loading);
  let error = $derived(q.error);
  function retry(): void {
    q.retry();
  }

  let schema = $state<SchemaLike | null>(null);
  let resolvedCube = $state<CubeRef | null>(null);

  $effect(() => {
    if (tile.cube) resolvedCube = tile.cube;
  });

  $effect(() => {
    void schemaCache.version;
    if (!resolvedCube) {
      schema = null;
      return;
    }
    const cached = schemaCache.peek(resolvedCube) as SchemaLike | null;
    if (cached) schema = cached;
    else void schemaCache.get(resolvedCube).catch(() => {});
  });

  let isEmpty = $derived(
    !!response && response.status === "SUCCESS" && (response.data?.length ?? 0) === 0,
  );
  let hasEffectiveFilters = $derived(
    activeFilters.all.some((f) => (f.filter.members?.length ?? 0) > 0),
  );
  function resetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

  // Lazy-init the ECharts instance the first time `host` binds.
  $effect(() => {
    if (!host || chart) return;
    const instance = echarts.init(host);
    instance.on("click", handleClick);
    resizeObserver = new ResizeObserver(() => instance.resize());
    resizeObserver.observe(host);
    chart = instance;
  });

  onDestroy(() => {
    resizeObserver?.disconnect();
    chart?.dispose();
    chart = null;
  });

  // Fetch through the shared hook — reuse the exact chart/table plumbing.
  $effect(() => {
    void q.retryTick;
    void q.refreshTick;
    runTileQueryEffect(q, {
      tile,
      activeFilters: activeFilters.all,
      schema,
      sharedResponse: null,
    });
  });

  // Render: merge the query data into the validated author option, then setOption.
  $effect(() => {
    const r = response;
    // Repaint on theme flip so author text/axes track the current palette.
    void theme.effective;
    // …and on an app re-theme (canvas, so no CSS-var repaint — see ChartTile).
    void appThemeSignature?.();
    const curMode = mode; // dep: re-render on toggle
    void emphasizeLast;
    if (!chart) return;
    if (!validation.ok || !r || r.status !== "SUCCESS") {
      chart.clear();
      return;
    }
    const proj = projectFromAiQueryResponse(r);
    const projection: EChartsDataProjection = {
      categories: proj.rowCategories,
      series: proj.columnCategories.map((name, j) => ({
        name,
        data: proj.matrix.map((row) => row[j]),
      })),
    };
    const filled = applyDataToEchartsOption(validation.value, projection);
    // Layer the author's option over the app's themed baseline. Author wins at
    // every leaf; the baseline only fills what they left unsaid.
    const brand = readAppBrandTokens(host);
    const tokens = resolveChartTokensFor(host);
    const option = withAppEchartsDefaults(
      filled,
      appEchartsBase(tokens, {
        body: brand?.fontBody || "inherit",
        display: brand?.fontDisplay || brand?.fontBody || "inherit",
      }),
    );
    applyModeAndEmphasis(option, curMode, brand?.accent2 || ACCENT_LAST_FALLBACK, tokens.bg);
    // Compiled from the tile's declarative pattern — the author never supplies
    // a function, so the validator's no-functions rule stays absolute.
    applyValueAxisFormat(option, tile.custom?.valueFormat);
    // notMerge=true so a re-render never leaves stale series/axis state behind.
    chart.setOption(option, true);
  });

  /** Post-process the data-filled option for the Trend/Breakdown mode + the
   *  emphasised last point. Mutates `option` in place (it is a fresh clone from
   *  applyDataToEchartsOption). "breakdown" turns line series into bars; the
   *  emphasised last point recolours series[0]'s final datum. */
  function applyModeAndEmphasis(
    option: Record<string, unknown>,
    curMode: "trend" | "breakdown",
    accentLast: string,
    surface: string,
  ): void {
    const series = Array.isArray(option.series) ? (option.series as Record<string, unknown>[]) : [];
    if (curMode === "breakdown") {
      for (const s of series) {
        s.type = "bar";
        delete s.areaStyle;
        delete s.smooth;
        delete s.lineStyle;
        delete s.symbol;
      }
      return;
    }
    // trend mode: optionally emphasise the final point of the first series.
    if (emphasizeLast && series.length > 0) {
      const s0 = series[0];
      const data = Array.isArray(s0.data) ? [...(s0.data as unknown[])] : [];
      if (data.length > 0) {
        const lastIdx = data.length - 1;
        const raw = data[lastIdx];
        const value = raw && typeof raw === "object" ? (raw as Record<string, unknown>).value : raw;
        data[lastIdx] = {
          value,
          symbolSize: 10,
          itemStyle: { color: accentLast, borderColor: surface, borderWidth: 2 },
        };
        s0.data = data;
      }
    }
  }

  // Best-effort click-to-filter for INLINE tiles: map a clicked category caption
  // back to a real member unique name on the tile's first row axis, mirroring
  // the built-in chart tile. Reference tiles (axes resolved server-side) and
  // non-category clicks are no-ops.
  function handleClick(params: echarts.ECElementEvent): void {
    if (!onClickFilter || tile.query?.kind !== "inline") return;
    const body = tile.query.body as {
      rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
    };
    const rowAxis = body.rows?.[0];
    const cube = resolvedCube;
    const name = typeof params.name === "string" ? params.name : null;
    if (!rowAxis || !cube || !name) return;
    const { dimension, hierarchy, level } = rowAxis;
    void searchMembers(cube, dimension, hierarchy, level, name).then((hits) => {
      const uniqueName = pickMemberUniqueName(hits, name);
      if (uniqueName) onClickFilter?.({ dimension, hierarchy, level, members: [uniqueName] });
    });
  }
</script>

{#if !hasOption}
  <div class="p-3 text-fg-muted text-sm">
    No ECharts option configured yet — open ⚙ to paste one.
  </div>
{:else if !validation.ok}
  <div class="p-3 text-danger text-sm" role="alert">
    <div class="font-medium">Invalid ECharts option</div>
    <div class="mt-1">{validation.error}</div>
  </div>
{:else if !tile.query}
  <div class="p-3 text-fg-muted text-sm">
    Tile has no query binding — open ⚙ to set one.
  </div>
{:else}
  <div class="ec-tile">
    {#if showToggle}
      <div class="ec-toggle" role="group" aria-label="Chart mode">
        <button
          type="button"
          class="ec-toggle__btn"
          class:is-active={mode === "trend"}
          aria-pressed={mode === "trend"}
          onclick={() => (mode = "trend")}>Trend</button>
        <button
          type="button"
          class="ec-toggle__btn"
          class:is-active={mode === "breakdown"}
          aria-pressed={mode === "breakdown"}
          onclick={() => (mode = "breakdown")}>Breakdown</button>
      </div>
    {/if}
    <div class="canvas" bind:this={host}></div>
    {#if loading && !response}
      <div class="overlay solid"><TileLoading variant="chart" /></div>
    {:else if error}
      <div class="overlay solid"><TileError message={error} onRetry={retry} /></div>
    {:else if isEmpty}
      <div class="overlay solid">
        <TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} />
      </div>
    {/if}
  </div>
{/if}

<style>
  .ec-tile {
    position: relative;
    height: 100%;
    width: 100%;
    overflow: hidden;
  }
  .canvas {
    width: 100%;
    height: 100%;
  }
  /* Trend / Breakdown segmented toggle, pinned top-right of the chart card. */
  .ec-toggle {
    position: absolute;
    top: 12px;
    right: 14px;
    z-index: 2;
    display: inline-flex;
    padding: 3px;
    border-radius: 9px;
    background: var(--saiku-app-toggle-bg, #efe9dc);
    gap: 2px;
  }
  .ec-toggle__btn {
    border: 0;
    background: transparent;
    padding: 4px 12px;
    border-radius: 7px;
    font-family: -apple-system, "Segoe UI", sans-serif;
    font-size: 0.76rem;
    font-weight: 600;
    color: var(--saiku-app-muted, #8a7f68);
    cursor: pointer;
    line-height: 1.2;
  }
  .ec-toggle__btn.is-active {
    background: var(--saiku-app-card, #fff);
    color: var(--saiku-app-fg, #1f3529);
    box-shadow: 0 1px 2px rgba(30, 40, 30, 0.12);
  }
  .overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--saiku-app-surface, hsl(var(--bg)));
    color: var(--saiku-app-muted, hsl(var(--fg-muted)));
    z-index: 1;
  }
  .overlay.solid {
    pointer-events: auto;
  }
</style>
