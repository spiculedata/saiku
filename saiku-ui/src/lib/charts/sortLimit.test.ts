/*
 * Unit tests for the client-side category sort + top-N transform (#1083).
 */

import { describe, test, expect } from "vitest";
import type { ChartProjection } from "$lib/charts/build";
import { applySortLimit, isNoOp, NO_SORT_LIMIT } from "$lib/charts/sortLimit";

/** Five stores, two measures. Sales (col 0) is intentionally unsorted. */
function sample(): ChartProjection {
  return {
    rowCategories: ["A", "B", "C", "D", "E"],
    columnCategories: ["Sales", "Units"],
    matrix: [
      [30, 1],
      [10, 2],
      [50, 3],
      [20, 4],
      [40, 5],
    ],
  };
}

describe("applySortLimit", () => {
  test("no-op when off: same order, fresh object, input not mutated", () => {
    const p = sample();
    const out = applySortLimit(p, NO_SORT_LIMIT);
    expect(out.rowCategories).toEqual(["A", "B", "C", "D", "E"]);
    expect(out.matrix).toEqual(p.matrix);
    expect(out).not.toBe(p); // new object
    expect(p.rowCategories).toEqual(["A", "B", "C", "D", "E"]); // unmutated
  });

  test("defaults to no-op when called with no state", () => {
    const out = applySortLimit(sample());
    expect(out.rowCategories).toEqual(["A", "B", "C", "D", "E"]);
  });

  test("ascending by the first measure", () => {
    const out = applySortLimit(sample(), { direction: "asc" });
    expect(out.rowCategories).toEqual(["B", "D", "A", "E", "C"]); // 10,20,30,40,50
    expect(out.matrix[0]).toEqual([10, 2]); // moved in lockstep with the label
  });

  test("descending by the first measure", () => {
    const out = applySortLimit(sample(), { direction: "desc" });
    expect(out.rowCategories).toEqual(["C", "E", "A", "D", "B"]); // 50,40,30,20,10
  });

  test("sort is stable: equal measure values keep their queried order", () => {
    const p: ChartProjection = {
      rowCategories: ["x1", "x2", "y", "x3"],
      columnCategories: ["m"],
      matrix: [[5], [5], [9], [5]],
    };
    const desc = applySortLimit(p, { direction: "desc" });
    expect(desc.rowCategories).toEqual(["y", "x1", "x2", "x3"]); // 9 first, ties stable
    const asc = applySortLimit(p, { direction: "asc" });
    expect(asc.rowCategories).toEqual(["x1", "x2", "x3", "y"]); // ties stable, 9 last
  });

  test("measureIndex selects the column to sort by", () => {
    // Units (col 1) ascends 1,2,3,4,5 = original order; descends reverse.
    const out = applySortLimit(sample(), { direction: "desc", measureIndex: 1 });
    expect(out.rowCategories).toEqual(["E", "D", "C", "B", "A"]);
  });

  test("out-of-range measureIndex clamps into the column range", () => {
    // 99 clamps to the last column (Units, col 1); desc on 1..5 = reverse order.
    const out = applySortLimit(sample(), { direction: "desc", measureIndex: 99 });
    expect(out.rowCategories).toEqual(["E", "D", "C", "B", "A"]);
    // negative clamps to the first column (Sales, col 0).
    const neg = applySortLimit(sample(), { direction: "desc", measureIndex: -5 });
    expect(neg.rowCategories).toEqual(["C", "E", "A", "D", "B"]);
  });

  test("top-N keeps the highest N when descending", () => {
    const out = applySortLimit(sample(), { direction: "desc", topN: 2 });
    expect(out.rowCategories).toEqual(["C", "E"]); // 50, 40
    expect(out.matrix).toEqual([
      [50, 3],
      [40, 5],
    ]);
  });

  test("top-N keeps the lowest N when ascending", () => {
    const out = applySortLimit(sample(), { direction: "asc", topN: 2 });
    expect(out.rowCategories).toEqual(["B", "D"]); // 10, 20
  });

  test("top-N with sort off keeps the first N as queried", () => {
    const out = applySortLimit(sample(), { direction: "none", topN: 3 });
    expect(out.rowCategories).toEqual(["A", "B", "C"]);
  });

  test("top-N clamps: N >= rowCount returns all rows", () => {
    expect(applySortLimit(sample(), { direction: "none", topN: 99 }).rowCategories).toEqual([
      "A",
      "B",
      "C",
      "D",
      "E",
    ]);
  });

  test("top-N <= 0 and null are treated as no limit", () => {
    expect(applySortLimit(sample(), { direction: "none", topN: 0 }).rowCategories.length).toBe(5);
    expect(applySortLimit(sample(), { direction: "none", topN: -3 }).rowCategories.length).toBe(5);
    expect(applySortLimit(sample(), { direction: "none", topN: null }).rowCategories.length).toBe(5);
  });

  test("null / NaN measure values sort to the end in both directions", () => {
    const p: ChartProjection = {
      rowCategories: ["a", "b", "c", "d"],
      columnCategories: ["m"],
      matrix: [[5], [null], [9], [NaN]],
    };
    const desc = applySortLimit(p, { direction: "desc" });
    expect(desc.rowCategories).toEqual(["c", "a", "b", "d"]); // 9,5 then missing (stable)
    const asc = applySortLimit(p, { direction: "asc" });
    expect(asc.rowCategories).toEqual(["a", "c", "b", "d"]); // 5,9 then missing (stable)
  });

  test("empty projection is handled", () => {
    const empty: ChartProjection = { rowCategories: [], columnCategories: [], matrix: [] };
    const out = applySortLimit(empty, { direction: "desc", topN: 5 });
    expect(out.rowCategories).toEqual([]);
    expect(out.matrix).toEqual([]);
  });
});

describe("isNoOp", () => {
  test("off + no limit is a no-op", () => {
    expect(isNoOp(NO_SORT_LIMIT, 5)).toBe(true);
    expect(isNoOp({ direction: "none", topN: null }, 5)).toBe(true);
  });

  test("a sort direction is not a no-op", () => {
    expect(isNoOp({ direction: "asc" }, 5)).toBe(false);
  });

  test("an effective top-N limit is not a no-op", () => {
    expect(isNoOp({ direction: "none", topN: 3 }, 5)).toBe(false);
  });

  test("a top-N >= rowCount is a no-op (nothing trimmed)", () => {
    expect(isNoOp({ direction: "none", topN: 5 }, 5)).toBe(true);
    expect(isNoOp({ direction: "none", topN: 99 }, 5)).toBe(true);
  });
});
