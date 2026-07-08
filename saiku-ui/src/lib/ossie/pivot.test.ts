import { describe, expect, test } from "vitest";
import { pivotResult } from "./pivot";
import type { OssieQueryResult } from "$lib/api/ossie";

/**
 * Tests for the client-side crosstab pivot. Roland Bouman's Huey uses a similar approach
 * (client-side reshape of a flat GROUP BY rowset), so the invariants below match what
 * both tools expect: long-form → grid, missing intersections stay blank, multi-value
 * shelves get their own metric-name header row, colspans line up on multi-level columns.
 */

function makeCell(v: string | number): { formattedValue: string; rawValue: string; rawNumber?: number } {
  if (typeof v === "number") {
    return { formattedValue: v.toFixed(2), rawValue: v.toString(), rawNumber: v };
  }
  return { formattedValue: v, rawValue: v };
}

function makeResult(rows: Array<Array<string | number>>): OssieQueryResult {
  return {
    cellSetHeaders: [],
    cellSetBody: rows.map((r) => r.map(makeCell)),
    width: rows[0]?.length ?? 0,
    height: rows.length,
  };
}

describe("pivotResult", () => {
  test("returns null when there are no Columns shelf entries", () => {
    const r = makeResult([["Alfa", 100]]);
    expect(pivotResult(["product.brand"], [], ["net_revenue"], r)).toBeNull();
  });

  test("basic 1 row × 1 col × 1 value crosstab", () => {
    const r = makeResult([
      ["Alfa", "Commercial", 250],
      ["Alfa", "Medicare", 400],
      ["Beta", "Commercial", 155],
      ["Beta", "Medicare", 556],
    ]);
    const grid = pivotResult(["product.brand"], ["payer.channel"], ["net_revenue"], r);
    expect(grid).not.toBeNull();
    // Header: one row with row-shelf label + column-value labels.
    expect(grid!.headerRows.length).toBe(1);
    expect(grid!.headerRows[0].map((c) => c.formatted)).toEqual(["product.brand", "Commercial", "Medicare"]);
    // Body: two rows, one per brand.
    expect(grid!.bodyRows.length).toBe(2);
    expect(grid!.bodyRows[0].map((c) => c.formatted)).toEqual(["Alfa", "250.00", "400.00"]);
    expect(grid!.bodyRows[1].map((c) => c.formatted)).toEqual(["Beta", "155.00", "556.00"]);
    // Metric cells carry rawNumber for the right-align CSS.
    expect(grid!.bodyRows[0][1].isNumeric).toBe(true);
    expect(grid!.bodyRows[0][1].rawNumber).toBe(250);
  });

  test("missing intersections render as empty cells", () => {
    // Alfa only has Commercial; Beta only has Medicare — the cross-cells stay blank.
    const r = makeResult([
      ["Alfa", "Commercial", 250],
      ["Beta", "Medicare", 556],
    ]);
    const grid = pivotResult(["product.brand"], ["payer.channel"], ["net_revenue"], r)!;
    expect(grid.bodyRows[0].map((c) => c.formatted)).toEqual(["Alfa", "250.00", ""]);
    expect(grid.bodyRows[1].map((c) => c.formatted)).toEqual(["Beta", "", "556.00"]);
  });

  test("multi-value shelves get a metric-name header row + colspans on column labels", () => {
    const r = makeResult([
      ["Alfa", "Commercial", 250, 5],
      ["Alfa", "Medicare", 400, 8],
      ["Beta", "Commercial", 155, 3],
    ]);
    const grid = pivotResult(
      ["product.brand"],
      ["payer.channel"],
      ["net_revenue", "rx_count"],
      r,
    )!;
    // Two header rows: column-key labels (Commercial / Medicare) and metric names.
    expect(grid.headerRows.length).toBe(2);
    // Row 0: empty corner + column-key label per group (colspan=2 for the two metrics).
    expect(grid.headerRows[0].map((c) => ({ text: c.formatted, span: c.colspan }))).toEqual([
      { text: "", span: undefined },
      { text: "Commercial", span: 2 },
      { text: "Medicare", span: 2 },
    ]);
    // Row 1: row-shelf label + metric names repeated per column-key.
    expect(grid.headerRows[1].map((c) => c.formatted)).toEqual([
      "product.brand",
      "net_revenue",
      "rx_count",
      "net_revenue",
      "rx_count",
    ]);
    // Body: Beta has no Medicare row so those two cells stay blank.
    expect(grid.bodyRows[1].map((c) => c.formatted)).toEqual(["Beta", "155.00", "3.00", "", ""]);
  });

  test("multi-row shelves prefix each body row with the combined key", () => {
    const r = makeResult([
      ["Alfa", "atorvastatin", "Commercial", 250],
      ["Alfa", "atorvastatin", "Medicare", 400],
      ["Beta", "metformin", "Commercial", 155],
    ]);
    const grid = pivotResult(
      ["product.brand", "product.molecule"],
      ["payer.channel"],
      ["net_revenue"],
      r,
    )!;
    expect(grid.rowHeaderCount).toBe(2);
    expect(grid.bodyRows[0].slice(0, 2).map((c) => c.formatted)).toEqual(["Alfa", "atorvastatin"]);
    expect(grid.bodyRows[1].slice(0, 2).map((c) => c.formatted)).toEqual(["Beta", "metformin"]);
    // Header row's corner labels the row-shelf columns 1:1 so the user can see
    // exactly which field each left prefix column belongs to.
    expect(grid.headerRows[0].slice(0, 2).map((c) => c.formatted)).toEqual([
      "product.brand",
      "product.molecule",
    ]);
  });

  test("empty body → empty pivot with headers only", () => {
    const grid = pivotResult(
      ["product.brand"],
      ["payer.channel"],
      ["net_revenue"],
      makeResult([]),
    )!;
    expect(grid.bodyRows).toEqual([]);
    // Headers still have the corner + row-shelf label but no column groups.
    expect(grid.headerRows[0].map((c) => c.formatted)).toEqual(["product.brand"]);
  });

  test("column keys sort lexicographically for stable presentation", () => {
    const r = makeResult([
      ["Alfa", "Medicare", 400],
      ["Alfa", "Commercial", 250],
      ["Alfa", "Medicaid", 100],
    ]);
    const grid = pivotResult(["product.brand"], ["payer.channel"], ["net_revenue"], r)!;
    // JSON.stringify-based key sort → alphabetical order of the payer.channel value.
    expect(grid.headerRows[0].slice(1).map((c) => c.formatted)).toEqual([
      "Commercial",
      "Medicaid",
      "Medicare",
    ]);
  });
});
