/*
 * SvelteKit load: extract the notebook repository path from the rest
 * segment of the URL, mirroring dashboards' [...path] load. The notebook
 * itself loads inside +page.svelte / NotebookEditor so load errors surface
 * in the editor's own frame rather than blocking the route render.
 */

import { normaliseRepoPath } from '$lib/api/notebooks';
import type { PageLoad } from './$types';

// Dynamic route, can't be prerendered.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
	const path = normaliseRepoPath(params.path ?? '');
	return { notebookPath: path };
};
