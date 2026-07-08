/**
 * Client for the Ossie semantic-layer endpoints. Two roles:
 *
 * 1. **Discover** — fetch the datasets / metrics / relationships tree for one Ossie
 *    connection so the workspace sidebar can render a schema browser + drag sources.
 * 2. **Query** — post shelf state (rows / columns / values / filters / sorts) through the
 *    existing `/rest/saiku/api/query/execute` endpoint. The server branches on
 *    `queryType === "OSSIE"` and hands off to `OssieQueryService`; the response envelope is
 *    the same `CellDataSet` JSON the OLAP path already returns, so the same downstream
 *    renderer works on both.
 */

/** Base for the legacy per-user discover mount that carries the Ossie model endpoint. */
const DISCOVER_BASE = "/rest/saiku";
const QUERY_EXECUTE = "/rest/saiku/api/query/execute";

/**
 * Slimmed projection of one Ossie `semantic_model` — matches `OssieModelDto` on the server.
 * We keep only what the schema browser and shelf state need; ai_context / vendor payloads
 * stay in the YAML on disk.
 */
export interface OssieModel {
  connection: string;
  name: string;
  description?: string;
  datasets: OssieDataset[];
  metrics: OssieMetric[];
  relationships: OssieRelationship[];
}

export interface OssieDataset {
  name: string;
  source?: string;
  description?: string;
  fields: OssieField[];
  primaryKey: string[];
}

export interface OssieField {
  name: string;
  expression?: string | null;
  label?: string | null;
  description?: string | null;
  time: boolean;
  pii: boolean;
}

export interface OssieMetric {
  name: string;
  expression?: string | null;
  description?: string | null;
  aggregationKind?: string | null;
}

export interface OssieRelationship {
  name: string;
  from: string;
  to: string;
  fromColumns: string[];
  toColumns: string[];
}

/**
 * Shelf state posted to the query endpoint. Structurally mirrors `OssieQueryModel` on the
 * server; kept flat to make drag-drop mutation cheap.
 */
export interface OssieQueryModel {
  connection: string;
  model: string;
  factDataset: string;
  rows: OssieFieldRef[];
  columns: OssieFieldRef[];
  values: OssieMetricRef[];
  filters: OssieFilterExpr[];
  sorts: OssieSortRef[];
  limit?: number;
}

export interface OssieFieldRef {
  dataset: string;
  field: string;
}

export interface OssieMetricRef {
  metric: string;
}

export type OssieFilterOp =
  | "EQ"
  | "NEQ"
  | "LT"
  | "LTE"
  | "GT"
  | "GTE"
  | "IN"
  | "BETWEEN"
  | "IS_NULL"
  | "IS_NOT_NULL";

export interface OssieFilterExpr {
  dataset?: string;
  field: string;
  op: OssieFilterOp;
  value?: string;
  /** Multi-value slot for IN / BETWEEN. Always an array (possibly empty) so callers
   *  don't have to null-check before iterating. */
  values: string[];
}

export interface OssieSortRef {
  dataset?: string;
  field?: string;
  metric?: string;
  direction: "ASC" | "DESC";
}

/** One cell in the Ossie result envelope, post-projection. */
export interface OssieResultCell {
  formattedValue?: string;
  rawValue?: string;
  rawNumber?: number;
}

export interface OssieQueryResult {
  cellSetHeaders: OssieResultCell[][];
  cellSetBody: OssieResultCell[][];
  width: number;
  height: number;
  runtime?: number;
}

/**
 * Shape of the shared `/query/execute` response after Query2Resource routes through
 * {@code RestUtil.convert(CellDataSet)}. Header rows are prepended to the same 2D array
 * as body rows, discriminated by {@code type=COLUMN_HEADER} vs {@code ROW_HEADER}/{@code
 * DATA_CELL}. Numeric values live in {@code properties.raw}.
 *
 * We consume this shape so both MDX and Ossie flow through the same wire envelope on
 * the server — the workbench splits it into headers + body client-side.
 */
interface WireCellSetCell {
  value: string;
  type: "COLUMN_HEADER" | "ROW_HEADER" | "DATA_CELL" | "EMPTY" | "UNKNOWN" | "ERROR";
  properties?: Record<string, string>;
}

interface WireQueryResult {
  cellset?: WireCellSetCell[][];
  runtime?: number;
  error?: string;
  width?: number;
  height?: number;
  topOffset?: number;
}

/**
 * Fetch the semantic-model tree for an OSSIE-typed connection. The username segment on the
 * discover mount is a Saiku legacy — every request goes through the current session's
 * principal, so we take it as an argument here so the caller keeps that responsibility.
 */
