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

// --- Drillthrough adapter -----------------------------------------------

const DRILLTHROUGH_METADATA_KEY = "saiku.drillthrough";

interface DrillthroughMetadata {
  captions: string[];
  rowCount: number;
  runtimeMs?: number;
}

function readDrillthroughMetadata(
  schemaMetadata: Map<string, string> | undefined,
): DrillthroughMetadata {
  if (!schemaMetadata) {
    throw new Error("Arrow drillthrough stream missing schema metadata");
  }
  const raw = schemaMetadata.get(DRILLTHROUGH_METADATA_KEY);
  if (!raw) {
    throw new Error(`Arrow drillthrough stream missing ${DRILLTHROUGH_METADATA_KEY} metadata entry`);
  }
  const parsed = JSON.parse(raw) as Partial<DrillthroughMetadata>;
  if (!Array.isArray(parsed.captions) || typeof parsed.rowCount !== "number") {
    throw new Error("Arrow drillthrough metadata malformed");
  }
  return {
    captions: parsed.captions.map(String),
    rowCount: parsed.rowCount,
    runtimeMs: parsed.runtimeMs,
  };
}

/**
 * Parse an Arrow IPC stream produced by ArrowDrillthroughWriter into the
 * legacy `QueryResult` shape the Svelte DrillthroughResultModal already
 * consumes. First cellset row is column headers (COLUMN_HEADER); subsequent
 * rows are DATA_CELLs. For Float64 columns we tag the cell properties with
 * `numeric: "true"` so the modal can right-align them — JSON drillthrough
 * responses have no per-column type info and default to left-align (that
 * divergence is intentional; the JSON path is shrinking, not growing).
 */
export async function parseArrowDrillthrough(buffer: ArrayBuffer): Promise<QueryResult> {
  const arrow = await import("apache-arrow");
  const table = arrow.tableFromIPC(new Uint8Array(buffer));
  const schemaMetadata = table.schema.metadata as Map<string, string> | undefined;
  const meta = readDrillthroughMetadata(schemaMetadata);

  const fields = table.schema.fields;
  const colCount = fields.length;
  const height = meta.rowCount;

  // Column types: a column is numeric iff its Arrow type is FloatingPoint.
  // (ArrowDrillthroughWriter collapses all JDBC numerics to Float64; strings
  // and date/timestamps are dictionary-encoded Utf8.)
  const isNumeric = new Array<boolean>(colCount);
  for (let c = 0; c < colCount; c++) {
    // Arrow's TypeId enum entry for FloatingPoint is 3; using the name via
    // toString is brittle, so compare via the type's toString which
    // reliably yields "Float64" for our numeric columns.
    const t = fields[c].type;
    isNumeric[c] = t?.toString?.().startsWith("Float") ?? false;
  }

  // Build column-header row from captions (fallback to field name).
  const header: CellEntry[] = [];
  for (let c = 0; c < colCount; c++) {
    const caption = c < meta.captions.length ? meta.captions[c] : fields[c].name;
    header.push({ value: caption, type: "COLUMN_HEADER" });
  }
  const cellset: CellEntry[][] = [header];

  // Data rows.
  const getters: ((i: number) => unknown)[] = [];
  for (let c = 0; c < colCount; c++) {
    const col = table.getChild(fields[c].name);
    getters.push((i) => col?.get(i));
  }
  for (let i = 0; i < height; i++) {
    const row: CellEntry[] = [];
    for (let c = 0; c < colCount; c++) {
      const v = getters[c](i);
      const value = v === null || v === undefined ? "" : String(v);
      const properties: Record<string, string> = {};
      if (isNumeric[c]) properties.numeric = "true";
      row.push({ value, type: "DATA_CELL", properties });
    }
    cellset.push(row);
  }

  return {
    cellset,
    width: colCount,
    height,
    runtime: meta.runtimeMs,
  };
}
