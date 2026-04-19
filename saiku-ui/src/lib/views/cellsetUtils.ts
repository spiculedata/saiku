import type { CellEntry, QueryResult } from "$lib/api/query";

export interface ParsedCellset {
  /** Number of COLUMN_HEADER rows at the top. */
  headerRowCount: number;
  /** Number of ROW_HEADER columns on the left (pinned). */
  rowHeaderColCount: number;
  /** Raw cellset reference. */
  cells: CellEntry[][];
  /** Derived header cells per column: one array per column header row. */
  columnHeaderRows: CellEntry[][];
  /** Derived row-header cells per body row (length == rowCount * rowHeaderColCount). */
  bodyRows: CellEntry[][];
  /** Data values for each body row, indexed by data column. */
  dataRows: CellEntry[][];
  /** Flattened labels for body rows (for chart categories). */
  rowCategories: string[];
  /** Flattened labels for data columns (for chart series). */
  columnCategories: string[];
}

export function parseCellset(result: QueryResult): ParsedCellset {
  const cells = result.cellset ?? [];
  if (cells.length === 0) {
    return {
      headerRowCount: 0,
      rowHeaderColCount: 0,
      cells,
      columnHeaderRows: [],
      bodyRows: [],
      dataRows: [],
      rowCategories: [],
      columnCategories: [],
    };
  }

  let headerRowCount = 0;
  for (const row of cells) {
    const onlyHeaders = row.every(
      (c) => c.type === "COLUMN_HEADER" || c.type === "ROW_HEADER_HEADER" || c.type === "EMPTY",
    );
    if (onlyHeaders && row.some((c) => c.type === "COLUMN_HEADER" || c.type === "ROW_HEADER_HEADER")) {
      headerRowCount += 1;
    } else {
      break;
    }
  }

  let rowHeaderColCount = 0;
  if (cells.length > headerRowCount) {
    const firstBody = cells[headerRowCount];
    for (const c of firstBody) {
      if (c.type === "ROW_HEADER") rowHeaderColCount += 1;
      else break;
    }
  }

  const columnHeaderRows = cells.slice(0, headerRowCount);
  const body = cells.slice(headerRowCount);
  const bodyRows = body.map((r) => r.slice(0, rowHeaderColCount));
  const dataRows = body.map((r) => r.slice(rowHeaderColCount));

  const rowCategories = bodyRows.map((r) =>
    r.map((c) => c.value ?? "").filter(Boolean).join(" / "),
  );
  const columnCategories = (dataRows[0] ?? []).map((_, colIdx) => {
    const parts: string[] = [];
    for (const headerRow of columnHeaderRows) {
      const c = headerRow[rowHeaderColCount + colIdx];
      if (c && c.value) parts.push(c.value);
    }
    return parts.join(" / ");
  });

  return {
    headerRowCount,
    rowHeaderColCount,
    cells,
    columnHeaderRows,
    bodyRows,
    dataRows,
    rowCategories,
    columnCategories,
  };
}

export function toNumber(cell: CellEntry | undefined): number | null {
  if (!cell || cell.value == null || cell.value === "") return null;
  const rawProp = cell.properties?.raw;
  const src = rawProp ?? cell.value;
  const n = Number(String(src).replace(/[, ]/g, ""));
  return Number.isFinite(n) ? n : null;
}
