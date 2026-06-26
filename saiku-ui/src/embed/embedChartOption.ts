/*
 * #1103 — the pure records→ECharts option builder for the embed chart,
 * extracted verbatim from EmbedChart.svelte so the host-theme var→ECharts
 * mapping (and the "byte-identical when unstyled" back-compat guarantee) is
 * unit-testable without standing up the Svelte component or a live ECharts
 * canvas. Behaviour-preserving: EmbedChart.svelte now imports buildEmbedChartOption.
 */
import type { ECBasicOption } from "echarts/types/dist/shared";
import type { EmbedChartTheme } from "./embedChartTheme";
import type { EmbedRow } from "./types";

function isNumericColumn(rs: EmbedRow[], col: string): boolean {
  for (const r of rs) {
    const v = r[col]?.value;
    if (v === null || v === undefined) continue;
    if (typeof v !== "number" || Number.isNaN(v)) return false;
  }
  return true;
}

export function buildEmbedChartOption(rs: EmbedRow[], chartMode: string, theme: EmbedChartTheme): ECBasicOption {
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
