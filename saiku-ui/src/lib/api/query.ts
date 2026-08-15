import type { SaikuCube } from '$lib/api/discover';

export type AxisLocation = 'FILTER' | 'COLUMNS' | 'ROWS' | 'PAGES';

export interface ThinLevel {
	name: string;
	selection?: {
		type: 'INCLUSION' | 'EXCLUSION';
		members: unknown[];
		parameterName?: string;
	};
}

export interface ThinHierarchy {
	name: string;
	caption?: string;
	dimension?: string;
	levels: Record<string, ThinLevel>;
	cmembers: Record<string, string>;
}

export interface ThinAxis {
	location: AxisLocation;
	mdx: string | null;
	filters: unknown[];
	sortOrder: string | null;
	sortEvaluationLiteral: string | null;
	hierarchizeMode: string | null;
	hierarchies: ThinHierarchy[];
	nonEmpty: boolean;
}

export interface ThinMeasure {
	name: string;
	uniqueName: string;
	caption: string;
	type: 'EXACT' | 'CALCULATED';
}

export interface ThinDetails {
	axis: AxisLocation;
	location: 'TOP' | 'BOTTOM';
	measures: ThinMeasure[];
}

export interface ThinQueryModel {
	axes: Record<AxisLocation, ThinAxis>;
	visualTotals: boolean;
	visualTotalsPattern: string | null;
	lowestLevelsOnly: boolean;
	details: ThinDetails;
	calculatedMeasures: unknown[];
	calculatedMembers: unknown[];
}

export interface ThinQuery {
	name: string;
	cube: SaikuCube;
	queryType: 'OLAP';
	type: 'QUERYMODEL' | 'MDX';
	queryModel?: ThinQueryModel;
	mdx?: string;
	properties?: Record<string, unknown>;
	parameters?: Record<string, string>;
}

export interface CellEntry {
	value: string;
	type:
		| 'ROW_HEADER'
		| 'ROW_HEADER_HEADER'
		| 'COLUMN_HEADER'
		| 'DATA_CELL'
		| 'EMPTY'
		| 'UNKNOWN'
		| 'ERROR';
	properties?: Record<string, string>;
}

export interface QueryResult {
	cellset: CellEntry[][];
	rowTotalsLists?: unknown[];
	colTotalsLists?: unknown[];
	runtime?: number;
	error?: string;
	height?: number;
	width?: number;
	query?: ThinQuery;
	topOffset?: number;
	leftOffset?: number;
}

const REST_BASE = '/rest/saiku/api/query';

export type AsyncStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED';

function acceptHeader(): string {
	const forceJson =
		typeof localStorage !== 'undefined' && localStorage.getItem('saiku_force_json') === '1';
	return forceJson
		? 'application/json'
		: 'application/vnd.apache.arrow.stream, application/json;q=0.1';
}

async function parseResultResponse(res: Response): Promise<QueryResult> {
	const contentType = res.headers.get('content-type') ?? '';
	if (contentType.includes('arrow')) {
		const { parseArrowExecute } = await import('$lib/api/arrow');
		return parseArrowExecute(await res.arrayBuffer());
	}
	return (await res.json()) as QueryResult;
}

export function newQueryModel(): ThinQueryModel {
	return {
		axes: {
			FILTER: {
				location: 'FILTER',
				mdx: null,
				filters: [],
				sortOrder: null,
				sortEvaluationLiteral: null,
				hierarchizeMode: null,
				hierarchies: [],
				nonEmpty: false
			},
			COLUMNS: {
				location: 'COLUMNS',
				mdx: null,
				filters: [],
				sortOrder: null,
				sortEvaluationLiteral: null,
				hierarchizeMode: null,
				hierarchies: [],
				nonEmpty: true
			},
			ROWS: {
				location: 'ROWS',
				mdx: null,
				filters: [],
				sortOrder: null,
				sortEvaluationLiteral: null,
				hierarchizeMode: null,
				hierarchies: [],
				nonEmpty: true
			},
			PAGES: {
				location: 'PAGES',
				mdx: null,
				filters: [],
				sortOrder: null,
				sortEvaluationLiteral: null,
				hierarchizeMode: null,
				hierarchies: [],
				nonEmpty: false
			}
		},
		visualTotals: false,
		visualTotalsPattern: null,
		lowestLevelsOnly: false,
		details: { axis: 'COLUMNS', location: 'BOTTOM', measures: [] },
		calculatedMeasures: [],
		calculatedMembers: []
	};
}

