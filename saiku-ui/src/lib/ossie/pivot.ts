import type { OssieQueryResult, OssieResultCell } from "$lib/api/ossie";

/**
 * Turn the server's flat rowset into a crosstab grid — one row-header column per Rows
 * shelf entry, one top-header row per Columns shelf entry, and metric cells at the
 * intersections. Long-form (no columns shelf) queries return `null` so the canvas can
 * fall through to its simple long-form renderer.
 *
 * The shape mirrors what the MDX CellDataSet produces server-side: a `headerRows` grid
 * of top labels + a `bodyRows` grid of row labels + metric values. Header cells carry an
 * optional `colspan` so a Columns shelf level spanning multiple metrics collapses into
 * one visual header rather than N repeated cells.
 */
export interface PivotedGrid {
  /** Rows above the body — one per Columns shelf entry + one for the metric names when
   *  `showMetricRow` is true. Each row is left-padded with `nRow` empty cells so the row-
   *  header column stays under the corner. */
  headerRows: PivotCell[][];
  /** One entry per unique row-shelf key combination. Left prefix = the row-shelf values,
   *  then one metric cell per (colKey × valueShelf) intersection. Missing values render
   *  as empty {@link PivotCell.formatted}. */
  bodyRows: PivotCell[][];
  /** Number of body columns per unique column-shelf combination (== values.length). */
  metricCount: number;
  /** How many left-header columns precede the metric grid. */
  rowHeaderCount: number;
  /**
   * Parallel array to each body row's columns starting at index {@link rowHeaderCount}.
   * Entry `i` is the column-shelf values that this body-column belongs to, as an array
   * of strings (one per Columns shelf entry). Enables the canvas' context-menu to
   * offer "Filter to <col-value>" on any crosstab body cell.
   *
   * The array's length equals the number of unique column-key combinations. Body-column
   * `i` (0-indexed within the metric grid) maps to `colKeyValues[Math.floor(i / metricCount)]`.
   */
  colKeyValues: string[][];
}

/** One rendered cell — text + optional colspan + numeric flag for right-align. */
export interface PivotCell {
  formatted: string;
  raw?: string;
  rawNumber?: number;
  colspan?: number;
  isHeader?: boolean;
  isNumeric?: boolean;
}

/**
 * Pivot `result` using the shelf structure. Returns `null` when the shelf has no columns
 * (the caller renders the long-form table directly).
 *
 * @param rowFields   The Rows-shelf entries. Their combined values form each body row's
 *                    left prefix and the row-key used for deduping.
 * @param columnFields The Columns-shelf entries. Their combined values form each top-
 *                     header column's key.
 * @param valueFields The Values-shelf entries. One metric column per entry per column-
 *                    key group.
 * @param result      The wire-envelope result the store received.
 */
export function pivotResult(
  rowFields: string[],
  columnFields: string[],
  valueFields: string[],
  result: OssieQueryResult,
): PivotedGrid | null {
  if (columnFields.length === 0) return null;

  const nRow = rowFields.length;
  const nCol = columnFields.length;
  const nVal = valueFields.length;

  const body = result.cellSetBody ?? [];

  // Collect unique row-key + column-key + metric-value triples. We sort keys
  // lexicographically so successive Runs produce a stable presentation order.
  const rowKeys = new Set<string>();
  const colKeys = new Set<string>();
  const rowLabels = new Map<string, string[]>();
  const colLabels = new Map<string, string[]>();
  const cellValues = new Map<string, Map<string, OssieResultCell[]>>();

  for (const row of body) {
    if (row.length < nRow + nCol + nVal) continue;
    const rowParts = row.slice(0, nRow).map(cellDisplay);
    const colParts = row.slice(nRow, nRow + nCol).map(cellDisplay);
    const metrics = row.slice(nRow + nCol, nRow + nCol + nVal);

    const rowKey = JSON.stringify(rowParts);
    const colKey = JSON.stringify(colParts);
    rowKeys.add(rowKey);
    colKeys.add(colKey);
    if (!rowLabels.has(rowKey)) rowLabels.set(rowKey, rowParts);
    if (!colLabels.has(colKey)) colLabels.set(colKey, colParts);

    let byCol = cellValues.get(rowKey);
    if (!byCol) {
      byCol = new Map();
      cellValues.set(rowKey, byCol);
    }
    byCol.set(colKey, metrics);
  }

  const sortedRowKeys = [...rowKeys].sort();
  const sortedColKeys = [...colKeys].sort();

  // Build the header grid: one row per Columns shelf level, then a trailing row of
  // metric names (only when more than one value — otherwise the single-value name lives
  // directly under the column-key label).
  const showMetricRow = nVal > 1;
  const headerRows: PivotCell[][] = [];
  for (let level = 0; level < nCol; level++) {
    const row: PivotCell[] = [];
    // Left corner cells: on the last column level, label them with the row-shelf names
    // so the corner reads "brand" instead of an empty box. Earlier levels stay empty.
    for (let r = 0; r < nRow; r++) {
      const isCornerLast = level === nCol - 1 && !showMetricRow;
      row.push({
        formatted: isCornerLast ? rowFields[r] : "",
        isHeader: true,
      });
    }
    // Column groups. Each column-key spans nVal metric cells.
    for (const colKey of sortedColKeys) {
      const label = colLabels.get(colKey)?.[level] ?? "";
      row.push({
        formatted: label,
        isHeader: true,
        colspan: nVal > 1 ? nVal : undefined,
      });
    }
    headerRows.push(row);
  }
  if (showMetricRow) {
    const row: PivotCell[] = [];
    for (let r = 0; r < nRow; r++) {
      row.push({ formatted: rowFields[r], isHeader: true });
    }
    for (let i = 0; i < sortedColKeys.length; i++) {
      for (const v of valueFields) {
        row.push({ formatted: v, isHeader: true });
      }
    }
    headerRows.push(row);
  }

  // Body: row-shelf prefix + metric cells filled from the map. Missing intersections
  // render as an empty cell.
  const bodyRows: PivotCell[][] = [];
  for (const rowKey of sortedRowKeys) {
    const cells: PivotCell[] = [];
    const labels = rowLabels.get(rowKey) ?? [];
    for (let r = 0; r < nRow; r++) {
      cells.push({ formatted: labels[r] ?? "", isHeader: true });
    }
    const byCol = cellValues.get(rowKey) ?? new Map();
    for (const colKey of sortedColKeys) {
      const metrics = byCol.get(colKey) as OssieResultCell[] | undefined;
      for (let v = 0; v < nVal; v++) {
        const cell = metrics?.[v];
        cells.push({
          formatted: cell?.formattedValue ?? cell?.rawValue ?? "",
          raw: cell?.rawValue,
          rawNumber: cell?.rawNumber,
          isNumeric: cell?.rawNumber !== undefined,
        });
      }
    }
    bodyRows.push(cells);
  }

  // Expose the resolved column-key values so the canvas can offer "Filter to <col-value>"
  // on any crosstab body cell without re-parsing headerRows or the JSON keys.
  const colKeyValues = sortedColKeys.map((key) => colLabels.get(key) ?? []);
  return {
    headerRows,
    bodyRows,
    metricCount: nVal,
    rowHeaderCount: nRow,
    colKeyValues,
  };
}

function cellDisplay(cell: OssieResultCell): string {
  return cell.formattedValue ?? cell.rawValue ?? "";
}
