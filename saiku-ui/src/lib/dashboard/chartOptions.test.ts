/*
 * Unit tests for the AiQueryResponse → ECharts option builder.
 * Covers v1 supported chart kinds + the rejection branch.
 */

import { describe, test, expect } from "vitest";
import { buildChartOption, isSupportedChartKind, projectForChart } from "$lib/dashboard/chartOptions";
import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";
import type { AiQueryResponse } from "$lib/api/aiQuery";

function sampleResponse(): AiQueryResponse {
  return {
    queryId: "q1",
    status: "SUCCESS",
    format: "records",
    metadata: {
      rows: [{ name: "1997", caption: "1997" }, { name: "1998", caption: "1998" }],
      columns: [{ name: "Store Sales", caption: "Store Sales" }, { name: "Unit Sales", caption: "Unit Sales" }],
      measures: ["Store Sales", "Unit Sales"],
    },
    data: [
      {
        Year: "1997",
        "Store Sales": { value: 565238.13, formatted: "565,238.13" },
        "Unit Sales": { value: 266773, formatted: "266,773" },
      },
      {
        Year: "1998",
        "Store Sales": { value: 612482.65, formatted: "612,482.65" },
        "Unit Sales": { value: 282417, formatted: "282,417" },
      },
    ],
    totalRows: 2,
  };
}

describe("isSupportedChartKind", () => {
  test("accepts the full chart palette (parity with workspace chartTypes.ts)", () => {
    for (const k of [
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
    ]) {
      expect(isSupportedChartKind(k)).toBe(true);
    }
  });
  test("rejects unknown types", () => {
    for (const k of ["totally-made-up", "gauge", "boxplot", ""]) {
      expect(isSupportedChartKind(k)).toBe(false);
    }
  });
});

describe("buildChartOption — bar", () => {
  test("emits category xAxis + one series per measure", () => {
    const opt = buildChartOption(sampleResponse(), "bar") as Record<string, unknown>;
    expect(opt).not.toBeNull();
    const xAxis = opt.xAxis as { data: string[] };
    expect(xAxis.data).toEqual(["1997", "1998"]);
    const series = opt.series as Array<{ name: string; type: string; data: (number | null)[] }>;
    expect(series).toHaveLength(2);
    expect(series[0].name).toBe("Store Sales");
    expect(series[0].type).toBe("bar");
    expect(series[0].data).toEqual([565238.13, 612482.65]);
    expect(series[1].name).toBe("Unit Sales");
    expect(series[1].data).toEqual([266773, 282417]);
  });

  test("stackedBar adds stack key to every series", () => {
    const opt = buildChartOption(sampleResponse(), "stackedBar") as Record<string, unknown>;
    const series = opt.series as Array<{ stack?: string }>;
    expect(series.every((s) => s.stack === "total")).toBe(true);
  });
});

describe("buildChartOption — line / area", () => {
  test("line uses series type=line, no areaStyle", () => {
    const opt = buildChartOption(sampleResponse(), "line") as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; areaStyle?: object }>;
    expect(series[0].type).toBe("line");
    expect(series[0].areaStyle).toBeUndefined();
  });

  test("area adds areaStyle to each series", () => {
    const opt = buildChartOption(sampleResponse(), "area") as Record<string, unknown>;
    const series = opt.series as Array<{ areaStyle?: object }>;
    expect(series[0].areaStyle).toBeDefined();
  });
});

