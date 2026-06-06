<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { QueryResult } from "$lib/api/query";
  import { deriveLeafRows, parseCellset, toNumber } from "$lib/views/cellsetUtils";
  import type { ChartType, ChartOptions } from "$lib/views/chartTypes";
  import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";
  import { isSingleMeasureKind, smallMultipleRowCount } from "$lib/dashboard/smallMultiples";
  import { theme } from "$lib/stores/theme.svelte";
  import { resolveThemeTokens } from "$lib/views/chartTheme";
  import { buildChartOption } from "$lib/charts/build";

  interface Props {
    result: QueryResult;
    type: ChartType;
    options?: ChartOptions;
  }

  let { result, type, options = DEFAULT_CHART_OPTIONS }: Props = $props();

  let host: HTMLDivElement | null = null;
  let chart: echarts.ECharts | null = null;

  // #1053: single-measure kinds (pie/donut/treemap/sunburst) with >1 measure
  // render as small multiples — 2 per row. Grow the host to N rows so each
  // chart stays full-size; the surrounding wrapper scrolls.
  let smallMultipleRows = $derived.by(() => {
    const measureCount = parseCellset(result).columnCategories.length;
    return isSingleMeasureKind(type) && measureCount > 1 ? smallMultipleRowCount(measureCount) : 1;
  });

  // Project the workspace cellset into the shared {rows, cols, matrix} shape and
  // delegate to the single canonical builder (#1076). The workspace is the
  // "roomy" (non-compact) surface; rollup filtering (which needs cellset depth)
  // happens here, before projection, since the builder treats rows as final.
  function buildOption(r: QueryResult, t: ChartType, o: ChartOptions): Record<string, unknown> | null {
    const tk = resolveThemeTokens();
    const parsed = parseCellset(r);
    // Multi-level row hierarchies (Year > Quarter, Country > City, …) come back
    // with both rollup and leaf rows in the same cellset. Showing the rollups on
    // a chart dwarfs the leaves; deriveLeafRows drops them and promotes each
    // leaf's parent context into the label (e.g. "2024 / Q1"). The grid view is
    // untouched.
    let rows = parsed.rowCategories;
    let matrix: (number | null)[][] = parsed.dataRows.map((row) => row.map(toNumber));
    if (o.hideRollupRows) {
      // deriveLeafRows is a no-op for single-level rowsets (all rows at the same
      // depth) and for empty results, so no extra guard is needed here.
      const leaf = deriveLeafRows(parsed);
      if (leaf.indices.length > 0 && leaf.indices.length < matrix.length) {
        rows = leaf.labels;
        matrix = leaf.indices.map((i) => matrix[i]);
      }
    }
    const cols = parsed.columnCategories;

    // Aspect-aware radius keeps each small-multiple the same on-screen size
    // regardless of how many there are (#1053); chartWidth drives the derived
    // per-label axis truncation width.
    const aspect = host && host.clientHeight > 0 ? host.clientWidth / host.clientHeight : 1;
    const chartWidth = host?.clientWidth ?? 0;
    return buildChartOption({ rowCategories: rows, columnCategories: cols, matrix }, t, o, tk, {
      aspect,
      chartWidth,
      compact: false,
    });
  }

  function render() {
    if (!chart) return;
    let opt: Record<string, unknown> | null;
    try {
      opt = buildOption(result, type, options);
    } catch (err) {
      // If buildOption throws (e.g. cellset shape doesn't fit the requested
      // chart type), clear the canvas instead of leaving the previous chart's
      // series on screen. Without this, switching from a "broken" chart to a
      // valid one would still render the broken state because the stale series
      // would merge with the new option.
      console.warn("[saiku] chart buildOption failed; clearing canvas:", err);
      chart.clear();
      return;
    }
    if (!opt) {
      // Unsupported kind / empty projection — clear rather than keep stale series.
      chart.clear();
      return;
    }
    // Include "series" in replaceMerge — switching chart types must drop the
    // previous series wholesale, otherwise a stacked-bar's series would merge
    // with the next radar's etc., producing the "stale chart" symptom.
    chart.setOption(opt, {
      notMerge: false,
      replaceMerge: ["xAxis", "yAxis", "legend", "tooltip", "title", "visualMap", "radar", "series"],
    });
  }

  onMount(() => {
    if (host) {
      chart = echarts.init(host, null);
      render();
      // Re-render (not just resize) so the aspect-aware small-multiple radius
      // recomputes for the new canvas size (#1053).
      const ro = new ResizeObserver(() => {
        chart?.resize();
        render();
      });
      ro.observe(host);
      return () => ro.disconnect();
    }
  });

  $effect(() => {
    // Track dependencies explicitly so any field on `options` triggers re-render.
    void result;
    void type;
    void options.title;
    void options.xAxisLabel;
    void options.yAxisLabel;
    void options.showLegend;
    void options.legendPosition;
    void options.trendLine;
    void options.trendPeriod;
    void options.hideRollupRows;
    void options.dualAxis;
    void options.seriesAxis;
    // Re-theme when the effective theme flips.
    void theme.effective;
    if (chart) render();
  });

  onDestroy(() => {
    chart?.dispose();
    chart = null;
  });
</script>

<div class="chart-scroll">
  <div
    class="chart"
    bind:this={host}
    style="height: {smallMultipleRows * 60}vh; min-height: {smallMultipleRows * 320}px;"
  ></div>
</div>

<style>
  /* #1053: the frame stays one viewport tall; small multiples grow the inner
     chart to N rows and this wrapper scrolls, keeping each chart full-size. */
  .chart-scroll {
    width: 100%;
    height: 60vh;
    min-height: 320px;
    overflow-y: auto;
    overflow-x: hidden;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
  .chart {
    width: 100%;
    /* height + min-height are set inline = smallMultipleRows × the single size. */
  }
</style>