function uuid(): string {
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
		const r = (Math.random() * 16) | 0;
		const v = c === 'x' ? r : (r & 0x3) | 0x8;
		return v.toString(16);
	});
}

export function newQuery(cube: SaikuCube): ThinQuery {
	return {
		name: uuid(),
		cube,
		queryType: 'OLAP',
		type: 'QUERYMODEL',
		queryModel: newQueryModel()
	};
}

/* ------------------------------------------------------------------ */
/* URL deep-link serialisation                                         */
/* ------------------------------------------------------------------ */

/** A lightweight view-state bag carried in the `?q=` deep-link alongside
 *  the `ThinQuery`. Kept separate so we don't pollute the server-visible
 *  query payload with client-only rendering preferences. */
export interface DeepLinkView {
	viewMode: string;
	chartType?: string;
	chartOptions?: unknown;
}

interface DeepLinkPayload {
	v: 1;
	q: ThinQuery;
	view: DeepLinkView;
}

function base64UrlEncode(str: string): string {
	// btoa works on binary strings only. Encode UTF-8 first so non-ASCII
	// captions (Chinese dimensions, accented names) survive the round-trip.
	const bytes = new TextEncoder().encode(str);
	let bin = '';
	for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
	const b64 = btoa(bin);
	return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlDecode(token: string): string {
	let b64 = token.replace(/-/g, '+').replace(/_/g, '/');
	const pad = b64.length % 4;
	if (pad === 2) b64 += '==';
	else if (pad === 3) b64 += '=';
	else if (pad === 1) throw new Error('invalid base64url length');
	const bin = atob(b64);
	const bytes = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
	return new TextDecoder().decode(bytes);
}

/** Strip result/runtime junk that might have snuck into a ThinQuery via a
 *  previous roundtrip, and drop the randomly-regenerated `name`. */
function pruneQuery(q: ThinQuery): ThinQuery {
	const { name: _name, ...rest } = q;
	return { ...(rest as Omit<ThinQuery, 'name'>), name: '' } as ThinQuery;
}

export function serializeQueryToHash(q: ThinQuery, view: DeepLinkView): string {
	const payload: DeepLinkPayload = { v: 1, q: pruneQuery(q), view };
	return base64UrlEncode(JSON.stringify(payload));
}

export function deserializeQueryFromHash(
	token: string
): { query: ThinQuery; view: DeepLinkView } | null {
	try {
		const raw = base64UrlDecode(token);
		const parsed = JSON.parse(raw) as DeepLinkPayload;
		if (!parsed || parsed.v !== 1 || !parsed.q || !parsed.view) return null;
		return { query: parsed.q, view: parsed.view };
	} catch (err) {
		console.warn('saiku: failed to decode ?q= token', err);
		return null;
	}
}

export async function executeQuery(q: ThinQuery): Promise<QueryResult> {
	// Kill-switch: set localStorage.saiku_force_json=1 to fall back to the
	// legacy JSON path (e.g. for debugging an Arrow serialisation bug).
	const res = await fetch(`${REST_BASE}/execute`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: acceptHeader() },
		body: JSON.stringify(q)
	});
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new Error(`execute ${res.status}: ${text.slice(0, 200)}`);
	}
	// Lazy-load the Arrow adapter so apache-arrow stays out of the initial
	// bundle. The dynamic import means bundlers emit a separate chunk.
	return parseResultResponse(res);
}

