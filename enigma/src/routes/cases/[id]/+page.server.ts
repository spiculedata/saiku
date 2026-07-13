import type { PageServerLoad } from './$types';
import { error } from '@sveltejs/kit';
import { getCaseDetail, storeEnabled } from '$lib/server/store';

export const load: PageServerLoad = async ({ params }) => {
	if (!storeEnabled()) throw error(503, 'Write-back store is not configured');
	const detail = await getCaseDetail(params.id);
	if (!detail) throw error(404, 'Case not found');
	return detail;
};
