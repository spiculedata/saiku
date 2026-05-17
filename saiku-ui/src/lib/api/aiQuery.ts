/*
 * AI Query API client — TypeScript bindings to /rest/saiku/api/ai/query
 * and its response shape. Used by the dashboard tile renderers to
 * execute their effective queries.
 *
 * The Workspace UI uses /rest/saiku/api/query (ThinQuery) for its
 * live-editing flow; the dashboard layer uses the AI surface because
 * (a) the tile inline body shape is AiQueryRequest verbatim, and
 * (b) the typed cell envelope (AiCell.value + formatted + unit)
 * survives the JSON wire without locale-string re-parsing.
 */

const REST_BASE = "/rest/saiku/api/ai";

/** Server-side enum mirror. */
export type AiQueryStatus =
  | "SUCCESS"
  | "VALIDATION_ERROR"
  | "EXECUTION_ERROR"
  | "PERMISSION_DENIED"
  | "RATE_LIMITED"
  | "TIMEOUT"
  | "WAREHOUSE_ERROR"
  | "CUBE_NOT_FOUND";

export interface AiCell {
  value: number | null;
  formatted: string;
  unit?: string | null;
  properties?: Record<string, string>;
}

export interface AiQueryCaption {
  name: string;
  caption: string;
}

export interface AiQueryFreshness {
  computedAtMillis: number;
  computedAt: string;
  cached: boolean;
}

export interface AiQueryMetadata {
  rows: AiQueryCaption[];
  columns: AiQueryCaption[];
  measures: string[];
  generatedMdx?: string;
  freshness?: AiQueryFreshness;
}

export interface AiQueryResponse {
  queryId: string;
  status: AiQueryStatus;
  format?: "records" | "matrix";
  metadata?: AiQueryMetadata;
  /** Records-format payload — one map per row, keyed by caption.
   *  Row-header columns hold plain strings; measure columns hold AiCell. */
  data?: Array<Record<string, AiCell | string>>;
  /** Matrix-format payload — positional rows of colIndex→AiCell. */
  matrix?: Array<Record<string, AiCell>>;
  totalRows?: number;
  runtimeMs?: number;
  error?: string;
  field?: string;
  available?: string[];
}

/** POST /rest/saiku/api/ai/query with the supplied request body.
 *  Returns the parsed response — including 400-status VALIDATION_ERROR
 *  envelopes, since those carry the structured error metadata the UI
 *  surfaces in the tile frame. */
export async function executeAiQuery(
  body: Record<string, unknown>,
  format: "records" | "matrix" = "records",
): Promise<AiQueryResponse> {
  const url = `${REST_BASE}/query?format=${encodeURIComponent(format)}`;
  const res = await fetch(url, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  });
  // 400 / 5xx with a parseable body still carries the AiQueryResponse
  // shape (with .status, .error, .field, .available) — surface it
  // verbatim so the tile can render the structured validation message.
  // Only treat parse failures / transport errors as throws.
  const text = await res.text();
  if (!text) {
    throw new Error(`executeAiQuery -> ${res.status}: empty body`);
  }
  try {
    return JSON.parse(text) as AiQueryResponse;
  } catch (e) {
    throw new Error(`executeAiQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/** True if the cell-shaped value is an AiCell (has .formatted), false
 *  otherwise (e.g. row-header string). Cheap structural narrow. */
export function isAiCell(v: unknown): v is AiCell {
  return typeof v === "object" && v !== null && "formatted" in (v as object);
}

/** POST /rest/saiku/api/ai/query/saved — resolver for reference-bound
 *  tiles. The server loads the .saiku file from the JCR, runs the
 *  ThinQuery, and returns the same AiQueryResponse shape inline tiles
 *  already render. */
export async function executeSavedQuery(path: string): Promise<AiQueryResponse> {
  const res = await fetch(`${REST_BASE}/query/saved`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({ path }),
  });
  const text = await res.text();
  if (!text) throw new Error(`executeSavedQuery -> ${res.status}: empty body`);
  try {
    return JSON.parse(text) as AiQueryResponse;
  } catch (e) {
    throw new Error(`executeSavedQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}
