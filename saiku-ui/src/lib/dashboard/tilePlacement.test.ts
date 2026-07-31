/*
 * Unit tests for the tile-placement helpers. Covers:
 *   - empty layout drops at (0, 0)
 *   - second tile flows right when there's space
 *   - second tile flows to the next row when the first row is full
 *   - oversize tile falls back to a full row at the bottom
 *   - defaultSizeFor covers all four tile types
 */

import { describe, test, expect } from "vitest";
import { buildTile, defaultSizeFor, firstFreeSlot, repositionTile } from "$lib/dashboard/tilePlacement";
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

describe("repositionTile — validation", () => {
  function layout(): DashboardLayout {
    return {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 6, h: 4, type: "chart" },
        { id: "b", x: 6, y: 0, w: 6, h: 4, type: "table" },
      ],
    };
  }

  test("rejects x + w > cols", () => {
    const r = repositionTile(layout(), "a", { x: 8, y: 0, w: 6, h: 4 });
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/right edge/i);
  });

  test("rejects negative x", () => {
    expect(repositionTile(layout(), "a", { x: -1, y: 0, w: 4, h: 2 }).ok).toBe(false);
  });

  test("rejects w < 1", () => {
    expect(repositionTile(layout(), "a", { x: 0, y: 0, w: 0, h: 2 }).ok).toBe(false);
  });

  test("rejects unknown id", () => {
    const r = repositionTile(layout(), "nope", { x: 0, y: 0, w: 4, h: 2 });
    expect(r.ok).toBe(false);
    expect(r.error).toMatch(/not found/i);
  });
});

describe("repositionTile — cascade", () => {
  test("no-op move returns identical positions", () => {
    const before: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 6, h: 4, type: "chart" },
        { id: "b", x: 6, y: 0, w: 6, h: 4, type: "table" },
      ],
    };
    const r = repositionTile(before, "a", { x: 0, y: 0, w: 6, h: 4 });
    expect(r.ok).toBe(true);
    expect(r.tiles).toEqual(before.tiles);
  });

  test("widening A so it overlaps B pushes B down", () => {
    const before: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 6, h: 4, type: "chart" },
        { id: "b", x: 6, y: 0, w: 6, h: 4, type: "table" },
      ],
    };
    // Widen A to span the whole row — B now overlaps A.
    const r = repositionTile(before, "a", { x: 0, y: 0, w: 12, h: 4 });
    expect(r.ok).toBe(true);
    const b = r.tiles!.find((t) => t.id === "b")!;
    // B's top should be pushed to A.y + A.h = 4.
    expect(b.y).toBe(4);
    expect(b.x).toBe(6); // x preserved
  });

  test("cascade — moved tile pushes B which pushes C", () => {
    const before: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 6, h: 2, type: "chart" },
        { id: "b", x: 0, y: 2, w: 6, h: 2, type: "table" },
        { id: "c", x: 0, y: 4, w: 6, h: 2, type: "text" },
      ],
    };
    // Make A tall enough to swallow B's slot.
    const r = repositionTile(before, "a", { x: 0, y: 0, w: 6, h: 4 });
    expect(r.ok).toBe(true);
    const tiles = r.tiles!;
    const b = tiles.find((t) => t.id === "b")!;
    const c = tiles.find((t) => t.id === "c")!;
    // B should land at y=4 (below the resized A).
    expect(b.y).toBe(4);
    // C should be pushed below B (was overlapping after B moved).
    expect(c.y).toBeGreaterThanOrEqual(b.y + b.h);
  });

  test("moving down into empty space doesn't disturb anyone", () => {
    const before: DashboardLayout = {
      cols: 12,
      tiles: [
        { id: "a", x: 0, y: 0, w: 4, h: 2, type: "chart" },
        { id: "b", x: 8, y: 0, w: 4, h: 2, type: "table" },
      ],
    };
    const r = repositionTile(before, "a", { x: 0, y: 5, w: 4, h: 2 });
    expect(r.ok).toBe(true);
    const b = r.tiles!.find((t) => t.id === "b")!;
    expect(b.y).toBe(0); // unchanged
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
    expect(text.h).toBe(1);
  });
});
