import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { searchEntities } from '$lib/server/benafide';

const MIN_QUERY_LENGTH = 2;

export const GET: RequestHandler = async ({ url }) => {
	const q = url.searchParams.get('q')?.trim() ?? '';
	if (q.length < MIN_QUERY_LENGTH) return json([]);
	return json(await searchEntities(q));
};
