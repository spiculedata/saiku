import type { PageServerLoad } from './$types';
import { listCases, caseSummary, storeEnabled, CASE_STATUSES, type CaseStatus } from '$lib/server/store';

export const load: PageServerLoad = async ({ url }) => {
	if (!storeEnabled()) {
		return { enabled: false, cases: [], summary: { open: 0, in_review: 0, escalated: 0, closed: 0 }, status: null };
	}
	const statusParam = url.searchParams.get('status');
	const status = CASE_STATUSES.includes(statusParam as CaseStatus) ? (statusParam as CaseStatus) : null;
	const [cases, summary] = await Promise.all([listCases(status ?? undefined), caseSummary()]);
	return { enabled: true, cases, summary, status };
};
