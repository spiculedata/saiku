/*
 * URL deep-link encoding for dashboard filter state (saiku#926).
 *
 * Captures the current active-filter set into URL query parameters so
 * a shared link reproduces the exact filtered view. Encodes both panel
 * picks and click-captured filters into the same shape — the parser
 * doesn't care which layer they came from; the decoder simply pushes
 * them as transient clicks on the receiving session, which take
 * precedence over the dashboard's persisted panel defaults.
 *
 * Format:
 *   ?f=<dim>/<hier>/<level>=<m1>,<m2>...
 *
 * One `f` parameter per (dim, hier, level) target. The value is a
 * comma-joined list of MDX unique names. Each unique name is encoded
 * with `encodeURIComponent` so brackets / commas inside member ids
 * survive a round-trip.
 *
 * Empty member lists (the `— any —` state) are intentionally dropped
 * from the URL — the deep link should only carry non-trivial slicing.
 */

import type { DashboardFilter } from "$lib/api/dashboards";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";

const PARAM = "f";
const KEY_VALUE_SEP = "=";
const PATH_SEP = "/";
const MEMBER_SEP = ",";

/** Build the URL query string from the current active-filter set. The
 *  resulting fragment is intended for `URL.search` assignment — it
 *  starts with `?` when non-empty, empty string otherwise. */
export function encodeActiveFilters(active: ActiveFilter[]): string {
  // De-dup by target — clicks already win over panel at the activeFilters
  // merge step, so multiple entries for the same target can't appear
  // here today; the guard is defensive against future overlap.
  const seen = new Set<string>();
  const params = new URLSearchParams();
  for (const af of active) {
    const f = af.filter;
    const target = `${f.dimension}${PATH_SEP}${f.hierarchy}${PATH_SEP}${f.level}`;
    if (seen.has(target)) continue;
    const members = (f.members ?? []).filter((m) => m && m.length > 0);
    if (members.length === 0) continue;
    seen.add(target);
    params.append(PARAM, `${target}${KEY_VALUE_SEP}${members.join(MEMBER_SEP)}`);
  }
  const s = params.toString();
  return s ? `?${s}` : "";
}

/** Parse a URL's search params into a list of DashboardFilter records.
 *  Malformed entries (missing `=`, missing path segments, empty member
 *  list) are silently skipped — a partially-broken URL still loads the
 *  rest of the dashboard. */
export function decodeFilterParams(search: URLSearchParams): DashboardFilter[] {
  const out: DashboardFilter[] = [];
  for (const raw of search.getAll(PARAM)) {
    const eq = raw.indexOf(KEY_VALUE_SEP);
    if (eq < 0) continue;
    const target = raw.substring(0, eq);
    const memberPart = raw.substring(eq + 1);
    const segs = target.split(PATH_SEP);
    if (segs.length !== 3) continue;
    const [dim, hier, level] = segs;
    if (!dim || !hier || !level) continue;
    const members = memberPart
      .split(MEMBER_SEP)
      .map((m) => m.trim())
      .filter((m) => m.length > 0);
    if (members.length === 0) continue;
    out.push({ dimension: dim, hierarchy: hier, level, members });
  }
  return out;
}
