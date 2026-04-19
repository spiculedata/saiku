<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { QueryResult } from "$lib/api/query";
  import { parseCellset, toNumber } from "$lib/views/cellsetUtils";
  import type { ChartType, ChartOptions } from "$lib/views/chartTypes";
  import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";

  interface Props {
    result: QueryResult;
    type: ChartType;
    options?: ChartOptions;
  }

  let { result, type, options = DEFAULT_CHART_OPTIONS }: Props = $props();

  let host: HTMLDivElement | null = null;
  let chart: echarts.ECharts | null = null;

  const AXIS_LABEL = { color: "#a8aeba" };
  const AXIS_LINE = { lineStyle: { color: "#333a49" } };
  const SPLIT = { lineStyle: { color: "#1a1e27" } };

  function legendConfig(o: ChartOptions) {
    if (!o.showLegend) return { show: false };
    const position: Record<string, unknown> = { textStyle: { color: "#e6e8ec" } };
    if (o.legendPosition === "top") position.top = 10;
    else if (o.legendPosition === "bottom") position.bottom = 10;
    else if (o.legendPosition === "left") { position.left = 10; position.top = "middle"; position.orient = "vertical"; }
    else if (o.legendPosition === "right") { position.right = 10; position.top = "middle"; position.orient = "vertical"; }
    return position;
  }

  function titleConfig(o: ChartOptions) {
    if (!o.title) return undefined;
    return { text: o.title, left: "center", textStyle: { color: "#e6e8ec" } };
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

  function trendSeries(name: string, vals: (number | null)[], o: ChartOptions) {
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
      lineStyle: { type: "dashed" as const, width: 2 },
    }];
  }

  function buildOption(r: QueryResult, t: ChartType, o: ChartOptions): echarts.EChartsOption {
    const parsed = parseCellset(r);
    const rows = parsed.rowCategories;
    const cols = parsed.columnCategories;
    const matrix: (number | null)[][] = parsed.dataRows.map((row) => row.map(toNumber));

    const baseAxis = { type: "category" as const, axisLabel: AXIS_LABEL, axisLine: AXIS_LINE, splitLine: SPLIT };
    const valueAxis = { type: "value" as const, axisLabel: AXIS_LABEL, axisLine: AXIS_LINE, splitLine: SPLIT };
    const legend = legendConfig(o);
    const title = titleConfig(o);
    const tooltip = { trigger: "axis" as const, backgroundColor: "#11141b", borderColor: "#242935", textStyle: { color: "#e6e8ec" } };
    const xName = o.xAxisLabel || undefined;
    const yName = o.yAxisLabel || undefined;

    if (t === "pie" || t === "donut") {
      const totals = cols.map((_, c) => matrix.reduce((s, row) => s + (row[c] ?? 0), 0));
      const radius = t === "donut" ? ["45%", "70%"] : ["0%", "70%"];
      return {
        title, legend,
        tooltip: { trigger: "item", backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        series: [{
          type: "pie",
          radius,
          label: { color: "#e6e8ec" },
          data: cols.map((name, c) => ({ name, value: totals[c] })),
        }],
      };
    }

    if (t === "treemap") {
      // Each row = node, each column = measure series → flatten using first measure
      const data = rows.map((name, i) => ({
        name,
        value: (matrix[i] ?? []).reduce((s, v) => s + (v ?? 0), 0),
      })).filter((d) => d.value > 0);
      return {
        title,
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        series: [{
          type: "treemap",
          data,
          label: { color: "#fff" },
          breadcrumb: { show: false },
        }],
      };
    }

    if (t === "sunburst") {
      const data = rows.map((name, i) => ({
        name,
        value: (matrix[i] ?? []).reduce((s, v) => s + (v ?? 0), 0),
      })).filter((d) => d.value > 0);
      return {
        title,
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        series: [{ type: "sunburst", data, radius: [0, "90%"], label: { color: "#fff" } }],
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
        title,
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        grid: { left: 120, top: 40, right: 40, bottom: 80 },
        xAxis: { ...baseAxis, data: cols, name: xName },
        yAxis: { ...baseAxis, data: rows, inverse: true, name: yName },
        visualMap: { min, max, calculable: true, orient: "horizontal", left: "center", bottom: 10, textStyle: { color: "#a8aeba" } },
        series: [{ type: "heatmap", data, label: { show: false }, emphasis: { itemStyle: { shadowBlur: 10 } } }],
      };
    }

    if (t === "radar") {
      const max = Math.max(0, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
      return {
        title,
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        legend,
        radar: { indicator: cols.map((c) => ({ name: c, max: max || 1 })), axisName: { color: "#a8aeba" } },
        series: [{ type: "radar", data: rows.map((r, i) => ({ name: r, value: matrix[i].map((v) => v ?? 0) })) }],
      };
    }

    if (t === "scatter" || t === "bubble") {
      return {
        title,
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        legend,
        xAxis: { ...baseAxis, data: cols, name: xName },
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
      // Waterfall over rows (first measure). Transparent spacer stack + delta stack.
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
        title,
        tooltip,
        legend,
        grid: { left: 60, top: title ? 50 : 30, right: 40, bottom: 60 },
        xAxis: { ...baseAxis, data: rows, name: xName },
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
    const kind: "bar" | "line" = t.includes("bar") ? "bar" : "line";
    const areaStyle = t === "area" || t === "stackedArea" ? {} : undefined;
    const base = cols.map((name, c) => ({
      type: kind as "bar" | "line",
      name,
      stack: isStacked ? "total" : undefined,
      areaStyle,
      smooth: kind === "line",
      data: matrix.map((row) => row[c] ?? 0),
    }));
    const trend = kind === "line" && cols.length > 0
      ? trendSeries(cols[0], matrix.map((row) => row[0] ?? null), o)
      : [];

    return {
      title, tooltip, legend,
      grid: { left: 60, top: title ? 50 : 40, right: 40, bottom: 60 },
      xAxis: { ...baseAxis, data: rows, name: xName },
      yAxis: { ...valueAxis, name: yName },
      series: [...base, ...trend],
    };
  }

  function render() {
    if (!chart) return;
    chart.setOption(buildOption(result, type, options), true);
  }

  onMount(() => {
    if (host) {
      chart = echarts.init(host, "dark");
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
