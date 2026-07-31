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
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import TileLoading from "../TileLoading.svelte";
  import TileError from "../TileError.svelte";
  import TileEmpty from "../TileEmpty.svelte";

  interface Props {
    tile: DashboardTile;
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

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
    const option = applyDataToEchartsOption(validation.value, projection);
    // notMerge=true so a re-render never leaves stale series/axis state behind.
    chart.setOption(option, true);
  });

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
  .overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg);
    color: var(--fg-muted);
    z-index: 1;
  }
  .overlay.solid {
    pointer-events: auto;
  }
</style>
