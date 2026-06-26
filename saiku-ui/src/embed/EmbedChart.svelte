<script lang="ts">
  /*
   * Records → ECharts renderer. Imports ECharts modularly (core + bar +
   * line + pie + the four common components) rather than the full
   * `echarts` umbrella so the embed bundle stays well below the 200 KB
   * gz forecast — tree-shaking drops the rest.
   *
   * Maps a records-format response to a {category → series} chart:
   *   - First non-numeric column → category axis labels (xAxis)
   *   - Every numeric column → its own series
   *   - Cell `value` (not `formatted`) feeds the chart; `formatted`
   *     drives the tooltip so the host page sees the same string the
   *     workbench would.
   */
  import { onMount } from "svelte";
  import { use, init } from "echarts/core";
  import { BarChart, LineChart, PieChart } from "echarts/charts";
  import {
    GridComponent,
    LegendComponent,
    TitleComponent,
    TooltipComponent,
  } from "echarts/components";
  import { CanvasRenderer } from "echarts/renderers";
  import type { EChartsType, ECBasicOption } from "echarts/types/dist/shared";
  import type { EmbedRow } from "./types";
  // #1103: host-page chart theming via CSS custom properties.
  import { readEmbedChartTheme, type EmbedChartTheme } from "./embedChartTheme";

  // Register modules once at module scope. ECharts dedupes a repeat
  // `use()` so this is safe across many <saiku-embed> instances.
  use([
    BarChart,
    LineChart,
    PieChart,
    GridComponent,
    LegendComponent,
    TitleComponent,
    TooltipComponent,
    CanvasRenderer,
  ]);

  interface Props {
    rows: EmbedRow[];
    /** `bar` (default), `line`, or `pie`. */
    mode?: string;
  }

  let { rows, mode = "bar" }: Props = $props();

  let container = $state<HTMLDivElement | undefined>(undefined);
  let chart: EChartsType | null = null;

  onMount(() => {
    if (!container) return;
    chart = init(container);
    // Resize on host-page layout changes so the chart fills its grid
    // cell as the dashboard reflows.
    const observer = new ResizeObserver(() => chart?.resize());
    observer.observe(container);
    return () => {
      observer.disconnect();
      chart?.dispose();
      chart = null;
    };
  });

  /* Re-derive the option each time rows / mode change. ECharts merges
   * the new option onto the prior state by default, so we pass
   * { notMerge: true } to keep series counts honest when the shape
   * shifts (e.g. a column appearing / disappearing). */
  $effect(() => {
    if (!chart) return;
    // #1103: resolve host-set chart theming from CSS vars on this element
    // (custom properties inherit through the shadow boundary). Unstyled →
    // empty theme → ECharts defaults (back-compat).
    const el = container;
    const theme: EmbedChartTheme = el
      ? readEmbedChartTheme((n) => getComputedStyle(el).getPropertyValue(n))
      : { palette: [] };
    const opt = buildOption(rows, mode, theme);
    chart.setOption(opt, { notMerge: true });
  });

  function isNumericColumn(rs: EmbedRow[], col: string): boolean {
    for (const r of rs) {
      const v = r[col]?.value;
      if (v === null || v === undefined) continue;
      if (typeof v !== "number" || Number.isNaN(v)) return false;
    }
    return true;
  }

  function buildOption(rs: EmbedRow[], chartMode: string, theme: EmbedChartTheme): ECBasicOption {
    const fg = theme.fg;
    const axisColor = theme.axisLine;
    // #1103: host-set series palette → ECharts `color` cycle. Spread so an
    // unstyled embed emits no `color` key (output byte-identical to pre-#1103).
    const colorOpt: ECBasicOption = theme.palette.length ? { color: theme.palette } : {};
    const legendOpt = { bottom: 0, ...(fg ? { textStyle: { color: fg } } : {}) };
    const fgText = fg ? { color: fg } : {};
    const axisLineOpt = axisColor ? { axisLine: { lineStyle: { color: axisColor } } } : {};

    if (rs.length === 0) {
      return {
        title: { text: "No data", left: "center", top: "middle", textStyle: { fontSize: 12, ...fgText } },
      };
    }
    const cols = Object.keys(rs[0]);
    const numericCols = cols.filter((c) => isNumericColumn(rs, c));
    const categoryCol = cols.find((c) => !isNumericColumn(rs, c)) ?? cols[0];
    const categories = rs.map((r) => r[categoryCol]?.formatted ?? "");

    if (chartMode === "pie") {
      // Pie uses the FIRST numeric column only — falls back to a bar
      // chart if there isn't one.
      const valCol = numericCols[0];
      if (!valCol) return { title: { text: "No numeric series", textStyle: { ...fgText } } };
      return {
        ...colorOpt,
        tooltip: { trigger: "item" },
        legend: legendOpt,
        series: [
          {
            type: "pie",
            radius: ["40%", "70%"],
            label: { show: true, formatter: "{b}: {d}%", ...fgText },
            data: rs.map((r) => ({
              name: r[categoryCol]?.formatted ?? "",
              value: r[valCol]?.value ?? 0,
            })),
          },
        ],
      };
    }

    return {
      ...colorOpt,
      tooltip: {
        trigger: "axis",
        // ECharts default tooltip is bare numbers; replace with the
        // pre-formatted cells from the server so the host page sees
        // the same caption the workbench would. The trigger fires
        // once per category with all series, so we walk the params.
        formatter: (params: unknown) => {
          const arr = Array.isArray(params) ? params : [params];
          // arr[0].axisValue is the category caption; per-series .seriesName + .dataIndex
          const cat = (arr[0] as { axisValue?: string }).axisValue ?? "";
          const lines = arr
            .map((p) => {
              const idx = (p as { dataIndex: number }).dataIndex;
              const name = (p as { seriesName: string }).seriesName;
              const cell = rs[idx]?.[name];
              const disp = cell?.formatted ?? String((p as { value: unknown }).value ?? "");
              return `${name}: ${disp}`;
            })
            .join("<br/>");
          return `<b>${cat}</b><br/>${lines}`;
        },
      },
      legend: legendOpt,
      grid: { left: 40, right: 16, top: 24, bottom: 36, containLabel: true },
      xAxis: {
        type: "category",
        data: categories,
        axisLabel: { interval: 0, rotate: categories.length > 8 ? -30 : 0, ...fgText },
        ...axisLineOpt,
      },
      yAxis: {
        type: "value",
        ...(fg ? { axisLabel: { color: fg } } : {}),
        ...axisLineOpt,
        ...(axisColor ? { splitLine: { lineStyle: { color: axisColor } } } : {}),
      },
      series: numericCols.map((c) => ({
        name: c,
        type: chartMode === "line" ? ("line" as const) : ("bar" as const),
        data: rs.map((r) => r[c]?.value ?? null),
      })),
    };
  }
</script>

<div bind:this={container} class="chart" role="img" aria-label="Saiku embed chart"></div>

<style>
  /* The chart needs a concrete height to render — ECharts measures
   * its container synchronously. The :host's min-height in
   * SaikuEmbed.svelte already supplies one for the outer shell; we
   * just fill it. */
  .chart {
    width: 100%;
    height: 100%;
    min-height: 240px;
  }
</style>
