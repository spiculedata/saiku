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
 * Lay out `n` charts into a grid filling the 0–100% box.
 *
 * Columns = ceil(sqrt(n)); rows = ceil(n / cols). Cells are equal-sized, with
 * a small gutter around each and a little headroom at the top of each cell so a
 * per-chart title can sit above the plot. The drawing center is the center of
 * the plot area (below the title headroom).
 *
 * n <= 0 → []. n <= 1 → a single full-box cell.
 */
export function gridCells(n: number): GridCell[] {
  if (n <= 0) return [];
  const cols = n <= 1 ? 1 : Math.ceil(Math.sqrt(n));
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
