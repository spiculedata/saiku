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

import type { ActiveFilter } from '$lib/stores/activeFilters.svelte';
import type { DashboardTile } from '$lib/api/dashboards';

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
	return (s ?? '').trim().toLowerCase();
}

/** Resolve a dim/hier/level triple against the schema. Returns the
 *  canonical names if found, null if the level doesn't resolve.
 *  Honours dimensionAliases (e.g. "revenue" → "store sales"). */
export function resolveTarget(
	schema: SchemaLike,
	dimension: string,
	hierarchy: string,
	level: string
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

/** saiku#1774 — 0-based position of a level within its hierarchy, or -1 when it
 *  doesn't resolve. `levels` is an insertion-ordered record mirroring the
 *  server's level order, so the index IS the depth. */
export function levelDepth(
	schema: SchemaLike,
	dimension: string,
	hierarchy: string,
	level: string
): number {
	const dimKeyRaw = k(dimension);
	const dim = schema.dimensions[schema.dimensionAliases?.[dimKeyRaw] ?? dimKeyRaw];
	const hier = dim?.hierarchies[k(hierarchy)];
	if (!hier) return -1;
	return Object.keys(hier.levels).indexOf(k(level));
}

/** The axis entry sitting at the deepest level among `entries` (all assumed to
 *  share one hierarchy). Null when none resolve. */
function deepestAxisEntry(schema: SchemaLike, entries: AxisSelection[]): AxisSelection | null {
	let best: AxisSelection | null = null;
	let bestDepth = -1;
	for (const a of entries) {
		const d = levelDepth(schema, a.dimension, a.hierarchy, a.level);
		if (d > bestDepth) {
			bestDepth = d;
			best = a;
		}
	}
	return best;
}

/* --------------------------- applicability --------------------------- */

/** Does this filter target a resolvable dim/hier/level in the schema? */
export function appliesToSchema(
	filter: FilterSelection | ActiveFilter['filter'],
	schema: SchemaLike
): boolean {
	return resolveTarget(schema, filter.dimension, filter.hierarchy, filter.level) !== null;
}

/** Is the filter's hierarchy already on the tile's rows/columns axis?
 *  Kept as a public predicate for callers that want to short-circuit
 *  before invoking the full merge; the merge itself no longer DROPS on
 *  this case — see {@link mergeFilters} for the axis-rewrite semantics. */
export function conflictsWithAxis(filter: FilterSelection, base: AiQueryRequestLike): boolean {
	const axes = [...(base.rows ?? []), ...(base.columns ?? [])];
	const fkey = `${k(filter.dimension)}/${k(filter.hierarchy)}`;
	return axes.some((ax) => `${k(ax.dimension)}/${k(ax.hierarchy)}` === fkey);
}

/* ------------------------------- merge ------------------------------- */

/** Merge the active filter set into a base query, returning a new query
 *  — never mutates the input. Semantics mirror the server-side
 *  ThinQueryFilterMerge for reference tiles:
 *
 *   - For each applicable active filter:
 *       - if the filter's hierarchy is ALREADY on rows / columns of the
 *         tile, REWRITE that axis selection in place: collapse all
 *         entries for the matching hierarchy down to a single entry at
 *         the filter's level + members. The dashboard filter's level
 *         wins (so a chart showing Country+State drilldown narrowed by
 *         a State chip will render at the State grain).
 *       - else replace-or-append into `filters[]` keyed by hierarchy.
 *   - Filters whose dim/hier/level doesn't resolve in the schema are
 *     dropped silently (per design decision 5 — multi-cube auto-skip).
 *
 *  Previously this dropped axis-overlap filters silently (the old saiku#784
 *  guard). That was wrong: dashboard chips should narrow the existing
 *  axis, not be a no-op. Mondrian's hierarchy-on-both-axis rejection is
 *  still respected because we never produce a query with the same
 *  hierarchy on an axis AND in filters[] — only one wins per filter.
 *
 *  `activeFilters` is the merged set from $lib/stores/activeFilters —
 *  callers should pass `.all`, not the source-tagged sub-arrays. */
export function mergeFilters(
	base: AiQueryRequestLike,
	activeFilters: ActiveFilter[],
	schema: SchemaLike
): AiQueryRequestLike {
	// Mutable shallow copies — the merge rewrites entries in place when
	// an active filter shares a hierarchy with an axis selection.
	let rows: AxisSelection[] = (base.rows ?? []).map((a) => ({ ...a }));
	let columns: AxisSelection[] = (base.columns ?? []).map((a) => ({ ...a }));
	const merged: FilterSelection[] = (base.filters ?? []).map((f) => ({ ...f }));

	function collapseAxisOnHierarchy(
		axis: AxisSelection[],
		hkey: string,
		replacement: AxisSelection
	): AxisSelection[] {
		let inserted = false;
		const next: AxisSelection[] = [];
		for (const a of axis) {
			const akey = `${k(a.dimension)}/${k(a.hierarchy)}`;
			if (akey !== hkey) {
				next.push(a);
				continue;
			}
			if (!inserted) {
				next.push(replacement);
				inserted = true;
			}
			// subsequent matches on the same hierarchy are dropped — the
			// dashboard filter collapses the hierarchical drilldown into a
			// single axis entry at the filter's level.
		}
		return next;
	}

	for (const af of activeFilters) {
		const f = af.filter;
		const resolved = resolveTarget(schema, f.dimension, f.hierarchy, f.level);
		if (!resolved) continue;

		const candidate: FilterSelection = {
			dimension: resolved.dimension,
			hierarchy: resolved.hierarchy,
			level: resolved.level,
			members: f.members.slice()
		};
		const hkey = `${k(candidate.dimension)}/${k(candidate.hierarchy)}`;

		const onRows = rows.some((a) => `${k(a.dimension)}/${k(a.hierarchy)}` === hkey);
		const onCols = !onRows && columns.some((a) => `${k(a.dimension)}/${k(a.hierarchy)}` === hkey);

		if (onRows || onCols) {
			// saiku#1774: collapsing to the FILTER's level is right for a drilldown
			// (Country+State on the axis, narrowed by a State chip → render at State).
			// It is wrong when the tile groups FINER than the filter: an App context
			// pill on warehouse State Province against a list of Warehouse Names used
			// to replace the axis, turning 13 warehouses into a single "WA" row. When
			// the axis is deeper, keep the tile's own level and hand the ancestor
			// members to it — the converter emits Descendants(members, level), which
			// narrows without changing the grain and stays axis-only (a WHERE slicer
			// would trip Mondrian's hierarchy-on-two-axes rule).
			const axisLevels = (onRows ? rows : columns).filter(
				(a) => `${k(a.dimension)}/${k(a.hierarchy)}` === hkey
			);
			const filterDepth = levelDepth(
				schema,
				candidate.dimension,
				candidate.hierarchy,
				candidate.level
			);
			const deepest = deepestAxisEntry(schema, axisLevels);
			// Only descend when the filter's level ISN'T already on the axis. If it is,
			// the tile is showing a drilldown THROUGH the filtered level and collapsing
			// to it is the established, wanted behaviour (Country+State narrowed by a
			// State chip → render at State).
			const filterLevelOnAxis = axisLevels.some((a) => k(a.level) === k(candidate.level));
			const axisIsFiner =
				!filterLevelOnAxis &&
				deepest != null &&
				filterDepth >= 0 &&
				levelDepth(schema, deepest.dimension, deepest.hierarchy, deepest.level) > filterDepth;

			const axisReplacement: AxisSelection = axisIsFiner
				? { ...deepest, members: (candidate.members ?? []).slice() }
				: {
						dimension: candidate.dimension,
						hierarchy: candidate.hierarchy,
						level: candidate.level,
						members: (candidate.members ?? []).slice()
					};
			if (onRows) {
				rows = collapseAxisOnHierarchy(rows, hkey, axisReplacement);
			} else {
				columns = collapseAxisOnHierarchy(columns, hkey, axisReplacement);
			}
			continue;
		}

		// Hierarchy isn't on either axis — replace-by-hierarchy in filters[]
		// or append.
		//
		// A zero-member filter means "no constraint", and the server rejects an
		// `in` with no members ("'in' filter must specify at least one member").
		// So an emptied filter REMOVES its entry rather than emitting an invalid
		// one. Note this only applies here: on an axis, empty members legitimately
		// means "every member at this level", which the branch above relies on.
		const idx = merged.findIndex(
			(existing) => `${k(existing.dimension)}/${k(existing.hierarchy)}` === hkey
		);
		if ((candidate.members?.length ?? 0) === 0) {
			if (idx >= 0) merged.splice(idx, 1);
			continue;
		}
		if (idx >= 0) {
			merged[idx] = candidate;
		} else {
			merged.push(candidate);
		}
	}

	return { ...base, rows, columns, filters: merged };
}

/* --------------------------- effective query ------------------------- */

/** Project the active filters into the SavedQueryFilter shape the
 *  /ai/query/saved endpoint expects. Drops filters whose dim/hier/level
 *  doesn't resolve in the tile's cube schema (multi-cube auto-skip).
 *  Returns canonical names so the server-side AiSchema lookup hits the
 *  same key whether the client supplied a canonical or display-name
 *  variant. */
export function applicableSavedFilters(
	schema: SchemaLike | null,
	activeFilters: ActiveFilter[]
): FilterSelection[] {
	if (!schema) return [];
	const out: FilterSelection[] = [];
	for (const af of activeFilters) {
		const f = af.filter;
		// Same reasoning as the inline path: a zero-member filter is no constraint,
		// and the server rejects an `in` with no members.
		if (f.members.length === 0) continue;
		const resolved = resolveTarget(schema, f.dimension, f.hierarchy, f.level);
		if (!resolved) continue;
		out.push({
			dimension: resolved.dimension,
			hierarchy: resolved.hierarchy,
			level: resolved.level,
			members: f.members.slice()
		});
	}
	return out;
}

/** Compute the AiQueryRequest a tile should send to /ai/query right now.
 *  Returns null for tiles that have no inline body (reference tiles
 *  require fetching the saved query first — handled by the caller). */
export function effectiveQueryFor(
	tile: DashboardTile,
	activeFilters: ActiveFilter[],
	schema: SchemaLike | null
): AiQueryRequestLike | null {
	// chart / table + queryable custom tiles (App Builder Phase 2, saiku#1441):
	// a custom renderer with an inline query body computes its effective query the
	// same way. Non-query tile types (filter / text / kpi / image) have no inline
	// body to merge, so they short-circuit to null.
	if (tile.type !== 'chart' && tile.type !== 'table' && tile.type !== 'custom') return null;
	if (!tile.query) return null;
	if (tile.query.kind !== 'inline') {
		// Reference tiles: the caller resolves the .saiku file first, then
		// can call mergeFilters() directly with the loaded body.
		return null;
	}
	const base = tile.query.body as unknown as AiQueryRequestLike;
	if (!base || typeof base !== 'object') return null;
	if (!schema) return base; // can't merge without the schema — return base unchanged
	// saiku#1085: a tile must NOT apply its own brush cross-filter — it keeps
	// full context while every OTHER tile narrows. Click/panel filters still
	// apply everywhere (unchanged), so only "cross" filters from this tile are
	// excluded here.
	const applicable = activeFilters.filter(
		(af) => !(af.source.kind === 'cross' && af.source.tileId === tile.id)
	);
	return mergeFilters(base, applicable, schema);
}
