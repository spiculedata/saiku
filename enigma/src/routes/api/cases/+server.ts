import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import {
	listCases,
	caseSummary,
	saveCase,
	storeEnabled,
	CASE_STATUSES,
	CASE_PRIORITIES,
	type CaseStatus,
	type CasePriority
} from '$lib/server/store';

const MAX_TITLE = 200;

/** List cases (optionally ?status=) plus the queue summary counts. */
export const GET: RequestHandler = async ({ url }) => {
	const statusParam = url.searchParams.get('status');
	const status = CASE_STATUSES.includes(statusParam as CaseStatus) ? (statusParam as CaseStatus) : undefined;
	const [cases, summary] = await Promise.all([listCases(status), caseSummary()]);
	return json({ enabled: storeEnabled(), cases, summary });
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
		priority?: string;
		payload?: unknown;
	};
	const title = (body.title ?? '').trim();
	if (!title) return json({ error: 'title is required' }, { status: 400 });
	const priority = CASE_PRIORITIES.includes(body.priority as CasePriority)
		? (body.priority as CasePriority)
		: 'normal';

	const saved = await saveCase({
		title: title.slice(0, MAX_TITLE),
		subjectId: body.subjectId ?? null,
		subjectName: body.subjectName ?? null,
		jurisdiction: body.jurisdiction ?? null,
		note: body.note ?? null,
		kind: body.kind ?? 'entity',
		priority,
		payload: body.payload
	});
	return json({ case: saved }, { status: 201 });
};
