import { describe, it, expect } from "vitest";
import {
  forecastLabels,
  buildForecastSeries,
  lastFinite,
  applyForecastOverlay,
} from "./forecastOverlay";
import type { ForecastPoint } from "$lib/api/aiQuery";

const fp = (value: number, lower: number, upper: number): ForecastPoint => ({
  value,
  lower,
  upper,
  forecast: true,
});

describe("forecastLabels", () => {
  it("generates +1..+h", () => {
    expect(forecastLabels(3)).toEqual(["+1", "+2", "+3"]);
    expect(forecastLabels(0)).toEqual([]);
  });
});

describe("lastFinite", () => {
  it("finds the last finite number", () => {
    expect(lastFinite([1, 2, 3])).toBe(3);
    expect(lastFinite([1, 2, null])).toBe(2);
    expect(lastFinite([{ value: 5 }, { value: 9 }])).toBe(9);
    expect(lastFinite([null, null])).toBeNull();
    expect(lastFinite("nope")).toBeNull();
  });
});

describe("buildForecastSeries", () => {
  const pts = [fp(11, 9, 13), fp(12, 9, 15)];

  it("produces base + range + dashed line, padded under future slots", () => {
    const [base, range, line] = buildForecastSeries("Sales", "#abc", pts, 3, 10);
    // observedCount=3, horizon=2 → length 5
    expect(line.data.length).toBe(5);
    // anchor at index 2 (last observed) connects the dashed line + zero-width band
    expect(line.data[2]).toBe(10);
    expect(base.data[2]).toBe(10);
    expect(range.data[2]).toBe(0);
    // forecast slots 3,4
    expect(line.data[3]).toBe(11);
    expect(line.data[4]).toBe(12);
    expect(base.data[3]).toBe(9); // lower
    expect(range.data[3]).toBe(4); // upper-lower = 13-9
    expect(range.data[4]).toBe(6); // 15-9
    // observed slots before the anchor are empty
    expect(line.data[0]).toBeNull();
    expect(line.data[1]).toBeNull();
  });

  it("dashed line carries the series colour; band shares the stack", () => {
    const [base, range, line] = buildForecastSeries("Sales", "#ff0000", pts, 2, 8);
    expect(line.lineStyle?.type).toBe("dashed");
    expect(line.lineStyle?.color).toBe("#ff0000");
    expect(base.stack).toBe(range.stack);
    expect(range.areaStyle?.color).toBe("#ff0000");
    expect(line.name).toBe("Sales (forecast)");
  });

  it("handles a null anchor (no last observed)", () => {
    const [base, , line] = buildForecastSeries("S", "#000", [fp(1, 0, 2)], 2, null);
    expect(line.data[1]).toBeNull(); // anchor not seeded
    expect(line.data[2]).toBe(1);
    expect(base.data[2]).toBe(0);
  });
});

describe("applyForecastOverlay", () => {
  it("extends the category axis + appends overlay series for matching measures", () => {
    const option: Record<string, unknown> = {
      xAxis: { type: "category", data: ["Jan", "Feb", "Mar"] },
      series: [{ name: "Sales", type: "bar", data: [1, 2, 3], itemStyle: { color: "#123" } }],
    };
    const applied = applyForecastOverlay(option, { Sales: [fp(4, 3, 5), fp(5, 3, 7)] }, "#999");
    expect(applied).toBe(1);
    expect((option.xAxis as { data: string[] }).data).toEqual(["Jan", "Feb", "Mar", "+1", "+2"]);
    // original series + 3 overlay series (base, range, dashed line)
    expect((option.series as unknown[]).length).toBe(4);
  });

  it("is a no-op when no series matches the forecast block", () => {
    const option: Record<string, unknown> = {
      xAxis: { type: "category", data: ["a", "b"] },
      series: [{ name: "Other", type: "line", data: [1, 2] }],
    };
    const applied = applyForecastOverlay(option, { Sales: [fp(3, 2, 4)] }, "#999");
    expect(applied).toBe(0);
    expect((option.series as unknown[]).length).toBe(1);
    expect((option.xAxis as { data: string[] }).data).toEqual(["a", "b"]);
  });

  it("is a no-op with an empty block or no category axis", () => {
    expect(applyForecastOverlay({ series: [] }, {}, "#999")).toBe(0);
    expect(applyForecastOverlay({ series: [{ name: "S", data: [1] }] }, { S: [fp(1, 0, 2)] }, "#999")).toBe(0);
  });
});
