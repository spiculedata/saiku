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
import { gridCells } from "$lib/dashboard/smallMultiples";

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
 *  or the kind isn't recognised. */
export function buildChartOption(response: AiQueryResponse, kind: string): Record<string, unknown> | null {
  if (!isSupportedChartKind(kind)) return null;
  const p = projectFromAiQueryResponse(response);
  if (p.matrix.length === 0) return null;
  const t = kind;

  const rows = p.rowCategories;
  const cols = p.columnCategories;
  const matrix = p.matrix;

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
    textStyle: { fontSize: 12 },
  }));

  if (t === "pie" || t === "donut") {
    return {
      tooltip: { trigger: "item" },
      legend: { type: "scroll", bottom: 0 },
      title: titles,
      series: cells.map((cell, m) => ({
        type: "pie",
        name: cols[m],
        radius:
          t === "donut"
            ? [(cell.widthPct * 0.22) + "%", (cell.widthPct * 0.34) + "%"]
            : [0, (cell.widthPct * 0.34) + "%"],
        center: [cell.centerXPct + "%", cell.centerYPct + "%"],
        data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })),
      })),
    };
  }

  if (t === "treemap") {
    return {
      tooltip: {},
      title: titles,
      series: cells.map((cell, m) => ({
        type: "treemap",
        name: cols[m],
        left: cell.leftPct + "%",
        top: cell.topPct + cell.heightPct * 0.18 + "%",
        width: cell.widthPct + "%",
        height: cell.heightPct * 0.82 + "%",
        breadcrumb: { show: false },
        data: rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })).filter((d) => d.value > 0),
      })),
    };
  }

  if (t === "sunburst") {
    return {
      tooltip: {},
      title: titles,
      series: cells.map((cell, m) => ({
        type: "sunburst",
        name: cols[m],
        center: [cell.centerXPct + "%", cell.centerYPct + "%"],
        radius: [0, (cell.widthPct * 0.42) + "%"],
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
      tooltip: {},
      grid: { left: 120, top: 24, right: 16, bottom: 64 },
      xAxis: { type: "category", data: cols, axisLabel: categoryAxisLabel() },
      yAxis: { type: "category", data: rows, inverse: true },
      visualMap: { min, max, calculable: true, orient: "horizontal", left: "center", bottom: 8 },
      series: [{ type: "heatmap", data, label: { show: false } }],
    };
  }

  if (t === "radar") {
    const max = Math.max(0, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
    return {
      tooltip: {},
      legend: { type: "scroll", bottom: 0 },
      radar: { indicator: cols.map((c) => ({ name: c, max: max || 1 })) },
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
      tooltip: {},
      legend: { type: "scroll", bottom: 0 },
      xAxis: { type: "category", data: cols, axisLabel: categoryAxisLabel() },
      yAxis: { type: "value" },
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
      tooltip: { trigger: "axis" },
      legend: { type: "scroll", bottom: 0 },
      grid: { top: 24, left: 48, right: 16, bottom: 36 },
      xAxis: { type: "category", data: rows, axisLabel: categoryAxisLabel() },
      yAxis: { type: "value" },
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
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", bottom: 0 },
    grid: { top: 24, left: 48, right: 16, bottom: 36 },
    xAxis: { type: "category", data: rows, axisLabel: categoryAxisLabel(rows.length > 8 ? 30 : 0) },
    yAxis: { type: "value" },
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