describe("buildChartOption — pie / donut (small multiples)", () => {
  test("2-measure pie fans out into 2 series, each sliced by row category", () => {
    const opt = buildChartOption(sampleResponse(), "pie") as Record<string, unknown>;
    const series = opt.series as Array<{
      type: string;
      name: string;
      radius: unknown;
      data: { name: string; value: number }[];
    }>;
    // One chart (series) per measure.
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "pie")).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["Store Sales", "Unit Sales"]);
    // Each chart's slices are the ROW categories, sized by that one measure.
    expect(series[0].data.map((d) => d.name)).toEqual(["1997", "1998"]);
    expect(series[0].data[0].value).toBeCloseTo(565238.13, 2);
    expect(series[0].data[1].value).toBeCloseTo(612482.65, 2);
    expect(series[1].data.map((d) => d.name)).toEqual(["1997", "1998"]);
    expect(series[1].data[0].value).toBeCloseTo(266773, 2);
    expect(series[1].data[1].value).toBeCloseTo(282417, 2);
    // One per-measure title per chart.
    const titles = opt.title as Array<{ text: string }>;
    expect(titles.map((tt) => tt.text)).toEqual(["Store Sales", "Unit Sales"]);
  });

  test("1-measure pie renders a single chart (the M=1 case)", () => {
    const single: AiQueryResponse = {
      ...sampleResponse(),
      data: [
        { Year: "1997", "Store Sales": { value: 565238.13, formatted: "565,238.13" } },
        { Year: "1998", "Store Sales": { value: 612482.65, formatted: "612,482.65" } },
      ],
    };
    const opt = buildChartOption(single, "pie") as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(1);
    expect(series[0].data.map((d) => d.name)).toEqual(["1997", "1998"]);
    const titles = opt.title as Array<{ text: string }>;
    expect(titles).toHaveLength(1);
    expect(titles[0].text).toBe("Store Sales");
  });

  test("donut uses a ring radius (inner hole) per series", () => {
    const opt = buildChartOption(sampleResponse(), "donut") as Record<string, unknown>;
    const series = opt.series as Array<{ radius: (string | number)[] }>;
    expect(series).toHaveLength(2);
    // Donut keeps a non-zero inner radius.
    expect(Array.isArray(series[0].radius)).toBe(true);
    expect(parseFloat(String(series[0].radius[0]))).toBeGreaterThan(0);
  });
});

describe("buildChartOption — extended types", () => {
  test("treemap fans out into one series per measure, items = rows", () => {
    const opt = buildChartOption(sampleResponse(), "treemap") as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; name: string; data: { name: string; value: number }[] }>;
    expect(series).toHaveLength(2);
    expect(series.every((s) => s.type === "treemap")).toBe(true);
    expect(series.map((s) => s.name)).toEqual(["Store Sales", "Unit Sales"]);
    // Each chart's items are the ROW categories, sized by that one measure.
    expect(series[0].data[0].name).toBe("1997");
    expect(series[0].data[0].value).toBeCloseTo(565238.13, 2);
    expect(series[1].data[0].value).toBeCloseTo(266773, 2);
  });

  test("heatmap emits [col, row, value] tuples with a visualMap", () => {
    const opt = buildChartOption(sampleResponse(), "heatmap") as Record<string, unknown>;
    const series = opt.series as Array<{ type: string; data: [number, number, number][] }>;
    expect(series[0].type).toBe("heatmap");
    // 2 rows × 2 cols = 4 cells.
    expect(series[0].data).toHaveLength(4);
    expect(opt.visualMap).toBeDefined();
  });

  test("radar uses cols as indicators + rows as series entries", () => {
    const opt = buildChartOption(sampleResponse(), "radar") as Record<string, unknown>;
    const radar = opt.radar as { indicator: { name: string }[] };
    expect(radar.indicator.map((i) => i.name)).toEqual(["Store Sales", "Unit Sales"]);
    const series = opt.series as Array<{ data: { name: string; value: number[] }[] }>;
    expect(series[0].data).toHaveLength(2);
  });

  test("waterfall emits three stacked series (spacer + pos + neg)", () => {
    const opt = buildChartOption(sampleResponse(), "waterfall") as Record<string, unknown>;
    const series = opt.series as Array<{ stack: string }>;
    expect(series).toHaveLength(3);
    expect(series.every((s) => s.stack === "waterfall")).toBe(true);
  });
});

