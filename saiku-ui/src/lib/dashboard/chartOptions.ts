/*
 * AiQueryResponse → ECharts option builder for dashboard chart tiles.
 *
 * Scoped intentionally narrow in v1 — covers the most common chart
 * types (bar / stacked bar / line / stacked line / area / stacked area
 * / pie / donut). The other shapes from chartTypes.ts (heatmap, radar,
 * scatter, bubble, treemap, sunburst, waterfall) need substantially
 * different option shapes and the dashboard renderer falls back to a
 * note when one is requested.
 *
 * Pure: no DOM, no fetches, deterministic given the same inputs.
 * Lives outside the Svelte component so it can be unit-tested.
 */

import type { AiCell, AiQueryResponse } from "$lib/api/aiQuery";

/** Supported v1 chart kinds. Other ids in chartTypes.ts fall through to
 *  the not-implemented banner. */
export type ChartKind =
  | "bar"
  | "stackedBar"
  | "line"
  | "stackedLine"
  | "area"
  | "stackedArea"
  | "pie"
  | "donut";

const SUPPORTED: ReadonlySet<string> = new Set<ChartKind>([
  "bar",
  "stackedBar",
  "line",
  "stackedLine",
  "area",
  "stackedArea",
  "pie",
  "donut",
]);

export function isSupportedChartKind(kind: string): kind is ChartKind {
  return SUPPORTED.has(kind);
}

interface RowShape {
  category: string;
  series: { name: string; value: number | null }[];
}

function isAiCell(v: unknown): v is AiCell {
  return typeof v === "object" && v !== null && "formatted" in (v as object);
}

/** Project the records-format response into a `[{category, series: [{name, value}]}]`
 *  shape. Categories come from the first row-header column (plain string);
 *  series come from the measure columns (AiCell). */
function projectRows(response: AiQueryResponse): RowShape[] {
  const rows = response.data ?? [];
  if (rows.length === 0) return [];
  // Find the first plain-string key (row header). If there isn't one,
  // we synthesise sequential indices.
  const firstRow = rows[0];
  const keys = Object.keys(firstRow);
  const headerKey = keys.find((k) => !isAiCell(firstRow[k]));

  return rows.map((row, i) => {
    const category = headerKey ? String(row[headerKey] ?? `row ${i + 1}`) : `row ${i + 1}`;
    const series: { name: string; value: number | null }[] = [];
    for (const k of Object.keys(row)) {
      if (k === headerKey) continue;
      const v = row[k];
      if (!isAiCell(v)) continue;
      series.push({ name: k, value: v.value });
    }
    return { category, series };
  });
}

/** Build an ECharts option object for the given chart kind. Returns null
 *  if the kind isn't supported in v1 — caller renders a placeholder. */
export function buildChartOption(response: AiQueryResponse, kind: string): Record<string, unknown> | null {
  if (!isSupportedChartKind(kind)) return null;
  const rows = projectRows(response);
  if (rows.length === 0) return null;

  // Unique series names in stable order (first row defines the column set).
  const seriesNames = rows[0].series.map((s) => s.name);
  const categories = rows.map((r) => r.category);

  if (kind === "pie" || kind === "donut") {
    // Pie chart: total each series across rows; one slice per row using
    // the FIRST measure (matches the workspace's pie semantics).
    const firstMeasure = seriesNames[0] ?? "value";
    const data = rows.map((r) => ({
      name: r.category,
      value: r.series.find((s) => s.name === firstMeasure)?.value ?? 0,
    }));
    return {
      tooltip: { trigger: "item" },
      legend: { type: "scroll", bottom: 0 },
      series: [
        {
          type: "pie",
          radius: kind === "donut" ? ["40%", "70%"] : "65%",
          center: ["50%", "45%"],
          data,
        },
      ],
    };
  }

  // bar / line / area family — every series becomes one line/bar.
  const stacked = kind.startsWith("stacked");
  const isLine = kind === "line" || kind === "stackedLine";
  const isArea = kind === "area" || kind === "stackedArea";
  const seriesType = isLine || isArea ? "line" : "bar";

  const series = seriesNames.map((name) => ({
    name,
    type: seriesType,
    stack: stacked ? "total" : undefined,
    areaStyle: isArea ? {} : undefined,
    data: rows.map((r) => r.series.find((s) => s.name === name)?.value ?? null),
  }));

  return {
    tooltip: { trigger: "axis" },
    legend: { type: "scroll", bottom: 0 },
    grid: { top: 24, left: 48, right: 16, bottom: 36 },
    xAxis: { type: "category", data: categories, axisLabel: { rotate: categories.length > 8 ? 30 : 0 } },
    yAxis: { type: "value" },
    series,
  };
}
