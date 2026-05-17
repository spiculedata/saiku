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
  test("accepts the v1 set", () => {
    for (const k of ["bar", "stackedBar", "line", "stackedLine", "area", "stackedArea", "pie", "donut"]) {
      expect(isSupportedChartKind(k)).toBe(true);
    }
  });
  test("rejects unsupported types", () => {
    for (const k of ["heatmap", "radar", "scatter", "treemap", "totally-made-up"]) {
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

describe("buildChartOption — pie / donut", () => {
  test("pie has one slice per row using the first measure", () => {
    const opt = buildChartOption(sampleResponse(), "pie") as Record<string, unknown>;
    const series = opt.series as Array<{
      type: string;
      radius: unknown;
      data: { name: string; value: number }[];
    }>;
    expect(series[0].type).toBe("pie");
    expect(series[0].data).toEqual([
      { name: "1997", value: 565238.13 },
      { name: "1998", value: 612482.65 },
    ]);
  });

  test("donut uses a ring radius", () => {
    const opt = buildChartOption(sampleResponse(), "donut") as Record<string, unknown>;
    const series = opt.series as Array<{ radius: string[] }>;
    expect(Array.isArray(series[0].radius)).toBe(true);
  });
});

describe("buildChartOption — rejection", () => {
  test("returns null for unsupported chart kinds", () => {
    expect(buildChartOption(sampleResponse(), "treemap")).toBeNull();
    expect(buildChartOption(sampleResponse(), "made-up-kind")).toBeNull();
  });

  test("returns null when there's no data", () => {
    const empty: AiQueryResponse = { ...sampleResponse(), data: [] };
    expect(buildChartOption(empty, "bar")).toBeNull();
  });
});
