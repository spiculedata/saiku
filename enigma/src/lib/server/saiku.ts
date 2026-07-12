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

/** Signals radar — screening stats, feed, category tally + risk leaderboard — via the Ossie model. */
export async function ossieSignals(flags = 60, risk = 20, o: Opts = {}): Promise<Signals | null> {
	const f = o.fetch ?? fetch;
	const base = o.base ?? config.saikuApi;
	const url = `${base}${OSSIE}/signals/${CM}?flags=${flags}&risk=${risk}`;
	const r = await f(url, { headers: { accept: 'application/json', authorization: authHeader(o) } });
	if (!r.ok) return null;
	return (await r.json()) as Signals;
}
