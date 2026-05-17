/*
 * Dashboards REST client.
 *
 * Mirrors org.saiku.web.rest.resources.dashboards.DashboardResource on the
 * Java side. CRUD only — query execution still flows through
 * $lib/api/query (per-tile, with merged filters computed client-side).
 *
 * See docs/plans/2026-05-16-dashboards-design.md for the full design.
 */

import type { ThinQuery } from "$lib/api/query";

const REST_BASE = "/rest/saiku/api/dashboards";

/** Cube ref shape — matches AiCubeRef on the Java side. */
export interface CubeRef {
  connectionName: string;
  catalog: string;
  schema: string;
  cubeName: string;
}

/** Saved-query reference. */
export interface ReferenceQuery {
  kind: "reference";
  path: string;
}

/** Inline query embedded in the tile. {@code body} matches AiQueryRequest. */
export interface InlineQuery {
  kind: "inline";
  // Loose body shape — the typed AiQueryRequest types live in $lib/api/query;
  // the dashboard layer treats it opaquely (read in, hand to /ai/query out).
  body: Record<string, unknown>;
}

export type TileQuery = ReferenceQuery | InlineQuery;

export type TileType = "chart" | "table" | "text" | "filter";

export type FilterWidget = "single-select" | "multi-select" | "date-range";

/** A dim/hier/level filter — used as both a dashboard-level default and
 *  as the {@code target} of a filter-widget tile. */
export interface DashboardFilter {
  dimension: string;
  hierarchy: string;
  level: string;
  members: string[]; // MDX unique names; empty = "any"
}

export interface DashboardTile {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
  type: TileType;
  title?: string;
  cube?: CubeRef;
  query?: TileQuery;
  chartType?: string;
  text?: string;
  target?: DashboardFilter;
  widget?: FilterWidget;
}

export interface DashboardLayout {
  cols: number;
  tiles: DashboardTile[];
}

export interface Dashboard {
  id: string;
  name: string;
  version: number;
  layout: DashboardLayout;
  filters: DashboardFilter[];
}

export interface DashboardSaveResponse {
  status: "OK";
  path: string;
}

export interface DashboardErrorResponse {
  status: "NOT_FOUND" | "VALIDATION_ERROR" | "ERROR";
  field?: string;
  error: string;
  path?: string;
}

/** Load a dashboard by repository path. Throws on 4xx / 5xx with the
 *  parsed error body when available. */
export async function loadDashboard(path: string): Promise<Dashboard> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    const err = await readError(res);
    throw new Error(`loadDashboard(${path}) -> ${res.status}: ${err}`);
  }
  return (await res.json()) as Dashboard;
}

/** Save (create or overwrite) a dashboard. */
export async function saveDashboard(path: string, dashboard: Dashboard): Promise<DashboardSaveResponse> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(dashboard),
  });
  if (!res.ok) {
    const err = await readError(res);
    throw new Error(`saveDashboard(${path}) -> ${res.status}: ${err}`);
  }
  return (await res.json()) as DashboardSaveResponse;
}

export async function deleteDashboard(path: string): Promise<void> {
  const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
    method: "DELETE",
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    const err = await readError(res);
    throw new Error(`deleteDashboard(${path}) -> ${res.status}: ${err}`);
  }
}

/** Build an empty Dashboard with a fresh id, ready to be populated by the
 *  editor before the first save. */
export function newDashboard(name = "Untitled dashboard"): Dashboard {
  return {
    id: cryptoUuid(),
    name,
    version: 1,
    layout: { cols: 12, tiles: [] },
    filters: [],
  };
}

/** AI Query API cube summary — what /ai/cubes returns. Used by the
 *  add-tile flow's cube picker. */
export interface AiCubeSummary {
  connectionName: string;
  catalog: string;
  schema: string;
  cubeName: string;
  cubeCaption?: string;
  defaultMeasure?: string;
  measureCount?: number;
}

/** GET /rest/saiku/api/ai/cubes — list of cubes the current user can
 *  query. Same endpoint MCP's list_cubes wraps. */
export async function listAiCubes(): Promise<AiCubeSummary[]> {
  const res = await fetch("/rest/saiku/api/ai/cubes", {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`listAiCubes -> ${res.status}`);
  return (await res.json()) as AiCubeSummary[];
}

/** Mint a fresh tile id for the add-tile flow. Exposed so the modal
 *  can do it inline rather than reaching into the internals. */
export function newTileId(): string {
  return cryptoUuid();
}

/* ---------------------------- internals ---------------------------- */

/** URL-encode each path segment but leave slashes as-is — DashboardResource
 *  binds {path:.+} which captures slashes natively. */
function encodePath(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

async function readError(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as DashboardErrorResponse;
    return body.error ?? JSON.stringify(body);
  } catch {
    return res.statusText;
  }
}

function cryptoUuid(): string {
  // Same generator pattern as query.svelte.ts — avoid the global UUID
  // helper import to keep this module standalone.
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Kept for forward compatibility — the inline TileQuery body uses the same
// shape ThinQuery already round-trips through the AI API.
export type { ThinQuery };
