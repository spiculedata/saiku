import { json, error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { ossieEntity } from '$lib/server/saiku';

// Entity profile — attributes + risk + opacity — all via the Ossie model (Saiku/DuckDB).
export const GET: RequestHandler = async ({ params }) => {
	const entity = await ossieEntity(params.id);
	if (!entity || !entity.id) throw error(404, 'entity not found');
	return json(entity);
};
