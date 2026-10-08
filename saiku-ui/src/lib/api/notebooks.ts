/*
 * Notebooks REST client (issue #1108: markdown + MDX cells, shareable).
 *
 * Mirrors org.saiku.web.rest.resources.notebooks.NotebookResource on the
 * Java side, the same way $lib/api/dashboards.ts mirrors DashboardResource.
 * CRUD goes through this resource's own {path} endpoints; MDX cell execution
 * goes through its POST /run endpoint (runNotebookCell below), NOT the
 * workspace's POST /rest/saiku/api/query/execute. A cell persists a
 * name-only NotebookCubeRef (same shape the AI Query API's cube field
 * uses), never a full server-authored SaikuCube — see NotebookCell.java's
 * javadoc on the Java side for why a full SaikuCube isn't a safe
 * client-authored request/storage shape (it has no JSON setters; it's
 * built only from a live schema scan). /run resolves the ref against the
 * live schema server-side before executing.
 *
 * Phase 1 of #1108: markdown + mdx cells only, no chart cell yet — results
 * render in-cell as a grid.
 */

import type { QueryResult } from '$lib/api/query';

const REST_BASE = '/rest/saiku/api/notebooks';
const SHARE_BASE = '/rest/saiku/share';

export type NotebookCellType = 'markdown' | 'mdx';

/** Name-only cube reference — mirrors AiCubeRef on the Java side. A
 *  notebook cell stores this, not a full SaikuCube (see module doc). */
export interface NotebookCubeRef {
	connectionName: string;
	catalog: string;
	schema: string;
	cubeName: string;
}

export interface NotebookCell {
	id: string;
	type: NotebookCellType;
	/** Optional per-cell display title. */
	name?: string;
	/** Markdown source. Only meaningful when {@code type === 'markdown'}. */
	markdown?: string;
	/** Raw MDX text. Only meaningful when {@code type === 'mdx'}. */
	mdx?: string;
	/** Cube this cell's MDX runs against. Only meaningful when {@code type
	 *  === 'mdx'}. Each MDX cell carries its own cube, so cells run
	 *  independently (issue #1108 scope). */
	cube?: NotebookCubeRef;
}

export interface Notebook {
	id: string;
	name: string;
	version: number;
	cells: NotebookCell[];
}

export interface NotebookSaveResponse {
	status: 'OK';
	path: string;
}

export interface NotebookErrorResponse {
	status: 'NOT_FOUND' | 'VALIDATION_ERROR' | 'ERROR';
	field?: string;
	error: string;
	path?: string;
}

/** Load a notebook by repository path. Throws on 4xx / 5xx with the parsed
 *  error body when available. */
export async function loadNotebook(path: string): Promise<Notebook> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`loadNotebook(${path}) -> ${res.status}: ${err}`);
	}
	return (await res.json()) as Notebook;
}

/** Save (create or overwrite) a notebook. */
export async function saveNotebook(
	path: string,
	notebook: Notebook
): Promise<NotebookSaveResponse> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		method: 'POST',
		credentials: 'include',
		headers: {
			'Content-Type': 'application/json',
			Accept: 'application/json'
		},
		body: JSON.stringify(notebook)
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`saveNotebook(${path}) -> ${res.status}: ${err}`);
	}
	return (await res.json()) as NotebookSaveResponse;
}

export async function deleteNotebook(path: string): Promise<void> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		method: 'DELETE',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`deleteNotebook(${path}) -> ${res.status}: ${err}`);
	}
}

/** Build an empty Notebook with a fresh id and a starter markdown cell,
 *  ready to be populated by the editor before the first save. */
export function newNotebook(name = 'Untitled notebook'): Notebook {
	return {
		id: cryptoUuid(),
		name,
		version: 1,
		cells: [{ id: cryptoUuid(), type: 'markdown', markdown: '' }]
	};
}

export function newMarkdownCell(): NotebookCell {
	return { id: cryptoUuid(), type: 'markdown', markdown: '' };
}

export function newMdxCell(cube?: NotebookCubeRef): NotebookCell {
	return { id: cryptoUuid(), type: 'mdx', mdx: '', cube };
}

/** Run one MDX cell: POST /rest/saiku/api/notebooks/run. Resolves {@code
 *  cube} against the live schema server-side and executes, returning the
 *  same QueryResult shape POST /rest/saiku/api/query/execute returns. */
export async function runNotebookCell(mdx: string, cube: NotebookCubeRef): Promise<QueryResult> {
	const res = await fetch(`${REST_BASE}/run`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
		body: JSON.stringify({ mdx, cube })
	});
	const body = (await res.json()) as QueryResult;
	if (!res.ok && !body?.error) {
		throw new Error(`runNotebookCell -> ${res.status}`);
	}
	return body;
}

/* ---------------------------- internals ---------------------------- */

/** URL-encode each path segment but leave slashes as-is — NotebookResource
 *  binds {path:.+} which captures slashes natively. */
function encodePath(path: string): string {
	return path.split('/').map(encodeURIComponent).join('/');
}

