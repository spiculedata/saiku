/*
 * REST client for the Saiku schema-generator workflow.
 *
 * Mirrors the endpoints exposed by SchemaGeneratorController (backend C3):
 *   POST /start/{dataSourceId}, GET /{id}/status, GET /{id}/draft,
 *   GET /{id}/suggestions, POST /{id}/ops, POST /{id}/save.
 *
 * Keeps the same shape as the other api/*.ts modules — thin wrappers over
 * `fetch`, relying on cookie-based auth (`credentials: "include"`) and the
 * XSRF interceptor installed in api/http.ts.
 */

/** Lifecycle stage for a schema-generator session; mirrors the backend enum. */
export type Stage =
	'PENDING' | 'INTROSPECTING' | 'INFERRING' | 'ENRICHING' | 'READY' | 'SAVED' | 'FAILED';

export interface StartResponse {
	sessionId: string;
	dataSourceId: string;
	stage: Stage;
}

export interface StatusResponse {
	sessionId: string;
	stage: Stage;
	failureMessage?: string | null;
	cubeCount: number;
	suggestionCount: number;
	/**
	 * Number of paths the delta reconciler classified as newly introduced
	 * upstream (present in the fresh introspection, absent from the baseline
	 * sidecar). `0` on first-run or before reconciliation has completed.
	 */
	deltaNewCount: number;
	/**
	 * Number of paths present in the baseline sidecar but missing from the
	 * fresh introspection — i.e. upstream tables/columns that have been
	 * dropped since the last generation. `0` on first-run.
	 */
	deltaRemovedCount: number;
}

/**
 * Draft schema payload returned by the backend. Mirrors the Jackson-serialised
 * `DraftView` record in saiku-web.
 *
 * An optional `provenance` field is carried on each node so the UI can render
 * a badge indicating where the node came from (rule / llm / user). The backend
 * currently omits this field; the UI treats it as `null` by default.
 */
export type ProvenanceSource = 'RULE' | 'LLM' | 'USER';

export interface Provenance {
	source: ProvenanceSource;
	ruleId?: string | null;
}

export interface LevelView {
	name: string;
	column: string | null;
	type: string | null;
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface HierarchyView {
	name: string;
	primaryKey: string | null;
	levels: LevelView[];
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface DimView {
	name: string;
	type: string | null;
	sourceTable: string | null;
	foreignKey: string | null;
	hierarchies: HierarchyView[];
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface MeasureView {
	name: string;
	column: string | null;
	aggregator: string | null;
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface CubeView {
	name: string;
	factTable: string | null;
	dimensions: DimView[];
	measures: MeasureView[];
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface SharedDimView {
	name: string;
	type: string | null;
	sourceTable: string | null;
	hierarchies: HierarchyView[];
	caption?: string | null;
	description?: string | null;
	provenance?: Provenance | null;
}

export interface DraftView {
	schemaName: string;
	cubes: CubeView[];
	sharedDimensions: SharedDimView[];
}

export interface SuggestionView {
	ops: SuggestionOp[];
	degraded: boolean;
}

/**
 * Discriminated union of operations the enrichment layer (rules / LLM)
 * proposes or the UI applies against a draft.
 *
 * Matches the backend sealed hierarchy in
 * {@code org.saiku.service.schema.generate.enrich.ops.SuggestionOp}: every op
 * carries a {@code targetPath} (e.g. {@code cubes/Sales/measures/Amount}),
 * a nominal {@code confidence} in {@code [0.0, 1.0]}, and a short
 * {@code rationale} string. Op-specific fields follow.
 */
export type SuggestionOp = RenameOp | HierarchyOp | AggregatorOp | DegenerateDimOp | IgnoreOp;

/** Fields every op carries, regardless of variant. */
interface OpCommon {
	targetPath: string;
	confidence: number;
	rationale: string;
}

/** Set a friendlier caption (and optional description) on any named element. */
export interface RenameOp extends OpCommon {
	op: 'rename';
	oldCaption: string;
	newCaption: string;
	description?: string | null;
}

/** Propose a multi-level hierarchy on a dimension. */
export interface HierarchyOp extends OpCommon {
	op: 'hierarchy';
	hierarchyName: string;
	levelColumns: string[];
}

/** Change a measure's aggregator. */
export interface AggregatorOp extends OpCommon {
	op: 'aggregator';
	oldAggregator: string;
	newAggregator: string;
}

/** Promote a fact-table column to a degenerate dimension on a cube. */
export interface DegenerateDimOp extends OpCommon {
	op: 'degenerateDim';
	factColumn: string;
	dimName: string;
}

/** Propose dropping an element (cube, dim, hierarchy, level, or measure). */
export interface IgnoreOp extends OpCommon {
	op: 'ignore';
}

export interface SchemaGenClient {
	start(dataSourceId: string): Promise<StartResponse>;
	status(sessionId: string): Promise<StatusResponse>;
	draft(sessionId: string): Promise<DraftView>;
	suggestions(sessionId: string): Promise<SuggestionView>;
	applyOp(sessionId: string, op: SuggestionOp): Promise<DraftView>;
	save(sessionId: string, schemaName?: string): Promise<void>;
}

const PATH = '/rest/saiku/admin/schema-generator';

/**
 * Build a schema-generator client. Accepts an injectable `fetcher` (handy for
 * tests) and an optional `baseUrl` prefix so callers running outside the Vite
 * dev proxy can point at a remote Saiku host.
 */
export function createSchemaGenClient(
	fetcher: typeof fetch = fetch,
	baseUrl = ''
): SchemaGenClient {
	const root = `${baseUrl}${PATH}`;

	async function getJson<T>(path: string): Promise<T> {
		const res = await fetcher(`${root}${path}`, {
			credentials: 'include',
			headers: { Accept: 'application/json' }
		});
		if (!res.ok) {
			throw new Error(`schema-generator GET ${path} -> ${res.status}`);
		}
		return (await res.json()) as T;
	}

	async function postJson<T>(path: string, body: unknown): Promise<T | null> {
		const res = await fetcher(`${root}${path}`, {
			method: 'POST',
			credentials: 'include',
			headers: {
				'Content-Type': 'application/json',
				Accept: 'application/json'
			},
			body: JSON.stringify(body ?? {})
		});
		if (!res.ok) {
			throw new Error(`schema-generator POST ${path} -> ${res.status}`);
		}
		// 204 or empty body → return null; callers that expect a payload cast.
		if (res.status === 204) return null;
		const text = await res.text();
		return text ? (JSON.parse(text) as T) : null;
	}

	return {
		start(dataSourceId) {
			// Backend accepts POST with no body; we send `{}` via postJson so the
			// Content-Type header stays consistent and CSRF interception kicks in.
			return postJson<StartResponse>(
				`/start/${encodeURIComponent(dataSourceId)}`,
				{}
			) as Promise<StartResponse>;
		},
		status(sessionId) {
			return getJson<StatusResponse>(`/${encodeURIComponent(sessionId)}/status`);
		},
		draft(sessionId) {
			return getJson<DraftView>(`/${encodeURIComponent(sessionId)}/draft`);
		},
		suggestions(sessionId) {
			return getJson<SuggestionView>(`/${encodeURIComponent(sessionId)}/suggestions`);
		},
		applyOp(sessionId, op) {
			return postJson<DraftView>(`/${encodeURIComponent(sessionId)}/ops`, {
				op
			}) as Promise<DraftView>;
		},
		async save(sessionId, schemaName) {
			const body = schemaName !== undefined ? { schemaName } : {};
			await postJson<void>(`/${encodeURIComponent(sessionId)}/save`, body);
		}
	};
}
