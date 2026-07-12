import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { deleteCase, storeEnabled } from '$lib/server/store';

/** Delete a saved case. */
export const DELETE: RequestHandler = async ({ params }) => {
	if (!storeEnabled()) {
		return json({ error: 'write-back store is not configured' }, { status: 503 });
	}
	const removed = await deleteCase(params.id);
	return json({ removed }, { status: removed ? 200 : 404 });
};
