/*
 * Effective-query builder. For each tile on a dashboard, computes the
 * AiQueryRequest that should actually run given:
 *
 *   - the tile's base query (inline AiQueryRequest body), and
 *   - the set of currently-active filters (defaults ∪ widget ∪ click).
 *
 * Per the design doc:
 *   - a filter applies to a tile only if its dim/hier/level resolves
 *     in the tile's cube schema (alias-aware);
 *   - for the same hierarchy, the active filter REPLACES any baked-in
 *     filter on the base query;
 *   - a filter whose hierarchy is already on the tile's rows/columns
 *     axis is silently skipped (saiku#784 — axis-reuse).
 *
 * Pure functions. No side effects, no fetches. The tile renderer
 * subscribes to the active-filters store and recomputes via this
 * builder on every change.
 */

import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";
import type { DashboardTile } from "$lib/api/dashboards";

/* ------------------------ minimal schema shape ----------------------- */

/** Loose schema shape — just the bits the applicability check reads.
 *  Aligns with org.saiku.service.olap.ai.AiSchema on the Java side. */
export interface SchemaLevelLike {
  name: string;
}
export interface SchemaHierarchyLike {
  name: string;
  levels: Record<string, SchemaLevelLike>;
}
export interface SchemaDimensionLike {
  name: string;
  hierarchies: Record<string, SchemaHierarchyLike>;
}
export interface SchemaLike {
  dimensions: Record<string, SchemaDimensionLike>;
  /** Optional alias maps. Both Java-side maps use lowercased keys; we
   *  mirror that here. */
  dimensionAliases?: Record<string, string>;
}

/* ------------------------ query request shape ------------------------ */

/** Just enough of AiQueryRequest to do the merge — bodies are forwarded
 *  to /ai/query verbatim so unknown fields pass through. */
export interface AxisSelection {
  dimension: string;
  hierarchy: string;
  level: string;
  members?: string[];
  // ...passthrough fields tolerated
  [extra: string]: unknown;
}

export interface FilterSelection {
  dimension: string;
  hierarchy: string;
  level: string;
  members?: string[];
  op?: string;
  [extra: string]: unknown;
}

export interface AiQueryRequestLike {
  cube: unknown;
  measures: unknown[];
  rows?: AxisSelection[];
  columns?: AxisSelection[];
  filters?: FilterSelection[];
  [extra: string]: unknown;
}

/* ---------------------------- canonicalise --------------------------- */

/** Mirror Java's AiSchema.key — trim + lowercase for case-insensitive
 *  map lookups. */
function k(s: string | undefined | null): string {
  return (s ?? "").trim().toLowerCase();
}

/** Resolve a dim/hier/level triple against the schema. Returns the
 *  canonical names if found, null if the level doesn't resolve.
 *  Honours dimensionAliases (e.g. "revenue" → "store sales"). */
export function resolveTarget(
  schema: SchemaLike,
  dimension: string,
  hierarchy: string,
  level: string,
): { dimension: string; hierarchy: string; level: string } | null {
  const dimKeyRaw = k(dimension);
  // Walk the alias map first; it stores synonym → canonical.
  const canonicalDimKey = schema.dimensionAliases?.[dimKeyRaw] ?? dimKeyRaw;
  const dim = schema.dimensions[canonicalDimKey];
  if (!dim) return null;
  const hier = dim.hierarchies[k(hierarchy)];
  if (!hier) return null;
  const lvl = hier.levels[k(level)];
  if (!lvl) return null;
  return { dimension: dim.name, hierarchy: hier.name, level: lvl.name };
}

/* --------------------------- applicability --------------------------- */

/** Does this filter target a resolvable dim/hier/level in the schema? */
export function appliesToSchema(filter: FilterSelection | ActiveFilter["filter"], schema: SchemaLike): boolean {
  return resolveTarget(schema, filter.dimension, filter.hierarchy, filter.level) !== null;
}

/** Is the filter's hierarchy ALREADY on the tile's rows/columns axis?
 *  saiku#784 — same hierarchy on axis + filter is a server-side validation
 *  error; we surface it as "silently skip" client-side to avoid the
 *  agent-facing 400. */
export function conflictsWithAxis(filter: FilterSelection, base: AiQueryRequestLike): boolean {
  const axes = [...(base.rows ?? []), ...(base.columns ?? [])];
  const fkey = `${k(filter.dimension)}/${k(filter.hierarchy)}`;
  return axes.some((ax) => `${k(ax.dimension)}/${k(ax.hierarchy)}` === fkey);
}

/* ------------------------------- merge ------------------------------- */

/** Merge the active filter set into a base query, returning a new
 *  query — never mutates the input. Replace-by-hierarchy semantics:
 *
 *   - For each applicable active filter:
 *       - if base.filters already has one for the same (dim, hier),
 *         replace it.
 *       - else append.
 *   - Filters whose dim/hier/level doesn't resolve in the schema are
 *     dropped silently (per design decision 5 — multi-cube auto-skip).
 *   - Filters whose hierarchy is already on base.rows/columns are
 *     dropped silently (saiku#784).
 *
 *  `activeFilters` is the merged set from $lib/stores/activeFilters —
 *  callers should pass `.all`, not the source-tagged sub-arrays. */
export function mergeFilters(
  base: AiQueryRequestLike,
  activeFilters: ActiveFilter[],
  schema: SchemaLike,
): AiQueryRequestLike {
  const merged: FilterSelection[] = (base.filters ?? []).map((f) => ({ ...f }));

  for (const af of activeFilters) {
    const f = af.filter;
    // 1. applicability against schema
    const resolved = resolveTarget(schema, f.dimension, f.hierarchy, f.level);
    if (!resolved) continue;
    // 2. axis-reuse conflict
    const candidate: FilterSelection = {
      dimension: resolved.dimension,
      hierarchy: resolved.hierarchy,
      level: resolved.level,
      members: f.members.slice(),
    };
    if (conflictsWithAxis(candidate, base)) continue;
    // 3. replace-by-hierarchy or append
    const hkey = `${k(candidate.dimension)}/${k(candidate.hierarchy)}`;
    const idx = merged.findIndex((existing) => `${k(existing.dimension)}/${k(existing.hierarchy)}` === hkey);
    if (idx >= 0) {
      merged[idx] = candidate;
    } else {
      merged.push(candidate);
    }
  }

  return { ...base, filters: merged };
}

/* --------------------------- effective query ------------------------- */

/** Compute the AiQueryRequest a tile should send to /ai/query right now.
 *  Returns null for tiles that have no inline body (reference tiles
 *  require fetching the saved query first — handled by the caller). */
export function effectiveQueryFor(
  tile: DashboardTile,
  activeFilters: ActiveFilter[],
  schema: SchemaLike | null,
): AiQueryRequestLike | null {
  if (tile.type !== "chart" && tile.type !== "table") return null;
  if (!tile.query) return null;
  if (tile.query.kind !== "inline") {
    // Reference tiles: the caller resolves the .saiku file first, then
    // can call mergeFilters() directly with the loaded body.
    return null;
  }
  const base = tile.query.body as unknown as AiQueryRequestLike;
  if (!base || typeof base !== "object") return null;
  if (!schema) return base; // can't merge without the schema — return base unchanged
  return mergeFilters(base, activeFilters, schema);
}
