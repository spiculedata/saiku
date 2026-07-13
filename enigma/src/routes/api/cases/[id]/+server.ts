import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import {
	getCaseDetail,
	updateCase,
	deleteCase,
	storeEnabled,
	CASE_STATUSES,
	CASE_PRIORITIES,
	type CaseStatus,
	type CasePriority
} from '$lib/server/store';

/** Full case detail: case + activity timeline + linked Ask threads. */
export const GET: RequestHandler = async ({ params }) => {
	const detail = await getCaseDetail(params.id);
	if (!detail) return json({ error: 'case not found' }, { status: 404 });
	return json(detail);
};

/** Patch lifecycle fields (status / priority / assignee); each change is logged to the timeline. */
export const PATCH: RequestHandler = async ({ params, request }) => {
	if (!storeEnabled()) return json({ error: 'write-back store is not configured' }, { status: 503 });
	const body = (await request.json().catch(() => ({}))) as {
		status?: string;
		priority?: string;
		assignee?: string | null;
	};
	const patch: { status?: CaseStatus; priority?: CasePriority; assignee?: string | null } = {};
	if (body.status !== undefined) {
		if (!CASE_STATUSES.includes(body.status as CaseStatus)) {
			return json({ error: `invalid status; expected one of ${CASE_STATUSES.join(', ')}` }, { status: 400 });
		}
		patch.status = body.status as CaseStatus;
	}
	if (body.priority !== undefined) {
		if (!CASE_PRIORITIES.includes(body.priority as CasePriority)) {
			return json({ error: `invalid priority; expected one of ${CASE_PRIORITIES.join(', ')}` }, { status: 400 });
		}
		patch.priority = body.priority as CasePriority;
	}
	if (body.assignee !== undefined) {
		patch.assignee = body.assignee ? String(body.assignee).slice(0, 80) : null;
	}
	const updated = await updateCase(params.id, patch);
	if (!updated) return json({ error: 'case not found' }, { status: 404 });
	return json({ case: updated });
};

/** Delete a saved case. */
export const DELETE: RequestHandler = async ({ params }) => {
	if (!storeEnabled()) return json({ error: 'write-back store is not configured' }, { status: 503 });
	const removed = await deleteCase(params.id);
	return json({ removed }, { status: removed ? 200 : 404 });
};