describe("buildChartOption — theme tokens (#1050 repaint)", () => {
  const darkTokens = {
    fg: "#e5e7eb",
    fgMuted: "#9ca3af",
    bg: "#0b1220",
    bgMuted: "#111827",
    border: "#334155",
    accent: "#818cf8",
    chartColors: ["#aaa111", "#bbb222"],
  };

  test("bar threads the categorical palette + text/axis colours from tk", () => {
    const opt = buildChartOption(sampleResponse(), "bar", 1, darkTokens) as Record<string, unknown>;
    // common: transparent canvas + palette + default text fg.
    expect(opt.backgroundColor).toBe("transparent");
    expect(opt.color).toEqual(darkTokens.chartColors);
    expect((opt.textStyle as { color: string }).color).toBe("#e5e7eb");
    // axis labels follow fgMuted; gridlines follow border.
    const xAxis = opt.xAxis as { axisLabel: { color: string }; axisLine: { lineStyle: { color: string } } };
    expect(xAxis.axisLabel.color).toBe("#9ca3af");
    expect(xAxis.axisLine.lineStyle.color).toBe("#334155");
    // tooltip surface follows bg/border/fg.
    const tooltip = opt.tooltip as { backgroundColor: string; textStyle: { color: string } };
    expect(tooltip.backgroundColor).toBe("#0b1220");
    expect(tooltip.textStyle.color).toBe("#e5e7eb");
  });

  test("pie slice labels follow fg (single chart) and titles follow fg", () => {
    const single: AiQueryResponse = {
      ...sampleResponse(),
      data: [
        { Year: "1997", "Store Sales": { value: 1, formatted: "1" } },
        { Year: "1998", "Store Sales": { value: 2, formatted: "2" } },
      ],
    };
    const opt = buildChartOption(single, "pie", 1, darkTokens) as Record<string, unknown>;
    const series = opt.series as Array<{ label: { position: string; color: string } }>;
    // M=1 → single chart → labels drawn outside in fg (not the inside "#fff").
    expect(series[0].label.position).toBe("outside");
    expect(series[0].label.color).toBe("#e5e7eb");
    const titles = opt.title as Array<{ textStyle: { color: string } }>;
    expect(titles[0].textStyle.color).toBe("#e5e7eb");
  });

  test("defaults to the light fallback palette when tk is omitted", () => {
    const opt = buildChartOption(sampleResponse(), "bar") as Record<string, unknown>;
    // Light default fg (#0f172a) — proves the pure callers still get a complete,
    // deterministic token set with no DOM.
    expect((opt.textStyle as { color: string }).color).toBe("#0f172a");
    expect(opt.backgroundColor).toBe("transparent");
  });
});

describe("buildChartOption — per-tile options (#1077)", () => {
  test("omitting options keeps the legacy baseline (single y-axis, bottom legend)", () => {
    const opt = buildChartOption(sampleResponse(), "bar") as Record<string, unknown>;
    // Baseline dualAxis=false → single y-axis object (not an array).
    expect(Array.isArray(opt.yAxis)).toBe(false);
    const legend = opt.legend as { type: string; bottom: number };
    expect(legend.type).toBe("scroll");
    expect(legend.bottom).toBe(0);
  });

  test("passing options enables tile dual-axis + a user title", () => {
    const options = {
      ...DEFAULT_CHART_OPTIONS,
      title: "My tile",
      dualAxis: true,
      seriesAxis: { "Unit Sales": "right" as const },
    };
    const opt = buildChartOption(sampleResponse(), "bar", 1, undefined, options) as Record<string, unknown>;
    expect(Array.isArray(opt.yAxis)).toBe(true);
    const series = opt.series as Array<{ name: string; yAxisIndex: number }>;
    expect(series.find((s) => s.name === "Unit Sales")?.yAxisIndex).toBe(1);
    // Cartesian (non-pie) chart → single title object carrying the user title.
    expect((opt.title as { text: string }).text).toBe("My tile");
  });

  test("passing options with a trend line adds a trend series on a line tile", () => {
    const options = { ...DEFAULT_CHART_OPTIONS, dualAxis: false, trendLine: "linear" as const };
    const opt = buildChartOption(sampleResponse(), "line", 1, undefined, options) as Record<string, unknown>;
    const series = opt.series as Array<{ name: string }>;
    // 2 measure series + 1 trend on the first measure.
    expect(series.length).toBe(3);
    expect(series[series.length - 1].name).toContain("(trend)");
  });
});

describe("buildChartOption — rejection", () => {
  test("returns null for unknown chart kinds", () => {
    expect(buildChartOption(sampleResponse(), "made-up-kind")).toBeNull();
    expect(buildChartOption(sampleResponse(), "")).toBeNull();
  });

  test("returns null when there's no data", () => {
    const empty: AiQueryResponse = { ...sampleResponse(), data: [] };
    expect(buildChartOption(empty, "bar")).toBeNull();
  });
});

/* ====================================================================
 * saiku#1797 — sortDirection / topN / hideRollupRows on TILES.
 *
 * These three ChartOptions fields were applied only by the workspace
 * (ChartView.svelte); the tile adapter went straight from the raw projection
 * to the builder, so the editor wrote them, the docs promised them, and the
 * tile silently rendered the query's natural order at full length.
 * ==================================================================== */

/** Five cities, deliberately unsorted, one measure. */
function citiesResponse(): AiQueryResponse {
  const row = (city: string, sqft: number) => ({
    "Store City": city,
    "Store Sqft": { value: sqft, formatted: String(sqft) },
  });
  return {
    queryId: "q2",
    status: "SUCCESS",
    format: "records",
    metadata: {
      rows: [],
      columns: [{ name: "Store Sqft", caption: "Store Sqft" }],
      measures: ["Store Sqft"],
    },
    data: [
      row("Vancouver", 23112),
      row("Hidalgo", 68966),
      row("Victoria", 34452),
      row("Salem", 27694),
      row("Bremerton", 39696),
    ],
    totalRows: 5,
  };
}

