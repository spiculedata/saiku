/*
 * Small-multiples grid geometry for single-measure chart kinds.
 *
 * Pie, donut, treemap and sunburst can each encode only ONE measure. When a
 * query returns M measures we render M charts — small multiples — laid out in
 * a grid inside the SAME ECharts instance (M series, no extra DOM/instances),
 * one chart per measure. This module computes the per-cell geometry (as
 * percentages of the 0–100% plot box) that the chart builders position each
 * series and title into.
 *
 * Pure: no DOM, no ECharts. Tests live alongside.
 */

/** Chart kinds that encode a single measure and therefore fan out into
 *  small multiples (one chart per measure). */
export const SINGLE_MEASURE_KINDS: ReadonlySet<string> = new Set(["pie", "donut", "treemap", "sunburst"]);

/** Whether a chart kind is single-measure (and so uses small multiples). */
export function isSingleMeasureKind(kind: string): boolean {
  return SINGLE_MEASURE_KINDS.has(kind);
}

/** One cell of the small-multiples grid, expressed as percentages of the
 *  0–100% plot box. `left/top/width/height` give the cell rect (after a small
 *  gutter and top headroom for a title); `centerX/centerY` give the cell's
 *  drawing center (used for radial charts like pie/sunburst). */
export interface GridCell {
  row: number;
  col: number;
  leftPct: number;
  topPct: number;
  widthPct: number;
  heightPct: number;
  centerXPct: number;
  centerYPct: number;
}

/** Gutter between cells, as a percentage of the full box (per side). */
const GUTTER_PCT = 2;
/** Headroom reserved at the top of each cell for its title, as a percentage
 *  of the cell height. */
const TITLE_HEADROOM_FRAC = 0.18;

/**
 * Lay out `n` charts into a grid.
 *
 * **Two charts per row** (cols = 2), rows = ceil(n / 2). We deliberately cap at
 * 2 columns rather than ceil(sqrt(n)) so charts stay large and readable —
 * adding more measures grows the number of rows (the host container grows +
 * scrolls) instead of shrinking every chart. Cells are equal-sized with a small
 * gutter and a little title headroom at the top; the drawing center sits in the
 * plot area below the title.
 *
 * n <= 0 → []. n <= 1 → a single full-box cell.
 */
export function gridCells(n: number): GridCell[] {
  if (n <= 0) return [];
  const cols = n <= 1 ? 1 : 2;
  const rows = Math.ceil(n / cols);

  const cellW = 100 / cols;
  const cellH = 100 / rows;

  const out: GridCell[] = [];
  for (let i = 0; i < n; i++) {
    const row = Math.floor(i / cols);
    const col = i % cols;

    const leftPct = col * cellW + GUTTER_PCT;
    const topPct = row * cellH + GUTTER_PCT;
    const widthPct = cellW - GUTTER_PCT * 2;
    const heightPct = cellH - GUTTER_PCT * 2;

    // Title sits in the top headroom; the plot occupies the rest of the cell,
    // so the drawing center is biased downward by half the headroom.
    const headroom = heightPct * TITLE_HEADROOM_FRAC;
    const centerXPct = leftPct + widthPct / 2;
    const centerYPct = topPct + headroom + (heightPct - headroom) / 2;

    out.push({ row, col, leftPct, topPct, widthPct, heightPct, centerXPct, centerYPct });
  }
  return out;
}

/**
 * ECharts radius (%) for a pie/sunburst that fills ~`frac` of its grid cell.
 *
 * ECharts radii are a percentage of min(canvasWidth, canvasHeight), so a naive
 * `widthPct * k` makes charts shrink/grow as the canvas aspect changes with the
 * number of small multiples (e.g. 2 measures = 1 short row → tiny pies; 3+ = a
 * taller canvas → bigger pies). Compensating for the canvas `aspect`
 * (width / height) keeps every chart the same on-screen size regardless of how
 * many there are. Returns a plain number (percent); the caller appends "%".
 */
export function cellRadiusPct(cell: GridCell, aspect: number, frac = 0.42): number {
  const a = Number.isFinite(aspect) && aspect > 0 ? aspect : 1;
  // Plot area excludes the title headroom at the top of the cell.
  const plotHeightPct = cell.heightPct * (1 - 0.18);
  // pixel radius target = frac * min(cellWidthPx, plotHeightPx); express that as
  // a % of min(canvasW, canvasH). When W>=H, min is H; when W<H, min is W.
  const limit = a >= 1 ? Math.min(cell.widthPct * a, plotHeightPct) : Math.min(cell.widthPct, plotHeightPct / a);
  return Math.max(1, limit * frac);
}
