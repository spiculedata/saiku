/*
 * Issue #1086 — click-to-drill on the WORKSPACE chart.
 *
 * The workspace grid (CellsetTable) already drills on right-click by
 * dispatching a `saiku-drillthrough` CustomEvent carrying ABSOLUTE cellset
 * coordinates `{ row, col }`, which QueryCanvas listens for and feeds into the
 * existing DrillthroughModal flow. We reuse that exact mechanism for the chart
 * so there is no new backend path: clicking a data point translates the
 * ECharts category/series indices back into the same absolute cellset
 * coordinates the grid would emit for that cell.
 *
 * The wrinkle is `hideRollupRows` (#1053): when active the chart renders only
 * the LEAF rows of a multi-level hierarchy (via `deriveLeafRows`), so the
 * chart's category index is an index into a FILTERED subset, not the raw
 * cellset body rows. `leafIndices` maps each chart category back to its
 * original body-row index; when rollups are shown it is undefined and the
 * chart index already equals the body-row index.
 *
 * Kept side-effect free (no DOM, no echarts, no fetch) so it can be unit
 * tested under the project's `node` vitest environment, mirroring
 * `dashboard/drillthroughCoord.ts`.
 */

import type { ParsedCellset } from "$lib/views/cellsetUtils";

export interface ChartDrillTarget {
  /** Absolute cellset row index (header rows + body-row offset). */
  row: number;
  /** Absolute cellset column index (row-header cols + data-col offset). */
  col: number;
}

/**
 * Translate an ECharts click on the workspace chart into the absolute cellset
 * coordinates the `saiku-drillthrough` event expects.
 *
 * @param parsed       The same parsed cellset the chart was projected from.
 * @param categoryIndex `params.dataIndex` — the clicked category (chart row).
 *                      For pie/donut this is the slice index.
 * @param seriesIndex   `params.seriesIndex` — the clicked series (chart
 *                      column = measure). Pie series may omit it; pass 0.
 * @param leafIndices   When `hideRollupRows` reindexed the rows, the original
 *                      body-row index for each chart category. Omit/undefined
 *                      when rollups are shown (chart index == body-row index).
 * @returns absolute `{ row, col }`, or `null` for any out-of-range or
 *   non-integer index (background clicks, empty cellsets), so the caller can
 *   treat it as a no-op exactly like the grid does.
 */
export function chartDrillTarget(
  parsed: ParsedCellset,
  categoryIndex: number,
  seriesIndex: number,
  leafIndices?: number[],
): ChartDrillTarget | null {
  if (!isNonNegativeInt(categoryIndex) || !isNonNegativeInt(seriesIndex)) return null;

  const rowCount = parsed.dataRows.length;
  if (rowCount === 0) return null;

  // Resolve the chart category back to a raw body-row index.
  let bodyRowIndex: number;
  if (leafIndices && leafIndices.length > 0) {
    if (categoryIndex >= leafIndices.length) return null;
    bodyRowIndex = leafIndices[categoryIndex];
  } else {
    bodyRowIndex = categoryIndex;
  }
  if (!isNonNegativeInt(bodyRowIndex) || bodyRowIndex >= rowCount) return null;

  // The series index addresses a DATA column. Guard against the rare event
  // shape that reports a series the cellset doesn't have (e.g. a derived/
  // synthetic series); clamp to a valid data column rather than emitting an
  // out-of-range col.
  const dataColCount = parsed.dataRows[bodyRowIndex]?.length ?? 0;
  if (dataColCount === 0) return null;
  if (seriesIndex >= dataColCount) return null;

  return {
    row: parsed.headerRowCount + bodyRowIndex,
    col: parsed.rowHeaderColCount + seriesIndex,
  };
}

function isNonNegativeInt(n: number): boolean {
  return Number.isInteger(n) && n >= 0;
}
