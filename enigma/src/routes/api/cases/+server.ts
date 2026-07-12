import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { listCases, saveCase, storeEnabled } from '$lib/server/store';

const MAX_TITLE = 200;

/** List saved cases (newest first). */
export const GET: RequestHandler = async () => {
	return json({ enabled: storeEnabled(), cases: await listCases() });
};

/** Save a new case (write-back). */
export const POST: RequestHandler = async ({ request }) => {
	if (!storeEnabled()) {
		return json({ error: 'write-back store is not configured' }, { status: 503 });
	}
	const body = (await request.json().catch(() => ({}))) as {
		title?: string;
		subjectId?: string;
		subjectName?: string;
		jurisdiction?: string;
		note?: string;
		kind?: string;
		payload?: unknown;
	};
	const title = (body.title ?? '').trim();
	if (!title) return json({ error: 'title is required' }, { status: 400 });

	const saved = await saveCase({
		title: title.slice(0, MAX_TITLE),
		subjectId: body.subjectId ?? null,
		subjectName: body.subjectName ?? null,
		jurisdiction: body.jurisdiction ?? null,
		note: body.note ?? null,
		kind: body.kind ?? 'entity',
		payload: body.payload
	});
	return json({ case: saved }, { status: 201 });
};