/** Normalise a user- or URL-supplied notebook path into a save-safe repo
 *  path. Mirrors normaliseDashboardPath: a leading `/` is an explicit
 *  absolute repo location; everything else gets the user's home prefixed so
 *  non-admins don't trip the saveFile ACL gate. */
export function normaliseNotebookPath(rawPath: string, username: string): string {
	const trimmed = rawPath.trim();
	if (!trimmed) throw new Error('Notebook path is required');
	const isAbsolute = trimmed.startsWith('/');
	const p = isAbsolute ? normaliseRepoPath(trimmed) : toRepoRelative(trimmed).trim();
	if (!p) throw new Error('Notebook path is required');
	if (isAbsolute) return p;
	if (p.startsWith('homes/')) return p;
	if (!username) throw new Error('Cannot resolve home: no current user');
	return `homes/${username}/${p}`;
}

/** Strip the saiku-home filesystem prefix the repository listing API returns
 *  down to the repo-relative form the notebooks REST API expects. Mirrors
 *  toRepoRelative in $lib/api/dashboards.ts. */
export function toRepoRelative(path: string): string {
	const m = path.match(/^.*?\/data\/[^/]+\/(.+)$/);
	const candidate = m ? m[1] : path;
	return normaliseRepoPath(candidate);
}

export function normaliseRepoPath(path: string): string {
	return path.replace(/\/+/g, '/').replace(/^\/+|\/+$/g, '');
}

/** Drop the reserved `.saikunb` extension for display, mirroring
 *  displayPath in $lib/api/dashboards.ts. */
export function displayPath(path: string): string {
	return path.endsWith('.saikunb') ? path.slice(0, -'.saikunb'.length) : path;
}

async function readError(res: Response): Promise<string> {
	try {
		const body = (await res.json()) as NotebookErrorResponse;
		return body.error ?? JSON.stringify(body);
	} catch {
		return res.statusText;
	}
}

function cryptoUuid(): string {
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
		const r = (Math.random() * 16) | 0;
		const v = c === 'x' ? r : (r & 0x3) | 0x8;
		return v.toString(16);
	});
}

/* ---------------------- share (#941, widened by #1108) ------------------ */

export interface ShareMintResult {
	status: string;
	token: string;
	url: string;
	expiresAt: number;
}

export interface ShareTokenInfo {
	token: string;
	dashboardPath: string;
	createdBy: string;
	createdAt: number;
	expiresAt: number;
	revoked: boolean;
	label: string;
	active: boolean;
}

/** Mint a share link for a notebook. Uses the same /rest/saiku/share/mint
 *  endpoint dashboards use (#941) — the server tells dashboard and notebook
 *  paths apart by their extension, not by a separate endpoint. See
 *  ShareTokenResource's javadoc for why the request field keeps its
 *  `dashboardPath` name for both kinds. */
export async function mintNotebookShare(
	notebookPath: string,
	ttlHours?: number,
	label?: string
): Promise<ShareMintResult> {
	const res = await fetch(`${SHARE_BASE}/mint`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
		body: JSON.stringify({ dashboardPath: notebookPath, ttlHours, label })
	});
	if (!res.ok) {
		throw new Error(`mintNotebookShare -> ${res.status}: ${await readError(res)}`);
	}
	return (await res.json()) as ShareMintResult;
}

export async function listShareTokens(): Promise<ShareTokenInfo[]> {
	const res = await fetch(`${SHARE_BASE}/tokens`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`listShareTokens -> ${res.status}`);
	}
	return (await res.json()) as ShareTokenInfo[];
}

export async function revokeShareToken(token: string): Promise<void> {
	const res = await fetch(`${SHARE_BASE}/tokens/${encodeURIComponent(token)}`, {
		method: 'DELETE',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`revokeShareToken -> ${res.status}`);
	}
}

/** Load a notebook via the account-free guest share surface (#1108, mirrors
 *  loadSharedDashboard). Token in the `X-Saiku-Share-Token` header — no
 *  cookie. */
export async function loadSharedNotebook(token: string): Promise<Notebook> {
	const res = await fetch(`${SHARE_BASE}/view/notebook`, {
		headers: { 'X-Saiku-Share-Token': token, Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`loadSharedNotebook -> ${res.status}`);
	}
	return (await res.json()) as Notebook;
}

/** Run one MDX cell's authored query via the guest share surface. Mirrors
 *  runSharedTileQuery in $lib/api/aiQuery.ts, but returns the raw-MDX
 *  QueryResult shape (the same one executeQuery() returns), not the AI
 *  Query API's records envelope — notebook MDX cells never go through
 *  /ai/query. */
export async function runSharedNotebookCellQuery(
	token: string,
	cellId: string
): Promise<QueryResult> {
	const res = await fetch(`${SHARE_BASE}/view/notebook/cell/${encodeURIComponent(cellId)}/query`, {
		method: 'POST',
		headers: { 'X-Saiku-Share-Token': token, Accept: 'application/json' }
	});
	const body = (await res.json()) as QueryResult;
	if (!res.ok && !body?.error) {
		throw new Error(`runSharedNotebookCellQuery -> ${res.status}`);
	}
	return body;
}
