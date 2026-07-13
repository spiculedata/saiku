import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { addNote, storeEnabled } from '$lib/server/store';

const MAX_NOTE = 4000;

/** Append a free-text note to a case's activity timeline. */
export const POST: RequestHandler = async ({ params, request }) => {
	if (!storeEnabled()) return json({ error: 'write-back store is not configured' }, { status: 503 });
	const body = (await request.json().catch(() => ({}))) as { body?: string };
	const text = (body.body ?? '').trim();
	if (!text) return json({ error: 'note body is required' }, { status: 400 });
	const activity = await addNote(params.id, text.slice(0, MAX_NOTE));
	if (!activity) return json({ error: 'case not found' }, { status: 404 });
	return json({ activity }, { status: 201 });
};
