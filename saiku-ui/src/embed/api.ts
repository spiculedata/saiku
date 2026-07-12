import type { EmbedDashboardLayout, EmbedError, EmbedQueryResponse } from "./types";

/**
 * Fetch a saved query through the embed surface. Sends the token via the
 * `X-Saiku-Embed-Token` header (NEVER as `?token=` — same posture as
 * `ShareTokenAuthFilter`, since URL params leak into access logs, proxy
 * logs, browser history, and outbound `Referer`).
 *
 * Anonymous public reads simply omit the token; the server admits them
 * when the resource has a matching {@link EmbedPublicGrant}.
 *
 * @param server   Origin where the Saiku launcher is serving (e.g.
 *                 `https://demo.saiku.bi`). Stripped of any trailing slash.
 * @param path     Repository path of the saved query (`.saiku`), e.g.
 *                 `homes/admin/Examples/Heatgrid.saiku`. The fetcher
 *                 URL-encodes each segment so paths containing spaces
 *                 round-trip correctly.
 * @param token    Optional. When present, sent as the bearer header.
 */
export async function fetchSavedQuery(
  server: string,
  path: string,
  token?: string | null,
  format: "records" | "matrix" = "records",
  filters?: EmbedFilterOverride[] | null,
): Promise<EmbedQueryResponse> {
  const base = stripTrailingSlash(server);
  const suffix = format === "matrix" ? "?format=matrix" : "";
  const url = `${base}/rest/saiku/api/embed/query/${encodePath(path)}${suffix}`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) {
    headers["X-Saiku-Embed-Token"] = token;
  }
  // Filters at embed time ride the same POST channel + validated slicer path the
  // dashboard tile overrides already use (EmbedViewResource#queryFiltered). Absent
  // filters keep the original GET so existing embeds and their wire tests are unchanged.
  const hasFilters = Array.isArray(filters) && filters.length > 0;
  const init: RequestInit = { headers, credentials: "omit" };
  if (hasFilters) {
    headers["Content-Type"] = "application/json";
    init.method = "POST";
    init.body = JSON.stringify({ filters });
  }
  const resp = await fetch(url, init);
  if (!resp.ok) {
    throw await readError(resp);
  }
  return (await resp.json()) as EmbedQueryResponse;
}

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}

/**
 * Encode each path segment but preserve the slashes — `homes/admin/My
 * Sales.saiku` → `homes/admin/My%20Sales.saiku`. The auth filter expects
 * this exact shape because it then URL-decodes the segment to compare
 * against the canonical stored path (see EmbedAuthFilter.parseTarget).
 */
function encodePath(path: string): string {
  return path
    .split("/")
    .map((seg) => encodeURIComponent(seg))
    .join("/");
}

class EmbedFetchError extends Error {
  constructor(
    public status: number,
    public body: EmbedError,
  ) {
    super(body.error ?? `Embed fetch failed: HTTP ${status}`);
    this.name = "EmbedFetchError";
  }
}

async function readError(resp: Response): Promise<EmbedFetchError> {
  let body: EmbedError = { status: "ERROR" };
  try {
    body = (await resp.json()) as EmbedError;
  } catch {
    /* server returned non-JSON; fall back to a synthesised envelope */
    body = { status: "ERROR", error: `HTTP ${resp.status}` };
  }
  return new EmbedFetchError(resp.status, body);
}

/**
 * Fetch the layout of a saved dashboard. Returns the raw JSON shape;
 * the caller walks `dash.layout.tiles` and dispatches by tile type.
 *
 * Same header / encoding posture as {@link fetchSavedQuery}.
 */
export async function fetchDashboard(
  server: string,
  path: string,
  token?: string | null,
): Promise<EmbedDashboardLayout> {
  const base = stripTrailingSlash(server);
  const url = `${base}/rest/saiku/api/embed/dashboard/${encodePath(path)}`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers["X-Saiku-Embed-Token"] = token;
  const resp = await fetch(url, { headers, credentials: "omit" });
  if (!resp.ok) throw await readError(resp);
  return (await resp.json()) as EmbedDashboardLayout;
}

/**
 * Run one tile's authored query through the embed surface. The server
 * pulls the query body from the pinned dashboard, so the client only
 * supplies the tile id — the tile body / cube binding is never
 * accepted from the client side.
 */
