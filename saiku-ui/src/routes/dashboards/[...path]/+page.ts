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

import { normaliseRepoPath } from "$lib/api/dashboards";
import type { PageLoad } from "./$types";

// Dynamic route, can't be prerendered. Saiku's root layout prerenders by
// default; opt out here so the [...path] segment is honoured at runtime.
export const prerender = false;

export const load: PageLoad = ({ params }) => {
  // params.path is the raw rest segment — SvelteKit returns it as a single
  // string with slashes preserved (NOT an array). The router usually trims
  // leading / trailing slashes, but URLs that opened from older clients can
  // still carry `//homes/<uuid>/foo.saikudash/` (the double slash is the
  // tell — listed JCR paths sometimes have a leading slash that the URL
  // builder concatenated to the route prefix). Normalise defensively so
  // refresh always lands on the same dashboard regardless of how the URL
  // was constructed (user-reported 2026-06-08).
  const path = normaliseRepoPath(params.path ?? "");
  return { dashboardPath: path };
};
