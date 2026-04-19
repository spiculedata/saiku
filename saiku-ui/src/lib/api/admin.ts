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
