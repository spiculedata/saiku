/*
 * AiQueryResponse → ECharts option builder for dashboard chart tiles.
 *
 * Covers the same 15 chart types as the workspace's ChartView so the
 * dashboard offers the full palette: bar, stackedBar, line, stackedLine,
 * area, stackedArea, pie, donut, heatmap, radar, scatter, bubble,
 * treemap, sunburst, waterfall. The chart-type-specific option shapes
 * mirror ChartView's buildOption logic; the only difference is the
 * input projector (AiQueryResponse vs the workspace's CellDataSet).
 *
 * Pure: no DOM, no fetches. Tests live alongside.
 */

import type { AiCell, AiQueryResponse } from "$lib/api/aiQuery";
import { axisLabelConfig } from "$lib/views/chartAxisLabel";
import { cellRadiusPct, gridCells, MAX_LABELLED_SLICES } from "$lib/dashboard/smallMultiples";
import { type ThemeTokens, DEFAULT_THEME_TOKENS } from "$lib/views/chartTheme";

/**
 * Truncating axisLabel for a dashboard tile's category axis. Tiles are small,
 * so a fixed cap keeps deep member captions from overflowing; ECharts shows
 * the full label in the axis-pointer tooltip on hover. `rotate` is preserved
 * for callers that crowd many categories. Width default suits a tile.
 */
function categoryAxisLabel(rotate = 0): Record<string, unknown> {
  return { rotate, ...axisLabelConfig(100) };
}

/** All chart kinds the workspace exposes — matches chartTypes.ts. */
export type ChartKind =
  | "bar"
  | "stackedBar"
  | "line"
  | "stackedLine"
  | "area"
  | "stackedArea"
  | "pie"
  | "donut"
  | "heatmap"
  | "radar"
  | "scatter"
  | "bubble"
  | "treemap"
  | "sunburst"
  | "waterfall";

const KNOWN: ReadonlySet<string> = new Set<ChartKind>([
  "bar",
  "stackedBar",
  "line",
  "stackedLine",
  "area",
  "stackedArea",
  "pie",
  "donut",
  "heatmap",
  "radar",
  "scatter",
  "bubble",
  "treemap",
  "sunburst",
  "waterfall",
]);

export function isSupportedChartKind(kind: string): kind is ChartKind {
  return KNOWN.has(kind);
}

interface ChartProjection {
  rowCategories: string[];
  columnCategories: string[];
  matrix: (number | null)[][];
}

function isAiCell(v: unknown): v is AiCell {
  return typeof v === "object" && v !== null && "formatted" in (v as object);
}

/** Normalise an AiQueryResponse into the same {rowCategories, cols,
 *  matrix} shape ChartView's parseCellset emits — so the builder
 *  switches below match the workspace's logic 1:1. */
function projectFromAiQueryResponse(response: AiQueryResponse): ChartProjection {
  const rows = response.data ?? [];
  if (rows.length === 0) return { rowCategories: [], columnCategories: [], matrix: [] };

  // Pick row-header columns (plain string keys) vs measure columns
  // (AiCell keys) from the first row's shape — insertion order.
  const firstRow = rows[0];
  const headerKeys: string[] = [];
  const measureKeys: string[] = [];
  for (const k of Object.keys(firstRow)) {
    if (isAiCell(firstRow[k])) measureKeys.push(k);
    else headerKeys.push(k);
  }

  const rowCategories = rows.map((r, i) => {
    const parts = headerKeys.map((k) => String(r[k] ?? "")).filter((s) => s.length > 0);
    return parts.length > 0 ? parts.join(" / ") : `row ${i + 1}`;
  });

  const matrix = rows.map((r) =>
    measureKeys.map((k) => {
      const v = r[k];
      return isAiCell(v) ? v.value : null;
    }),
  );

  return { rowCategories, columnCategories: measureKeys, matrix };
}

/* ----------------------------- builder ----------------------------- */

/** Build an ECharts option object for the given chart kind, projected
 *  from an AiQueryResponse. Returns null when the response has no rows
 *  or the kind isn't recognised.
 *
 *  `tk` carries the active theme tokens (see chartTheme.ts) so the chart
 *  text/axes/palette repaint on a light/dark/system flip. It's optional and
 *  defaults to the light fallback so the pure callers (tests) stay DOM-free
 *  and structurally unchanged; ChartTile passes the live resolved tokens. */
