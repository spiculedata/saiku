import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { ossieAsk, type AskTurn } from '$lib/server/saiku';
import { saveAskThread } from '$lib/server/store';

const MAX_QUESTION = 2000;
const MAX_HISTORY = 20;

/**
 * Natural-language ask proxy — the client never talks to Saiku directly. Runs the
 * ask over the Ossie model, then persists the turn to the write-back store (best
 * effort; a store failure never fails the ask). Optionally attaches to a case.
 */
export const POST: RequestHandler = async ({ request, fetch }) => {
	const body = (await request.json().catch(() => ({}))) as {
		question?: string;
		history?: AskTurn[];
		caseId?: string;
	};
	const question = (body.question ?? '').trim();
	if (!question) return json({ error: { code: 'BAD_REQUEST', message: 'question is required' } }, { status: 400 });
	if (question.length > MAX_QUESTION) {
		return json({ error: { code: 'TOO_LONG', message: 'question is too long' } }, { status: 400 });
	}
	const history = Array.isArray(body.history) ? body.history.slice(-MAX_HISTORY) : [];

	const result = await ossieAsk(question, history, { fetch });

	// Persist the thread (best effort). Store a compact, replayable answer snapshot.
	try {
		const answer = result.error
			? `ERROR: ${result.error.code} — ${result.error.message}`
			: JSON.stringify({ columns: result.columns, records: result.records.slice(0, 50) });
		const thread = await saveAskThread({
			caseId: body.caseId ?? null,
			question,
			answer,
			intent: result.error ? 'error' : 'query',
			model: result.model
		});
		return json({ ...result, threadId: thread?.id ?? null });
	} catch (e) {
		console.error('[api/ask] thread persist failed', e instanceof Error ? e.message : e);
		return json({ ...result, threadId: null });
	}
};
