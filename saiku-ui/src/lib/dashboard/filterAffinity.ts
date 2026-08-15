/*
 * Filter affinity (#924). Pure compatibility check answering "which tiles
 * does this dashboard filter actually narrow?" — used to render the
 * "Affects N of M tiles" hover hint on a panel filter and to highlight /
 * dim the grid tiles accordingly.
 *
 * It reuses the SAME resolveTarget compatibility the filter-merge uses
 * (effectiveQuery.ts), so the hint can never disagree with what merging
 * actually does: a filter affects a tile iff the tile is a chart/table
 * with a cube whose loaded schema resolves the filter's dim/hier/level
 * (alias-aware). No DOM, no fetch — the caller supplies a schema resolver
 * (typically schemaCache.peek) so this unit-tests cleanly.
 */

import { resolveTarget, type SchemaLike } from '$lib/dashboard/effectiveQuery';
import type { CubeRef, DashboardTile } from '$lib/api/dashboards';
// saiku#1803: model-backed tiles are reached through a semantic binding, not by
// resolving a dim/hier/level in an MDX schema.
import { isOssieSource } from '$lib/dashboard/tileSource';
import { filterReachesSource, type SemanticFilter } from '$lib/dashboard/semanticFilter';

/** The dim/hier/level a panel filter targets. */
export interface FilterTarget {
	dimension: string;
	hierarchy: string;
	level: string;
	/** saiku#1803: per-source bindings, when the caller has them. Without these a
	 *  filter can only be judged against MDX tiles, which is what made the badge
	 *  under-report once semantic filters existed. */
	bindings?: SemanticFilter['bindings'];
}

/** Just the tile fields the affinity check reads — keeps callers (and
 *  tests) from having to build a whole DashboardTile. */
export type AffinityTile = Pick<DashboardTile, 'id' | 'type' | 'cube' | 'query' | 'kpi'>;

export interface FilterAffinity {
	/** ids of filterable tiles whose cube schema resolves the filter target. */
	affected: Set<string>;
	affectedCount: number;
	/** total tiles on the dashboard — the denominator of "N of M". */
	totalCount: number;
}

/** Tiles a panel filter can actually narrow:
 *   - chart / table tiles that carry a query, and
 *   - KPI tiles with a measure (they carry no query — slicing comes purely
 *     from the dashboard-active filters applied to their cube).
 *  Text and image tiles never consume filters, so they always dim. */
export function isFilterableTile(tile: Pick<DashboardTile, 'type' | 'query' | 'kpi'>): boolean {
	if (tile.type === 'chart' || tile.type === 'table') return !!tile.query;
	if (tile.type === 'kpi') return !!tile.kpi?.measure;
	return false;
}

/** Pure: which tiles a filter affects. `schemaFor` returns the loaded
 *  schema for a tile's cube (or null when not cached yet / no cube).
 *
 *  A tile counts as affected iff it is filterable, has a cube, that cube's
 *  schema is loaded, AND the filter's dim/hier/level resolves in it. A
 *  tile whose schema isn't loaded (or whose cube can't be determined) is
 *  treated as NOT affected — a conservative hint never promises a narrow
 *  it can't prove. `totalCount` is every tile on the dashboard so the
 *  badge reads "N of M" against the full board, and every non-affected
 *  tile (incl. text/image/kpi) dims. */
export function computeFilterAffinity(
	target: FilterTarget,
	tiles: readonly AffinityTile[],
	schemaFor: (cube: CubeRef) => SchemaLike | null
): FilterAffinity {
	const affected = new Set<string>();
	for (const t of tiles) {
		if (!isFilterableTile(t)) continue;
		if (!t.cube) continue;

		// saiku#1803: a tile bound to an Ossie semantic model has no MDX schema to
		// resolve a dim/hier/level against — it is reached through the filter's
		// semantic BINDING instead. Counting only the MDX path made the badge
		// actively wrong once semantic filters existed: a filter narrowing three
		// cube tiles and three model tiles read "Affects 3 of 7", and the three it
		// didn't mention moved anyway.
		if (isOssieSource(t.cube)) {
			if (filterReachesSource(target as SemanticFilter, t.cube)) affected.add(t.id);
			continue;
		}

		const schema = schemaFor(t.cube);
		if (!schema) continue;
		if (resolveTarget(schema, target.dimension, target.hierarchy, target.level)) {
			affected.add(t.id);
		}
	}
	return { affected, affectedCount: affected.size, totalCount: tiles.length };
}
