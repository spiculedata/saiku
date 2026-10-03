/*
 * AI Dashboard narrative-summary client — TypeScript bindings to
 * POST /rest/saiku/api/ai/narrate-dashboard (saiku#910, Tier-2 aggregated).
 *
 * The dashboard layer is layout-only on the backend (see DashboardResource) —
 * the frontend already computes each visible tile's effective filters and
 * re-issues its query via /ai/query. This endpoint follows the same shape:
 * the caller posts each VISIBLE tile's already filter-resolved AiQueryRequest
 * directly; the server re-executes it itself (so k-anonymity suppression and
 * PII caption redaction apply to freshly-run data, never client-supplied
 * numbers) before narrating the result.
 *
 * Wire contract:
 *   - `degraded` is the always-present discriminator (a primitive boolean).
 *   - On success: {degraded:false, narrative, model?}.
 *   - On degrade: {degraded:true, reason, model?} — `narrative` absent.
 *   - An empty `tiles` array, or every tile executing to zero rows, is still
 *     a non-degraded 200 whose `narrative` is the fixed "No data to
 *     summarise." string — the LLM is never called in that case.
 *
 * Backend: AiQueryResource.narrateDashboard.
 */

import { AiAskTransportError } from './aiAsk';

const NARRATE_URL = '/rest/saiku/api/ai/narrate-dashboard';

/** One visible dashboard tile: display title + its already filter-resolved query. */
export interface NarrativeTileInput {
	title: string;
	/** Full AiQueryRequest (cube + measures + rows/columns/filters) — the same shape /ai/query accepts. */
	query: Record<string, unknown>;
}

export interface NarrateDashboardRequest {
	dashboardTitle?: string;
	tiles: NarrativeTileInput[];
}

export interface NarrateDashboardResponse {
	/** Always present. true ⇒ the provider degraded/refused; render `reason`, do not show a narrative. */
	degraded: boolean;
	/** Generic, user-safe message — populated when degraded. */
	reason?: string;
	/** Provider model id; may be absent. */
	model?: string;
	/** The generated narrative, or "No data to summarise." when every tile was empty. Absent when degraded. */
	narrative?: string;
}

/** Type guard: does a parsed body look like a NarrateDashboardResponse? */
function isNarrateDashboardResponse(body: unknown): body is NarrateDashboardResponse {
	return (
		!!body &&
		typeof body === 'object' &&
		typeof (body as { degraded?: unknown }).degraded === 'boolean'
	);
}

/**
 * POST the narrate request and return the parsed response.
 *
 * A degraded call is a normal 200 (or a preamble 429/503) whose body carries
 * `degraded:true` — it is returned as-is, NOT thrown, so the caller can
 * render the generic `reason`. A transport failure, an empty/non-JSON body,
 * or a non-2xx response that is not a recognisable envelope throws
 * {@link AiAskTransportError}.
 */
export async function narrateDashboard(
	req: NarrateDashboardRequest,
	signal?: AbortSignal
): Promise<NarrateDashboardResponse> {
	let res: Response;
	try {
		res = await fetch(NARRATE_URL, {
			method: 'POST',
			credentials: 'include',
			headers: {
				'Content-Type': 'application/json',
				Accept: 'application/json'
			},
			body: JSON.stringify(req),
			signal
		});
	} catch (e) {
		if ((e as Error)?.name === 'AbortError') throw e;
		throw new AiAskTransportError(`narrateDashboard: transport error (${(e as Error).message})`, 0);
	}

	const text = await res.text();
	if (!text) {
		throw new AiAskTransportError(`narrateDashboard -> ${res.status}: empty body`, res.status);
	}
	let parsed: unknown;
	try {
		parsed = JSON.parse(text);
	} catch (e) {
		throw new AiAskTransportError(
			`narrateDashboard -> ${res.status}: non-JSON response (${(e as Error).message})`,
			res.status
		);
	}
	if (isNarrateDashboardResponse(parsed)) {
		return parsed;
	}
	if (!res.ok) {
		throw new AiAskTransportError(`narrateDashboard -> ${res.status}`, res.status);
	}
	throw new AiAskTransportError(
		`narrateDashboard -> ${res.status}: response is not a NarrateDashboardResponse`,
		res.status
	);
}
