<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { QueryResult } from "$lib/api/query";
  import { assignSeriesAxes, deriveLeafRows, parseCellset, toNumber } from "$lib/views/cellsetUtils";
  import type { ChartType, ChartOptions } from "$lib/views/chartTypes";
  import { DEFAULT_CHART_OPTIONS, SERIES_AXIS_THRESHOLD } from "$lib/views/chartTypes";
  import { axisLabelConfig, deriveAxisLabelWidth } from "$lib/views/chartAxisLabel";
  import { gridCells } from "$lib/dashboard/smallMultiples";
  import { theme } from "$lib/stores/theme.svelte";

  interface Props {
    result: QueryResult;
    type: ChartType;
    options?: ChartOptions;
  }

  let { result, type, options = DEFAULT_CHART_OPTIONS }: Props = $props();

  let host: HTMLDivElement | null = null;
  let chart: echarts.ECharts | null = null;

  interface ThemeTokens {
    fg: string;
    fgMuted: string;
    bg: string;
    bgMuted: string;
    border: string;
    accent: string;
    /** Categorical series palette read from --chart-1..8 tokens.
     *  Falls back to Indigo-anchored defaults harmonised with --accent
     *  if the tokens are absent for any reason. */
    chartColors: string[];
  }

  const CHART_FALLBACK_COLORS = [
    "#4f46e5", "#0ea5e9", "#10b981", "#f59e0b",
    "#ef4444", "#a855f7", "#ec4899", "#14b8a6",
  ];

  function resolveThemeTokens(): ThemeTokens {
    const cs = getComputedStyle(document.documentElement);
    const get = (name: string, fallback: string) =>
      cs.getPropertyValue(name).trim() || fallback;
    const chartColors = CHART_FALLBACK_COLORS.map((fallback, i) =>
      get(`--chart-${i + 1}`, fallback),
    );
    return {
      fg: get("--fg", "#0f172a"),
      fgMuted: get("--fg-muted", "#475569"),
      bg: get("--bg", "#ffffff"),
      bgMuted: get("--bg-muted", "#f6f7f9"),
      border: get("--border", "#e2e8f0"),
      accent: get("--accent", "#4f46e5"),
      chartColors,
    };
  }

  function legendConfig(o: ChartOptions, tk: ThemeTokens) {
    if (!o.showLegend) return { show: false };
    const position: Record<string, unknown> = { textStyle: { color: tk.fg } };
    if (o.legendPosition === "top") position.top = 10;
    else if (o.legendPosition === "bottom") position.bottom = 10;
    else if (o.legendPosition === "left") { position.left = 10; position.top = "middle"; position.orient = "vertical"; }
    else if (o.legendPosition === "right") { position.right = 10; position.top = "middle"; position.orient = "vertical"; }
    return position;
  }

  function titleConfig(o: ChartOptions, tk: ThemeTokens) {
    if (!o.title) return undefined;
    return { text: o.title, left: "center", textStyle: { color: tk.fg } };
  }

  function linearRegression(vals: (number | null)[]): number[] {
    const n = vals.length;
    let sumX = 0, sumY = 0, sumXY = 0, sumXX = 0, count = 0;
    for (let i = 0; i < n; i++) {
      const y = vals[i];
      if (y == null || !Number.isFinite(y)) continue;
      sumX += i;
      sumY += y;
      sumXY += i * y;
      sumXX += i * i;
      count += 1;
    }
    if (count < 2) return vals.map(() => 0);
    const meanX = sumX / count;
    const meanY = sumY / count;
    const denom = sumXX - sumX * meanX;
    const slope = denom === 0 ? 0 : (sumXY - sumX * meanY) / denom;
    const intercept = meanY - slope * meanX;
    return vals.map((_, i) => slope * i + intercept);
  }

  function movingAverage(vals: (number | null)[], period: number): (number | null)[] {
    const out: (number | null)[] = [];
    for (let i = 0; i < vals.length; i++) {
      if (i < period - 1) { out.push(null); continue; }
      let sum = 0; let c = 0;
      for (let k = i - period + 1; k <= i; k++) {
        const v = vals[k];
        if (v != null && Number.isFinite(v)) { sum += v; c += 1; }
      }
      out.push(c ? sum / c : null);
    }
    return out;
  }

  function weightedMovingAverage(vals: (number | null)[], period: number): (number | null)[] {
    const out: (number | null)[] = [];
    for (let i = 0; i < vals.length; i++) {
      if (i < period - 1) { out.push(null); continue; }
      let num = 0, den = 0;
      for (let k = 0; k < period; k++) {
        const v = vals[i - period + 1 + k];
        if (v != null && Number.isFinite(v)) { num += (k + 1) * v; den += k + 1; }
      }
      out.push(den ? num / den : null);
    }
    return out;
  }

  function trendSeries(name: string, vals: (number | null)[], o: ChartOptions, tk: ThemeTokens) {
    if (o.trendLine === "none") return [];
    const period = Math.max(2, o.trendPeriod || 3);
    let data: (number | null)[] = [];
    let seriesName = name;
    if (o.trendLine === "linear") {
      data = linearRegression(vals);
      seriesName = `${name} (trend)`;
    } else if (o.trendLine === "ma") {
      data = movingAverage(vals, period);
      seriesName = `${name} (MA${period})`;
    } else if (o.trendLine === "wma") {
      data = weightedMovingAverage(vals, period);
      seriesName = `${name} (WMA${period})`;
    }
    return [{
      type: "line" as const,
      name: seriesName,
      data,
      smooth: true,
      showSymbol: false,
      lineStyle: { type: "dashed" as const, width: 2, color: tk.fgMuted },
      itemStyle: { color: tk.fgMuted },
    }];
  }

  function buildOption(r: QueryResult, t: ChartType, o: ChartOptions): echarts.EChartsOption {
    const tk = resolveThemeTokens();
    const parsed = parseCellset(r);
    // Multi-level row hierarchies (Year > Quarter, Country > City, …) come
    // back with both rollup and leaf rows in the same cellset. Showing the
    // rollups on a chart dwarfs the leaves; deriveLeafRows drops them and
    // promotes each leaf's parent context into the label (e.g. "2024 / Q1").
    // The grid view is untouched.
    let rows = parsed.rowCategories;
    let matrix: (number | null)[][] = parsed.dataRows.map((row) => row.map(toNumber));
    if (o.hideRollupRows) {
      // deriveLeafRows is a no-op for single-level rowsets (all rows at the
      // same depth) and for empty results, so no extra guard is needed here.
      const leaf = deriveLeafRows(parsed);
      if (leaf.indices.length > 0 && leaf.indices.length < matrix.length) {
        rows = leaf.labels;
        matrix = leaf.indices.map((i) => matrix[i]);
      }
    }
    const cols = parsed.columnCategories;

    const axisLabel = { color: tk.fgMuted };
    const axisLine = { lineStyle: { color: tk.border } };
    const axisTick = { lineStyle: { color: tk.border } };
    const splitLine = { lineStyle: { color: tk.border, opacity: 0.5 } };
    const nameTextStyle = { color: tk.fg };

    // Truncate long category labels (e.g. deep member captions) so they don't
    // overflow the plot and break the layout. ECharts shows the full,
    // untruncated label in the axis-pointer tooltip on hover. Width is derived
    // from the available chart width divided by the number of categories.
    const chartWidth = host?.clientWidth ?? 0;
    const catCount = Math.max(rows.length, cols.length, 1);
    const truncLabel = {
      color: tk.fgMuted,
      ...axisLabelConfig(deriveAxisLabelWidth(chartWidth, catCount)),
    };

    const baseAxis = {
      type: "category" as const,
      axisLabel, axisLine, axisTick, splitLine, nameTextStyle,
    };
    // Bottom category axis that truncates long labels with an ellipsis.
    const categoryAxis = {
      ...baseAxis,
      axisLabel: truncLabel,
    };
    const valueAxis = {
      type: "value" as const,
      axisLabel, axisLine, axisTick, splitLine, nameTextStyle,
    };
    const legend = legendConfig(o, tk);
    const title = titleConfig(o, tk);
    const tooltip = {
      trigger: "axis" as const,
      backgroundColor: tk.bg,
      borderColor: tk.border,
      textStyle: { color: tk.fg },
    };
    const itemTooltip = {
      backgroundColor: tk.bg,
      borderColor: tk.border,
      textStyle: { color: tk.fg },
    };
    const xName = o.xAxisLabel || undefined;
    const yName = o.yAxisLabel || undefined;

    const common = {
      backgroundColor: "transparent",
      color: tk.chartColors,
      textStyle: { color: tk.fg },
    };

    // Pie / donut / treemap / sunburst encode a SINGLE measure. With M measures
    // we fan out into M small-multiple charts — one per measure — in a grid
    // inside the same option (M series, one per cell). Each chart shows the row
    // categories as its items, sized by that one measure. M=1 is a single
    // full-box chart. The user's overall chart title (if any) is kept alongside
    // the per-measure cell titles in ECharts' title array.
    if (t === "pie" || t === "donut" || t === "treemap" || t === "sunburst") {
      const cells = gridCells(cols.length);
      const cellTitles = cells.map((cell, m) => ({
        text: cols[m],
        left: cell.centerXPct + "%",
        top: cell.topPct + "%",
        textAlign: "center" as const,
        textStyle: { color: tk.fg, fontSize: 12 },
      }));
      const titles = title ? [title, ...cellTitles] : cellTitles;
      // Per-slice labels + leader lines overlap into noise with many categories
      // or in a small-multiple grid; show them only on a single chart with a
      // readable slice count. Tooltip carries the detail otherwise.
      const showSliceLabels = cells.length === 1 && rows.length <= 10;

      if (t === "pie" || t === "donut") {
        return {
          ...common,
          title: titles,
          legend: rows.length <= 20 ? legend : undefined,
          tooltip: { trigger: "item", ...itemTooltip },
          series: cells.map((cell, m) => ({
            type: "pie",
            name: cols[m],
            radius:
              t === "donut"
                ? [cell.widthPct * 0.22 + "%", cell.widthPct * 0.34 + "%"]
                : [0, cell.widthPct * 0.34 + "%"],
            center: [cell.centerXPct + "%", cell.centerYPct + "%"],
            label: { show: showSliceLabels, color: tk.fg },
            labelLine: { show: showSliceLabels },
            data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })),
          })),
        };
      }

      if (t === "treemap") {
        return {
          ...common,
          title: titles,
          tooltip: itemTooltip,
          series: cells.map((cell, m) => ({
            type: "treemap",
            name: cols[m],
            left: cell.leftPct + "%",
            top: cell.topPct + cell.heightPct * 0.18 + "%",
            width: cell.widthPct + "%",
            height: cell.heightPct * 0.82 + "%",
            label: { color: "#fff" },
            breadcrumb: { show: false },
            data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })).filter((d) => d.value > 0),
          })),
        };
      }

      // sunburst
      return {
        ...common,
        title: titles,
        tooltip: itemTooltip,
        series: cells.map((cell, m) => ({
          type: "sunburst",
          name: cols[m],
          center: [cell.centerXPct + "%", cell.centerYPct + "%"],
          radius: [0, cell.widthPct * 0.42 + "%"],
          label: { show: showSliceLabels, color: "#fff" },
          data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })).filter((d) => d.value > 0),
        })),
      };
    }

    if (t === "heatmap") {
      const data: [number, number, number][] = [];
      for (let i = 0; i < matrix.length; i++) {
        for (let j = 0; j < (matrix[i]?.length ?? 0); j++) {
          data.push([j, i, matrix[i][j] ?? 0]);
        }
      }
      const values = data.map((d) => d[2]);
      const min = values.length ? Math.min(...values) : 0;
      const max = values.length ? Math.max(...values) : 1;
      return {
        ...common,
        title,
        tooltip: itemTooltip,
        grid: { left: 120, top: 40, right: 40, bottom: 80 },
        xAxis: { ...categoryAxis, data: cols, name: xName },
        yAxis: { ...baseAxis, data: rows, inverse: true, name: yName },
        visualMap: {
          min, max, calculable: true, orient: "horizontal",
          left: "center", bottom: 10,
          textStyle: { color: tk.fgMuted },
        },
        series: [{ type: "heatmap", data, label: { show: false }, emphasis: { itemStyle: { shadowBlur: 10 } } }],
      };
    }

    if (t === "radar") {
      const max = Math.max(0, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
      return {
        ...common,
        title,
        tooltip: itemTooltip,
        legend,
        radar: {
          indicator: cols.map((c) => ({ name: c, max: max || 1 })),
          axisName: { color: tk.fgMuted },
          axisLine: { lineStyle: { color: tk.border } },
          splitLine: { lineStyle: { color: tk.border, opacity: 0.5 } },
          splitArea: { areaStyle: { color: [tk.bg, tk.bgMuted], opacity: 0.3 } },
        },
        series: [{ type: "radar", data: rows.map((r, i) => ({ name: r, value: matrix[i].map((v) => v ?? 0) })) }],
      };
    }

    if (t === "scatter" || t === "bubble") {
      return {
        ...common,
        title,
        tooltip: itemTooltip,
        legend,
        xAxis: { ...categoryAxis, data: cols, name: xName },
        yAxis: { ...valueAxis, name: yName },
        series: rows.map((name, i) => ({
          type: "scatter",
          name,
          symbolSize: t === "bubble"
            ? (val: unknown) => {
                const v = Array.isArray(val) ? (val[1] as number) : (val as number);
                const max = Math.max(1, ...matrix.flatMap((r) => r.map((vv) => Math.abs(vv ?? 0))));
                return Math.max(6, Math.sqrt(Math.abs(v) / max) * 40);
              }
            : 10,
          data: matrix[i].map((v, j) => [j, v ?? 0]),
        })),
      };
    }

    if (t === "waterfall") {
      const vals = matrix.map((r) => r[0] ?? 0);
      const spacers: number[] = [];
      const positives: (number | null)[] = [];
      const negatives: (number | null)[] = [];
      let running = 0;
      for (const v of vals) {
        if (v >= 0) {
          spacers.push(running);
          positives.push(v);
          negatives.push(null);
        } else {
          spacers.push(running + v);
          positives.push(null);
          negatives.push(-v);
        }
        running += v;
      }
      return {
        ...common,
        title,
        tooltip,
        legend,
        grid: { left: 60, top: title ? 50 : 30, right: 40, bottom: 60 },
        xAxis: { ...categoryAxis, data: rows, name: xName },
        yAxis: { ...valueAxis, name: yName },
        series: [
          { type: "bar", name: "", stack: "waterfall", itemStyle: { borderColor: "transparent", color: "transparent" }, data: spacers, emphasis: { itemStyle: { borderColor: "transparent", color: "transparent" } } },
          { type: "bar", name: cols[0] ?? "Positive", stack: "waterfall", data: positives, itemStyle: { color: "#4c9ee6" } },
          { type: "bar", name: `-${cols[0] ?? "Negative"}`, stack: "waterfall", data: negatives, itemStyle: { color: "#e66c6c" } },
        ],
      };
    }

    // bar / stackedBar / line / stackedLine / area / stackedArea
    const isStacked = t.startsWith("stacked");
    // includes("bar") is case-sensitive — "stackedBar" has a capital B,
    // so the naive check returned false and stacked-bar fell through to
    // the line branch (i.e. was drawn as a stacked line).
    const lower = t.toLowerCase();
    const kind: "bar" | "line" = lower.includes("bar") ? "bar" : "line";
    const areaStyle = t === "area" || t === "stackedArea" ? {} : undefined;

    // Dual y-axis: auto-split low-magnitude series to the right when
    // they'd otherwise be crushed to the zero line by a dominant series
    // (Event Count in thousands vs Avg Tone in single digits, etc.).
    // Per-series picks in o.seriesAxis always win; explicit auto-disable
    // via o.dualAxis=false reverts to single-axis.
    const seriesSides = assignSeriesAxes(cols, matrix, {
      dualAxis: o.dualAxis,
      seriesAxis: o.seriesAxis,
      threshold: SERIES_AXIS_THRESHOLD,
    });
    const hasRight = seriesSides.some((s) => s === "right");
    const yAxis = hasRight
      ? [
          { ...valueAxis, name: yName, position: "left" as const },
          // Render the right axis without splitLine duplicates so the
          // gridlines from the left axis aren't double-drawn.
          { ...valueAxis, name: undefined, position: "right" as const, splitLine: { show: false } },
        ]
      : { ...valueAxis, name: yName };

    const base = cols.map((name, c) => ({
      type: kind as "bar" | "line",
      name,
      stack: isStacked ? (seriesSides[c] === "right" ? "total-right" : "total-left") : undefined,
      areaStyle,
      smooth: kind === "line",
      yAxisIndex: hasRight ? (seriesSides[c] === "right" ? 1 : 0) : 0,
      data: matrix.map((row) => row[c] ?? 0),
    }));
    const trend = kind === "line" && cols.length > 0
      ? trendSeries(cols[0], matrix.map((row) => row[0] ?? null), o, tk)
      : [];

    return {
      ...common,
      title, tooltip, legend,
      grid: { left: 60, top: title ? 50 : 40, right: hasRight ? 60 : 40, bottom: 60 },
      xAxis: { ...categoryAxis, data: rows, name: xName },
      yAxis,
      series: [...base, ...trend],
    };
  }

  function render() {
    if (!chart) return;
    let opt: echarts.EChartsCoreOption;
    try {
      opt = buildOption(result, type, options);
    } catch (err) {
      // If buildOption throws (e.g. cellset shape doesn't fit the requested
      // chart type), clear the canvas instead of leaving the previous chart's
      // series on screen. Without this, switching from a "broken" chart to a
      // valid one would still render the broken state because the stale
      // series would merge with the new option.
      console.warn("[saiku] chart buildOption failed; clearing canvas:", err);
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
      const ro = new ResizeObserver(() => chart?.resize());
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

<div class="chart" bind:this={host}></div>

<style>
  .chart {
    width: 100%;
    height: 60vh;
    min-height: 320px;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
  }
</style>
