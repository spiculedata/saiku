import type { PageLoad } from './$types';
import { error } from '@sveltejs/kit';
import { base } from '$app/paths';
import type { EntityProfile } from '$lib/types';

export const load: PageLoad = async ({ params, fetch }): Promise<EntityProfile> => {
	const r = await fetch(`${base}/api/entities/${encodeURIComponent(params.id)}`);
	if (!r.ok) throw error(r.status, 'Entity not found');
	return (await r.json()) as EntityProfile;
};
