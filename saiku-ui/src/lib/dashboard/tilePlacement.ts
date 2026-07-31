/*
 * Tile placement helpers — pick (x, y) for a freshly-created tile so
 * it doesn't overlap existing tiles. Pure: no DOM, no stores, easy
 * to unit-test.
 */

import type { DashboardLayout, DashboardTile, TileType } from "$lib/api/dashboards";

/** Default size for each tile type. Tuned for the 12-column grid:
 *  - chart / table fill half the row (analyst can resize later)
 *  - filter is a wide skinny strip (full row, 1 row tall)
 *  - text is half-width and a single row tall — a short note or heading
 *    shouldn't reserve a big block of empty space that shoves charts
 *    below the fold (#1603). The analyst resizes it up when the note is
 *    long; the grid's auto-rows also let it grow to fit taller content.
 *  - kpi is a compact card — quarter-row width, 2 rows tall, so four
 *    fit across the top of a dashboard */
export function defaultSizeFor(type: TileType): { w: number; h: number } {
  switch (type) {
    case "chart":
      return { w: 6, h: 4 };
    case "table":
      return { w: 6, h: 4 };
    case "filter":
      return { w: 12, h: 1 };
    case "text":
      return { w: 6, h: 1 };
    case "kpi":
      return { w: 3, h: 2 };
    case "image":
      return { w: 4, h: 3 };
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

/** Reposition / resize a tile within the layout. Validates the new
 *  rectangle stays inside the grid; if it does, returns a new tiles
 *  array with overlapping siblings pushed downward to make room.
 *
 *  The cascade rule:
 *    1. Apply the user-supplied (x, y, w, h) to the source tile.
 *    2. Find any other tile that now overlaps the source — push that
 *       tile DOWN (increase y) by exactly enough to clear:
 *          new_y = source.y + source.h
 *    3. Pushed tiles may overlap further siblings; repeat (2) until
 *       no overlaps remain.
 *
 *  Pure + immutable: never mutates the input layout. Returns null
 *  when the user's input is invalid (x+w > cols, y/h/w out of range).
 */
export interface RepositionResult {
  ok: boolean;
  /** Populated when `ok`; the new tiles array to commit. */
  tiles?: DashboardTile[];
  /** Populated when `!ok`; a sentence the modal can render. */
  error?: string;
}

export function repositionTile(
  layout: DashboardLayout,
  tileId: string,
  next: { x: number; y: number; w: number; h: number },
): RepositionResult {
  const cols = layout.cols || 12;
  if (next.w < 1) return { ok: false, error: "Width must be at least 1 column." };
  if (next.h < 1) return { ok: false, error: "Height must be at least 1 row." };
  if (next.x < 0) return { ok: false, error: "x must be ≥ 0." };
  if (next.y < 0) return { ok: false, error: "y must be ≥ 0." };
  if (next.w > cols) {
    return { ok: false, error: `Width ${next.w} exceeds the ${cols}-column grid.` };
  }
  if (next.x + next.w > cols) {
    return {
      ok: false,
      error: `Tile would extend past the right edge (x=${next.x}, w=${next.w}, max=${cols}). Reduce width or move left.`,
    };
  }

  // Apply the user's change to a copy of the layout.
  const idx = layout.tiles.findIndex((t) => t.id === tileId);
  if (idx === -1) {
    return { ok: false, error: "Tile id not found in layout." };
  }
  const updated: DashboardTile[] = layout.tiles.map((t, i) =>
    i === idx ? { ...t, x: next.x, y: next.y, w: next.w, h: next.h } : { ...t },
  );

  // Cascade: walk tiles in y-then-x order. If any sibling overlaps a
  // higher-priority tile, push it down. Repeat until stable. The loop
  // terminates because every push increases at least one tile's y by
  // a positive integer, and grid coords are bounded.
  const safety = 1000; // catastrophic-bug ceiling, in case the math wedges
  for (let iter = 0; iter < safety; iter++) {
    let pushed = false;
    // Sort indices by (y, x) so we resolve top-to-bottom, left-to-right.
    const order = updated
      .map((_, i) => i)
      .sort((a, b) => updated[a].y - updated[b].y || updated[a].x - updated[b].x);
    for (let i = 0; i < order.length; i++) {
      const ai = order[i];
      const a = updated[ai];
      for (let j = i + 1; j < order.length; j++) {
        const bi = order[j];
        const b = updated[bi];
        if (rectsOverlap(a.x, a.y, a.w, a.h, b.x, b.y, b.w, b.h)) {
          updated[bi] = { ...b, y: a.y + a.h };
          pushed = true;
        }
      }
    }
    if (!pushed) break;
  }

  return { ok: true, tiles: updated };
}

/**
 * Pull every tile (except {@code anchoredId}) as far upward as possible
 * without overlapping a higher-priority tile or the anchored tile.
 * Called after a resize-shrink to reclaim the freed vertical space:
 * tiles that were below the shrunk tile slide up to be adjacent.
 *
 * <p>Not called after a drag — drag-drop is treated as an explicit
 * placement, so any gap a user creates by dragging a tile downward is
 * preserved until they do another resize-shrink (which kicks gravity
 * back in).
 */
export function compactUpward(tiles: DashboardTile[], anchoredId: string): DashboardTile[] {
  const next = tiles.map((t) => ({ ...t }));
  let moved = true;
  const safety = 1000;
  for (let iter = 0; moved && iter < safety; iter++) {
    moved = false;
    // Settle upward in (y, x) order so upper tiles claim their final
    // y position before lower ones probe for space.
    const order = next
      .map((_, i) => i)
      .sort((a, b) => next[a].y - next[b].y || next[a].x - next[b].x);
    for (const i of order) {
      const t = next[i];
      if (t.id === anchoredId) continue;
      while (t.y > 0) {
        const probeY = t.y - 1;
        const blocked = next.some(
          (o, oi) => oi !== i && rectsOverlap(t.x, probeY, t.w, t.h, o.x, o.y, o.w, o.h),
        );
        if (blocked) break;
        t.y = probeY;
        moved = true;
      }
    }
  }
  return next;
}
