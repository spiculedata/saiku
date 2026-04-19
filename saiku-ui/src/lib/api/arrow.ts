/*
 * Client-side adapter: Apache Arrow IPC stream -> legacy QueryResult shape.
 *
 * The Saiku backend (Phase 5 Task 3) emits one RecordBatch per /query/execute
 * response when `Accept: application/vnd.apache.arrow.stream` is sent.
 * This module reconstructs the legacy CellEntry[][] cellset so the existing
 * views (CellsetTable, cellsetUtils, ChartView) continue to work unchanged.
 *
 * TODO (phase 5b): skip the 2D rebuild; expose columns directly to CellsetTable.
 */

import type { CellEntry, QueryResult } from "$lib/api/query";

const METADATA_KEY = "saiku.cellset";

interface CellsetMetadata {
  rowHeaderColCount: number;
  columnHeaderRows: string[][];
  runtimeMs?: number;
  width?: number;
  height?: number;
  mdx?: string | null;
  queryName?: string | null;
}

function readMetadata(schemaMetadata: Map<string, string> | undefined): CellsetMetadata {
  if (!schemaMetadata) {
    throw new Error("Arrow stream missing schema metadata");
  }
  const raw = schemaMetadata.get(METADATA_KEY);
  if (!raw) {
    throw new Error(`Arrow stream missing ${METADATA_KEY} metadata entry`);
  }
  const parsed = JSON.parse(raw) as Partial<CellsetMetadata>;
  if (
    typeof parsed.rowHeaderColCount !== "number" ||
    !Array.isArray(parsed.columnHeaderRows)
  ) {
    throw new Error("Arrow cellset metadata malformed");
  }
  return {
    rowHeaderColCount: parsed.rowHeaderColCount,
    columnHeaderRows: parsed.columnHeaderRows.map((r) => (Array.isArray(r) ? r.map(String) : [])),
    runtimeMs: parsed.runtimeMs,
    width: parsed.width,
    height: parsed.height,
    mdx: parsed.mdx ?? null,
    queryName: parsed.queryName ?? null,
  };
}

function stringify(value: unknown): string {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") return value;
  return String(value);
}

/**
 * Parse an Arrow IPC stream produced by ArrowCellsetWriter into the legacy
 * `QueryResult` shape used by Saiku's existing Svelte views.
 */
export async function parseArrowExecute(buffer: ArrayBuffer): Promise<QueryResult> {
  const arrow = await import("apache-arrow");
  const table = arrow.tableFromIPC(new Uint8Array(buffer));
  const schemaMetadata = table.schema.metadata as Map<string, string> | undefined;
  const meta = readMetadata(schemaMetadata);

  const rowHeaderColCount = meta.rowHeaderColCount;
  const height = meta.height ?? table.numRows;
  const columnHeaderRows = meta.columnHeaderRows;

  // Discover data column count by sniffing c{j}_raw fields.
  const dataColCount = (() => {
    let n = 0;
    while (table.schema.fields.some((f) => f.name === `c${n}_raw`)) n += 1;
    return n;
  })();

  // Pre-pull columns so we don't retraverse for every cell.
  interface RowHeaderCols {
    value: (i: number) => string;
    uniqueName: (i: number) => string;
    dimension: (i: number) => string;
    hierarchy: (i: number) => string;
    level: (i: number) => string;
  }
  const rowHeaderCols: RowHeaderCols[] = [];
  for (let r = 0; r < rowHeaderColCount; r++) {
    const vValue = table.getChild(`r${r}_value`);
    const vUniq = table.getChild(`r${r}_uniqueName`);
    const vDim = table.getChild(`r${r}_dimension`);
    const vHier = table.getChild(`r${r}_hierarchy`);
    const vLvl = table.getChild(`r${r}_level`);
    rowHeaderCols.push({
      value: (i) => stringify(vValue?.get(i)),
      uniqueName: (i) => stringify(vUniq?.get(i)),
      dimension: (i) => stringify(vDim?.get(i)),
      hierarchy: (i) => stringify(vHier?.get(i)),
      level: (i) => stringify(vLvl?.get(i)),
    });
  }

  interface DataCols {
    raw: (i: number) => number | null;
    fmt: (i: number) => string;
  }
  const dataCols: DataCols[] = [];
  for (let c = 0; c < dataColCount; c++) {
    const vRaw = table.getChild(`c${c}_raw`);
    const vFmt = table.getChild(`c${c}_fmt`);
    dataCols.push({
      raw: (i) => {
        const v = vRaw?.get(i);
        return v === null || v === undefined ? null : Number(v);
      },
      fmt: (i) => stringify(vFmt?.get(i)),
    });
  }

  const totalWidth = rowHeaderColCount + dataColCount;
  const cellset: CellEntry[][] = [];

  // Build column-header rows. Each header row has rowHeaderColCount empty
  // "ROW_HEADER_HEADER" cells on the left followed by dataColCount
  // COLUMN_HEADER cells. The server only stores the column header captions.
  for (const headerRow of columnHeaderRows) {
    const row: CellEntry[] = [];
    for (let r = 0; r < rowHeaderColCount; r++) {
      row.push({ value: "", type: "ROW_HEADER_HEADER" });
    }
    for (let c = 0; c < dataColCount; c++) {
      const caption = c < headerRow.length ? headerRow[c] ?? "" : "";
      row.push({ value: caption, type: "COLUMN_HEADER" });
    }
    // Pad if server header row was unexpectedly short.
    while (row.length < totalWidth) row.push({ value: "", type: "COLUMN_HEADER" });
    cellset.push(row);
  }

  // Data rows.
  for (let i = 0; i < height; i++) {
    const row: CellEntry[] = [];
    for (let r = 0; r < rowHeaderColCount; r++) {
      const rc = rowHeaderCols[r];
      const value = rc.value(i);
      const properties: Record<string, string> = {
        uniquename: rc.uniqueName(i),
        dimension: rc.dimension(i),
        hierarchy: rc.hierarchy(i),
        level: rc.level(i),
      };
      row.push({ value, type: "ROW_HEADER", properties });
    }
    for (let c = 0; c < dataColCount; c++) {
      const dc = dataCols[c];
      const raw = dc.raw(i);
      const fmt = dc.fmt(i);
      let displayValue: string;
      if (fmt !== "") {
        displayValue = fmt;
      } else if (raw !== null) {
        displayValue = String(raw);
      } else {
        displayValue = "";
      }
      const properties: Record<string, string> = {};
      if (raw !== null) properties.raw = String(raw);
      const cell: CellEntry =
        raw === null && displayValue === ""
          ? { value: "", type: "EMPTY" }
          : { value: displayValue, type: "DATA_CELL", properties };
      row.push(cell);
    }
    cellset.push(row);
  }

  const result: QueryResult = {
    cellset,
    width: meta.width ?? totalWidth,
    height,
    runtime: meta.runtimeMs,
  };
  return result;
}
