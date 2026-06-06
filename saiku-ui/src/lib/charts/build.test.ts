/*
 * Unit tests for the single canonical chart-option builder (#1076).
 *
 * Covers all 15 chart types, both presentation modes (compact = dashboard
 * tiles, non-compact = workspace), and the features that previously only
 * existed on the workspace path (dual-axis split, trend lines) — now shared.
 */

import { describe, test, expect } from "vitest";
import { buildChartOption, type ChartProjection } from "$lib/charts/build";
import { DEFAULT_CHART_OPTIONS, type ChartOptions } from "$lib/views/chartTypes";

/** Two row categories × two measures. */
function sample(): ChartProjection {
  return {
    rowCategories: ["1997", "1998"],
    columnCategories: ["Store Sales", "Unit Sales"],
    matrix: [
      [565238.13, 266773],
      [612482.65, 282417],
    ],
  };
}

/** Single measure (the small-multiples M=1 case). */
function singleMeasure(): ChartProjection {
  return {
    rowCategories: ["1997", "1998"],
    columnCategories: ["Store Sales"],
    matrix: [[1], [2]],
  };
}

function opts(over: Partial<ChartOptions> = {}): ChartOptions {
  return { ...DEFAULT_CHART_OPTIONS, ...over };
}

const ALL_TYPES = [
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
] as const;

describe("buildChartOption — coverage of all 15 types", () => {
  test("every supported type produces a non-null option with series (both modes)", () => {
    for (const t of ALL_TYPES) {
      for (const compact of [false, true]) {
        const opt = buildChartOption(sample(), t, opts(), undefined, { compact });
        expect(opt, `${t} compact=${compact}`).not.toBeNull();
        expect(Array.isArray((opt as Record<string, unknown>).series)).toBe(true);
      }
    }
  });

  test("snapshot of every type's structure (non-compact / workspace)", () => {
    const out: Record<string, unknown> = {};
    for (const t of ALL_TYPES) {
      out[t] = buildChartOption(sample(), t, opts(), undefined, { compact: false });
    }
    expect(out).toMatchSnapshot();
  });

  test("snapshot of every type's structure (compact / dashboard)", () => {
    const out: Record<string, unknown> = {};
    for (const t of ALL_TYPES) {
      out[t] = buildChartOption(sample(), t, opts(), undefined, { compact: true });
    }
    expect(out).toMatchSnapshot();
  });
});

