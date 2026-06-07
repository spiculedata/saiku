/*
 * Mobile auto-stack — pure ordering/layout helpers (issue #932).
 *
 * On narrow viewports the free-form 12-column grid is unusable, so the
 * dashboard collapses every tile into a single full-width column. This
 * module owns the *pure* part of that behaviour: deciding whether the
 * viewport is narrow, and computing the stacked render order without ever
 * touching the DOM or the saved layout.
 *
 * Display-only: nothing here mutates the persisted (x, y, w, h). The
 * grid component reads the computed order and renders each tile
 * full-width in that order; the saved positions are untouched, so
 * switching back to a wide viewport restores the free-form layout
 * exactly.
 *
 * Stacking rules (from the issue):
 *   - filter tiles stick at the very top of the stack, in their own
 *     (y, x) order, regardless of where they sit in the saved grid;
 *   - every other tile follows, sorted by (y, x) — top row first, then
 *     left-to-right within a row.
 */

import type { DashboardTile } from "$lib/api/dashboards";

/** Default narrow breakpoint in CSS pixels. At or below this container
 *  width the grid auto-stacks. Mirrors the issue's 768px default; the
 *  task brief calls for ~640px, so we expose it as an argument and keep
 *  the historical 768 as the default to match the existing CSS media
 *  query in DashboardGrid.svelte. */
export const DEFAULT_STACK_BREAKPOINT = 768;

/** True when {@code width} (the grid container's content width, in px)
 *  is at or below {@code breakpoint} and so the grid should auto-stack.
 *  A non-positive width (container not yet measured) is treated as wide
 *  so the free-form layout renders until the first real measurement. */
export function isNarrow(width: number, breakpoint: number = DEFAULT_STACK_BREAKPOINT): boolean {
  if (!Number.isFinite(width) || width <= 0) return false;
  return width <= breakpoint;
}

/** True for tile types that pin to the top of the mobile stack. Today
 *  that's only the "filter" tile type; isolated here so the rule has a
 *  single home if more pinned types appear. */
export function isPinnedToTop(tile: DashboardTile): boolean {
  return tile.type === "filter";
}

/** Compare two tiles by saved position: row first (y ascending), then
 *  column (x ascending), so the result reads top-to-bottom then
 *  left-to-right. Ties (same x and y) fall back to a stable-ish id
 *  compare so the order is deterministic across renders. */
export function compareByPosition(a: DashboardTile, b: DashboardTile): number {
  if (a.y !== b.y) return a.y - b.y;
  if (a.x !== b.x) return a.x - b.x;
  return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
}

/**
 * Pure: compute the mobile auto-stack render order for a set of tiles.
 *
 * Filter tiles come first (in (y, x) order), then all remaining tiles in
 * (y, x) order. The input array is not mutated — a new sorted array is
 * returned. Use the returned order to drive a CSS `order` per tile (see
 * {@link stackOrderMap}) so the DOM order from the saved layout's
 * {@code #each} is overridden visually without re-keying the list.
 */
export function stackedOrder(tiles: readonly DashboardTile[]): DashboardTile[] {
  const pinned = tiles.filter(isPinnedToTop).slice().sort(compareByPosition);
  const rest = tiles.filter((t) => !isPinnedToTop(t)).slice().sort(compareByPosition);
  return [...pinned, ...rest];
}

/**
 * Pure: map each tile id to its zero-based position in the stacked
 * order. Handy for assigning the CSS `order` property per cell when the
 * tiles are still rendered in saved order by the template's {@code #each}.
 */
export function stackOrderMap(tiles: readonly DashboardTile[]): Map<string, number> {
  const order = stackedOrder(tiles);
  const map = new Map<string, number>();
  order.forEach((tile, index) => map.set(tile.id, index));
  return map;
}