/** Two-level row axis: the rollup rows carry an empty deeper header cell,
 *  exactly as /ai/query serialises them in `records` format. */
function stateAndStoreResponse(): AiQueryResponse {
  const row = (state: string, store: string, sqft: number) => ({
    "Store State": state,
    "Store Name": store,
    "Store Sqft": { value: sqft, formatted: String(sqft) },
  });
  return {
    queryId: "q3",
    status: "SUCCESS",
    format: "records",
    metadata: {
      rows: [],
      columns: [{ name: "Store Sqft", caption: "Store Sqft" }],
      measures: ["Store Sqft"],
    },
    data: [
      row("BC", "", 57564), // rollup
      row("BC", "Store 19", 23112),
      row("BC", "Store 20", 34452),
      row("DF", "", 36509), // rollup
      row("DF", "Store 9", 36509),
    ],
    totalRows: 5,
  };
}

function categoriesOf(opt: Record<string, unknown>): string[] {
  const axis = opt.xAxis as { data?: string[] } | Array<{ data?: string[] } | undefined>;
  const first = Array.isArray(axis) ? axis[0] : axis;
  return first?.data ?? [];
}

describe("projectForChart — saiku#1797", () => {
  test("sortDirection desc orders the categories by the first measure", () => {
    const p = projectForChart(citiesResponse(), {
      ...DEFAULT_CHART_OPTIONS,
      sortDirection: "desc",
    });
    expect(p.rowCategories).toEqual(["Hidalgo", "Bremerton", "Victoria", "Salem", "Vancouver"]);
    expect(p.matrix[0][0]).toBe(68966);
  });

  test("topN trims to the leading N after sorting", () => {
    const p = projectForChart(citiesResponse(), {
      ...DEFAULT_CHART_OPTIONS,
      sortDirection: "desc",
      topN: 3,
    });
    expect(p.rowCategories).toEqual(["Hidalgo", "Bremerton", "Victoria"]);
    expect(p.matrix.length).toBe(3);
  });

  test("hideRollupRows drops the partial-header rows of a multi-level axis", () => {
    const p = projectForChart(stateAndStoreResponse(), DEFAULT_CHART_OPTIONS);
    expect(p.rowCategories).toEqual(["BC / Store 19", "BC / Store 20", "DF / Store 9"]);
  });

  test("hideRollupRows off keeps every row", () => {
    const p = projectForChart(stateAndStoreResponse(), {
      ...DEFAULT_CHART_OPTIONS,
      hideRollupRows: false,
    });
    expect(p.rowCategories.length).toBe(5);
  });

  test("a single-level axis is untouched by the rollup filter", () => {
    const p = projectForChart(citiesResponse(), DEFAULT_CHART_OPTIONS);
    expect(p.rowCategories.length).toBe(5);
  });

  test("an all-rollup result is left alone rather than emptied", () => {
    // Every row partial (no leaves at all) — dropping them would blank the tile,
    // so the filter stands down exactly as the workspace's does.
    const allRollups: AiQueryResponse = {
      ...stateAndStoreResponse(),
      data: (stateAndStoreResponse().data ?? []).filter((r) => r["Store Name"] === ""),
    };
    const p = projectForChart(allRollups, DEFAULT_CHART_OPTIONS);
    expect(p.rowCategories.length).toBe(2);
  });

  test("no options = the raw projection (legacy tiles unchanged)", () => {
    const p = projectForChart(stateAndStoreResponse());
    expect(p.rowCategories.length).toBe(5);
  });
});

describe("buildChartOption — saiku#1797 wiring", () => {
  test("the built option carries the sorted, trimmed categories", () => {
    const opt = buildChartOption(citiesResponse(), "bar", 1, undefined, {
      ...DEFAULT_CHART_OPTIONS,
      sortDirection: "desc",
      topN: 3,
    }) as Record<string, unknown>;
    expect(categoriesOf(opt)).toEqual(["Hidalgo", "Bremerton", "Victoria"]);
  });

  test("legacy tiles with no per-tile options keep the query's order", () => {
    const opt = buildChartOption(citiesResponse(), "bar") as Record<string, unknown>;
    expect(categoriesOf(opt)).toEqual([
      "Vancouver",
      "Hidalgo",
      "Victoria",
      "Salem",
      "Bremerton",
    ]);
  });
});
