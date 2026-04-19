const REST_BASE = "/rest/saiku";

export interface RepositoryNode {
  path: string;
  name: string;
  type: "FOLDER" | "FILE";
  fileType?: string | null;
  acl?: string | null;
  repoObjects?: RepositoryNode[];
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
