/*
 * Creator Mode client (saiku#1435).
 *
 * Every call here targets `/rest/saiku/api/embed/authoring/<cube>/…`, the one
 * embed surface that writes. The server derives the tenant's folder from the
 * token, so this module never sends a path — only a name, which the server
 * sanitises. The token rides the same `X-Saiku-Embed-Token` header as the read
 * surface, and `credentials: 'omit'` keeps the host page's cookies out of it.
 */
import type { EmbedQueryResponse } from './types';
import { EmbedFetchError } from './api';

/* ------------------------------ types ------------------------------ */

/** The pinned cube + the catalogue the creator may build from. */
export interface EmbedCreatorContext {
	tenantId: string;
	scopePath: string;
	cube: string;
	cubeCaption: string;
	dimensions: EmbedCreatorDimension[];
	measures: EmbedCreatorMeasure[];
}

export interface EmbedCreatorDimension {
	name: string;
	caption: string;
	levels: EmbedCreatorLevel[];
}

export interface EmbedCreatorLevel {
	name: string;
	caption: string;
	/** True when the level has more members than the server will enumerate. */
	truncated: boolean;
	members: string[];
}

export interface EmbedCreatorMeasure {
	name: string;
	caption: string;
}

/** One row of the creator's ROWS axis: a level plus the members kept. */
export interface CreatorAxisPick {
	level: string;
	/** Empty = every member (the usual "show me all" starting point). */
	members: string[];
}

/** The picks that make up a creator query. */
export interface CreatorSelection {
	rows: CreatorAxisPick[];
	measures: string[];
}

export interface EmbedCreatorObject {
	name: string;
	path: string;
	type: 'query' | 'dashboard';
	date: number;
	owner: string;
}

/* ----------------------------- the query ----------------------------- */

/**
 * Build the `ThinQuery` document a creator's picks describe. This is the only
 * place the wire shape is assembled, and it is deliberately typed-only: axes of
 * levels + members, a measures block, and nothing else — no MDX, no filters, no
 * parameters, none of which the server accepts (AuthoringQueryValidator refuses
 * them, and this client has no way to emit one).
 *
 * Pure and exported so it can be unit-tested without a browser.
 */
export function buildCreatorQuery(selection: CreatorSelection): Record<string, unknown> {
	const hierarchies = selection.rows
		.filter((pick) => Boolean(pick.level))
		.map((pick) => ({
			name: pick.level,
			caption: pick.level,
			dimension: pick.level.split('].[')[0] + ']',
			levels: {
				[pick.level]: {
					name: pick.level,
					caption: pick.level,
					selection: {
						type: 'INCLUSION',
						members: pick.members.map((m) => ({ name: m, uniqueName: m, caption: m }))
					},
					aggs: []
				}
			}
		}));

	const measures = selection.measures.filter(Boolean);
	// A measures axis is emitted only when there is something to put on it: an
	// empty "[Measures]" hierarchy would look like a cube hierarchy the server
	// rightly refuses. (The server also insists on at least one measure.)
	const measureAxis = measures.length
		? [
				{
					name: '[Measures]',
					caption: 'Measures',
					dimension: '[Measures]',
					levels: Object.fromEntries(
						measures.map((m) => [m, { name: m, caption: m, selection: null, aggs: [] }])
					)
				}
			]
		: [];

	return {
		name: 'embed-creator',
		type: 'QUERYMODEL',
		queryType: 'OLAP',
		parameters: {},
		typedParameters: [],
		queryModel: {
			visualTotals: false,
			lowestLevelsOnly: false,
			axes: {
				ROWS: { location: 'ROWS', hierarchies, nonEmpty: hierarchies.length > 0, aggs: [] },
				COLUMNS: {
					location: 'COLUMNS',
					nonEmpty: measureAxis.length > 0,
					aggs: [],
					hierarchies: measureAxis
				},
				FILTER: { location: 'FILTER', hierarchies: [], nonEmpty: false, aggs: [] },
				PAGES: { location: 'PAGES', hierarchies: [], nonEmpty: false, aggs: [] }
			},
			details: {
				axis: 'COLUMNS',
				location: 'BOTTOM',
				measures: measures.map((m) => ({
					name: m,
					uniqueName: m,
					caption: m,
					type: 'EXACT',
					aggs: []
				}))
			}
		}
	};
}

/* ------------------------------ calls ------------------------------ */

function base(server: string, cube: string): string {
	const root = server.endsWith('/') ? server.slice(0, -1) : server;
	const ref = cube
		.split('/')
		.filter(Boolean)
		.map((seg) => encodeURIComponent(seg))
		.join('/');
	if (!ref) throw new Error('a cube reference is required');
	return `${root}/rest/saiku/api/embed/authoring/${ref}`;
}

