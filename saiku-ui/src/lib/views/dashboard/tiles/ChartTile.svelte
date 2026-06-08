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
    searchMembers,
    detectAnomalies,
    forecastQuery,
    type AiQueryResponse,
    type ForecastPoint,
  } from "$lib/api/aiQuery";
  // #1231 — shared fetch state + effect body across ChartTile / TableTile.
  import {
    TileQueryState,
    runTileQueryEffect,
  } from "$lib/hooks/useTileQuery.svelte";
  // #1166: resolve a clicked category caption to a real member unique name.
  import { pickMemberUniqueName } from "$lib/dashboard/clickFilterMember";
  // Issue #907 — server-side anomaly detection overlay (markPoints).
  import {
    anomalyMarksBySeries,
    hasAnyAnomaly,
    type AnomalyMark,
  } from "$lib/dashboard/anomalyMarkPoints";
  // Issue #908 — forecast horizon overlay (dashed line + confidence band).
  import { applyForecastOverlay } from "$lib/dashboard/forecastOverlay";
  import { escapeHtml } from "$lib/charts/sparkline";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import { type SchemaLike } from "$lib/dashboard/effectiveQuery";
  import {
    inferCubeFromReference,
    inferRowAxesFromReference,
    type RowAxisRef,
  } from "$lib/dashboard/filterSuggestions";
  import {
    buildChartOption,
    isSupportedChartKind,
    projectFromAiQueryResponse,
  } from "$lib/dashboard/chartOptions";
  // #1090: accessible data-table mirror of the chart for screen readers.
  import { chartSummary } from "$lib/charts/a11y";
  // issue #1071: map tiles need the GeoJSON registered with ECharts before
  // setOption — lazy-loaded here (the builder/adapter stay pure).
  import { ensureGeoMap, isGeoMapRegistered } from "$lib/charts/geoMaps";
  // #1092: preserve the dataZoom window (+ brush) across re-renders (tile resize
  // / theme flip / same-shape data refresh). Pure decision in the helper.
  import { reconcileZoomState } from "$lib/charts/zoomState";
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
  // Issue #931 — per-tile auto-refresh: timer wiring + "last updated" indicator.
  import { isAutoRefreshOn } from "$lib/dashboard/autoRefresh";
  import TileRefreshIndicator from "./TileRefreshIndicator.svelte";

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

  // #1231 — fetch lifecycle (loading / error / response / dedupe cache /
  // retry tick / refresh tick / auto-refresh) lives on TileQueryState so
  // ChartTile + TableTile share the same plumbing. KpiTile builds its
  // body inline and does NOT use this hook. The `let foo = $derived(q.foo)`
  // aliases below keep the rest of the script + template reading off
  // local names; assignments only happen inside runTileQueryEffect.
  const q = new TileQueryState();
  let response = $derived(q.response);
  let loading = $derived(q.loading);
  let error = $derived(q.error);
  const auto = q.auto;
  function retry(): void {
    q.retry();
  }
  let schema = $state<SchemaLike | null>(null);
  let unsupported = $state(false);

  /* --- issue #907: anomaly detection ------------------------------------
   * When the tile opts in (tile.anomaly.enabled) AND the chart kind is a
   * time-series type (line / bar / area), the fetch routes through
   * POST /ai/anomaly instead of /ai/query (via the inlineFetch override on
   * the shared #1231 hook). The augmented response (cells carry an `anomaly`
   * verdict) drives a markPoint overlay in the render effect. Anomaly is only
   * valid for inline tiles — reference tiles run a saved ThinQuery through a
   * different endpoint, so we leave those on the plain path. */
  const ANOMALY_KINDS = new Set(["line", "bar", "area"]);
  let anomalyEnabled = $derived(
    !!tile.anomaly?.enabled
      && ANOMALY_KINDS.has(tile.chartType ?? "")
      && tile.query?.kind === "inline"
      && !sharedResponse,
  );

  /* --- issue #908: forecast -----------------------------------------------
   * Same gating as anomaly (time-series, inline, live). When on, the fetch
   * routes through POST /ai/forecast; the returned forecast block drives a
   * dashed continuation + confidence band in the render effect. If BOTH
   * forecast and anomaly are enabled, forecast wins the single inline fetch
   * (one endpoint per pass) — documented limitation. */
  let forecastEnabled = $derived(
    !!tile.forecast?.enabled
      && ANOMALY_KINDS.has(tile.chartType ?? "")
      && tile.query?.kind === "inline"
      && !sharedResponse,
  );
  // Forecast block (measure caption → horizon points), set by the inline fetch.
  let forecastSeries = $state<Record<string, ForecastPoint[]> | null>(null);
  // Fallback overlay colour when a series carries no explicit colour.
  const FORECAST_FALLBACK_COLOR = "#7c7c7c";
  // issue #1071: bumped once the world GeoJSON finishes registering, to
  // re-run the render effect and paint the map (registration is async).
  let mapReadyTick = $state(0);
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

  /* --- issue #931: auto-refresh ------------------------------------------
   * A per-tile timer re-fires the existing (filter-aware) fetch on the
   * configured cadence. q.triggerAutoRefresh clears the dedupe cache +
   * bumps q.refreshTick the fetch effect reads — same mechanism as
   * q.retry() — so the re-run picks up the CURRENT effective query
   * (active filters included). Never auto-refreshes in the share viewer
   * (no live /ai/query access). */
  let autoRefreshOn = $derived(!sharedResponse && isAutoRefreshOn(tile.refreshInterval));
  // Arm / re-arm whenever the interval changes (config edit) or the tile
  // re-renders — the returned teardown clears the prior timer so they never
  // stack ("reset on re-render / config change").
  $effect(() => auto.arm(tile.refreshInterval, () => q.triggerAutoRefresh()));
  // Heartbeat keeps the "X ago" label fresh; only while the indicator shows.
  $effect(() => {
    if (autoRefreshOn && auto.lastUpdated > 0) return auto.startHeartbeat();
  });
  /* --- end issue #931 block ---------------------------------------------- */
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

  // #1090: accessible data-table mirror for screen readers (the canvas is
  // aria-hidden). Built from the same projection the chart builder uses, so it
  // always matches what's drawn. Null until a successful response.
  let a11y = $derived.by(() => {
    const r = response;
    if (!r || r.status !== "SUCCESS") return null;
    return chartSummary(tile.chartType ?? "bar", tile.title ?? "", projectFromAiQueryResponse(r));
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

  $effect(() => {
    // Reactive deps the effect body reads internally — read here so the
    // effect framework registers them on the COMPONENT's scope.
    void q.retryTick;
    void q.refreshTick;
    runTileQueryEffect(q, {
      tile,
      activeFilters: activeFilters.all,
      schema,
      sharedResponse: sharedResponse ?? null,
      // #907 anomaly + #908 forecast each route the inline fetch through their
      // own endpoint; forecast wins if both are on (one endpoint per pass). The
      // configs fold into the dedupe key so toggling forces a re-fetch.
      dedupeExtra:
        forecastEnabled || anomalyEnabled
          ? JSON.stringify({
              f: forecastEnabled ? tile.forecast : null,
              a: anomalyEnabled ? tile.anomaly : null,
            })
          : null,
      inlineFetch: forecastEnabled
        ? async (effective) => {
            // #908: project the horizon via /ai/forecast; stash the block for render.
            const timeAxis = resolveTimeAxis(effective, tile.forecast?.timeAxis);
            const out = await forecastQuery(effective, {
              method: tile.forecast?.method ?? "ets",
              horizon: tile.forecast?.horizon,
              confidence: tile.forecast?.confidence,
              timeAxis,
            });
            forecastSeries = out.forecast?.series ?? null;
            return out.response;
          }
        : anomalyEnabled
          ? async (effective) => {
              // #907: route through /ai/anomaly (per-cell verdicts on the response).
              const timeAxis = resolveTimeAxis(effective, tile.anomaly?.timeAxis);
              const out = await detectAnomalies(effective, {
                method: tile.anomaly?.method ?? "zscore",
                threshold: tile.anomaly?.threshold,
                timeAxis,
              });
              return out.response;
            }
          : undefined,
    });
  });

  /* #907: pick the time-axis unique name for the anomaly request. Prefers an
   * explicit config value; otherwise derives it from the first row axis of the
   * effective query body ("[Dim].[Hier].[Level]"). Falls back to a best-effort
   * dimension name when the level shape is incomplete. */
  function resolveTimeAxis(effective: Record<string, unknown>, configured?: string): string {
    if (configured && configured.trim()) return configured.trim();
    const rows = (effective as { rows?: Array<Record<string, unknown>> }).rows;
    const first = rows && rows.length > 0 ? rows[0] : undefined;
    if (first) {
      const lvl = typeof first.level === "string" ? first.level : "";
      if (lvl) return lvl;
      const dim = typeof first.dimension === "string" ? first.dimension : "";
      if (dim) return dim;
    }
    return "";
  }

  /* ----------------------------- render effect ------------------------ */

  $effect(() => {
    const r = response;
    const kind = tile.chartType ?? "bar";
    void r;
    // Re-render when the per-tile chart options change (editor save) (#1077).
    void tile.chartOptions;
    // Re-run when the canvas resizes so the small-multiple radius tracks the
    // current aspect ratio (#1053).
    void resizeTick;
    void smallMultipleRows;
    // Re-theme when the effective theme flips (light/dark/system) so chart
    // text/axes/palette repaint — resolveThemeTokens() reads the now-current
    // :root tokens (#1050).
    void theme.effective;
    // #1091: repaint when the colour-blind-safe pref flips.
    void theme.colorBlindSafe;
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
    // issue #1071: defer the first map render until its GeoJSON is registered,
    // then bump mapReadyTick to re-run this effect. Cheap sync check after.
    void mapReadyTick;
    if (kind === "map" && !isGeoMapRegistered("world")) {
      void ensureGeoMap("world")
        .then(() => (mapReadyTick += 1))
        .catch((err) => console.warn("[saiku] failed to load world map:", err));
      return;
    }
    const aspect = host && host.clientHeight > 0 ? host.clientWidth / host.clientHeight : 1;
    // #1077: per-tile chart options (title/axes/legend/dualAxis/trend). Undefined
    // on legacy tiles → the adapter falls back to the dashboard baseline.
    const option = buildChartOption(r, kind, aspect, resolveThemeTokens(), tile.chartOptions);
    if (option) {
      // #907: layer anomaly markPoints on top of the freshly-built option,
      // leaving the existing render path untouched when detection is off / no
      // anomalies were found.
      if (anomalyEnabled) overlayAnomalyMarkPoints(option, r);
      // #908: append the forecast horizon (dashed line + confidence band) when
      // forecasting is on and the server returned a forecast block.
      if (forecastEnabled && forecastSeries) {
        applyForecastOverlay(option, forecastSeries, FORECAST_FALLBACK_COLOR);
      }
      // #1092: capture the live zoom/brush before overwriting; only re-applied
      // when the chart type + category count are unchanged (resize / theme flip
      // / same-shape refresh), so a new query or chart-type switch starts fresh.
      const preserved = reconcileZoomState(
        chart.getOption() as Record<string, unknown>,
        option,
        kind,
      );
      // notMerge=true so axis category changes don't leave stale ticks.
      chart.setOption(option, true);
      // Overlay the saved window/selection (merge so the fresh render is kept).
      if (preserved) chart.setOption(preserved, { notMerge: false });
    } else {
      chart.clear();
    }
  });

  /* --------------------------- anomaly overlay (#907) ----------------- */

  /** Mutate the built ECharts option, attaching a markPoint set to each series
   *  that has anomalies. The marker uses the cell's plotted value at the
   *  anomalous category; the tooltip reports the score + expected value with an
   *  HTML-escaped series/category name (XSS-safe — names are data-derived). */
  function overlayAnomalyMarkPoints(option: Record<string, unknown>, r: AiQueryResponse): void {
    const marks = anomalyMarksBySeries(r);
    if (!hasAnyAnomaly(marks)) return;
    const series = option.series;
    if (!Array.isArray(series)) return;
    for (const [si, list] of marks) {
      const s = series[si] as Record<string, unknown> | undefined;
      if (!s || typeof s !== "object") continue;
      s.markPoint = {
        symbol: "pin",
        symbolSize: 38,
        itemStyle: { color: ANOMALY_MARK_COLOR },
        label: { show: false },
        tooltip: {
          formatter: (p: { data?: { anomalyTip?: string } }) => p?.data?.anomalyTip ?? "",
        },
        data: list.map((m) => ({
          // ECharts coord: [categoryValue, numericValue]. Category value matches
          // the x-axis label so it lands on the right point regardless of order.
          coord: [m.category, m.value],
          value: "",
          anomalyTip: anomalyTooltip(m),
        })),
      };
    }
  }

  /** Build the escaped HTML tooltip for one anomaly mark. Data-derived names
   *  (series caption + category) are run through escapeHtml (the established
   *  #1071/#1087 pattern reused from $lib/charts). */
  function anomalyTooltip(m: AnomalyMark): string {
    const sigma = m.anomaly.score.toFixed(2);
    const dirWord =
      m.anomaly.direction === "above"
        ? i18n.t("dashboard.anomaly.above", "above")
        : m.anomaly.direction === "below"
          ? i18n.t("dashboard.anomaly.below", "below")
          : "";
    const expectedNum = Number.isFinite(m.anomaly.expected)
      ? m.anomaly.expected.toLocaleString(undefined, { maximumFractionDigits: 2 })
      : "—";
    const title = i18n.t("dashboard.anomaly.tooltipTitle", "Anomaly detected");
    const scoreLabel = i18n.t("dashboard.anomaly.score", "Score");
    const expectedLabel = i18n.t("dashboard.anomaly.expected", "Expected");
    const sigmaSuffix = i18n.t("dashboard.anomaly.sigmaExpectation", "σ from expectation");
    // "Nσ above expectation" — direction word only when present.
    const headline = dirWord ? `${sigma}σ ${escapeHtml(dirWord)} ${escapeHtml(sigmaSuffix)}`
      : `${sigma} ${escapeHtml(sigmaSuffix)}`;
    return (
      `<div style="font-weight:600">${escapeHtml(title)}</div>`
      + `<div>${escapeHtml(m.series)} — ${escapeHtml(m.category)}: ${escapeHtml(m.formatted)}</div>`
      + `<div>${headline}</div>`
      + `<div>${escapeHtml(scoreLabel)}: ${sigma} · ${escapeHtml(expectedLabel)}: ${escapeHtml(expectedNum)}</div>`
    );
  }

  // Anomaly marker colour — a warning red that stays legible on the chart
  // palette and in both themes.
  const ANOMALY_MARK_COLOR = "#e2483d";

  /* ----------------------------- click capture ------------------------ */

  // #1166: per-(cube/dim/hier/level) cache of the level's members so a
  // repeated click on the same chart doesn't re-hit /ai/members/search.
  const clickMemberCache = new Map<string, Promise<{ uniqueName: string; caption: string }[]>>();

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
    // is the slice name. In both cases that's the clicked member's caption.
    const name = typeof params.name === "string" ? params.name : null;
    if (!name) return;

    const cube = resolvedCube;
    if (!cube) return;

    // #1166: the click gives us a caption ("Beer") but the filter contract
    // needs a real MDX unique name. Hand-building "[dim].[hier].[caption]"
    // is wrong — it omits ancestor segments and may use display aliases the
    // cube doesn't know — so ask the server for the level's members and pick
    // the one whose caption matches. Its uniqueName is the server's own, so
    // it round-trips back into /ai/query. If we can't resolve it, no-op
    // rather than push a member every consuming tile would choke on.
    const { dimension, hierarchy, level } = rowAxis;
    const cacheKey = `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}|${dimension}/${hierarchy}/${level}|${name}`;
    let lookup = clickMemberCache.get(cacheKey);
    if (!lookup) {
      lookup = searchMembers(cube, dimension, hierarchy, level, name);
      clickMemberCache.set(cacheKey, lookup);
    }
    void lookup.then((hits) => {
      const uniqueName = pickMemberUniqueName(hits, name);
      if (!uniqueName) {
        clickMemberCache.delete(cacheKey); // don't cache a miss — allow a retry
        console.warn(
          `[saiku] click-to-filter: no member matched caption "${name}" in ${dimension}/${level}`,
        );
        return;
      }
      const filter: DashboardFilter = { dimension, hierarchy, level, members: [uniqueName] };
      onClickFilter(filter);
    });
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
    <!-- #1090: the canvas is decorative to assistive tech; the sr-only table is
         the accessible representation, so hide the canvas from screen readers. -->
    <div class="canvas" bind:this={host} aria-hidden="true" style="height: {smallMultipleRows * 100}%"></div>
    {#if a11y}
      <!-- #1090: visually-hidden data-table mirror for screen readers. -->
      <table class="sr-only">
        <caption>{a11y.caption}</caption>
        {#if !a11y.empty}
          <thead>
            <tr>
              {#each a11y.headers as h, i (i)}
                <th scope="col">{h}</th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each a11y.rows as row, ri (ri)}
              <tr>
                <th scope="row">{row[0]}</th>
                {#each row.slice(1) as cell, ci (ci)}
                  <td>{cell}</td>
                {/each}
              </tr>
            {/each}
          </tbody>
        {/if}
      </table>
    {/if}
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
    {#if autoRefreshOn && auto.lastUpdated > 0}
      <!-- #931: auto-refresh "last updated" badge + spinning icon. -->
      <div class="refresh-badge">
        <TileRefreshIndicator lastUpdated={auto.lastUpdated} spinning={loading} now={auto.now} />
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
  /* #1090: visually hide the a11y data table while keeping it in the
     accessibility tree (standard sr-only / visually-hidden pattern). */
  .sr-only {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
    border: 0;
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
  /* #931: auto-refresh badge — top-right, above the canvas, click-through.
     Top (not bottom) so it clears the ECharts legend + x-axis labels that
     live along the chart's bottom edge. */
  .refresh-badge {
    position: absolute;
    right: 0.25rem;
    top: 0.25rem;
    z-index: 2;
    background: color-mix(in srgb, var(--bg) 80%, transparent);
    border-radius: 4px;
    padding: 0.0625rem 0.25rem;
    pointer-events: none;
    max-width: calc(100% - 0.5rem);
  }
  code {
    background: var(--bg-subtle);
    padding: 0.0625em 0.25em;
    border-radius: 3px;
    font-size: 0.85em;
  }
</style>
