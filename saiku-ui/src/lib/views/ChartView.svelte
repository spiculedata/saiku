<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { QueryResult } from "$lib/api/query";
  import { parseCellset, toNumber } from "$lib/views/cellsetUtils";
  import type { ChartType } from "$lib/views/chartTypes";

  interface Props {
    result: QueryResult;
    type: ChartType;
  }

  let { result, type }: Props = $props();

  let host: HTMLDivElement | null = null;
  let chart: echarts.ECharts | null = null;

  function buildOption(r: QueryResult, t: ChartType): echarts.EChartsOption {
    const parsed = parseCellset(r);
    const rows = parsed.rowCategories;
    const cols = parsed.columnCategories;
    const matrix: (number | null)[][] = parsed.dataRows.map((row) => row.map(toNumber));

    const baseAxis = {
      type: "category" as const,
      axisLabel: { color: "#a8aeba" },
      axisLine: { lineStyle: { color: "#333a49" } },
      splitLine: { lineStyle: { color: "#1a1e27" } },
    };
    const valueAxis = {
      type: "value" as const,
      axisLabel: { color: "#a8aeba" },
      axisLine: { lineStyle: { color: "#333a49" } },
      splitLine: { lineStyle: { color: "#1a1e27" } },
    };
    const legend = { textStyle: { color: "#e6e8ec" } };
    const tooltip = {
      trigger: "axis" as const,
      backgroundColor: "#11141b",
      borderColor: "#242935",
      textStyle: { color: "#e6e8ec" },
    };

    if (t === "pie") {
      const totals = cols.map((_, c) => matrix.reduce((s, row) => s + (row[c] ?? 0), 0));
      return {
        tooltip: { trigger: "item", backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        legend,
        series: [
          {
            type: "pie",
            radius: ["30%", "65%"],
            data: cols.map((name, c) => ({ name, value: totals[c] })),
          },
        ],
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
      const min = Math.min(...values);
      const max = Math.max(...values);
      return {
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        grid: { left: 120, top: 40, right: 40, bottom: 80 },
        xAxis: { ...baseAxis, data: cols },
        yAxis: { ...baseAxis, data: rows, inverse: true },
        visualMap: {
          min, max,
          calculable: true,
          orient: "horizontal",
          left: "center",
          bottom: 10,
          textStyle: { color: "#a8aeba" },
        },
        series: [
          {
            type: "heatmap",
            data,
            label: { show: false },
            emphasis: { itemStyle: { shadowBlur: 10 } },
          },
        ],
      };
    }

    if (t === "radar") {
      const max = Math.max(
        0,
        ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))),
      );
      return {
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        legend,
        radar: {
          indicator: cols.map((c) => ({ name: c, max: max || 1 })),
          axisName: { color: "#a8aeba" },
        },
        series: [
          {
            type: "radar",
            data: rows.map((r, i) => ({ name: r, value: matrix[i].map((v) => v ?? 0) })),
          },
        ],
      };
    }

    if (t === "scatter") {
      return {
        tooltip: { backgroundColor: "#11141b", textStyle: { color: "#e6e8ec" } },
        legend,
        xAxis: { ...baseAxis, data: cols },
        yAxis: valueAxis,
        series: rows.map((name, i) => ({
          type: "scatter",
          name,
          data: matrix[i].map((v, j) => [j, v ?? 0]),
        })),
      };
    }

    const isStacked = t.startsWith("stacked") || t === "stackedArea";
    const kind: "bar" | "line" = t.includes("bar") ? "bar" : "line";
    const areaStyle = t === "area" || t === "stackedArea" ? {} : undefined;

    return {
      tooltip,
      legend,
      grid: { left: 60, top: 40, right: 40, bottom: 60 },
      xAxis: { ...baseAxis, data: rows },
      yAxis: valueAxis,
      series: cols.map((name, c) => ({
        type: kind,
        name,
        stack: isStacked ? "total" : undefined,
        areaStyle,
        smooth: kind === "line",
        data: matrix.map((row) => row[c] ?? 0),
      })),
    };
  }

  function render() {
    if (!chart) return;
    chart.setOption(buildOption(result, type), true);
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
