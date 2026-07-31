/**
 * DimSum agent-loop request/response shaping — lifted out of
 * SchemaCanvasView.svelte (audit finding #1040).
 *
 * The `.svelte` component still owns the loop orchestration + all reactive
 * `$state` (drafting flag, error text, message history). These pure helpers
 * own the wire format: building the `/api/inference/dimsum` request body from
 * the running chat history + canvas summary, and turning one HTTP turn into
 * the assistant's content blocks (throwing a gateway-shaped Error on failure).
 */
import { readGatewayErrorMessage } from './gateway-errors';
import type { AnthropicBlock, ChatMessage } from './ai-chat-types';

/** Wire body the gateway's DimSum endpoint expects. */
export interface DimSumRequestBody {
	messages: Array<{ role: ChatMessage['role']; content: ChatMessage['content'] }>;
	canvasSummary: string;
}

/**
 * Build the `/api/inference/dimsum` request body from the rolling chat
 * history + the current canvas summary. Strips each turn down to
 * `{ role, content }` — the `ts` display metadata never goes to the server.
 */
export function buildDimSumRequestBody(
	messages: ChatMessage[],
	canvasSummary: string
): DimSumRequestBody {
	return {
		messages: messages.map((m) => ({ role: m.role, content: m.content })),
		canvasSummary
	};
}

/**
 * Run one DimSum agent turn against the gateway and return the assistant's
 * content blocks. Throws a gateway-shaped Error (via
 * {@link readGatewayErrorMessage}) on a non-2xx response so the caller's
 * try/catch can surface it as `aiError`.
 *
 * `fetchImpl` is injectable so unit tests can drive the shaping without a
 * network (defaults to the global `fetch`).
 */
export async function postDimSumTurn(
	messages: ChatMessage[],
	canvasSummary: string,
	fetchImpl: typeof fetch = fetch
): Promise<AnthropicBlock[]> {
	const resp = await fetchImpl('/api/inference/dimsum', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(buildDimSumRequestBody(messages, canvasSummary))
	});
	if (!resp.ok) {
		const body = (await resp.json().catch(() => ({}))) as {
			message?: string;
			error?: string;
		};
		throw new Error(
			readGatewayErrorMessage(resp.status, body, `AI call failed (HTTP ${resp.status}).`)
		);
	}
	const payload = (await resp.json()) as {
		stopReason?: string;
		content?: AnthropicBlock[];
	};
	return payload.content ?? [];
}
