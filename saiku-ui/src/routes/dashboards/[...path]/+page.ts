/*
 * SvelteKit load: extract the dashboard repository path from the rest
 * segment of the URL. The Dashboard JCR path can contain slashes
 * (e.g. `marketing/Q4-sales.saikudash`), so we use SvelteKit's [...rest]
 * matcher and rejoin the segments.
 *
 * Loading the dashboard body itself happens in +page.svelte — we want
 * the editor to surface load errors inside its own frame rather than
 * blocking the route render.
 */

import type { PageLoad } from "./$types";

// Dynamic route, can't be prerendered. Saiku's root layout prerenders by
// default; opt out here so the [...path] segment is honoured at runtime.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
  // params.path is the raw rest segment — SvelteKit returns it as a single
  // string with slashes preserved (NOT an array). Leading/trailing slashes
  // are trimmed by the router already.
  const path = params.path ?? "";
  return { dashboardPath: path };
};
