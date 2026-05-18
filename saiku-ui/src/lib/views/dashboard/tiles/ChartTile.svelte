<script lang="ts">
  /*
   * Chart tile. Same fetch pattern as TableTile — effective query
   * computed from base + active filters + schema, posted to /ai/query
   * in records format. The response is projected through
   * buildChartOption() and handed to an ECharts instance.
   *
   * Click capture: ECharts emits `click` events on data points; for a
   * category-axis click we map back to the tile's first row-axis
   * level and emit an onClickFilter with the clicked category as the
   * member. Pie slice clicks use the slice name as the member.
   */

  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import {
    executeAiQuery,
    executeSavedQuery,
    type AiQueryResponse,
  } from "$lib/api/aiQuery";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    effectiveQueryFor,
    applicableSavedFilters,
    type SchemaLike,
  } from "$lib/dashboard/effectiveQuery";
  import {
    inferCubeFromReference,
    inferRowAxesFromReference,
    type RowAxisRef,
  } from "$lib/dashboard/filterSuggestions";
  import { buildChartOption, isSupportedChartKind } from "$lib/dashboard/chartOptions";
  import type { CubeRef } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  let host = $state<HTMLDivElement | null>(null);
  let chart: echarts.ECharts | null = null;
  let resizeObserver: ResizeObserver | null = null;

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);
  let schema = $state<SchemaLike | null>(null);
  let unsupported = $state(false);

  /* ----------------------------- lifecycle --------------------------- */

  onMount(() => {
    if (!host) return;
    chart = echarts.init(host);
    chart.on("click", handleEChartsClick);

    resizeObserver = new ResizeObserver(() => chart?.resize());
    resizeObserver.observe(host);
  });

  onDestroy(() => {
    resizeObserver?.disconnect();
    chart?.dispose();
    chart = null;
  });

  /* ----------------------------- schema cache ------------------------- */

  // Resolved cube — usually tile.cube directly, but for reference tiles
  // authored before saiku#878 (where the modal only set tile.cube on an
  // explicit pick), we lazy-infer it from the saved .saiku ThinQuery so
  // the schema lookup + filter applicability check still works.
  let resolvedCube = $state<CubeRef | null>(null);
  let inferenceAttempted = $state(false);
  // Row axes from the saved ThinQuery for reference tiles. Click-to-
  // filter uses the first entry as the dim/hier/level the clicked
  // category corresponds to — inline tiles read the same shape directly
  // off `tile.query.body.rows`. Null until inference completes; click
  // handler short-circuits while we wait.
  let referenceRowAxes = $state<RowAxisRef[] | null>(null);

  $effect(() => {
    if (tile.cube) {
      resolvedCube = tile.cube;
      // Inline tiles never need row-axis inference (the handler reads
      // tile.query.body.rows directly), but leave the state null so
      // the reference branch in handleEChartsClick stays opt-in.
      return;
    }
    if (tile.query?.kind !== "reference" || inferenceAttempted) return;
    inferenceAttempted = true;
    const refPath = tile.query.path;
    void inferCubeFromReference(refPath).then((inferred) => {
      if (inferred) resolvedCube = inferred;
    });
    void inferRowAxesFromReference(refPath).then((axes) => {
      referenceRowAxes = axes;
    });
  });

  $effect(() => {
    const v = schemaCache.version;
    void v;
    if (!resolvedCube) {
      schema = null;
      return;
    }
    const cached = schemaCache.peek(resolvedCube) as SchemaLike | null;
    if (cached) {
      schema = cached;
    } else {
      void schemaCache.get(resolvedCube).catch(() => {});
    }
  });

  /* ----------------------------- fetch effect ------------------------- */

  let lastQueryJson = $state<string>("");

  $effect(() => {
    const tileQuery = tile.query;
    const active = activeFilters.all;
    const s = schema;
    void s;
    if (!tileQuery) return;

    if (tileQuery.kind === "reference") {
      const refFilters = applicableSavedFilters(schema, active);
      const key = `ref:${tileQuery.path}|${JSON.stringify(refFilters)}`;
      if (key === lastQueryJson) return;
      lastQueryJson = key;
      loading = true;
      error = null;
      void (async () => {
        try {
          const r = await executeSavedQuery(
            tileQuery.path,
            refFilters.map((f) => ({
              dimension: f.dimension,
              hierarchy: f.hierarchy,
              level: f.level,
              members: f.members ?? [],
            })),
          );
          response = r;
          if (r.status !== "SUCCESS") error = r.error ?? `Query failed: ${r.status}`;
        } catch (e: unknown) {
          error = e instanceof Error ? e.message : String(e);
          response = null;
        } finally {
          loading = false;
        }
      })();
      return;
    }

    const effective = effectiveQueryFor(tile, active, schema);
    if (!effective) return;
    const json = JSON.stringify(effective);
    if (json === lastQueryJson) return;
    lastQueryJson = json;

    loading = true;
    error = null;
    void (async () => {
      try {
        const r = await executeAiQuery(effective, "records");
        response = r;
        if (r.status !== "SUCCESS") {
          error = r.error ?? `Query failed: ${r.status}`;
        }
      } catch (e: unknown) {
        error = e instanceof Error ? e.message : String(e);
        response = null;
      } finally {
        loading = false;
      }
    })();
  });

  /* ----------------------------- render effect ------------------------ */

  $effect(() => {
    const r = response;
    const kind = tile.chartType ?? "bar";
    void r;
    if (!chart) return;
    unsupported = !isSupportedChartKind(kind);
    if (!r || r.status !== "SUCCESS") {
      chart.clear();
      return;
    }
    if (unsupported) {
      chart.clear();
      return;
    }
    const option = buildChartOption(r, kind);
    if (option) {
      // notMerge=true so axis category changes don't leave stale ticks.
      chart.setOption(option, true);
    } else {
      chart.clear();
    }
  });

  /* ----------------------------- click capture ------------------------ */

  function handleEChartsClick(params: echarts.ECElementEvent): void {
    if (!onClickFilter) return;
    if (!tile.query) return;

    // Resolve the row axis the clicked category maps to. Inline tiles
    // carry it directly on the request body; reference tiles need the
    // axes inferred lazily from the saved ThinQuery (handled in the
    // mount-time effect above). If inference hasn't completed yet, the
    // click is a no-op rather than building a malformed filter.
    let rowAxis: { dimension: string; hierarchy: string; level: string } | undefined;
    if (tile.query.kind === "inline") {
      const body = tile.query.body as {
        rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
      };
      rowAxis = body.rows?.[0];
    } else if (tile.query.kind === "reference") {
      rowAxis = referenceRowAxes?.[0];
    }
    if (!rowAxis) return;

    // ECharts click events carry different shapes per series type. For
    // bar/line/area: params.name is the category. For pie: params.name
    // is the slice name. In both cases that's the dashboard.click
    // "member" value.
    const name = typeof params.name === "string" ? params.name : null;
    if (!name) return;
    const filter: DashboardFilter = {
      dimension: rowAxis.dimension,
      hierarchy: rowAxis.hierarchy,
      level: rowAxis.level,
      members: [name],
    };
    onClickFilter(filter);
  }
