import { json } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { ossieSearch } from '$lib/server/saiku';

const MIN_QUERY_LENGTH = 2;

export const GET: RequestHandler = async ({ url }) => {
	const q = url.searchParams.get('q')?.trim() ?? '';
	if (q.length < MIN_QUERY_LENGTH) return json([]);
	return json(await ossieSearch(q));
};
