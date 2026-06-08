const BASE = "/rest/saiku/admin";

export interface AdminUser {
  id: number;
  username: string;
  email?: string | null;
  password?: string;
  roles: string[];
}

export interface AdminDatasource {
  id: string;
  name: string;
  connectionName?: string;
  driver?: string;
  location?: string;
  type?: string;
  username?: string;
  password?: string;
  properties?: Record<string, string>;
  schemaName?: string | null;
}

export interface AdminSchema {
  id?: string;
  name: string;
  path?: string;
  type?: string;
  xml?: string;
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return (await res.json()) as T;
}

async function json<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T | null> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    credentials: "include",
    headers: body ? { "Content-Type": "application/json", Accept: "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : null;
}

export const users = {
  list: () => get<AdminUser[]>("/users"),
  get: (id: number) => get<AdminUser>(`/users/${id}`),
  create: (u: AdminUser) => json<AdminUser>("POST", "/users", u),
  update: (u: AdminUser) => json<AdminUser>("PUT", `/users/${encodeURIComponent(u.username)}`, u),
  remove: (username: string) => json<null>("DELETE", `/users/${encodeURIComponent(username)}`),
};

/**
 * Derive the set of distinct roles from the admin user listing. The server
 * does not expose a dedicated `/admin/roles` endpoint; the role universe is
 * the union of every user's role grants. Includes ROLE_ADMIN even when no
 * user has it (it's the privileged role and the ACL editor always wants to
 * show it as an option).
 */
export async function listAllRoles(): Promise<string[]> {
  const all = await users.list();
  const set = new Set<string>(["ROLE_ADMIN", "ROLE_USER"]);
  for (const u of all) {
    for (const r of u.roles) set.add(r);
  }
  return Array.from(set).sort();
}

export const adminDatasources = {
  list: () => get<AdminDatasource[]>("/datasources"),
  refresh: (id: string) => json<AdminDatasource>("PUT", `/datasources/${encodeURIComponent(id)}/refresh`),
  create: (ds: AdminDatasource) => json<AdminDatasource>("POST", "/datasources", ds),
  update: (ds: AdminDatasource) =>
    json<AdminDatasource>("PUT", `/datasources/${encodeURIComponent(ds.id)}`, ds),
  remove: (id: string) => json<null>("DELETE", `/datasources/${encodeURIComponent(id)}`),
};

export const adminSchemas = {
  list: () => get<AdminSchema[]>("/schema"),
  get: (id: string) => get<AdminSchema>(`/schema/${encodeURIComponent(id)}`),
  upload: async (name: string, xml: string, path?: string) => {
    const body = new URLSearchParams({ name, xml });
    if (path) body.set("path", path);
    const res = await fetch(`${BASE}/schema/${encodeURIComponent(name)}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    if (!res.ok) throw new Error(`schema upload -> ${res.status}`);
  },
  remove: (id: string) => json<null>("DELETE", `/schema/${encodeURIComponent(id)}`),
};

export const adminLogs = {
  fetch: async (name: string): Promise<string> => {
    const res = await fetch(`${BASE}/log/${encodeURIComponent(name)}`, {
      credentials: "include",
      headers: { Accept: "text/plain" },
    });
    if (!res.ok) throw new Error(`log -> ${res.status}`);
    return res.text();
  },
};

export async function getVersion(): Promise<string> {
  const res = await fetch(`${BASE}/version`, { credentials: "include" });
  if (!res.ok) throw new Error(`version -> ${res.status}`);
  return res.text();
}

/* ---------------- Mondrian statistics ---------------- */

export interface MondrianVersion {
  productName?: string;
  versionString?: string;
  majorVersion?: number;
  minorVersion?: number;
}

export interface MondrianServerInfo {
  cellCount?: number;
  cellCoordinateCount?: number;
  cellSize?: number;
  segmentCount?: number;
  segmentCreateCount?: number;
  cellCacheRequestCount?: number;
  cellCacheHitCount?: number;
  cellCacheMissCount?: number;
  sqlStatementExecuteCount?: number;
  sqlStatementCellRequestCount?: number;
  startTimeMillis?: number;
  [k: string]: unknown;
}

/**
 * Mondrian connection-info shape as actually emitted by
 * {@code /rest/saiku/statistics/mondrian}. Verified against the
 * 4.8 server's StatementInfo/ConnectionInfo output 2026-06-07 —
 * the legacy `connectionId` / `catalogName` / `startTimeMillis`
 * fields are NOT present at runtime, so don't add them back as
 * optional unless you've confirmed mondrian-saiku now emits them.
 */
export interface MondrianConnectionInfo {
  stack?: string | null;
  cellCacheHitCount?: number;
  cellCacheRequestCount?: number;
  cellCacheMissCount?: number;
  cellCachePendingCount?: number;
  statementStartCount?: number;
  statementEndCount?: number;
  executeStartCount?: number;
  executeEndCount?: number;
  [k: string]: unknown;
}

/**
 * Mondrian statement-info shape as actually emitted by the server.
 * No mdx / state / startTimeMillis here — Mondrian's StatementInfo
 * carries counters, not a snapshot of the SQL text or wall-clock.
 */
export interface MondrianStatementInfo {
  statementId?: number;
  stack?: string | null;
  executing?: boolean;
  executeStartCount?: number;
  executeEndCount?: number;
  phaseCount?: number;
  cellRequestCount?: number;
  cellCacheHitCount?: number;
  cellCacheMissCount?: number;
  cellCacheRequestCount?: number;
  cellCachePendingCount?: number;
  sqlStatementStartCount?: number;
  sqlStatementExecuteCount?: number;
  sqlStatementEndCount?: number;
  sqlStatementRowFetchCount?: number;
  sqlStatementExecuteNanos?: number;
  [k: string]: unknown;
}

export interface MondrianStats {
  server: MondrianServerInfo;
  version: MondrianVersion;
  openConnectionCount: number;
  openMdxStatementCount: number;
  openSqlStatementCount: number;
  executingMdxStatementCount: number;
  avgCellDimensionality: number;
  connections: MondrianConnectionInfo[];
  statements: MondrianStatementInfo[];
}

const STATS_BASE = "/rest/saiku/statistics";

export const adminStats = {
  mondrian: async (): Promise<MondrianStats | null> => {
    const res = await fetch(`${STATS_BASE}/mondrian`, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) throw new Error(`stats/mondrian -> ${res.status}`);
    const text = await res.text();
    return text ? (JSON.parse(text) as MondrianStats) : null;
  },
};