</script>

{#if !tile.query}
  <div class="placeholder">Tile has no query binding — open ⚙ to set one.</div>
{:else}
  <div class="chart-tile">
    {#if loading && !response}
      <div class="overlay">Loading…</div>
    {/if}
    {#if error}
      <div class="overlay error">{error}</div>
    {/if}
    {#if unsupported}
      <div class="overlay">
        Chart type <code>{tile.chartType}</code> not yet supported in dashboards.
      </div>
    {/if}
    <div class="canvas" bind:this={host}></div>
  </div>
{/if}

<style>
  .chart-tile {
    position: relative;
    height: 100%;
    width: 100%;
  }
  .canvas {
    height: 100%;
    width: 100%;
  }
  .overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: color-mix(in srgb, var(--bg) 75%, transparent);
    color: var(--fg-muted);
    font-size: 0.875rem;
    z-index: 1;
    pointer-events: none;
    text-align: center;
    padding: 0.5rem;
  }
  .overlay.error {
    color: var(--danger);
    background: color-mix(in srgb, var(--danger) 18%, var(--bg));
  }
  .placeholder {
    padding: 1rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
  }
  code {
    background: var(--bg-subtle);
    padding: 0.0625em 0.25em;
    border-radius: 3px;
    font-size: 0.85em;
  }
</style>
