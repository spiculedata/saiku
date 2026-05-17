/*
 * Unit tests for the tile-placement helpers. Covers:
 *   - empty layout drops at (0, 0)
 *   - second tile flows right when there's space
 *   - second tile flows to the next row when the first row is full
 *   - oversize tile falls back to a full row at the bottom
 *   - defaultSizeFor covers all four tile types
 */

import { describe, test, expect } from "vitest";
import { buildTile, defaultSizeFor, firstFreeSlot } from "$lib/dashboard/tilePlacement";
import type { DashboardLayout } from "$lib/api/dashboards";

function emptyLayout(cols = 12): DashboardLayout {
  return { cols, tiles: [] };
}

describe("defaultSizeFor", () => {
  test("each tile type has a non-zero default", () => {
    for (const t of ["chart", "table", "text", "filter"] as const) {
      const s = defaultSizeFor(t);
      expect(s.w).toBeGreaterThan(0);
      expect(s.h).toBeGreaterThan(0);
      expect(s.w).toBeLessThanOrEqual(12);
    }
  });
});

describe("firstFreeSlot", () => {
  test("empty layout drops at (0, 0)", () => {
    expect(firstFreeSlot(emptyLayout(), 6, 4)).toEqual({ x: 0, y: 0 });
  });

  test("flows to the right when there's room", () => {
    const layout: DashboardLayout = {
      cols: 12,
      tiles: [{ id: "a", x: 0, y: 0, w: 6, h: 4, type: "chart" }],
    };
    expect(firstFreeSlot(layout, 6, 4)).toEqual({ x: 6, y: 0 });
  });

  test("flows to the next row when the first row is full", () => {
    const layout: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 6, h: 4, type: "chart" },
        { id: "b", x: 6, y: 0, w: 6, h: 4, type: "table" },
      ],
    };
    // Next 6×4 must land below; rows 0–3 are full.
    const slot = firstFreeSlot(layout, 6, 4);
    expect(slot.y).toBeGreaterThanOrEqual(4);
  });

  test("oversize tile clamps to a full row at the bottom", () => {
    const layout: DashboardLayout = {
      cols: 12,
      tiles: [{ id: "a", x: 0, y: 0, w: 12, h: 3, type: "filter" }],
    };
    // A 14-wide tile can't fit anywhere; clamp behaviour returns x=0
    // at the first y past the existing content.
    const slot = firstFreeSlot(layout, 14, 1);
    expect(slot.x).toBe(0);
  });

  test("finds gaps left between two non-contiguous tiles", () => {
    const layout: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 4, h: 2, type: "chart" },
        // gap (4-11, 0-1) is free
        { id: "b", x: 0, y: 2, w: 12, h: 2, type: "table" },
      ],
    };
    // A 6×2 should land in the gap (x=4, y=0), not below b.
    const slot = firstFreeSlot(layout, 6, 2);
    expect(slot).toEqual({ x: 4, y: 0 });
  });
});

describe("buildTile", () => {
  test("composes a full DashboardTile with placement + id", () => {
    const tile = buildTile(emptyLayout(), "chart", "abc-123");
    expect(tile.id).toBe("abc-123");
    expect(tile.type).toBe("chart");
    expect(tile.x).toBe(0);
    expect(tile.y).toBe(0);
    expect(tile.w).toBe(6);
    expect(tile.h).toBe(4);
  });

  test("uses default sizes for each type", () => {
    const filter = buildTile(emptyLayout(), "filter", "f");
    expect(filter.w).toBe(12);
    expect(filter.h).toBe(1);
    const text = buildTile(emptyLayout(), "text", "t");
    expect(text.w).toBe(6);
    expect(text.h).toBe(2);
  });
});
