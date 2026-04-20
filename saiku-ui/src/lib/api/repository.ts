import type { ThinQuery } from "$lib/api/query";

const REST_BASE = "/rest/saiku";

export interface RepositoryNode {
  path: string;
  name: string;
  type: "FOLDER" | "FILE";
  fileType?: string | null;
  acl?: string | null;
  repoObjects?: RepositoryNode[];
}

/** A flattened descriptor for the saved-queries browser. */
export interface SavedQueryFile {
  path: string;
  name: string;
  modified?: string;
  type: "saiku";
}

export async function listRepository(type: string[] = ["saiku"]): Promise<RepositoryNode[]> {
  const params = new URLSearchParams();
  for (const t of type) params.append("type", t);
  const res = await fetch(`${REST_BASE}/api/repository?${params.toString()}`, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!res.ok) throw new Error(`repository -> ${res.status}`);
  return (await res.json()) as RepositoryNode[];
}

export async function getResource(path: string): Promise<string> {
  const res = await fetch(
    `${REST_BASE}/api/repository/resource?file=${encodeURIComponent(path)}`,
    { credentials: "include" },
  );
  if (!res.ok) throw new Error(`resource -> ${res.status}`);
  return res.text();
}

export async function saveResource(path: string, content: string): Promise<void> {
  const body = new URLSearchParams({ file: path, content });
  const res = await fetch(`${REST_BASE}/api/repository/resource`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) throw new Error(`saveResource -> ${res.status}`);
}

export async function deleteResource(path: string): Promise<void> {
  const res = await fetch(
    `${REST_BASE}/api/repository/resource?file=${encodeURIComponent(path)}`,
    { method: "DELETE", credentials: "include" },
  );
  if (!res.ok) throw new Error(`deleteResource -> ${res.status}`);
}

export async function moveResource(source: string, target: string): Promise<void> {
  const body = new URLSearchParams({ source, target });
  const res = await fetch(`${REST_BASE}/api/repository/resource/move`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) throw new Error(`moveResource -> ${res.status}`);
}

export function flatten(nodes: RepositoryNode[]): RepositoryNode[] {
  const out: RepositoryNode[] = [];
  const walk = (list: RepositoryNode[]) => {
    for (const n of list) {
      out.push(n);
      if (n.repoObjects?.length) walk(n.repoObjects);
    }
  };
  walk(nodes);
  return out;
}

/* ------------------------------------------------------------------ */
/* Saved-query helpers (thin wrappers around the generic repo calls)   */
/* ------------------------------------------------------------------ */

export async function listSavedQueries(): Promise<SavedQueryFile[]> {
  const tree = await listRepository(["saiku"]);
  const flat = flatten(tree);
  return flat
    .filter((n) => n.type === "FILE" && (n.fileType === "saiku" || n.path.endsWith(".saiku")))
    .map((n) => ({ path: n.path, name: n.name, type: "saiku" as const }));
}

export async function readSavedQuery(path: string): Promise<ThinQuery> {
  const body = await getResource(path);
  return JSON.parse(body) as ThinQuery;
}

export async function writeSavedQuery(path: string, q: ThinQuery): Promise<void> {
  await saveResource(path, JSON.stringify(q));
}

export async function deleteSavedQuery(path: string): Promise<void> {
  await deleteResource(path);
}

export async function moveSavedQuery(from: string, to: string): Promise<void> {
  await moveResource(from, to);
}

export function foldersOnly(nodes: RepositoryNode[]): string[] {
  const out = new Set<string>([""]);
  const walk = (list: RepositoryNode[]) => {
    for (const n of list) {
      if (n.type === "FOLDER") {
        out.add(n.path);
        if (n.repoObjects) walk(n.repoObjects);
      }
    }
  };
  walk(nodes);
  return Array.from(out).sort();
}
