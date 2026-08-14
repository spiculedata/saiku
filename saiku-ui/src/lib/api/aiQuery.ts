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

// saiku#1803: /ai/ossie/query answers in its own envelope; this maps it onto
// AiQueryResponse so every tile renderer is unchanged. See ossieResponse.ts.
import { toAiQueryResponse } from "$lib/dashboard/ossieResponse";

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

/** Per-point statistical anomaly verdict (saiku#907). Attached to a cell by
 *  POST /ai/anomaly only when the detector flagged that point. */
export interface AnomalyPoint {
  /** Unsigned deviation in detector-native units (sigmas for zscore/mad). */
  score: number;
  /** Central tendency the point was compared against (mean / median). */
  expected: number;
  /** "above" / "below" relative to expected, or null on a tie. */
  direction?: "above" | "below" | null;
  /** Always true on attached cells (non-anomalous cells carry no AnomalyPoint). */
  anomaly: boolean;
}

export interface AiCell {
  value: number | null;
  formatted: string;
  unit?: string | null;
  properties?: Record<string, string>;
  /** Set only on cells flagged by the /ai/anomaly endpoint (saiku#907). */
  anomaly?: AnomalyPoint | null;
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

/**
 * POST /ai/ossie/query — the semantic-model twin of {@link executeAiQuery}
 * (saiku#1803).
 *
 * A separate endpoint because neither half matches. The REQUEST shapes share no
 * fields: a cube query names measures and dimension/hierarchy/level axes, a
 * model query names metrics and dataset/field axes. And the RESPONSE envelope
 * differs too — rows arrive as `records`, the column descriptors are top-level
 * rather than under `metadata`, and there is no `status` on success:
 *
 *   { queryId, columns: [...], records: [...], meta: { rowCount }, runtime }
 *
 * What DOES match is the cell: `{value, formatted}` for a measure, a plain
 * string for a row header. So `toAiQueryResponse` renames the wrapper and every
 * renderer downstream of this function stays untouched, which is the property
 * the whole feature rests on.
 *
 * (The surfaces are described as "the same shape" in docs/AI-OSSIE-API.md. That
 * is true of the request semantics and the cells, and false of the envelope —
 * believing it wholesale is what made the first cut of this render nothing.)
 */
export async function executeOssieQuery(
  body: Record<string, unknown>,
  format: "records" | "matrix" = "records",
): Promise<AiQueryResponse> {
  const url = `${REST_BASE}/ossie/query?format=${encodeURIComponent(format)}`;
  const res = await fetch(url, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!text) {
    throw new Error(`executeOssieQuery -> ${res.status}: empty body`);
  }
  try {
    // Both the 200 and the 400 bodies go through the adapter — it normalises
    // the two error shapes this endpoint can emit into the same
    // status/error/field/available envelope the tiles already render.
    return toAiQueryResponse(JSON.parse(text));
  } catch (e) {
    throw new Error(`executeOssieQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/** True if the cell-shaped value is an AiCell (has .formatted), false
 *  otherwise (e.g. row-header string). Cheap structural narrow. */
export function isAiCell(v: unknown): v is AiCell {
  return typeof v === "object" && v !== null && "formatted" in (v as object);
}

/* ------------------------- member search (#1166) ------------------------- */

/** One hit from GET /ai/members/search — a real cube member. `uniqueName`
 *  is the server's own MDX unique name (canonical, or display-aliased
 *  exactly as the server emits it) so it round-trips back into /ai/query
 *  filters without us ever hand-assembling a `[dim].[hier].[member]` string. */
export interface AiMemberHit {
  uniqueName: string;
  caption: string;
  name?: string;
}

/** GET /rest/saiku/api/ai/members/search — discover real members of a level.
 *  `dimension` + `level` are required server-side; `hierarchy` may be blank.
 *  `q` is a case-insensitive substring match on the caption. Resolves to an
 *  empty list on any non-OK / transport / parse failure so callers can treat
 *  "couldn't resolve" as "no members" rather than throwing. */
export async function searchMembers(
  cube: { connectionName: string; catalog: string; schema: string; cubeName: string },
  dimension: string,
  hierarchy: string,
  level: string,
  q: string,
  limit = 100,
): Promise<AiMemberHit[]> {
  const cubeId = `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`;
  const params = new URLSearchParams({ cubeId, dimension, level, limit: String(limit) });
  if (hierarchy) params.set("hierarchy", hierarchy);
  if (q) params.set("q", q);
  try {
    const res = await fetch(`${REST_BASE}/members/search?${params.toString()}`, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) return [];
    const hits: unknown = await res.json();
    return Array.isArray(hits) ? (hits as AiMemberHit[]) : [];
  } catch {
    return [];
  }
}

/* ------------------------- share view (#941 PR2) ------------------------- */

/** Base for the account-free guest view surface. The token is carried in the
 *  `X-Saiku-Share-Token` header (NOT a cookie / query param) — see the
 *  hardened backend. */
const SHARE_VIEW_BASE = "/rest/saiku/share/view";

/** Run one tile's authored query under the share owner's scope (#941). The
 *  guest supplies no query body — only the tile id, validated server-side
 *  against the token-pinned dashboard. */
export async function runSharedTileQuery(
  token: string,
  tileId: string,
  format: "records" | "matrix" = "records",
): Promise<AiQueryResponse> {
  const url = `${SHARE_VIEW_BASE}/tile/${encodeURIComponent(tileId)}/query?format=${encodeURIComponent(format)}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "X-Saiku-Share-Token": token, Accept: "application/json" },
  });
  const text = await res.text();
  if (!text) {
    throw new Error(`runSharedTileQuery -> ${res.status}: empty body`);
  }
  try {
    return JSON.parse(text) as AiQueryResponse;
  } catch (e) {
    throw new Error(`runSharedTileQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/** Slicer-style filter shape that /ai/query/saved accepts to merge
 *  dashboard-level filters onto the loaded ThinQuery. Mirrors the
 *  server-side AiFilterSelection: dim/hier/level + canonical MDX member
 *  unique names. */
export interface SavedQueryFilter {
  dimension: string;
  hierarchy: string;
  level: string;
  members: string[];
}

/** POST /rest/saiku/api/ai/query/saved — resolver for reference-bound
 *  tiles. The server loads the .saiku file from the JCR, applies any
 *  supplied filters (axis-rewrite when the hierarchy is already on an
 *  axis, otherwise slicer; see server-side ThinQueryFilterMerge), runs
 *  the ThinQuery, and returns the same AiQueryResponse shape inline tiles
 *  already render. */
/** Defensive: legacy dashboards may have persisted absolute filesystem
 *  paths from the old listing API. Strip the saiku-home prefix before
 *  posting so the server-side ThinQuery lookup always sees clean
 *  repo-relative names. */
function stripSavedQueryPath(path: string): string {
  const m = path.match(/^.*?\/data\/[^/]+\/(.+)$/);
  return m ? m[1] : path;
}

/* ------------------------------------------------------------------------
 * Issue #930 — drillthrough on cell click.
 *
 * Drills the raw fact rows behind one aggregated cell of an already-executed
 * AI query, addressed by its server-assigned queryId. The dashboard tile
 * renderers consume this when a data point (chart) / measure cell (table) is
 * right-clicked. Active dashboard filters are honoured automatically: the
 * query identified by `queryId` already had them merged at execution time.
 * ---------------------------------------------------------------------- */

/** Flat tabular drillthrough payload — one map per fact row, keyed by the
 *  projected column captions in `columns`. Mirrors the server response of
 *  GET /ai/query/{queryId}/drillthrough. */
export interface AiDrillthroughResult {
  queryId: string;
  rowCount: number;
  columns: string[];
  rows: Array<Record<string, AiCell>>;
}

export interface AiDrillthroughOptions {
  /** `"{columnIndex}:{rowIndex}"` addressing the cell to drill. Omit to
   *  drill the whole result. Malformed values yield a server 400. */
  position?: string;
  /** Cap on returned fact rows. */
  maxRows?: number;
  /** Dimension/measure unique names to project. Omit for all columns. */
  returns?: string[];
}

/** GET /rest/saiku/api/ai/query/{queryId}/drillthrough — fetch the raw fact
 *  rows behind one (or all) cell(s) of a prior AI query. Throws on a non-ok
 *  response, surfacing the server's error message when one is present. */
export async function aiDrillthrough(
  queryId: string,
  opts: AiDrillthroughOptions = {},
): Promise<AiDrillthroughResult> {
  const params = new URLSearchParams();
  if (opts.position) params.set("position", opts.position);
  if (opts.maxRows != null) params.set("maxrows", String(opts.maxRows));
  if (opts.returns && opts.returns.length) params.set("returns", opts.returns.join(","));
  const qs = params.toString();
  const url = `${REST_BASE}/query/${encodeURIComponent(queryId)}/drillthrough${qs ? `?${qs}` : ""}`;
  const res = await fetch(url, {
    method: "GET",
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  const text = await res.text();
  if (!res.ok) {
    // Prefer the server's structured error message when the body parses.
    let msg = `aiDrillthrough -> ${res.status}`;
    if (text) {
      try {
        const parsed = JSON.parse(text) as { error?: string; message?: string };
        if (parsed.error || parsed.message) msg = parsed.error ?? parsed.message ?? msg;
      } catch {
        msg = `${msg}: ${text}`;
      }
    }
    throw new Error(msg);
  }
  if (!text) throw new Error(`aiDrillthrough -> ${res.status}: empty body`);
  try {
    return JSON.parse(text) as AiDrillthroughResult;
  } catch (e) {
    throw new Error(`aiDrillthrough -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/* ------------------------------------------------------------------------
 * Issue #907 — server-side statistical anomaly detection.
 *
 * Runs the tile's authored query through the same path /ai/query uses, then
 * flags anomalous points along the time axis with the chosen detector. The
 * returned response is the standard AiQueryResponse (records format) with an
 * `anomaly:{score,expected,direction}` object on each flagged cell, alongside
 * a compact summary block (method / threshold / timeAxis / anomalyCount).
 * ---------------------------------------------------------------------- */

export type AnomalyMethod = "zscore" | "mad" | "stl";

export interface AnomalySummary {
  method: string;
  threshold: number;
  timeAxis: string;
  /** Explicit count — 0 (never absent) when no anomalies were found. */
  anomalyCount: number;
}

export interface AiAnomalyResponse {
  response: AiQueryResponse;
  anomaly: AnomalySummary;
}

export interface AiAnomalyOptions {
  method?: AnomalyMethod;
  /** Detector cutoff; omit to use the method default (zscore 3.0, mad 3.5). */
  threshold?: number;
  /** Unique name of the time axis to scan. */
  timeAxis: string;
}

/** POST /rest/saiku/api/ai/anomaly. On a validation error the server returns a
 *  400 whose body is a bare AiQueryResponse (status VALIDATION_ERROR); we
 *  surface that as a thrown Error carrying the server message so the tile can
 *  fall back to the plain chart. */
export async function detectAnomalies(
  query: Record<string, unknown>,
  opts: AiAnomalyOptions,
): Promise<AiAnomalyResponse> {
  const body: Record<string, unknown> = {
    query,
    timeAxis: opts.timeAxis,
    method: opts.method ?? "zscore",
  };
  if (opts.threshold != null) body.threshold = opts.threshold;
  const res = await fetch(`${REST_BASE}/anomaly`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!text) throw new Error(`detectAnomalies -> ${res.status}: empty body`);
  if (!res.ok) {
    // 400 bodies are the bare AiQueryResponse validation envelope.
    let msg = `detectAnomalies -> ${res.status}`;
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      if (parsed.error || parsed.message) msg = parsed.error ?? parsed.message ?? msg;
    } catch {
      msg = `${msg}: ${text}`;
    }
    throw new Error(msg);
  }
  try {
    return JSON.parse(text) as AiAnomalyResponse;
  } catch (e) {
    throw new Error(`detectAnomalies -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/* ------------------------------------------------------------------------
 * Issue #908 — server-side time-series forecast.
 *
 * Runs the tile's query through the same path /ai/query uses, then projects each
 * measure series `horizon` steps. The observed response is returned UNCHANGED;
 * the projection lives in a sibling `forecast` block keyed by measure caption,
 * each point carrying {value, lower, upper, forecast:true}.
 * ---------------------------------------------------------------------- */

export type ForecastMethod = "ets" | "arima" | "prophet";

/** One projected horizon point + its prediction interval. */
export interface ForecastPoint {
  value: number;
  lower: number;
  upper: number;
  forecast: boolean;
}

export interface ForecastSummary {
  method: string;
  horizon: number;
  confidence: number;
  timeAxis: string;
  /** measure caption → horizon forecast points (in time order). */
  series: Record<string, ForecastPoint[]>;
}

export interface AiForecastResponse {
  response: AiQueryResponse;
  forecast: ForecastSummary;
}

export interface AiForecastOptions {
  method?: ForecastMethod;
  /** Future points to project (server clamps 1–365). */
  horizon?: number;
  /** Prediction-interval confidence in (0,1). */
  confidence?: number;
  /** Unique name of the time axis to project along. */
  timeAxis: string;
}

/** POST /rest/saiku/api/ai/forecast. On a validation error the server returns a
 *  400 whose body is a bare AiQueryResponse (status VALIDATION_ERROR); we
 *  surface that as a thrown Error carrying the server message so the tile can
 *  fall back to the plain chart. */
export async function forecastQuery(
  query: Record<string, unknown>,
  opts: AiForecastOptions,
): Promise<AiForecastResponse> {
  const body: Record<string, unknown> = {
    query,
    timeAxis: opts.timeAxis,
    method: opts.method ?? "ets",
  };
  if (opts.horizon != null) body.horizon = opts.horizon;
  if (opts.confidence != null) body.confidence = opts.confidence;
  const res = await fetch(`${REST_BASE}/forecast`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!text) throw new Error(`forecastQuery -> ${res.status}: empty body`);
  if (!res.ok) {
    let msg = `forecastQuery -> ${res.status}`;
    try {
      const parsed = JSON.parse(text) as { error?: string; message?: string };
      if (parsed.error || parsed.message) msg = parsed.error ?? parsed.message ?? msg;
    } catch {
      msg = `${msg}: ${text}`;
    }
    throw new Error(msg);
  }
  try {
    return JSON.parse(text) as AiForecastResponse;
  } catch (e) {
    throw new Error(`forecastQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}

/** Build the GET URL for the CSV export of a drillthrough (issue #1051).
 *  Mirrors {@link aiDrillthrough}'s params (position / maxRows / returns) but
 *  targets the streaming text/csv endpoint. The URL is same-origin and the
 *  browser sends session credentials automatically, so the download can be
 *  triggered with `window.open(url)` / an `<a download>` — no fetch needed. */
export function aiDrillthroughCsvUrl(queryId: string, opts: AiDrillthroughOptions = {}): string {
  const params = new URLSearchParams();
  if (opts.position) params.set("position", opts.position);
  if (opts.maxRows != null) params.set("maxrows", String(opts.maxRows));
  if (opts.returns && opts.returns.length) params.set("returns", opts.returns.join(","));
  const qs = params.toString();
  return `${REST_BASE}/query/${encodeURIComponent(queryId)}/drillthrough/export/csv${qs ? `?${qs}` : ""}`;
}

/** Trigger a browser download of the drillthrough CSV (issue #1051). The
 *  endpoint sets `Content-Disposition: attachment`, so opening the
 *  same-origin authenticated GET in a new tab streams the file straight to
 *  the user's downloads — the same mechanism the Workspace export uses. */
export function downloadAiDrillthroughCsv(queryId: string, opts: AiDrillthroughOptions = {}): void {
  window.open(aiDrillthroughCsvUrl(queryId, opts), "_blank");
}

export async function executeSavedQuery(
  path: string,
  filters: SavedQueryFilter[] = [],
): Promise<AiQueryResponse> {
  const res = await fetch(`${REST_BASE}/query/saved`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({ path: stripSavedQueryPath(path), filters }),
  });
  const text = await res.text();
  if (!text) throw new Error(`executeSavedQuery -> ${res.status}: empty body`);
  try {
    return JSON.parse(text) as AiQueryResponse;
  } catch (e) {
    throw new Error(`executeSavedQuery -> ${res.status}: non-JSON response (${(e as Error).message})`);
  }
}
