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
 *
 * App Builder (multi-page) extension: an app renders one page at a time, but a
 * shared link must reproduce BOTH which page is active AND each page's own
 * filter set. {@link encodeAppFilterState} / {@link decodeAppFilterState} add:
 *   ?p=<pageId>&f~<pageId>=<dim>/<hier>/<level>=<m1>,<m2>...
 * The per-page filter param is `f~<pageId>` — a distinct namespace from the
 * dashboards' bare `f`, so the two schemes never collide and each page's
 * filters round-trip independently. The per-target encoding is shared with the
 * single-dashboard path (same `appendFilterParams` / `parseFilterValues`).
 */

import type { DashboardFilter } from "$lib/api/dashboards";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";

const PARAM = "f";
const KEY_VALUE_SEP = "=";
const PATH_SEP = "/";
const MEMBER_SEP = ",";

/** Query-param key that carries the active page id for an app. */
export const PAGE_PARAM = "p";

/** Separator between the `f` namespace and a page id in the per-page filter
 *  param name (`f~<pageId>`). Chosen so it can't appear in the bare `f` used by
 *  the single-dashboard path. */
const PAGE_FILTER_SEP = "~";

/** The per-page filter param name for a given page id, e.g. `f~page-1`. */
export function pageFilterParam(pageId: string): string {
  return `${PARAM}${PAGE_FILTER_SEP}${pageId}`;
}

/** Append one `paramName` entry per (dim, hier, level) target to `params`.
 *  De-dups by target and drops empty member lists. Shared by the single-
 *  dashboard encoder (bare `f`) and the per-page app encoder (`f~<pageId>`). */
function appendFilterParams(
  params: URLSearchParams,
  paramName: string,
  filters: DashboardFilter[],
): void {
  const seen = new Set<string>();
  for (const f of filters) {
    const target = `${f.dimension}${PATH_SEP}${f.hierarchy}${PATH_SEP}${f.level}`;
    if (seen.has(target)) continue;
    const members = (f.members ?? []).filter((m) => m && m.length > 0);
    if (members.length === 0) continue;
    seen.add(target);
    params.append(paramName, `${target}${KEY_VALUE_SEP}${members.join(MEMBER_SEP)}`);
  }
}

/** Parse a list of raw `<target>=<members>` param values into DashboardFilter
 *  records. Malformed entries (missing `=`, wrong segment count, empty member
 *  list) are silently skipped so a partially-broken URL still loads the rest. */
function parseFilterValues(values: string[]): DashboardFilter[] {
  const out: DashboardFilter[] = [];
  for (const raw of values) {
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

/** Build the URL query string from the current active-filter set. The
 *  resulting fragment is intended for `URL.search` assignment — it
 *  starts with `?` when non-empty, empty string otherwise. */
export function encodeActiveFilters(active: ActiveFilter[]): string {
  const params = new URLSearchParams();
  appendFilterParams(
    params,
    PARAM,
    active.map((af) => af.filter),
  );
  const s = params.toString();
  return s ? `?${s}` : "";
}

/** Parse a URL's search params into a list of DashboardFilter records.
 *  Malformed entries (missing `=`, missing path segments, empty member
 *  list) are silently skipped — a partially-broken URL still loads the
 *  rest of the dashboard. */
export function decodeFilterParams(search: URLSearchParams): DashboardFilter[] {
  return parseFilterValues(search.getAll(PARAM));
}

/** Decoded App Builder URL state: which page is active plus each page's own
 *  filter set, keyed by page id. */
export interface AppFilterState {
  activePageId: string | null;
  filtersByPage: Record<string, DashboardFilter[]>;
}

/** Encode the App Builder's page + per-page filter state into a URL query
 *  string (leading `?` when non-empty). Pages whose filter set is empty (only
 *  `— any —` picks) contribute no `f~<pageId>` param, matching the single-
 *  dashboard rule that trivial slicing is dropped from the link. */
export function encodeAppFilterState(
  activePageId: string | null,
  filtersByPage: Record<string, DashboardFilter[]>,
): string {
  const params = new URLSearchParams();
  if (activePageId) params.set(PAGE_PARAM, activePageId);
  for (const [pageId, filters] of Object.entries(filtersByPage)) {
    appendFilterParams(params, pageFilterParam(pageId), filters);
  }
  const s = params.toString();
  return s ? `?${s}` : "";
}

/** Parse App Builder page + per-page filter state out of a URL's search
 *  params. The active page id comes from `p`; each `f~<pageId>` param yields
 *  that page's filter set. Pages with no valid filters are omitted from
 *  `filtersByPage`. */
export function decodeAppFilterState(search: URLSearchParams): AppFilterState {
  const activePageId = search.get(PAGE_PARAM);
  const filtersByPage: Record<string, DashboardFilter[]> = {};
  const prefix = `${PARAM}${PAGE_FILTER_SEP}`;
  for (const key of new Set(search.keys())) {
    if (!key.startsWith(prefix)) continue;
    const pageId = key.slice(prefix.length);
    if (!pageId) continue;
    const filters = parseFilterValues(search.getAll(key));
    if (filters.length > 0) filtersByPage[pageId] = filters;
  }
  return { activePageId: activePageId || null, filtersByPage };
}
