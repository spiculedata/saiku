/*
 * SvelteKit load: extract the :dataSourceId route param so the cube-designer
 * page can profile + author a schema against the right datasource.
 */
import type { PageLoad } from './$types';

// Dynamic param — opt out of the root layout's default prerender.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
	return { dataSourceId: params.dataSourceId };
};
