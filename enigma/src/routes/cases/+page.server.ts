import type { PageServerLoad } from './$types';
import { listCases, storeEnabled } from '$lib/server/store';

export const load: PageServerLoad = async () => {
	return { enabled: storeEnabled(), cases: storeEnabled() ? await listCases() : [] };
};
