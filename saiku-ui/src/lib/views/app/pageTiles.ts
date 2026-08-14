/*
 * saiku#1792 — how many tiles a page would take with it if removed.
 *
 * `AppPage.grid` is deliberately opaque in the app schema ($lib/api/apps): the
 * dashboard layout ($lib/api/dashboards → DashboardLayout) is its authority, and
 * the app document doesn't want to own that shape. The Pages inspector only needs
 * one number out of it — enough to tell an author "this discards 4 tiles" before
 * they remove a page — so it reads the grid structurally and defensively rather
 * than importing the dashboard schema and coupling the two.
 *
 * Never throws: a null / malformed / hand-edited grid reports 0, which downgrades
 * the confirm to a plain one, and losing a warning is a far better failure than a
 * crashed inspector.
 */

/** Count the tiles on a page's opaque grid. Unknown shapes report 0. */
export function pageTileCount(grid: unknown): number {
  if (!grid || typeof grid !== "object" || Array.isArray(grid)) return 0;
  const tiles = (grid as { tiles?: unknown }).tiles;
  return Array.isArray(tiles) ? tiles.length : 0;
}

/** Human phrasing for the removal confirm — "4 tiles" / "1 tile" / "" when empty. */
export function tilesPhrase(count: number): string {
  if (count <= 0) return "";
  return count === 1 ? "1 tile" : `${count} tiles`;
}
