/*
 * SvelteKit load: extract the :dataSourceId route param so the page can
 * kick off a schema-generator session against the right source.
 *
 * The load runs on both server (prerender/SSR) and client; we don't need
 * session data here because +page.svelte defers the Start call until the
 * user clicks the button.
 */

import type { PageLoad } from "./$types";

// Dynamic param in the route, can't be prerendered. The rest of the app is
// prerendered via the root layout default; we opt out here.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
  return { dataSourceId: params.dataSourceId };
};
