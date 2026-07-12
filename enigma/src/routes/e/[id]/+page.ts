import type { PageLoad } from './$types';
import { error } from '@sveltejs/kit';
import type { Entity, EntityRisk } from '$lib/types';

export interface EntityDetailData {
	entity: Entity;
	risk: EntityRisk | null;
}

export const load: PageLoad = async ({ params, fetch }): Promise<EntityDetailData> => {
	const r = await fetch(`/api/entities/${encodeURIComponent(params.id)}`);
	if (!r.ok) throw error(r.status, 'Entity not found');
	return (await r.json()) as EntityDetailData;
};