describe("buildChartOption — cartesian (bar/line/area)", () => {
  test("bar emits a category xAxis + one series per measure", () => {
    const opt = buildChartOption(sample(), "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    const xAxis = opt.xAxis as { data: string[]; type: string };
    expect(xAxis.type).toBe("category");
    expect(xAxis.data).toEqual(["1997", "1998"]);
    const series = opt.series as Array<{ name: string; type: string; data: (number | null)[] }>;
    expect(series).toHaveLength(2);
    expect(series[0].name).toBe("Store Sales");
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([565238.13, 612482.65]);
    expect(series[1].data).toEqual([266773, 282417]);
  });

  test("single-axis stacked uses one 'total' stack group", () => {
    const opt = buildChartOption(sample(), "stackedBar", opts({ dualAxis: false })) as Record<string, unknown>;
    const series = opt.series as Array<{ stack?: string }>;
    expect(series.every((s) => s.stack === "total")).toBe(true);
  });

  test("line uses type=line, no areaStyle; area adds areaStyle", () => {
    const line = buildChartOption(sample(), "line", opts()) as Record<string, unknown>;
    const ls = line.series as Array<{ type: string; areaStyle?: object }>;
    expect(ls[0].type).toBe("line");
    expect(ls[0].areaStyle).toBeUndefined();
    const area = buildChartOption(sample(), "area", opts()) as Record<string, unknown>;
    const as = area.series as Array<{ areaStyle?: object }>;
    expect(as[0].areaStyle).toBeDefined();
  });

  test("compact draws missing cells as gaps (null); non-compact as 0", () => {
    const gappy: ChartProjection = {
      rowCategories: ["a", "b"],
      columnCategories: ["m"],
      matrix: [[null], [5]],
    };
    const compact = buildChartOption(gappy, "bar", opts({ dualAxis: false }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const roomy = buildChartOption(gappy, "bar", opts({ dualAxis: false }), undefined, {
      compact: false,
    }) as Record<string, unknown>;
    expect((compact.series as Array<{ data: (number | null)[] }>)[0].data).toEqual([null, 5]);
    expect((roomy.series as Array<{ data: (number | null)[] }>)[0].data).toEqual([0, 5]);
  });
});

describe("buildChartOption — dual axis (#1076 brings this to both surfaces)", () => {
  // Unit Sales magnitudes here are >1% of Store Sales, so they stay on one axis;
  // a tiny second series drops to the right axis.
  const disparate: ChartProjection = {
    rowCategories: ["a", "b"],
    columnCategories: ["Big", "Tiny"],
    matrix: [
      [100000, 1],
      [120000, 2],
    ],
  };

  test("dualAxis=true splits a crushed low-magnitude series to a right axis", () => {
    const opt = buildChartOption(disparate, "bar", opts({ dualAxis: true })) as Record<string, unknown>;
    expect(Array.isArray(opt.yAxis)).toBe(true);
    const yAxis = opt.yAxis as Array<{ position: string }>;
    expect(yAxis).toHaveLength(2);
    expect(yAxis[0].position).toBe("left");
    expect(yAxis[1].position).toBe("right");
    const series = opt.series as Array<{ name: string; yAxisIndex: number }>;
    expect(series.find((s) => s.name === "Big")?.yAxisIndex).toBe(0);
    expect(series.find((s) => s.name === "Tiny")?.yAxisIndex).toBe(1);
  });

  test("dualAxis=false keeps a single y-axis", () => {
    const opt = buildChartOption(disparate, "bar", opts({ dualAxis: false })) as Record<string, unknown>;
    expect(Array.isArray(opt.yAxis)).toBe(false);
    const series = opt.series as Array<{ yAxisIndex: number }>;
    expect(series.every((s) => s.yAxisIndex === 0)).toBe(true);
  });

  test("explicit per-series pin overrides the auto decision", () => {
    const opt = buildChartOption(
      sample(),
      "bar",
      opts({ dualAxis: false, seriesAxis: { "Unit Sales": "right" } }),
    ) as Record<string, unknown>;
    const yAxis = opt.yAxis as Array<{ position: string }>;
    expect(Array.isArray(yAxis)).toBe(true);
    const series = opt.series as Array<{ name: string; yAxisIndex: number }>;
    expect(series.find((s) => s.name === "Unit Sales")?.yAxisIndex).toBe(1);
  });
});

describe("buildChartOption — trend lines (#1076 brings this to both surfaces)", () => {
  const ts: ChartProjection = {
    rowCategories: ["a", "b", "c", "d"],
    columnCategories: ["m"],
    matrix: [[1], [2], [3], [4]],
  };

  test("linear trend appends a dashed line series labelled '(trend)'", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "linear" })) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string; type: string; lineStyle?: { type: string } }>;
    // 1 data series + 1 trend series.
    expect(series).toHaveLength(2);
    const trend = series[1];
    expect(trend.name).toBe("m (trend)");
    expect(trend.type).toBe("line");
    expect(trend.lineStyle?.type).toBe("dashed");
  });

  test("moving average names the series with its period", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "ma", trendPeriod: 3 })) as Record<
      string,
      unknown
    >;
    const series = opt.series as Array<{ name: string }>;
    expect(series[1].name).toBe("m (MA3)");
  });

  test("trendLine=none adds no extra series", () => {
    const opt = buildChartOption(ts, "line", opts({ trendLine: "none" })) as Record<string, unknown>;
    expect((opt.series as unknown[]).length).toBe(1);
  });

  test("bar charts never get a trend series (line-only)", () => {
    const opt = buildChartOption(ts, "bar", opts({ trendLine: "linear" })) as Record<string, unknown>;
    expect((opt.series as unknown[]).length).toBe(1);
  });
});

