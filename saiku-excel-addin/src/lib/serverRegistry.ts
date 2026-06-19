import type { ServerEntry } from "./types";

export interface Registry { servers: ServerEntry[]; activeId: string | null; }

export function addServer(r: Registry, entry: ServerEntry): Registry {
  const servers = [...r.servers.filter(s => s.id !== entry.id), entry];
  return { servers, activeId: r.activeId ?? entry.id };
}

export function removeServer(r: Registry, id: string): Registry {
  const servers = r.servers.filter(s => s.id !== id);
  const activeId = r.activeId === id ? (servers[0]?.id ?? null) : r.activeId;
  return { servers, activeId };
}

export function setActive(r: Registry, id: string): Registry {
  return r.servers.some(s => s.id === id) ? { ...r, activeId: id } : r;
}

export function activeServer(r: Registry): ServerEntry | null {
  return r.servers.find(s => s.id === r.activeId) ?? null;
}

export function normalizeBaseUrl(url: string): string {
  let u = url.trim().replace(/\/+$/, "");
  u = u.replace(/\/rest$/, "");
  return u;
}
