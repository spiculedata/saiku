import { json, error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { ossieGraph } from '$lib/server/saiku';

const DEFAULT_DEPTH = 4;

// Ownership graph traversal — proxies the Ossie model's recursive relationship
// walk so the client never talks to Saiku directly.
export const GET: RequestHandler = async ({ params, url }) => {
	const depth = Number(url.searchParams.get('depth') ?? String(DEFAULT_DEPTH));
	const g = await ossieGraph(params.id, Number.isFinite(depth) ? depth : DEFAULT_DEPTH);
	if (!g) throw error(502, 'graph unavailable');
	return json(g);
};
