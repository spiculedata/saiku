import { describe, it, expect } from "vitest";
import { anomalyMarksBySeries, hasAnyAnomaly } from "./anomalyMarkPoints";
import type { AiQueryResponse, AiCell } from "$lib/api/aiQuery";

/** Build a records-format response: rows of {Month: string, <measure>: AiCell}. */
function resp(
  measure: string,
  rows: Array<{ month: string; value: number | null; anomaly?: AiCell["anomaly"] }>,
): AiQueryResponse {
  return {
    queryId: "q",
    status: "SUCCESS",
    format: "records",
    data: rows.map((r) => ({
      Month: r.month,
      [measure]: {
        value: r.value,
        formatted: r.value == null ? "" : String(r.value),
        anomaly: r.anomaly ?? null,
      } as AiCell,
    })),
  };
}

describe("anomalyMarksBySeries", () => {
  it("collects marks only on flagged cells, keyed by series index", () => {
    const r = resp("Sales", [
      { month: "Jan", value: 10 },
      { month: "Feb", value: 11 },
      {
        month: "Mar",
        value: 100,
        anomaly: { score: 4.2, expected: 10, direction: "above", anomaly: true },
      },
      { month: "Apr", value: 9 },
    ]);
    const marks = anomalyMarksBySeries(r);
    expect(marks.size).toBe(1);
    const series0 = marks.get(0)!;
    expect(series0).toHaveLength(1);
    expect(series0[0].category).toBe("Mar");
    expect(series0[0].value).toBe(100);
    expect(series0[0].series).toBe("Sales");
    expect(series0[0].anomaly.direction).toBe("above");
    expect(hasAnyAnomaly(marks)).toBe(true);
  });

  it("returns an empty map when nothing is flagged (no missing field)", () => {
    const r = resp("Sales", [
      { month: "Jan", value: 5 },
      { month: "Feb", value: 5 },
    ]);
    const marks = anomalyMarksBySeries(r);
    expect(marks.size).toBe(0);
    expect(hasAnyAnomaly(marks)).toBe(false);
  });

  it("skips flagged cells with a null value (cannot plot a coord)", () => {
    const r = resp("Sales", [
      {
        month: "Jan",
        value: null,
        anomaly: { score: 9, expected: 1, direction: "above", anomaly: true },
      },
    ]);
    expect(anomalyMarksBySeries(r).size).toBe(0);
  });

  it("handles an empty / null response", () => {
    expect(anomalyMarksBySeries(null).size).toBe(0);
    expect(
      anomalyMarksBySeries({ queryId: "q", status: "SUCCESS", data: [] }).size,
    ).toBe(0);
  });
});