export async function executeQueryAsync(
	q: ThinQuery,
	opts: {
		onStatus?: (s: AsyncStatus) => void;
		onQueryId?: (id: string) => void;
		signal?: AbortSignal;
	} = {}
): Promise<QueryResult & { queryId: string }> {
	const submit = await fetch(`${REST_BASE}/execute-async`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
		body: JSON.stringify(q),
		signal: opts.signal
	});
	if (!submit.ok) {
		const text = await submit.text().catch(() => '');
		throw new Error(`execute-async ${submit.status}: ${text.slice(0, 200)}`);
	}
	const { queryId, status: initialStatus } = (await submit.json()) as {
		queryId: string;
		status: AsyncStatus;
	};
	opts.onQueryId?.(queryId);
	opts.onStatus?.(initialStatus);

	const encodedId = encodeURIComponent(queryId);

	let delay = 500;
	const maxDelay = 5000;

	const abortError = (): Error => {
		if (typeof DOMException !== 'undefined') return new DOMException('aborted', 'AbortError');
		const e = new Error('aborted');
		e.name = 'AbortError';
		return e;
	};

	const handleAbort = async (): Promise<never> => {
		try {
			await fetch(`${REST_BASE}/async/${encodedId}/cancel`, {
				method: 'DELETE',
				credentials: 'include'
			});
		} catch {
			// swallow — the caller already knows this was cancelled
		}
		throw abortError();
	};

	for (;;) {
		if (opts.signal?.aborted) await handleAbort();
		await new Promise<void>((resolve, reject) => {
			const t = setTimeout(resolve, delay);
			if (opts.signal) {
				const onAbort = () => {
					clearTimeout(t);
					reject(abortError());
				};
				if (opts.signal.aborted) {
					clearTimeout(t);
					reject(abortError());
					return;
				}
				opts.signal.addEventListener('abort', onAbort, { once: true });
			}
		}).catch(async (err) => {
			if ((err as { name?: string }).name === 'AbortError') await handleAbort();
			throw err;
		});

		const statusRes = await fetch(`${REST_BASE}/async/${encodedId}/status`, {
			credentials: 'include',
			headers: { Accept: 'application/json' },
			signal: opts.signal
		}).catch(async (err) => {
			if ((err as { name?: string }).name === 'AbortError') await handleAbort();
			throw err;
		});
		if (!statusRes.ok) {
			const text = await statusRes.text().catch(() => '');
			throw new Error(`status ${statusRes.status}: ${text.slice(0, 200)}`);
		}
		const body = (await statusRes.json()) as {
			id: string;
			status: AsyncStatus;
			errorMessage?: string;
		};
		opts.onStatus?.(body.status);
		if (body.status === 'DONE') break;
		if (body.status === 'FAILED') throw new Error(body.errorMessage ?? 'query failed');
		if (body.status === 'CANCELLED') throw abortError();
		delay = Math.min(Math.round(delay * 1.5), maxDelay);
	}

	const resultRes = await fetch(`${REST_BASE}/async/${encodedId}/result`, {
		credentials: 'include',
		headers: { Accept: acceptHeader() },
		signal: opts.signal
	});
	if (!resultRes.ok) {
		const text = await resultRes.text().catch(() => '');
		throw new Error(`result ${resultRes.status}: ${text.slice(0, 200)}`);
	}
	const result = await parseResultResponse(resultRes);
	return { ...result, queryId };
}

export async function drillthrough(
	queryName: string,
	opts: { maxRows?: number; position?: string; returns?: string[] } = {}
): Promise<QueryResult> {
	const params = new URLSearchParams();
	params.set('maxrows', String(opts.maxRows ?? 1000));
	if (opts.position) params.set('position', opts.position);
	if (opts.returns?.length) params.set('returns', opts.returns.join(','));
	const url = `${REST_BASE}/${encodeURIComponent(queryName)}/drillthrough?${params.toString()}`;
	// Same Arrow-on-negotiation dance as executeQuery. Kill-switch via
	// localStorage.saiku_force_json=1 falls back to the legacy JSON path.
	const res = await fetch(url, {
		credentials: 'include',
		headers: { Accept: acceptHeader() }
	});
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new Error(`drillthrough ${res.status}: ${text.slice(0, 200)}`);
	}
	const contentType = res.headers.get('content-type') ?? '';
	if (contentType.includes('arrow')) {
		const { parseArrowDrillthrough } = await import('$lib/api/arrow');
		return parseArrowDrillthrough(await res.arrayBuffer());
	}
	return (await res.json()) as QueryResult;
}

export async function cancelQuery(name: string): Promise<void> {
	await fetch(`${REST_BASE}/${encodeURIComponent(name)}/cancel`, {
		method: 'DELETE',
		credentials: 'include'
	});
}

/** Fetch the current MDX for a registered query.
 *  The server returns `{ mdx: "..." }` (MdxQueryObject).
 *  Returns null if the query is not registered server-side or has no MDX. */
export async function getQueryMdx(name: string): Promise<string | null> {
	const res = await fetch(`${REST_BASE}/${encodeURIComponent(name)}/mdx`, {
		method: 'GET',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) return null;
	try {
		const body = (await res.json()) as { mdx?: string | null };
		return body?.mdx ?? null;
	} catch {
		return null;
	}
}