function headers(token?: string | null, json = false): Record<string, string> {
	const h: Record<string, string> = { Accept: 'application/json' };
	if (json) h['Content-Type'] = 'application/json';
	if (token) h['X-Saiku-Embed-Token'] = token;
	return h;
}

async function readError(resp: Response): Promise<EmbedFetchError> {
	let body: { status?: string; error?: string } = { status: 'ERROR' };
	try {
		body = await resp.json();
	} catch {
		body = { status: 'ERROR', error: `HTTP ${resp.status}` };
	}
	return new EmbedFetchError(resp.status, { status: body.status ?? 'ERROR', error: body.error });
}

/** The pinned cube's catalogue. The stripped workbench has no other source for
 *  its pickers — the full discover API is authenticated-only. */
export async function fetchAuthoringContext(
	server: string,
	cube: string,
	token?: string | null
): Promise<EmbedCreatorContext> {
	const resp = await fetch(`${base(server, cube)}/context`, {
		headers: headers(token),
		credentials: 'omit'
	});
	if (!resp.ok) throw await readError(resp);
	return (await resp.json()) as EmbedCreatorContext;
}

/** Run a creator-built query without saving it. */
export async function previewAuthoringQuery(
	server: string,
	cube: string,
	selection: CreatorSelection,
	token?: string | null,
	format: 'records' | 'matrix' = 'records'
): Promise<EmbedQueryResponse> {
	const resp = await fetch(`${base(server, cube)}/preview?format=${format}`, {
		method: 'POST',
		headers: headers(token, true),
		credentials: 'omit',
		body: JSON.stringify({ query: buildCreatorQuery(selection) })
	});
	if (!resp.ok) throw await readError(resp);
	return (await resp.json()) as EmbedQueryResponse;
}

/** Save the creator's query in their tenant folder. */
export async function saveAuthoringQuery(
	server: string,
	cube: string,
	name: string,
	selection: CreatorSelection,
	token?: string | null
): Promise<{ path: string }> {
	const resp = await fetch(`${base(server, cube)}/query`, {
		method: 'POST',
		headers: headers(token, true),
		credentials: 'omit',
		body: JSON.stringify({ name, query: buildCreatorQuery(selection) })
	});
	if (!resp.ok) throw await readError(resp);
	return (await resp.json()) as { path: string };
}

/**
 * Save a one-tile dashboard wrapping the current query. The tile is the same
 * `{kind:"reference", path}` shape the workspace's dashboard tiles use, so the
 * saved dashboard opens in the full Saiku UI for the owner afterwards.
 */
export async function saveAuthoringDashboard(
	server: string,
	cube: string,
	name: string,
	selection: CreatorSelection,
	savedQueryPath: string,
	chartType: string,
	token?: string | null
): Promise<{ path: string }> {
	const dashboard = {
		name,
		title: name,
		layout: {
			tiles: [
				{
					type: 'filter',
					x: 0,
					y: 0,
					width: 6,
					height: 6,
					chart: { type: chartType },
					query: { kind: 'reference', path: savedQueryPath }
				}
			]
		}
	};
	const resp = await fetch(`${base(server, cube)}/dashboard`, {
		method: 'POST',
		headers: headers(token, true),
		credentials: 'omit',
		body: JSON.stringify({ name, dashboard })
	});
	if (!resp.ok) throw await readError(resp);
	return (await resp.json()) as { path: string };
}

/** The tenant's own saved objects. */
export async function fetchAuthoringObjects(
	server: string,
	cube: string,
	token?: string | null
): Promise<{ tenantId: string; scopePath: string; objects: EmbedCreatorObject[] }> {
	const resp = await fetch(`${base(server, cube)}/objects`, {
		headers: headers(token),
		credentials: 'omit'
	});
	if (!resp.ok) throw await readError(resp);
	return (await resp.json()) as {
		tenantId: string;
		scopePath: string;
		objects: EmbedCreatorObject[];
	};
}

/** Delete one of the tenant's own objects. */
export async function deleteAuthoringObject(
	server: string,
	cube: string,
	name: string,
	token?: string | null
): Promise<void> {
	const resp = await fetch(`${base(server, cube)}/object?name=${encodeURIComponent(name)}`, {
		method: 'DELETE',
		headers: headers(token),
		credentials: 'omit'
	});
	if (!resp.ok) throw await readError(resp);
}

/** Turn a fetch/server error into one line a host-page visitor can act on,
 *  without leaking whether the failure was a bad token, a wrong cube or an
 *  expired link. */
export function creatorErrorMessage(e: unknown): string {
	if (e instanceof EmbedFetchError) {
		if (e.status === 401) return 'This analytics surface is unavailable.';
		if (e.status === 403) return 'You are not allowed to change this surface.';
		return e.body.error ?? `Request failed (${e.status}).`;
	}
	return 'Request failed.';
}
