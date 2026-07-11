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
): Promise<EmbedQueryResponse> {
  const base = stripTrailingSlash(server);
  const suffix = format === "matrix" ? "?format=matrix" : "";
  const url = `${base}/rest/saiku/api/embed/query/${encodePath(path)}${suffix}`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) {
    headers["X-Saiku-Embed-Token"] = token;
  }
  const resp = await fetch(url, { headers, credentials: "omit" });
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
): Promise<EmbedQueryResponse> {
  const base = stripTrailingSlash(server);
  const url =
    `${base}/rest/saiku/api/embed/dashboard/${encodePath(dashboardPath)}` +
    `/tile/${encodeURIComponent(tileId)}/query`;
  const headers: Record<string, string> = { Accept: "application/json" };
  if (token) headers["X-Saiku-Embed-Token"] = token;
  const resp = await fetch(url, { method: "POST", headers, credentials: "omit" });
  if (!resp.ok) throw await readError(resp);
  return (await resp.json()) as EmbedQueryResponse;
}

/* Re-export the dashboard shape so callers don't have to dig into types.ts. */
export type { EmbedDashboardLayout, EmbedDashboardTile } from "./types";

export { EmbedFetchError };