export async function fetchOssieModel(username: string, connection: string): Promise<OssieModel> {
  // Reachable path on the current server: `/rest/saiku/{username}/discover/{connection}/ossie-model`.
  const url = `${DISCOVER_BASE}/${encodeURIComponent(username)}/discover/${encodeURIComponent(connection)}/ossie-model`;
  const res = await fetch(url, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Ossie model fetch failed (${res.status}): ${body || res.statusText}`);
  }
  return (await res.json()) as OssieModel;
}

/**
 * Execute an Ossie shelf-state query through the shared `/query/execute` endpoint. Server
 * branches on `queryType`; response envelope is the CellDataSet shape the frontend already
 * knows how to render.
 */
export async function executeOssieQuery(
  name: string,
  model: OssieQueryModel,
): Promise<OssieQueryResult> {
  const body = {
    name,
    queryType: "OSSIE",
    ossieQueryModel: model,
    // The MDX path expects these fields to exist; we pass an empty object rather than
    // undefined so Jackson can round-trip the payload without needing null-safety in the
    // Java model.
    parameters: {},
    properties: {},
  };
  const res = await fetch(QUERY_EXECUTE, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Ossie query failed (${res.status}): ${text || res.statusText}`);
  }
  const wire = (await res.json()) as WireQueryResult;
  if (wire.error) throw new Error(wire.error);
  return projectWireToOssieResult(wire);
}

/**
 * Convert the shared `/query/execute` wire envelope into the header/body split the Ossie
 * canvas renders. Header rows are the leading N rows tagged {@code COLUMN_HEADER}; the
 * rest are body rows with a mix of {@code ROW_HEADER} (dimension cells) and
 * {@code DATA_CELL} (metric cells). Numeric values ride in {@code properties.raw}.
 */
export function projectWireToOssieResult(wire: WireQueryResult): OssieQueryResult {
  const cellset = wire.cellset ?? [];
  const headerRows: OssieResultCell[][] = [];
  const bodyRows: OssieResultCell[][] = [];
  for (const row of cellset) {
    if (row.length === 0) continue;
    const isHeader = row.every((c) => c.type === "COLUMN_HEADER");
    const projected = row.map<OssieResultCell>((c) => {
      const rawNum = c.properties?.raw ? Number(c.properties.raw) : undefined;
      return {
        formattedValue: c.value,
        rawValue: c.value,
        rawNumber: rawNum !== undefined && !Number.isNaN(rawNum) ? rawNum : undefined,
      };
    });
    if (isHeader) headerRows.push(projected);
    else bodyRows.push(projected);
  }
  const width = wire.width ?? headerRows[0]?.length ?? 0;
  return {
    cellSetHeaders: headerRows,
    cellSetBody: bodyRows,
    width,
    height: bodyRows.length,
    runtime: wire.runtime,
  };
}

/**
 * On-disk shape of a saved Ossie query. Stored as JSON to a `.saiku` file via the shared
 * repository endpoint — same file extension as MDX queries, distinguished by the
 * {@code queryType} discriminator on load. Keeps the RepositoryBrowser catalog simple: one
 * file type surfaces both flavours.
 */
export interface SavedOssieQuery {
  name: string;
  queryType: "OSSIE";
  ossieQueryModel: OssieQueryModel;
  /** Version stamp so future load-side migrations can branch. Bump when the shape breaks. */
  saikuOssieVersion: 1;
}

/**
 * Persist a shelf-state query as a `.saiku` file in the shared repository. Same endpoint as
 * MDX queries so listings and folder navigation just work — no separate CRUD surface.
 */
export async function saveOssieQuery(
  path: string,
  name: string,
  model: OssieQueryModel,
): Promise<void> {
  const payload: SavedOssieQuery = {
    name,
    queryType: "OSSIE",
    ossieQueryModel: model,
    saikuOssieVersion: 1,
  };
  const res = await fetch(
    `/rest/saiku/api/repository/resource?file=${encodeURIComponent(path)}`,
    {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        Accept: "application/json",
      },
      body: `content=${encodeURIComponent(JSON.stringify(payload))}`,
    },
  );
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Ossie save failed (${res.status}): ${text || res.statusText}`);
  }
}

/**
 * Read a `.saiku` file back and return it if it's Ossie-shaped. Returns `null` when the
 * file is an MDX query — the caller (workbench shell) then falls through to the MDX
 * load path so mixing OLAP + Ossie queries in the same folder Just Works.
 */
export async function loadOssieQuery(path: string): Promise<SavedOssieQuery | null> {
  const res = await fetch(
    `/rest/saiku/api/repository/resource?file=${encodeURIComponent(path)}`,
    {
      credentials: "include",
      headers: { Accept: "application/json" },
    },
  );
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Ossie load failed (${res.status}): ${text || res.statusText}`);
  }
  const raw = await res.text();
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== "object") return null;
  const p = parsed as Partial<SavedOssieQuery>;
  if (p.queryType !== "OSSIE" || !p.ossieQueryModel) return null;
  return p as SavedOssieQuery;
}

/** Return an empty shelf-state seed for a newly-picked model. */
export function newOssieQueryModel(connection: string, modelName: string): OssieQueryModel {
  return {
    connection,
    model: modelName,
    factDataset: "",
    rows: [],
    columns: [],
    values: [],
    filters: [],
    sorts: [],
  };
}

