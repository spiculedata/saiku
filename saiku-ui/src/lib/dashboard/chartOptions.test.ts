/*
 * Unit tests for the AiQueryResponse → ECharts option builder.
 * Covers v1 supported chart kinds + the rejection branch.
 */

import { describe, test, expect } from "vitest";
import { buildChartOption, isSupportedChartKind } from "$lib/dashboard/chartOptions";
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