export async function fetchDashboardTile(
  server: string,
  dashboardPath: string,
  tileId: string,
  token?: string | null,
  overrides?: EmbedFilterOverride[],
): Promise<EmbedQueryResponse> {
  const base = stripTrailingSlash(server);
  const url =
    `${base}/rest/saiku/api/embed/dashboard/${encodePath(dashboardPath)}` +
    `/tile/${encodeURIComponent(tileId)}/query`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers["X-Saiku-Embed-Token"] = token;
  const body = overrides && overrides.length > 0 ? JSON.stringify({ filters: overrides }) : undefined;
  if (body) headers["Content-Type"] = "application/json";
  const resp = await fetch(url, { method: "POST", headers, credentials: "omit", body });
  if (!resp.ok) throw await readError(resp);
  return (await resp.json()) as EmbedQueryResponse;
}

/**
 * Fetch the distinct member captions available for a filter tile's declared
 * dimension/hierarchy/level. Guest supplies only the tile id — the target axis
 * comes from the pinned dashboard, so a guest can't fish for arbitrary members.
 */
export async function fetchTileMembers(
  server: string,
  dashboardPath: string,
  tileId: string,
  token?: string | null,
  q?: string,
  limit = 50,
): Promise<EmbedMember[]> {
  const base = stripTrailingSlash(server);
  const qs = new URLSearchParams();
  if (q) qs.set("q", q);
  qs.set("limit", String(limit));
  const url =
    `${base}/rest/saiku/api/embed/dashboard/${encodePath(dashboardPath)}` +
    `/tile/${encodeURIComponent(tileId)}/members?${qs.toString()}`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers["X-Saiku-Embed-Token"] = token;
  const resp = await fetch(url, { headers, credentials: "omit" });
  if (!resp.ok) throw await readError(resp);
  return (await resp.json()) as EmbedMember[];
}

/**
 * Filter override sent to the tile query endpoint — matches the server's
 * {@code AiFilterSelection} shape. Dimension + hierarchy + level identify which
 * axis the filter drives; empty {@code members} clears the filter.
 */
export interface EmbedFilterOverride {
  dimension: string;
  hierarchy?: string | null;
  level: string;
  members: string[];
}

/**
 * One entry in the members list returned by {@link fetchTileMembers}. Mirrors
 * the AI Query API's SimpleCubeElement — {@code caption} is the display name and
 * {@code uniqueName} is what the server expects back in an override's {@code members}.
 */
export interface EmbedMember {
  name: string;
  caption: string;
  uniqueName: string;
}

/**
 * Ask a plain-English question against a kind="ai" embed. The cube ref is pinned
 * by the token; the client supplies only the question and optional history.
 *
 * The response is the same {@code AiAskApi.AskResponse} shape agent clients already know.
 * We return it as a typed record — callers can render the answer text, drill into
 * the generated MDX, or surface the executed query envelope.
 */
export async function askEmbedAi(
  server: string,
  cubeId: string,
  token: string | null | undefined,
  question: string,
  history?: EmbedAskMessage[],
  space?: string | null,
): Promise<EmbedAskResponse> {
  const base = stripTrailingSlash(server);
  const url =
    `${base}/rest/saiku/api/embed/ai/${encodePath(cubeId)}/ask`;
  const headers: Record<string, string> = {
    Accept: "application/json",
    "Content-Type": "application/json",
  };
  if (token) headers["X-Saiku-Embed-Token"] = token;
  // `space` names an admin-authored Agent Space persona (saiku#1440). When present the
  // server routes the ask through askInSpace, which prepends the persona prompt, filters
  // the skill catalogue, and enforces the space's cube allowlist — the pinned cube stays
  // pinned, so the space can only narrow, never widen, what a guest reaches.
  const sp = (space ?? "").trim();
  const body = JSON.stringify(sp ? { question, history: history ?? [], space: sp } : { question, history: history ?? [] });
  const resp = await fetch(url, { method: "POST", headers, credentials: "omit", body });
  if (!resp.ok) throw await readError(resp);
  return (await resp.json()) as EmbedAskResponse;
}

/** One turn of ask history. Role is "user" or "assistant". */
export interface EmbedAskMessage {
  role: "user" | "assistant";
  content: string;
}

/**
 * Response from the AI ask endpoint. Mirrors {@code AiAskApi.AskResponse} — narrated
 * answer, generated MDX (for debugging), the executed request/response envelope.
 * Fields the widget doesn't render round-trip via {@code unknown}.
 */
export interface EmbedAskResponse {
  answer?: string;
  narrative?: string;
  degraded?: boolean;
  reason?: string;
  mdx?: string;
  request?: unknown;
  response?: unknown;
}

/* Re-export the dashboard shape so callers don't have to dig into types.ts. */
export type { EmbedDashboardLayout, EmbedDashboardTile } from "./types";

export { EmbedFetchError };
