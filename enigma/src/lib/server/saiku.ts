import { config } from './config';

interface Shelf {
	rows?: unknown[];
	columns?: unknown[];
	values: unknown[];
	filters?: unknown[];
	sorts?: unknown[];
	limit?: number;
}

interface Opts {
	fetch?: typeof fetch;
	base?: string;
	user?: string;
	pass?: string;
}

interface OssieQueryResult {
	records: Record<string, unknown>[];
}

/** An entity as returned by the Ossie /search and /entity endpoints. */
export interface OssieEntity {
	id: string;
	name: string;
	jurisdiction: string | null;
	status: string | null;
	risk_score?: number | null;
	opacity_score?: number | null;
}

export interface GraphNode {
	id: string;
	label: string;
	kind: string;
}
export interface GraphEdge {
	owned: string;
	owner: string;
	percentage: number | null;
	depth: number;
	cycle: boolean;
}
export interface OwnershipGraph {
	rootId: string;
	nodes: GraphNode[];
	edges: GraphEdge[];
	maxDepth: number;
	hasCycle: boolean;
}

/** A high-risk company on the Signals leaderboard. */
export interface RiskEntity {
	id: string;
	name: string | null;
	jurisdiction: string | null;
	riskScore: number | null;
}
/** One screening hit — `topics` is the split-out list of matched categories. */
export interface Flag {
	name: string | null;
	topics: string[];
	matchType: string | null;
	status: string | null;
}
export interface TopicCount {
	topic: string;
	count: number;
}
export interface SignalsStats {
	totalFlags: number;
	sanctionFlags: number;
	highRiskEntities: number;
	distinctTopics: number;
}
export interface Signals {
	stats: SignalsStats;
	topRisk: RiskEntity[];
	flags: Flag[];
	topics: TopicCount[];
}

const CONNECTION = 'unknown_Benafide';
const MODEL = 'Benafide';
const OSSIE = '/rest/saiku/api/ai/ossie';
const CM = `${CONNECTION}/${MODEL}`;

function authHeader(o: Opts): string {
	const user = o.user ?? config.saikuUser;
	const pass = o.pass ?? config.saikuPass;
	return 'Basic ' + Buffer.from(`${user}:${pass}`).toString('base64');
}

/** Aggregate shelf-state query (distributions for The Deck). */
export async function ossieQuery(shelf: Shelf, o: Opts = {}): Promise<OssieQueryResult> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const body = { connection: CONNECTION, model: MODEL, columns: [], filters: [], sorts: [], ...shelf };
	const r = await f(`${base}${OSSIE}/query`, {
		method: 'POST',
		headers: { 'content-type': 'application/json', accept: 'application/json', authorization: authHeader(o) },
		body: JSON.stringify(body)
	});
	if (!r.ok) return { records: [] };
	return (await r.json()) as OssieQueryResult;
}

/** Entity name search (FTS-ranked) over the Ossie model. */
export async function ossieSearch(q: string, o: Opts = {}): Promise<OssieEntity[]> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const url = `${base}${OSSIE}/search/${CM}?q=${encodeURIComponent(q)}&limit=12`;
	const r = await f(url, { headers: { accept: 'application/json', authorization: authHeader(o) } });
	if (!r.ok) return [];
	return (await r.json()) as OssieEntity[];
}

/** Single-entity profile — attributes + risk + opacity — via the Ossie model. */
export async function ossieEntity(id: string, o: Opts = {}): Promise<OssieEntity | null> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const url = `${base}${OSSIE}/entity/${CM}?id=${encodeURIComponent(id)}`;
	const r = await f(url, { headers: { accept: 'application/json', authorization: authHeader(o) } });
	if (!r.ok) return null;
	return (await r.json()) as OssieEntity;
}

/** Ownership graph traversal (recursive) via the Ossie model's relationships. */
export async function ossieGraph(id: string, depth = 4, o: Opts = {}): Promise<OwnershipGraph | null> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const url = `${base}${OSSIE}/graph/${CM}?root=${encodeURIComponent(id)}&depth=${depth}`;
	const r = await f(url, { headers: { accept: 'application/json', authorization: authHeader(o) } });
	if (!r.ok) return null;
	return (await r.json()) as OwnershipGraph;
}

/** A chat turn passed to the Ossie ask endpoint for multi-turn context. */
export interface AskTurn {
	role: 'user' | 'assistant';
	content: string;
}

export interface AskColumn {
	key: string;
	label: string;
	type: string;
}
/** Result of a natural-language ask: the generated query + its executed result. */
export interface AskResult {
	question: string;
	model: string | null;
	queryUsed: unknown;
	columns: AskColumn[];
	records: Record<string, unknown>[];
	runtimeMs: number | null;
	/** Populated when the ask failed (not configured, validation, rate limit, …). */
	error?: { code: string; message: string; field?: string; available?: string[] };
}

/** Whether the Ossie ask (LLM) layer is configured on the Saiku backend. */
export async function ossieAskHealth(o: Opts = {}): Promise<{ configured: boolean; provider: string }> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	try {
		const r = await f(`${base}${OSSIE}/ask/health`, {
			headers: { accept: 'application/json', authorization: authHeader(o) }
		});
		if (!r.ok) return { configured: false, provider: 'unavailable' };
		const j = (await r.json()) as { configured?: boolean; provider?: string };
		return { configured: Boolean(j.configured), provider: j.provider ?? 'unknown' };
	} catch {
		return { configured: false, provider: 'unreachable' };
	}
}

/** Natural-language ask over the Ossie model — LLM translates to a query, runs it, returns rows. */
export async function ossieAsk(question: string, history: AskTurn[] = [], o: Opts = {}): Promise<AskResult> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const body = { connection: CONNECTION, model: MODEL, question, history };
	const r = await f(`${base}${OSSIE}/ask`, {
		method: 'POST',
		headers: { 'content-type': 'application/json', accept: 'application/json', authorization: authHeader(o) },
		body: JSON.stringify(body)
	});
	const j = (await r.json().catch(() => ({}))) as Record<string, unknown>;
	if (!r.ok) {
		return {
			question,
			model: null,
			queryUsed: null,
			columns: [],
			records: [],
			runtimeMs: null,
			error: {
				code: String(j.error ?? `HTTP_${r.status}`),
				message: String(j.message ?? j.error ?? `ask failed (${r.status})`),
				field: j.field as string | undefined,
				available: j.available as string[] | undefined
			}
		};
	}
	const resp = (j.response ?? {}) as { columns?: AskColumn[]; records?: Record<string, unknown>[]; runtimeMs?: number };
	return {
		question,
		model: (j.model as string) ?? null,
		queryUsed: j.queryUsed ?? null,
		columns: resp.columns ?? [],
		records: resp.records ?? [],
		runtimeMs: resp.runtimeMs ?? null
	};
}

/** Signals radar — screening stats, feed, category tally + risk leaderboard — via the Ossie model. */
export async function ossieSignals(flags = 60, risk = 20, o: Opts = {}): Promise<Signals | null> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const url = `${base}${OSSIE}/signals/${CM}?flags=${flags}&risk=${risk}`;
	const r = await f(url, { headers: { accept: 'application/json', authorization: authHeader(o) } });
	if (!r.ok) return null;
	return (await r.json()) as Signals;
}
