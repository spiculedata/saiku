/*
 * Client for POST /rest/saiku/api/ai/explain — "explain this number" (saiku#1118).
 *
 * The server answers from the cellset already cached for the named query, so this is a read of
 * what the user is looking at rather than a new query: the payload is a query name plus the
 * coordinates of the right-clicked cell.
 *
 * Backend: CellExplainService (saiku-core/saiku-service/.../olap/ai/explain/).
 */

/** Zero-based data coordinates of a cell. `row` counts data rows and `column` data
 *  columns — neither counts the header bands, which is what CellsetTable has on a
 *  right-click. */
export interface CellPosition {
	row: number;
	column: number;
}

export interface ExplainRequest {
	/** Session query name — the same one `/saiku/api/query/{name}` uses. */
	queryName: string;
	position: CellPosition;
	/** Default true. Set false to skip the driver analysis (a plain MDX/SQL peek). */
	includeDrivers?: boolean;
	/** Default true. Falls back to the deterministic summary when no LLM is configured. */
	includeNarrative?: boolean;
	/** Default true. Set false to skip re-running the cell query for SQL capture. */
	includeSql?: boolean;
}

export type ExplainDriverKind =
	'SHARE_OF_COLUMN' | 'SHARE_OF_ROW' | 'RANK_IN_COLUMN' | 'PREVIOUS_COLUMN' | 'ROW_PEAK';

/** One structured finding. Only the fields its kind uses are populated. */
export interface ExplainDriver {
	kind: ExplainDriverKind;
	member?: string;
	caption: string;
	detail: string;
	value?: number;
	share?: number;
	delta?: number;
	deltaPct?: number;
}

export interface ExplainResponse {
	cube?: string;
	measure?: string;
	rowPath?: string;
	columnPath?: string;
	value: number;
	formatted?: string;
	/** MDX of the parent cellset. */
	mdx?: string;
	/** MDX of the single cell. */
	cellMdx?: string;
	/** SQL the planner emitted for {@link cellMdx}; absent when it could not be captured. */
	sql?: string;
	drivers: ExplainDriver[];
	narrative?: string;
	/** Who wrote {@link narrative} — `LLM` only when a provider is configured and answered. */
	narrativeSource?: 'LLM' | 'COMPUTED' | 'NONE';
	model?: string;
	/** Anything the server degraded on, in plain English. Safe to show verbatim. */
	notes?: string[];
	elapsedMs?: number;
}

const EXPLAIN_URL = '/rest/saiku/api/ai/explain';

/** Thrown on a non-2xx answer. `code` is the server's stable machine code
 *  (`UNKNOWN_QUERY`, `NOT_EXECUTED`, `VALIDATION_ERROR`, …) so the panel can
 *  react to it without matching on prose. */
export class ExplainError extends Error {
	constructor(
		message: string,
		public readonly status: number,
		public readonly code: string
	) {
		super(message);
		this.name = 'ExplainError';
	}
}

export async function explainCell(req: ExplainRequest): Promise<ExplainResponse> {
	let res: Response;
	try {
		res = await fetch(EXPLAIN_URL, {
			method: 'POST',
			credentials: 'include',
			headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
			body: JSON.stringify(req)
		});
	} catch (e) {
		throw new ExplainError(e instanceof Error ? e.message : 'network error', 0, 'NETWORK');
	}
	if (!res.ok) {
		let message = `HTTP ${res.status}`;
		let code = 'HTTP_ERROR';
		try {
			const body = (await res.json()) as { error?: string; code?: string };
			if (body?.error) message = body.error;
			if (body?.code) code = body.code;
		} catch {
			/* keep the status-derived message */
		}
		throw new ExplainError(message, res.status, code);
	}
	return (await res.json()) as ExplainResponse;
}
