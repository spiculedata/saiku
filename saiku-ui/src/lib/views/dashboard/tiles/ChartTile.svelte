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

  import { onDestroy } from "svelte";
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
  import { isSingleMeasureKind, smallMultipleRowCount } from "$lib/dashboard/smallMultiples";
  import { theme } from "$lib/stores/theme.svelte";
  import { resolveThemeTokens } from "$lib/views/chartTheme";
  import type { CubeRef } from "$lib/api/dashboards";
  // Issue #930 — right-click a data point to drill into its raw fact rows.
  import TileDrillthrough from "./TileDrillthrough.svelte";
  import { drillthroughPosition } from "$lib/dashboard/drillthroughCoord";
  // Issue #933 — shared loading / error / empty states.
  import TileLoading from "./TileLoading.svelte";
  import TileError from "./TileError.svelte";
  import TileEmpty from "./TileEmpty.svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  // #941 share viewer (PR2): in the public /share view a prefetched response is
  // injected via context; tiles render from it instead of fetching live.
  import { getShareViewResponse } from "$lib/dashboard/shareViewContext";

  interface Props {
    tile: DashboardTile;
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  // Non-null only inside the share viewer (getContext at init); see fetch effect.
  // tile.id is stable for a tile instance and getContext must run at init, so the
  // one-time read is intentional.
  // svelte-ignore state_referenced_locally
  const sharedResponse = getShareViewResponse(tile.id);

  let host = $state<HTMLDivElement | null>(null);
  // `$state.raw` so the ECharts instance is reactive on *reassignment*
  // (lets the render $effect re-fire the moment init completes) without
  // Svelte proxying the library's internal mutable state. Using a plain
  // `let` here silently broke the chart whenever `tile.query` hydrated
  // asynchronously: the {:else} branch with bind:this didn't exist on
  // first render, so onMount fired with `host` still null and chart was
  // never initialised — see screenshots in saiku#1012.
  let chart = $state.raw<echarts.ECharts | null>(null);
  let resizeObserver: ResizeObserver | null = null;
  // Bumped by the ResizeObserver so the render effect recomputes the
  // aspect-aware small-multiple radius when the canvas size changes (#1053).
  let resizeTick = $state(0);

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);
  let schema = $state<SchemaLike | null>(null);
  let unsupported = $state(false);

  // Issue #933 — retry re-runs the deduped fetch effect by clearing its
  // last-query cache and bumping a tick the effect reads. Empty = a
  // successful query with zero rows; hasEffectiveFilters gates the
  // empty-state "Reset filters" affordance.
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
  // Radial charts get a ring-shaped loading skeleton; everything else bars.
  const RADIAL_KINDS = new Set(["pie", "donut", "sunburst"]);
  let loadingVariant: "chart" | "radial" = $derived(
    RADIAL_KINDS.has(tile.chartType ?? "") ? "radial" : "chart",
  );

  // Issue #1053: single-measure kinds (pie/donut/treemap/sunburst) with >1
  // measure render as small multiples — 2 per row (see gridCells). The canvas
  // grows to N rows and the tile scrolls, so each chart stays full-size rather
  // than shrinking as more measures are added.
  let smallMultipleRows = $derived.by(() => {
    const kind = tile.chartType ?? "bar";
    const measureCount = response?.metadata?.columns?.length ?? 0;
    return isSingleMeasureKind(kind) && measureCount > 1 ? smallMultipleRowCount(measureCount) : 1;
  });

  /* ----------------------------- lifecycle --------------------------- */

  // Lazy-init the ECharts instance the first time `host` is bound. The
  // {#if !tile.query} branch above means the .canvas div doesn't exist
  // on the very first render when tile.query is still null — onMount
  // would have missed the binding entirely. An $effect keyed on host
  // re-runs as soon as bind:this attaches, regardless of which template
  // branch was active at component mount.
  $effect(() => {
    if (!host || chart) return;
    const instance = echarts.init(host);
    instance.on("click", handleEChartsClick);
    instance.on("contextmenu", handleEChartsContextMenu);
    // Resize + bump resizeTick so the render effect recomputes the
    // aspect-aware small-multiple radius for the new canvas size (#1053).
    resizeObserver = new ResizeObserver(() => {
      instance.resize();
      resizeTick++;
    });
    resizeObserver.observe(host);
    chart = instance;
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
    if (tile.cube) resolvedCube = tile.cube;
    // Row axes still need inferring for reference tiles even when the
    // analyst picked the cube explicitly — click-to-filter needs the
    // saved ThinQuery's axes regardless. Skip only when the tile is
    // inline (handler reads tile.query.body.rows directly).
    if (tile.query?.kind !== "reference" || inferenceAttempted) return;
    inferenceAttempted = true;
    const refPath = tile.query.path;
    if (!tile.cube) {
      void inferCubeFromReference(refPath).then((inferred) => {
        if (inferred) resolvedCube = inferred;
      });
    }
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
    // #941 share viewer: render the prefetched guest response; never fetch live
    // (a share guest has no session / no /ai/query access).
    if (sharedResponse) {
      response = sharedResponse;
      loading = false;
      error = null;
      return;
    }
    const tileQuery = tile.query;
    const active = activeFilters.all;
    const s = schema;
    void s;
    // #933 — retry() bumps this to force a refetch with unchanged inputs.
    void retryTick;
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
    // Re-run when the canvas resizes so the small-multiple radius tracks the
    // current aspect ratio (#1053).
    void resizeTick;
    void smallMultipleRows;
    // Re-theme when the effective theme flips (light/dark/system) so chart
    // text/axes/palette repaint — resolveThemeTokens() reads the now-current
    // :root tokens (#1050).
    void theme.effective;
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
    const aspect = host && host.clientHeight > 0 ? host.clientWidth / host.clientHeight : 1;
    const option = buildChartOption(r, kind, aspect, resolveThemeTokens());
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

  /* --------------------------- drillthrough (#930) -------------------- */

  let drill = $state<TileDrillthrough | null>(null);

  // Right-click a data point → drill into the raw fact rows behind that
  // cell. The ECharts contextmenu event carries `dataIndex` (category =
  // ROW index) and `seriesIndex` (series = MEASURE/COLUMN index); the
  // verified backend convention is "{col}:{row}" so column index comes
  // first. Background clicks (no dataIndex) are a no-op. No-op too while
  // the tile has no successful response/queryId yet.
  function handleEChartsContextMenu(params: echarts.ECElementEvent): void {
    const queryId = response?.queryId;
    if (!queryId || response?.status !== "SUCCESS") return;
    const rowIndex = params.dataIndex;
    // Pie series omit seriesIndex on the element event in some echarts
    // builds; a pie has a single series so default to 0.
    const colIndex = params.seriesIndex ?? 0;
    if (typeof rowIndex !== "number") return;
    const position = drillthroughPosition(colIndex, rowIndex);
    if (!position) return;
    // Only suppress the browser's native context menu once we know we're
    // handling the drill (background clicks keep the native menu).
    const ev = params.event?.event as MouseEvent | undefined;
    ev?.preventDefault();
    void drill?.open(queryId, position);
  }
</script>

{#if !tile.query}
  <div class="placeholder">Tile has no query binding — open ⚙ to set one.</div>
{:else}
  <div class="chart-tile">
    <div class="canvas" bind:this={host} style="height: {smallMultipleRows * 100}%"></div>
    {#if loading && !response}
      <div class="overlay solid"><TileLoading variant={loadingVariant} /></div>
    {:else if error}
      <div class="overlay solid"><TileError message={error} onRetry={retry} /></div>
    {:else if unsupported}
      <div class="overlay">
        Chart type <code>{tile.chartType}</code> not yet supported in dashboards.
      </div>
    {:else if isEmpty}
      <div class="overlay solid">
        <TileEmpty filtered={hasEffectiveFilters} onReset={resetFilters} />
      </div>
    {/if}
  </div>
{/if}

<TileDrillthrough bind:this={drill} cube={resolvedCube} />

<style>
  .chart-tile {
    position: relative;
    height: 100%;
    width: 100%;
    /* #1053: small multiples grow the canvas to N rows; scroll within the tile
       so each chart stays full-size instead of shrinking. */
    overflow-y: auto;
    overflow-x: hidden;
  }
  .canvas {
    /* height is set inline = smallMultipleRows * 100% (100% for a single chart). */
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
  /* Opaque, interactive overlay for the loading skeleton / error / empty
     states (which carry Retry / Reset buttons). #933 */
  .overlay.solid {
    background: var(--bg);
    pointer-events: auto;
    padding: 0;
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
