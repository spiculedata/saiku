/*
 * SvelteKit load: extract the app repository path from the rest segment of
 * the URL. The .saikuapp JCR path can contain slashes (e.g.
 * `homes/admin/store.saikuapp`), so we use the [...rest] matcher and rejoin.
 *
 * Mirrors the dashboards route: loading the app body itself happens in the
 * AppEditor (via the appDoc store) so load errors surface inside the shell
 * rather than blocking the route render.
 */

import { normaliseRepoPath } from '$lib/api/dashboards';
import type { PageLoad } from './$types';

// Dynamic route, can't be prerendered — same as the dashboards route.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
	const path = normaliseRepoPath(params.path ?? '');
	return { appPath: path };
};
