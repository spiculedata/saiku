/*
 * AI Ask client — TypeScript bindings to /rest/saiku/api/ai/ask.
 *
 * The Ask endpoint takes a natural-language question + cube ref + optional
 * conversation history, sends it through the configured backend LLM provider
 * (Anthropic / OpenAI), and returns:
 *
 *   - the structured AiQueryRequest the model emitted (for "edit in canvas"),
 *   - the executed AiQueryResponse (for rendering result in the active tab),
 *   - the generated MDX (for display + copy),
 *   - a degraded envelope when the provider isn't configured or upstream
 *     fails — the UI surfaces the reason rather than throwing.
 *
 * Backend: AiAskApi (saiku-core/saiku-service/.../olap/ai/ask/AiAskApi.java).
 */

import type { AiQueryResponse } from './aiQuery';

const ASK_URL = '/rest/saiku/api/ai/ask';
const ASK_HEALTH_URL = '/rest/saiku/api/ai/ask/health';
const CHAIN_STREAM_URL = '/rest/saiku/api/ai/ask/chain/stream';

export interface AskHealth {
	configured: boolean;
}

/** GET /ai/ask/health. Returns {configured: false} on transport failure so the
 *  workspace silently hides the Ask AI button rather than showing an error. */
export async function fetchAskHealth(): Promise<AskHealth> {
	try {
		const res = await fetch(ASK_HEALTH_URL, {
			method: 'GET',
			credentials: 'include',
			headers: { Accept: 'application/json' }
		});
		if (!res.ok) return { configured: false };
		const body = (await res.json()) as AskHealth;
		return { configured: !!body.configured };
	} catch {
		return { configured: false };
	}
}

/** Mirror of {@link org.saiku.service.olap.ai.AiCubeRef}. */
export interface AiCubeRef {
	connectionName: string;
	catalog: string;
	schema: string;
	cubeName: string;
}

/** One conversation turn. Roles are restricted to user / assistant — the
 *  backend controls system prompts. */
export interface NlAskMessageDto {
	role: 'user' | 'assistant';
	content: string;
}

export interface AskRequest {
	question: string;
	cube: AiCubeRef;
	history?: NlAskMessageDto[];
	/**
	 * Markdown digest of the user's currently-rendered cellset, built client-side from
	 * `query.result`. Lets the AI reason about the data on screen so it can route to insight
	 * ("spot trends") or pick a sensible chart for view-change ("switch to chart"). Omit when no
	 * results are on screen — the AI will then only have schema context and can only build queries.
	 * Server-side AiPolicyGuard may strip this when AI policy is "schema-only".
	 */
	cellsetDigest?: string;
	/**
	 * Optional tool-choice override matching the drawer's mode picker. When omitted (default), the
	 * LLM picks among all four tools. When set, the server narrows the tool list to the requested
	 * intent (plus refusal, always available) so the user can force a specific behaviour when the
	 * LLM auto-routes wrong.
	 */
	forceTool?: 'auto' | 'query' | 'insight' | 'view_change';
	/**
	 * Snapshot of the user's currently-built AiQueryRequest (projected from the
	 * live queryModel via queryModelToAiRequest). Gives the LLM full context on
	 * what's already on screen so emit_query can EXTEND rather than REPLACE on
	 * follow-up turns — e.g. "add therapeutic class to columns" should preserve
	 * the user's existing rows/measures/filters, not nuke them.
	 */
	currentQuery?: AiQueryRequestShape;
}

/** Markdown analysis the AI emitted when the user asked for an insight about current data. */
export interface AiInsight {
	markdown: string;
	headline?: string;
}

/** Email draft summary the AI emitted when the model picked the email intent. */
export interface AiEmailDraft {
	summary: string;
}

/** View-target the AI emitted when the user asked to change how current data is displayed. */
export interface AiViewChange {
	viewMode: 'grid' | 'chart';
	chartType?: string;
	reason?: string;
}

/** The AiQueryRequest the model emitted. Opaque to the client — handed back
 *  to /saiku/api/ai/query verbatim if the user clicks "edit in canvas". */
