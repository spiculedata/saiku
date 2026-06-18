import { describe, it, expect } from "vitest";
import { toGrid } from "../src/lib/resultGrid";
import type { AiQueryResponse } from "../src/lib/types";

const resp: AiQueryResponse = {
  queryId: "1", status: "SUCCESS", format: "records",
  metadata: { measures: ["Unit Sales"] },
  data: [
    { "Product Family": "Drink", "Unit Sales": { value: 24597, formatted: "24,597", unit: null } },
    { "Product Family": "Food", "Unit Sales": { value: 191940, formatted: "191,940", unit: null } }
  ]
};
describe("toGrid", () => {
  it("splits dimension (string) and measure (object) columns", () => {
    const g = toGrid(resp);
    expect(g.columns).toEqual(["Product Family", "Unit Sales"]);
    expect(g.rows).toEqual([["Drink", 24597], ["Food", 191940]]);
  });
  it("uses formatted when value is null", () => {
    const g = toGrid({ ...resp, data: [{ "Product Family": "X", "Unit Sales": { value: null, formatted: "n/a", unit: null } }] });
    expect(g.rows[0]).toEqual(["X", "n/a"]);
  });
  it("returns empty grid for no data", () => {
    expect(toGrid({ queryId: null, status: "SUCCESS", data: [] }).rows).toEqual([]);
  });
});
