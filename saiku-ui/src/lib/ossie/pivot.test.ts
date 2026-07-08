import { describe, expect, test } from "vitest";
import { pivotResult } from "./pivot";
import { projectWireToOssieResult } from "$lib/api/ossie";
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

describe("full projection → pivot chain (regression for the empty-body bug)", () => {
  test("wire envelope with 10 body rows survives projection and pivots correctly", () => {
    // Exact wire shape the server produces for a 2-row × 1-col × 1-value shelf state.
    // Header row cells all typed COLUMN_HEADER; body row cells mix ROW_HEADER (dim
    // cells from the flat rowset's row-shelf prefix + column-shelf value) + DATA_CELL
    // for the metric.
    const wire = {
      runtime: 42,
      width: 4,
      height: 10,
      cellset: [
        [
          { value: "product.brand", type: "COLUMN_HEADER" as const },
          { value: "product.molecule", type: "COLUMN_HEADER" as const },
          { value: "geography.region", type: "COLUMN_HEADER" as const },
          { value: "net_revenue", type: "COLUMN_HEADER" as const },
        ],
        ...(
          [
            ["Alfa", "atorvastatin", "Midwest", 590.75],
            ["Alfa", "atorvastatin", "Northeast", 250.0],
            ["Alfa", "atorvastatin", "South", 210.0],
            ["Beta", "metformin", "Northeast", 120.5],
            ["Beta", "metformin", "South", 95.5],
            ["Beta", "metformin", "West", 495.75],
            ["Gamma", "sertraline", "Midwest", 85.0],
            ["Gamma", "sertraline", "Northeast", 175.0],
            ["Gamma", "sertraline", "South", 220.5],
            ["Gamma", "sertraline", "West", 60.25],
          ] as Array<Array<string | number>>
        ).map((row) => [
          { value: String(row[0]), type: "ROW_HEADER" as const },
          { value: String(row[1]), type: "ROW_HEADER" as const },
          { value: String(row[2]), type: "ROW_HEADER" as const },
          {
            value: (row[3] as number).toFixed(2),
            type: "DATA_CELL" as const,
            properties: { raw: String(row[3]) },
          },
        ]),
      ],
    };
    const projected = projectWireToOssieResult(wire);
    expect(projected.cellSetBody.length).toBe(10);
    const grid = pivotResult(
      ["product.brand", "product.molecule"],
      ["geography.region"],
      ["net_revenue"],
      projected,
    );
    expect(grid).not.toBeNull();
    expect(grid!.bodyRows.length).toBe(3);
    // Beta has no Midwest row; that cell must stay empty.
    expect(grid!.bodyRows[1][2].formatted).toBe("");
  });
});

describe("live-shape scenario (regression for 2-row × 1-col × 1-value crosstab)", () => {
  test("pivots 10 body rows with 2 row shelves + 1 column shelf", () => {
    // Simulates the exact scenario that renders 'Query returned no rows' in the browser:
    // Rows=[product.brand, product.molecule], Columns=[geography.region], Values=[net_revenue].
    // The server responds with 10 flat rows; the pivot must produce a 3×4 crosstab.
    const r = makeResult([
      ["Alfa", "atorvastatin", "Midwest", 590.75],
      ["Alfa", "atorvastatin", "Northeast", 250.0],
      ["Alfa", "atorvastatin", "South", 210.0],
      ["Beta", "metformin", "Northeast", 120.5],
      ["Beta", "metformin", "South", 95.5],
      ["Beta", "metformin", "West", 495.75],
      ["Gamma", "sertraline", "Midwest", 85.0],
      ["Gamma", "sertraline", "Northeast", 175.0],
      ["Gamma", "sertraline", "South", 220.5],
      ["Gamma", "sertraline", "West", 60.25],
    ]);
    const grid = pivotResult(
      ["product.brand", "product.molecule"],
      ["geography.region"],
      ["net_revenue"],
      r,
    );
    expect(grid).not.toBeNull();
    expect(grid!.rowHeaderCount).toBe(2);
    expect(grid!.bodyRows.length).toBe(3);
    expect(grid!.bodyRows[0][0].formatted).toBe("Alfa");
    expect(grid!.bodyRows[0][1].formatted).toBe("atorvastatin");
    expect(grid!.headerRows[0].slice(2).map((c) => c.formatted)).toEqual([
      "Midwest",
      "Northeast",
      "South",
      "West",
    ]);
    // Alfa/atorvastatin has values for Midwest / Northeast / South but not West.
    expect(grid!.bodyRows[0].slice(2).map((c) => c.formatted)).toEqual([
      "590.75",
      "250.00",
      "210.00",
      "",
    ]);
  });
});

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

  test("exposes column-key values so callers can resolve body cells → col-shelf values", () => {
    const r = makeResult([
      ["Alfa", "Commercial", 250],
      ["Alfa", "Medicare", 400],
      ["Beta", "Commercial", 155],
    ]);
    const grid = pivotResult(["product.brand"], ["payer.channel"], ["net_revenue"], r)!;
    // colKeyValues array — one entry per unique column-key, each an array with one string
    // per Columns shelf entry (nCol == 1 here).
    expect(grid.colKeyValues).toEqual([["Commercial"], ["Medicare"]]);
  });

  test("multi-level columns expose values-per-level in colKeyValues", () => {
    const r = makeResult([
      ["Alfa", "Commercial", "2024", 100],
      ["Alfa", "Commercial", "2025", 200],
      ["Alfa", "Medicare", "2024", 300],
    ]);
    const grid = pivotResult(
      ["product.brand"],
      ["payer.channel", "date.year"],
      ["net_revenue"],
      r,
    )!;
    // Each entry has two levels; sorted by JSON.stringify(key) so the pair sequence is
    // Commercial-2024, Commercial-2025, Medicare-2024.
    expect(grid.colKeyValues).toEqual([
      ["Commercial", "2024"],
      ["Commercial", "2025"],
      ["Medicare", "2024"],
    ]);
  });
});
