import { json, error } from '@sveltejs/kit';
import type { RequestHandler } from './$types';
import { getEntity } from '$lib/server/benafide';
import { config } from '$lib/server/config';
import type { EntityRisk } from '$lib/types';

export const GET: RequestHandler = async ({ params, fetch }) => {
	const entity = await getEntity(params.id);
	if (!entity) throw error(404, 'entity not found');

	let risk: EntityRisk | null = null;
	try {
		const r = await fetch(`${config.benafideApi}/v1/entities/${encodeURIComponent(params.id)}/risk`, {
			headers: { accept: 'application/json' }
		});
		if (r.ok) risk = await r.json();
	} catch {
		// risk is optional — entity detail still renders without it
	}

	return json({ entity, risk });
};