export type AiQueryRequestShape = Record<string, unknown>;

export interface AskResponse {
	degraded: boolean;
	reason?: string;
	model?: string;
	/** The structured request the model emitted; absent when degraded. */
	request?: AiQueryRequestShape;
	/** The executed query result; absent when degraded before execution. */
	response?: AiQueryResponse;
	/** Convenience mirror of {@code response.metadata.generatedMdx}. */
	generatedMdx?: string;
	/**
	 * The structured ThinQueryModel the server's AiSchemaConverter produced
	 * from the AI's request — the same shape the workbench chip UI manipulates.
	 * Present whenever conversion succeeded (i.e. on every non-degraded,
	 * non-VALIDATION_ERROR path). When present, "edit in canvas" hydrates the
	 * workspace builder directly so the user gets interactive chips instead of
	 * an opaque MDX paste. Typed as unknown here to avoid pulling the query
	 * store type into the API layer; consumers cast at the boundary.
	 */
	queryModel?: unknown;
	/** Set when the model picked the insight intent. Mutually exclusive with request/queryModel/viewChange. */
	insight?: AiInsight;
	/** Set when the model picked the view-change intent. Mutually exclusive with the other intents. */
	viewChange?: AiViewChange;
	/** Set when the model picked the email intent. Mutually exclusive with the other intents. */
	emailDraft?: AiEmailDraft;
}

/** Error thrown only on transport / non-JSON failures. The "not configured"
 *  and provider-degraded paths come back as 200 / 503 with an AskResponse body
 *  that has {@code degraded:true} — callers should read {@code .degraded}
 *  first, not assume a throw means failure. */
export class AiAskTransportError extends Error {
	constructor(
		message: string,
		public readonly status: number
	) {
		super(message);
		this.name = 'AiAskTransportError';
	}
}

/** POST /rest/saiku/api/ai/ask. Returns the AskResponse envelope only on a 2xx
 *  result OR a 503 degraded envelope (`degraded:true`, the "not configured" /
 *  provider-down path the drawer shows as an amber banner). Every other non-2xx
 *  (401/403 auth, 5xx, or a body that isn't a real envelope) THROWS
 *  AiAskTransportError carrying the status — so the caller surfaces the real
 *  error instead of rendering a 403 as "0 rows returned." */
export async function askAi(req: AskRequest): Promise<AskResponse> {
	let res: Response;
	try {
		res = await fetch(ASK_URL, {
			method: 'POST',
			credentials: 'include',
			headers: {
				'Content-Type': 'application/json',
				Accept: 'application/json'
			},
			body: JSON.stringify(req)
		});
	} catch (e) {
		throw new AiAskTransportError(`askAi: transport error (${(e as Error).message})`, 0);
	}

	const text = await res.text();
	let parsed: AskResponse | undefined;
	if (text) {
		try {
			parsed = JSON.parse(text) as AskResponse;
		} catch {
			parsed = undefined;
		}
	}

	// A non-2xx status is an ERROR, not a zero-row success — UNLESS the body is a
	// legitimate degraded envelope. The server returns 503 with {degraded:true}
	// for the "AI not configured" / provider-down path; that IS a real AskResponse
	// the drawer surfaces as an amber banner, so let it through. Every other
	// non-2xx (401/403 auth, 5xx server error, or an error body that isn't a
	// proper AskResponse) MUST throw so the caller renders the real error rather
	// than falling through to "0 rows returned." off a missing cellset.
	if (!res.ok) {
		if (parsed && parsed.degraded === true) {
			return parsed;
		}
		// Prefer a server-supplied reason/message/error; fall back to a status-based
		// default so 401/403 read as auth problems, not empty data.
		const p = parsed as Record<string, unknown> | undefined;
		const serverMsg = p && (p.reason ?? p.message ?? p.error);
		const message =
			typeof serverMsg === 'string' && serverMsg.trim().length > 0
				? serverMsg
				: `askAi -> ${res.status}`;
		throw new AiAskTransportError(message, res.status);
	}

	// 2xx but no parseable body — cannot be a genuine cellset result; treat as transport error.
	if (!parsed) {
		throw new AiAskTransportError(
			text ? `askAi -> ${res.status}: non-JSON response` : `askAi -> ${res.status}: empty body`,
			res.status
		);
	}
	return parsed;
}

