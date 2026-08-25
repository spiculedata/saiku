import type { ThinQuery } from '$lib/api/query';

const REST_BASE = '/rest/saiku';

export interface RepositoryNode {
	path: string;
	name: string;
	type: 'FOLDER' | 'FILE';
	fileType?: string | null;
	acl?: string | null;
	repoObjects?: RepositoryNode[];
	/** ACL owner of the file, when the server can resolve one from the
	 *  closest {@code acl.json}. Used by the catalogue's #935 owner filter. */
	owner?: string | null;
	/** Last-modified time in millis since epoch. Used by the catalogue's
	 *  #935 modified-sort. {@code 0} when the storage layer doesn't
	 *  expose mtime. */
	modified?: number;
}

export type AclType = 'PRIVATE' | 'SECURED' | 'PUBLIC';
export type AclMethod = 'READ' | 'WRITE' | 'GRANT';

export interface AclEntry {
	owner: string;
	type: AclType;
	roles: Record<string, AclMethod[]>;
	users: Record<string, AclMethod[]>;
}

/** A flattened descriptor for the saved-queries browser. */
export interface SavedQueryFile {
	path: string;
	name: string;
	modified?: string;
	type: 'saiku';
}

export async function listRepository(type: string[] = ['saiku']): Promise<RepositoryNode[]> {
	const params = new URLSearchParams();
	for (const t of type) params.append('type', t);
	const res = await fetch(`${REST_BASE}/api/repository?${params.toString()}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) throw new Error(`repository -> ${res.status}`);
	const tree = (await res.json()) as RepositoryNode[];
	return tree.map(normaliseNodePaths);
}

/** Strip the saiku-home filesystem prefix the legacy repository listing
 *  API returns ({@code /Users/.../saiku-home/repository/data/<workspace>/foo})
 *  down to the repo-relative form the rest of the API expects ({@code foo}).
 *  Applied recursively to every node + child so every consumer sees clean
 *  paths regardless of the storage backend. */
function normaliseNodePaths(node: RepositoryNode): RepositoryNode {
	const path = stripRepoRoot(node.path);
	const children = node.repoObjects ? node.repoObjects.map(normaliseNodePaths) : undefined;
	return children ? { ...node, path, repoObjects: children } : { ...node, path };
}

function stripRepoRoot(path: string): string {
	// Match anything up to and including `/data/<workspace>/`; the capture
	// group is the repo-relative remainder. Falls through unchanged when
	// the pattern doesn't match (already-relative paths or a different
	// storage layout).
	const m = path.match(/^.*?\/data\/[^/]+\/(.+)$/);
	return m ? m[1] : path;
}

export async function getResource(path: string): Promise<string> {
	const res = await fetch(
		`${REST_BASE}/api/repository/resource?file=${encodeURIComponent(stripRepoRoot(path))}`,
		{ credentials: 'include' }
	);
	if (!res.ok) throw new Error(`resource -> ${res.status}`);
	return res.text();
}

export async function saveResource(path: string, content: string): Promise<void> {
	const body = new URLSearchParams({ file: stripRepoRoot(path), content });
	const res = await fetch(`${REST_BASE}/api/repository/resource`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body
	});
	if (!res.ok) throw new Error(`saveResource -> ${res.status}`);
}

export async function deleteResource(path: string): Promise<void> {
	const res = await fetch(
		`${REST_BASE}/api/repository/resource?file=${encodeURIComponent(stripRepoRoot(path))}`,
		{ method: 'DELETE', credentials: 'include' }
	);
	if (!res.ok) throw new Error(`deleteResource -> ${res.status}`);
}

export async function getResourceAcl(path: string): Promise<AclEntry> {
	const res = await fetch(
		`${REST_BASE}/api/repository/resource/acl?file=${encodeURIComponent(stripRepoRoot(path))}`,
		{ credentials: 'include', headers: { Accept: 'application/json' } }
	);
	if (!res.ok) throw new Error(`getResourceAcl -> ${res.status}`);
	const raw = (await res.json()) as Partial<AclEntry>;
	return {
		owner: raw.owner ?? '',
		type: raw.type ?? 'PUBLIC',
		roles: raw.roles ?? {},
		users: raw.users ?? {}
	};
}

export async function setResourceAcl(path: string, acl: AclEntry): Promise<void> {
	const body = new URLSearchParams({ file: stripRepoRoot(path), acl: JSON.stringify(acl) });
	const res = await fetch(`${REST_BASE}/api/repository/resource/acl`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body
	});
	if (!res.ok) throw new Error(`setResourceAcl -> ${res.status}`);
}

export async function moveResource(source: string, target: string): Promise<void> {
	const body = new URLSearchParams({
		source: stripRepoRoot(source),
		target: stripRepoRoot(target)
	});
	const res = await fetch(`${REST_BASE}/api/repository/resource/move`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
		body
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
	const out = new Set<string>(['']);
	const walk = (list: RepositoryNode[]) => {
		for (const n of list) {
			if (n.type === 'FOLDER') {
				out.add(n.path);
				if (n.repoObjects) walk(n.repoObjects);
			}
		}
	};
	walk(nodes);
	return Array.from(out).sort();
}
