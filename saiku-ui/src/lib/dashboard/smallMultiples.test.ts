/*
 * Unit tests for the small-multiples grid geometry helper.
 * Pure (vitest env=node) — no DOM, no ECharts.
 */

import { describe, test, expect } from "vitest";
import { gridCells, isSingleMeasureKind, type GridCell } from "$lib/dashboard/smallMultiples";

/** True when two cell rects overlap (touching at gutters is fine). */
function overlaps(a: GridCell, b: GridCell): boolean {
  const aRight = a.leftPct + a.widthPct;
  const aBottom = a.topPct + a.heightPct;
  const bRight = b.leftPct + b.widthPct;
  const bBottom = b.topPct + b.heightPct;
  return a.leftPct < bRight && b.leftPct < aRight && a.topPct < bBottom && b.topPct < aBottom;
}

describe("isSingleMeasureKind", () => {
  test("true for pie/donut/treemap/sunburst", () => {
    for (const k of ["pie", "donut", "treemap", "sunburst"]) {
      expect(isSingleMeasureKind(k)).toBe(true);
    }
  });
  test("false for multi-measure / unknown kinds", () => {
    for (const k of ["bar", "line", "heatmap", "radar", "scatter", "waterfall", "", "made-up"]) {
      expect(isSingleMeasureKind(k)).toBe(false);
    }
  });
});

describe("gridCells", () => {
  test("n=0 → empty", () => {
    expect(gridCells(0)).toEqual([]);
    expect(gridCells(-3)).toEqual([]);
  });

  test("n=1 → one full-box cell centered ~50/50", () => {
    const cells = gridCells(1);
    expect(cells).toHaveLength(1);
    const c = cells[0];
    expect(c.centerXPct).toBeCloseTo(50, 0);
    // Center is biased slightly below 50 to leave title headroom at the top.
    expect(c.centerYPct).toBeGreaterThan(45);
    expect(c.centerYPct).toBeLessThan(62);
  });

  test("n=2 → two non-overlapping cells (1×2 or 2×1)", () => {
    const cells = gridCells(2);
    expect(cells).toHaveLength(2);
    expect(overlaps(cells[0], cells[1])).toBe(false);
    // 2 cols × 1 row (ceil(sqrt(2)) = 2).
    expect(cells[0].row).toBe(0);
    expect(cells[1].row).toBe(0);
    expect(cells[0].col).toBe(0);
    expect(cells[1].col).toBe(1);
  });

  test("n=3 → 2×2 grid with 3 cells, all non-overlapping", () => {
    const cells = gridCells(3);
    expect(cells).toHaveLength(3);
    // cols = ceil(sqrt(3)) = 2, rows = ceil(3/2) = 2.
    expect(cells.map((c) => [c.row, c.col])).toEqual([
      [0, 0],
      [0, 1],
      [1, 0],
    ]);
    for (let i = 0; i < cells.length; i++) {
      for (let j = i + 1; j < cells.length; j++) {
        expect(overlaps(cells[i], cells[j])).toBe(false);
      }
    }
  });

  test("n=4 → 2×2 grid", () => {
    const cells = gridCells(4);
    expect(cells).toHaveLength(4);
    expect(cells.map((c) => [c.row, c.col])).toEqual([
      [0, 0],
      [0, 1],
      [1, 0],
      [1, 1],
    ]);
    for (let i = 0; i < cells.length; i++) {
      for (let j = i + 1; j < cells.length; j++) {
        expect(overlaps(cells[i], cells[j])).toBe(false);
      }
    }
  });

  test("caps at 2 columns: n=6 → 2 cols × 3 rows (not ceil(sqrt))", () => {
    const cells = gridCells(6);
    expect(cells).toHaveLength(6);
    // Two per row, three rows — NOT 3×2. Keeps each chart large.
    expect(Math.max(...cells.map((c) => c.col))).toBe(1);
    expect(Math.max(...cells.map((c) => c.row))).toBe(2);
    expect(cells.map((c) => [c.row, c.col])).toEqual([
      [0, 0],
      [0, 1],
      [1, 0],
      [1, 1],
      [2, 0],
      [2, 1],
    ]);
  });

  test("all cells stay inside the 0–100% box", () => {
    for (const n of [1, 2, 3, 4, 5, 9]) {
      for (const c of gridCells(n)) {
        expect(c.leftPct).toBeGreaterThanOrEqual(0);
        expect(c.topPct).toBeGreaterThanOrEqual(0);
        expect(c.leftPct + c.widthPct).toBeLessThanOrEqual(100);
        expect(c.topPct + c.heightPct).toBeLessThanOrEqual(100);
      }
    }
  });
});
