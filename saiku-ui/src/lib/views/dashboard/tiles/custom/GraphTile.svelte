<script lang="ts">
  /*
   * Custom tile renderer: `graph` (App Builder Phase 2, saiku#1441).
   *
   * Renders a query's RECORDS as an ECharts `graph` series (nodes + edges) —
   * e.g. an ownership / relationship graph. The author supplies a declarative
   * column mapping (NO code) on `tile.custom.options`
   * ({idCol, labelCol?, sourceCol, targetCol, valueCol?, layout?}); it is
   * validated with validateGraphConfig BEFORE any transform, and an invalid
   * config renders an inline placeholder instead of a graph.
   *
   * Data + query reuse: this tile fetches through the SAME shared hook the
   * built-in chart tile + the sibling echarts-option tile use (TileQueryState +
   * runTileQueryEffect), so filters, dedupe, retry and auto-refresh all behave
   * identically. Unlike echarts-option it consumes the raw records
   * (response.data) rather than the {categories, series} projection — a graph
   * needs the per-row endpoint columns. Rendered on a shared echarts instance.
   *
   * Node click → cross-filter reuses the built-in chart tile's helper: the
   * clicked node id is resolved to a real member unique name on the tile's first
   * row axis via searchMembers + pickMemberUniqueName, then emitted through
   * onClickFilter (identical to EChartsOptionTile.handleClick).
   */

  import { onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { DashboardTile, DashboardFilter, CubeRef } from "$lib/api/dashboards";
  import { TileQueryState, runTileQueryEffect } from "$lib/hooks/useTileQuery.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import { type SchemaLike } from "$lib/dashboard/effectiveQuery";
  import {
    recordsToGraph,
    validateGraphConfig,
    weightRange,
    nodeSize,
    graphLayoutBox,
    graphLabelExtent,
  } from "$lib/dashboard/custom/graphTile";
  import { searchMembers } from "$lib/api/aiQuery";
  import { resolveChartTokensFor } from "$lib/dashboard/appChartTheme";
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

  // Whether the author has supplied any config at all — distinguishes a
  // brand-new / unconfigured tile from a genuinely invalid one.
  let hasConfig = $derived(
    !!tile.custom?.options && Object.keys(tile.custom.options).length > 0,
  );
  // Validate the column mapping (fail-closed). Recomputes when the tile's saved
  // options change (editor save).
  let validation = $derived(validateGraphConfig(tile.custom?.options));

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

  // Render: transform the raw records into nodes + links, then setOption a
  // `graph` series. The author owns the column mapping + layout; the data is
  // always rebuilt fresh from the current response.
  $effect(() => {
    const r = response;
    // Repaint on theme flip so labels track the current palette.
    void theme.effective;
    if (!chart) return;
    if (!validation.ok || !r || r.status !== "SUCCESS") {
      chart.clear();
      return;
    }
    const graph = recordsToGraph(r.data ?? [], validation.value);
    // saiku#1775: paint from the App shell's tokens like every other tile. With
    // nothing set, ECharts fell back to its own #5470c6 and the graph was the one
    // blue element on a cyan/amber app — the docs promise custom renderers
    // "follow the App theme". Outside an App this resolves to the Saiku UI
    // tokens, so dashboards are unchanged in spirit but now theme-aware too.
    const tokens = resolveChartTokensFor(host);
    const option = {
      tooltip: { trigger: "item" },
      series: [
        {
          type: "graph",
          layout: validation.value.layout ?? "force",
          // saiku#1793: inset the node ring so the outward-drawn labels have room.
          // Without a layout box ECharts sizes the ring to the container and every
          // outer label is clipped at the tile edge.
          ...graphLayoutBox(validation.value.layout),
          roam: true,
          draggable: true,
          label: { show: true, position: "right", color: tokens.fg, ...graphLabelExtent() },
          itemStyle: { color: tokens.accent },
          force: { repulsion: 140, edgeLength: 90, gravity: 0.08 },
          circular: { rotateLabel: true },
          emphasis: { focus: "adjacency" },
          lineStyle: { color: "source", curveness: 0.1, opacity: 0.55 },
          data: (() => {
            // Sizing is relative to the weights this graph actually carries
            // (saiku#1755), so the scale works whatever the measure's units.
            const range = weightRange(graph.nodes);
            return graph.nodes.map((n) => ({
              id: n.id,
              name: n.name,
              value: n.value,
              symbolSize: nodeSize(n.value, range),
            }));
          })(),
          links: graph.links.map((l) => ({
            source: l.source,
            target: l.target,
            value: l.value,
          })),
        },
      ],
    };
    // notMerge=true so a re-render never leaves stale nodes/links behind.
    chart.setOption(option, true);
  });


  // Best-effort click-to-filter for INLINE tiles: map a clicked node id back to
  // a real member unique name on the tile's first row axis, mirroring the
  // built-in chart tile + EChartsOptionTile. Reference tiles (axes resolved
  // server-side) and non-node clicks are no-ops.
  function handleClick(params: echarts.ECElementEvent): void {
    if (!onClickFilter || tile.query?.kind !== "inline") return;
    if (params.dataType !== "node") return;
    const data = params.data as { id?: string; name?: string } | undefined;
    const nodeId =
      (typeof data?.id === "string" && data.id) ||
      (typeof params.name === "string" ? params.name : null);
    const body = tile.query.body as {
      rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
    };
    const rowAxis = body.rows?.[0];
    const cube = resolvedCube;
    if (!rowAxis || !cube || !nodeId) return;
    const { dimension, hierarchy, level } = rowAxis;
    void searchMembers(cube, dimension, hierarchy, level, nodeId).then((hits) => {
      const uniqueName = pickMemberUniqueName(hits, nodeId);
      if (uniqueName) onClickFilter?.({ dimension, hierarchy, level, members: [uniqueName] });
    });
  }
</script>

{#if !hasConfig}
  <div class="p-3 text-fg-muted text-sm">
    No graph mapping configured yet — open ⚙ to pick the source / target columns.
  </div>
{:else if !validation.ok}
  <div class="p-3 text-danger text-sm" role="alert">
    <div class="font-medium">Invalid graph config</div>
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
    background: var(--saiku-app-surface, hsl(var(--bg)));
    color: var(--saiku-app-muted, hsl(var(--fg-muted)));
    z-index: 1;
  }
  .overlay.solid {
    pointer-events: auto;
  }
</style>