/** Callbacks for a chained (multi-step) ask stream. All optional. */
export interface AskStreamHandlers {
	onModel?: (model: string) => void;
	onIntent?: (kind: string, index: number) => void;
	onChunk?: (delta: string) => void;
	/** An intermediate step envelope (e.g. the built query to hydrate the workspace). */
	onStep?: (envelope: AskResponse) => void;
	/** The terminal step envelope — the stream is complete after this. */
	onFinal?: (envelope: AskResponse) => void;
	onError?: (reason: string) => void;
	/** Informational note (e.g. step-limit reached), may arrive after onFinal. */
	onNote?: (reason: string) => void;
}

/**
 * POST /ai/ask/chain/stream and dispatch the SSE events to the handlers as they arrive.
 * Resolves when the stream ends. Throws AiAskTransportError on a transport failure or a non-OK
 * response with no readable body. (EventSource can't POST a body, so this uses fetch + a manual
 * ReadableStream frame parser.)
 *
 * Wire contract (see SseWriter on the server): each frame is `event: <name>\ndata: <json>\n\n` —
 * `\n` line endings, frames separated by a blank line.
 */
export async function askAiStream(req: AskRequest, handlers: AskStreamHandlers): Promise<void> {
	let res: Response;
	try {
		res = await fetch(CHAIN_STREAM_URL, {
			method: 'POST',
			credentials: 'include',
			headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
			body: JSON.stringify(req)
		});
	} catch (e) {
		throw new AiAskTransportError(`askAiStream: transport error (${(e as Error).message})`, 0);
	}
	if (!res.ok || !res.body) {
		// A preamble rejection (413/429/503/4xx) comes back as JSON, not a stream — surface it.
		let reason = `askAiStream -> ${res.status}`;
		try {
			const j = await res.json();
			if (j && (j.reason || j.message)) reason = j.reason ?? j.message;
		} catch {
			/* ignore */
		}
		throw new AiAskTransportError(reason, res.status);
	}

	const reader = res.body.getReader();
	const decoder = new TextDecoder();
	let buf = '';
	for (;;) {
		const { value, done } = await reader.read();
		if (done) break;
		buf += decoder.decode(value, { stream: true });
		// SSE frames are separated by a blank line. Process every COMPLETE frame; keep the remainder.
		let sep: number;
		while ((sep = buf.indexOf('\n\n')) !== -1) {
			const frame = buf.slice(0, sep);
			buf = buf.slice(sep + 2);
			dispatchFrame(frame, handlers);
		}
	}
	// Flush any trailing frame without a terminating blank line (defensive).
	const tail = buf.trim();
	if (tail) dispatchFrame(tail, handlers);
}

function dispatchFrame(frame: string, h: AskStreamHandlers): void {
	let event = 'message';
	const dataLines: string[] = [];
	for (const line of frame.split('\n')) {
		if (line.startsWith('event:')) event = line.slice(6).trim();
		else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
	}
	if (dataLines.length === 0) return;
	const data = dataLines.join('\n');
	let payload: unknown;
	try {
		payload = JSON.parse(data);
	} catch {
		return; // malformed frame — skip
	}
	const p = payload as Record<string, unknown>;
	switch (event) {
		case 'model':
			if (typeof p.model === 'string') h.onModel?.(p.model);
			break;
		case 'intent':
			h.onIntent?.(String(p.kind ?? ''), typeof p.index === 'number' ? p.index : -1);
			break;
		case 'chunk':
			if (typeof p.delta === 'string') h.onChunk?.(p.delta);
			break;
		case 'step':
			h.onStep?.(payload as AskResponse);
			break;
		case 'final':
			h.onFinal?.(payload as AskResponse);
			break;
		case 'error':
			h.onError?.(String(p.reason ?? ''));
			break;
		case 'note':
			h.onNote?.(String(p.reason ?? ''));
			break;
		default:
			break;
	}
}