export function buildChartOption(
  response: AiQueryResponse,
  kind: string,
  aspect = 1,
  tk: ThemeTokens = DEFAULT_THEME_TOKENS,
): Record<string, unknown> | null {
  if (!isSupportedChartKind(kind)) return null;
  const p = projectFromAiQueryResponse(response);
  if (p.matrix.length === 0) return null;
  const t = kind;

  const rows = p.rowCategories;
  const cols = p.columnCategories;
  const matrix = p.matrix;

  // Theme-aware shared config — mirrors ChartView.buildOption so dashboard
  // tiles and the workspace chart colour identically. `common` themes the
  // canvas (transparent so the tile bg shows through), the categorical
  // palette, and default text; the axis/legend/tooltip helpers carry the
  // fg/border tokens into each branch.
  const common = {
    backgroundColor: "transparent",
    color: tk.chartColors,
    textStyle: { color: tk.fg },
  };
  const axisLabel = { color: tk.fgMuted };
  const axisLine = { lineStyle: { color: tk.border } };
  const axisTick = { lineStyle: { color: tk.border } };
  const splitLine = { lineStyle: { color: tk.border, opacity: 0.5 } };
  const nameTextStyle = { color: tk.fg };
  const legendStyle = { textStyle: { color: tk.fg } };
  const tooltipStyle = { backgroundColor: tk.bg, borderColor: tk.border, textStyle: { color: tk.fg } };

  // Pie / donut / treemap / sunburst encode a SINGLE measure. With M measures
  // we fan out into M small-multiple charts — one per measure — laid out in a
  // grid inside the same option (M series, one per cell). Each chart shows the
  // row categories as its items, sized by that one measure. M=1 is just a
  // single full-box chart (no special branch).
  const cells = gridCells(cols.length);
  const titles = cells.map((cell, m) => ({
    text: cols[m],
    left: cell.centerXPct + "%",
    top: cell.topPct + "%",
    textAlign: "center",
    textStyle: { color: tk.fg, fontSize: 12 },
  }));
  // Category identification: a shared category legend collides with the
  // per-measure titles and bloats off-screen once there are many categories.
  // Instead we name the slices directly when there are few enough to read, and
  // lean on the tooltip beyond that. Inside the slices for a small-multiple grid
  // (leader lines would cross between neighbouring charts); outside (with leader
  // lines) for a single chart where there's room.
  const showSliceLabels = rows.length <= MAX_LABELLED_SLICES;
  const sliceLabelInside = cells.length > 1;

  if (t === "pie" || t === "donut") {
    return {
      ...common,
      tooltip: { trigger: "item", ...tooltipStyle },
      title: titles,
      series: cells.map((cell, m) => {
        const outer = cellRadiusPct(cell, aspect);
        return {
          type: "pie",
          name: cols[m],
          radius: t === "donut" ? [outer * 0.55 + "%", outer + "%"] : [0, outer + "%"],
          center: [cell.centerXPct + "%", cell.centerYPct + "%"],
          label: {
            show: showSliceLabels,
            position: sliceLabelInside ? "inside" : "outside",
            formatter: "{b}",
            color: sliceLabelInside ? "#fff" : tk.fg,
          },
          labelLine: { show: showSliceLabels && !sliceLabelInside },
          data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })),
        };
      }),
    };
  }

  if (t === "treemap") {
    return {
      ...common,
      tooltip: tooltipStyle,
      title: titles,
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

  if (t === "sunburst") {
    return {
      ...common,
      tooltip: tooltipStyle,
      title: titles,
      series: cells.map((cell, m) => ({
        type: "sunburst",
        name: cols[m],
        center: [cell.centerXPct + "%", cell.centerYPct + "%"],
        radius: [0, cellRadiusPct(cell, aspect) + "%"],
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
      tooltip: tooltipStyle,
      grid: { left: 120, top: 24, right: 16, bottom: 64 },
      xAxis: {
        type: "category",
        data: cols,
        axisLabel: { ...categoryAxisLabel(), color: tk.fgMuted },
        axisLine,
        axisTick,
      },
      yAxis: { type: "category", data: rows, inverse: true, axisLabel, axisLine, axisTick },
      visualMap: {
        min,
        max,
        calculable: true,
        orient: "horizontal",
        left: "center",
        bottom: 8,
        textStyle: { color: tk.fgMuted },
      },
      series: [{ type: "heatmap", data, label: { show: false } }],
    };
  }

  if (t === "radar") {
    const max = Math.max(0, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
    return {
      ...common,
      tooltip: tooltipStyle,
      legend: { type: "scroll", bottom: 0, ...legendStyle },
      radar: {
        indicator: cols.map((c) => ({ name: c, max: max || 1 })),
        axisName: { color: tk.fgMuted },
        axisLine: { lineStyle: { color: tk.border } },
        splitLine: { lineStyle: { color: tk.border, opacity: 0.5 } },
        splitArea: { areaStyle: { color: [tk.bg, tk.bgMuted], opacity: 0.3 } },
      },
      series: [
        {
          type: "radar",
          data: rows.map((r, i) => ({ name: r, value: matrix[i].map((v) => v ?? 0) })),
        },
      ],
    };
  }

  if (t === "scatter" || t === "bubble") {
    const max = Math.max(1, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
    return {
      ...common,
      tooltip: tooltipStyle,
      legend: { type: "scroll", bottom: 0, ...legendStyle },
      xAxis: {
        type: "category",
        data: cols,
        axisLabel: { ...categoryAxisLabel(), color: tk.fgMuted },
        axisLine,
        axisTick,
        splitLine,
        nameTextStyle,
      },
      yAxis: { type: "value", axisLabel, axisLine, axisTick, splitLine, nameTextStyle },
      series: rows.map((name, i) => ({
        type: "scatter",
        name,
        symbolSize:
          t === "bubble"
            ? (val: unknown) => {
                const v = Array.isArray(val) ? (val[1] as number) : (val as number);
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
      tooltip: { trigger: "axis", ...tooltipStyle },
      legend: { type: "scroll", bottom: 0, ...legendStyle },
      grid: { top: 24, left: 48, right: 16, bottom: 36 },
      xAxis: {
        type: "category",
        data: rows,
        axisLabel: { ...categoryAxisLabel(), color: tk.fgMuted },
        axisLine,
        axisTick,
        splitLine,
        nameTextStyle,
      },
      yAxis: { type: "value", axisLabel, axisLine, axisTick, splitLine, nameTextStyle },
      series: [
        {
          type: "bar",
          name: "",
          stack: "waterfall",
          itemStyle: { borderColor: "transparent", color: "transparent" },
          emphasis: { itemStyle: { borderColor: "transparent", color: "transparent" } },
          data: spacers,
        },
        {
          type: "bar",
          name: cols[0] ?? "Positive",
          stack: "waterfall",
          data: positives,
          itemStyle: { color: "#4c9ee6" },
        },
        {
          type: "bar",
          name: `-${cols[0] ?? "Negative"}`,
          stack: "waterfall",
          data: negatives,
          itemStyle: { color: "#e66c6c" },
        },
      ],
    };
  }

  // bar / stackedBar / line / stackedLine / area / stackedArea
  const isStacked = t.startsWith("stacked");
  const lower = t.toLowerCase();
  const kindBase: "bar" | "line" = lower.includes("bar") ? "bar" : "line";
  const areaStyle = t === "area" || t === "stackedArea" ? {} : undefined;
  return {
    ...common,
    tooltip: { trigger: "axis", ...tooltipStyle },
    legend: { type: "scroll", bottom: 0, ...legendStyle },
    grid: { top: 24, left: 48, right: 16, bottom: 36 },
    xAxis: {
      type: "category",
      data: rows,
      axisLabel: { ...categoryAxisLabel(rows.length > 8 ? 30 : 0), color: tk.fgMuted },
      axisLine,
      axisTick,
      splitLine,
      nameTextStyle,
    },
    yAxis: { type: "value", axisLabel, axisLine, axisTick, splitLine, nameTextStyle },
    series: cols.map((name, c) => ({
      name,
      type: kindBase,
      stack: isStacked ? "total" : undefined,
      areaStyle,
      smooth: kindBase === "line",
      data: matrix.map((row) => row[c] ?? null),
    })),
  };
}