describe("buildChartOption — title & legend", () => {
  test("non-compact honours a user title + legend position", () => {
    const opt = buildChartOption(sample(), "bar", opts({ title: "My chart", legendPosition: "bottom" })) as Record<
      string,
      unknown
    >;
    expect((opt.title as { text: string }).text).toBe("My chart");
    expect((opt.legend as { bottom: number }).bottom).toBe(10);
  });

  test("compact ignores legendPosition and pins a bottom scroll legend", () => {
    const opt = buildChartOption(sample(), "bar", opts({ legendPosition: "left" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const legend = opt.legend as { type: string; bottom: number };
    expect(legend.type).toBe("scroll");
    expect(legend.bottom).toBe(0);
  });

  test("compact pie merges no overall title — just per-measure cell titles", () => {
    const opt = buildChartOption(sample(), "pie", opts({ title: "" }), undefined, {
      compact: true,
    }) as Record<string, unknown>;
    const titles = opt.title as Array<{ text: string }>;
    expect(titles.map((t) => t.text)).toEqual(["Store Sales", "Unit Sales"]);
  });

  test("non-compact pie prepends the user title to the cell titles", () => {
    const opt = buildChartOption(sample(), "pie", opts({ title: "Overall" })) as Record<string, unknown>;
    const titles = opt.title as Array<{ text: string }>;
    expect(titles.map((t) => t.text)).toEqual(["Overall", "Store Sales", "Unit Sales"]);
  });
});

describe("buildChartOption — small multiples (pie/donut/treemap/sunburst)", () => {
  test("2-measure pie fans out into 2 series, each sliced by row category", () => {
    const opt = buildChartOption(sample(), "pie", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; name: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "pie")).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["Store Sales", "Unit Sales"]);
    expect(series[0].data.map((d) => d.name)).toEqual(["1997", "1998"]);
    expect(series[0].data[0].value).toBeCloseTo(565238.13, 2);
  });

  test("1-measure pie renders a single chart with a single title", () => {
    const opt = buildChartOption(singleMeasure(), "pie", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string }>;
    expect(series).toHaveLength(1);
    expect((opt.title as unknown[]).length).toBe(1);
  });

  test("donut keeps a non-zero inner radius", () => {
    const opt = buildChartOption(sample(), "donut", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ radius: (string | number)[] }>;
    expect(Array.isArray(series[0].radius)).toBe(true);
    expect(parseFloat(String(series[0].radius[0]))).toBeGreaterThan(0);
  });

  test("treemap fans out one series per measure, items = rows", () => {
    const opt = buildChartOption(sample(), "treemap", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "treemap")).toBe(true);
    expect(series[0].data[0].name).toBe("1997");
  });
});

describe("buildChartOption — matrix types (heatmap/radar/scatter/waterfall)", () => {
  test("heatmap emits [col,row,value] tuples + a visualMap", () => {
    const opt = buildChartOption(sample(), "heatmap", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: [number, number, number][] }>;
    expect(series[0].type).toBe("heatmap");
    expect(series[0].data).toHaveLength(4);
    expect(opt.visualMap).toBeDefined();
  });

  test("radar uses cols as indicators + rows as series entries", () => {
    const opt = buildChartOption(sample(), "radar", opts()) as Record<string, unknown>;
    const radar = opt.radar as { indicator: { name: string }[] };
    expect(radar.indicator.map((i) => i.name)).toEqual(["Store Sales", "Unit Sales"]);
    const series = opt.series as Array<{ data: unknown[] }>;
    expect(series[0].data).toHaveLength(2);
  });

  test("waterfall emits three stacked series (spacer + pos + neg)", () => {
    const opt = buildChartOption(sample(), "waterfall", opts()) as Record<string, unknown>;
    const series = opt.series as Array<{ stack: string }>;
    expect(series).toHaveLength(3);
    expect(series.every((s) => s.stack === "waterfall")).toBe(true);
  });
});

describe("buildChartOption — theme tokens", () => {
  const dark = {
    fg: "#e5e7eb",
    fgMuted: "#9ca3af",
    bg: "#0b1220",
    bgMuted: "#111827",
    border: "#334155",
    accent: "#818cf8",
    chartColors: ["#aaa111", "#bbb222"],
  };

  test("bar threads palette + text/axis/tooltip colours from tk", () => {
    const opt = buildChartOption(sample(), "bar", opts(), dark) as Record<string, unknown>;
    expect(opt.backgroundColor).toBe("transparent");
    expect(opt.color).toEqual(dark.chartColors);
    expect((opt.textStyle as { color: string }).color).toBe("#e5e7eb");
    const xAxis = opt.xAxis as { axisLabel: { color: string }; axisLine: { lineStyle: { color: string } } };
    expect(xAxis.axisLabel.color).toBe("#9ca3af");
    expect(xAxis.axisLine.lineStyle.color).toBe("#334155");
    const tooltip = opt.tooltip as { backgroundColor: string; textStyle: { color: string } };
    expect(tooltip.backgroundColor).toBe("#0b1220");
    expect(tooltip.textStyle.color).toBe("#e5e7eb");
  });

  test("defaults to the light fallback when tk is omitted", () => {
    const opt = buildChartOption(sample(), "bar", opts()) as Record<string, unknown>;
    expect((opt.textStyle as { color: string }).color).toBe("#0f172a");
  });
});

describe("buildChartOption — rejection", () => {
  test("returns null for unknown chart kinds", () => {
    expect(buildChartOption(sample(), "made-up")).toBeNull();
    expect(buildChartOption(sample(), "")).toBeNull();
  });

  test("returns null when the projection has no rows", () => {
    expect(buildChartOption({ rowCategories: [], columnCategories: [], matrix: [] }, "bar")).toBeNull();
  });
});
