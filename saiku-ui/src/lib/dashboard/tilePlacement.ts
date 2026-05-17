/*
 * Tile placement helpers — pick (x, y) for a freshly-created tile so
 * it doesn't overlap existing tiles. Pure: no DOM, no stores, easy
 * to unit-test.
 */

import type { DashboardLayout, DashboardTile, TileType } from "$lib/api/dashboards";

/** Default size for each tile type. Tuned for the 12-column grid:
 *  - chart / table fill half the row (analyst can resize later)
 *  - filter is a wide skinny strip (full row, 1 row tall)
 *  - text is half-width, slightly shorter than chart/table */
export function defaultSizeFor(type: TileType): { w: number; h: number } {
  switch (type) {
    case "chart":
      return { w: 6, h: 4 };
    case "table":
      return { w: 6, h: 4 };
    case "filter":
      return { w: 12, h: 1 };
    case "text":
      return { w: 6, h: 2 };
  }
}

/** Find the first (x, y) where a tile of size w×h fits without
 *  overlapping any existing tile. Search row-by-row top-to-bottom,
 *  left-to-right. Always finds a slot because we eventually scan
 *  past the bottom row.
 *
 *  Algorithm: keep a per-row occupancy mask up to maxRow + h;
 *  for each (y, x) candidate, check the w×h block is fully free.
 *  Once we find one, return it. Worst case O(N²) but N is small
 *  (dashboards rarely exceed dozens of tiles). */
export function firstFreeSlot(layout: DashboardLayout, w: number, h: number): { x: number; y: number } {
  const cols = layout.cols || 12;
  if (w > cols) {
    // Tile too wide; clamp to a full row at the bottom.
    return { x: 0, y: highestY(layout) };
  }
  const maxRow = highestY(layout) + h + 1; // search far enough to guarantee a slot
  for (let y = 0; y <= maxRow; y++) {
    for (let x = 0; x <= cols - w; x++) {
      if (rectIsFree(layout, x, y, w, h)) {
        return { x, y };
      }
    }
  }
  // Unreachable in practice; clamp.
  return { x: 0, y: highestY(layout) };
}

/** True if no existing tile overlaps the rectangle (x, y, w, h). */
function rectIsFree(layout: DashboardLayout, x: number, y: number, w: number, h: number): boolean {
  for (const t of layout.tiles) {
    if (rectsOverlap(x, y, w, h, t.x, t.y, t.w, t.h)) return false;
  }
  return true;
}

function rectsOverlap(
  ax: number,
  ay: number,
  aw: number,
  ah: number,
  bx: number,
  by: number,
  bw: number,
  bh: number,
): boolean {
  return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
}

/** Highest y + h across the layout's tiles, or 0 if empty. Used as the
 *  search ceiling. */
function highestY(layout: DashboardLayout): number {
  let max = 0;
  for (const t of layout.tiles) {
    if (t.y + t.h > max) max = t.y + t.h;
  }
  return max;
}

/** Build a fresh tile of the given type, sized + positioned to fit
 *  the layout. Caller fills in cube / query / target afterwards via
 *  dashboardStore.updateTile. */
export function buildTile(layout: DashboardLayout, type: TileType, id: string): DashboardTile {
  const size = defaultSizeFor(type);
  const slot = firstFreeSlot(layout, size.w, size.h);
  return {
    id,
    x: slot.x,
    y: slot.y,
    w: size.w,
    h: size.h,
    type,
  };
}
