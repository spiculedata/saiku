/*
 * Builds the POST /ai/narrate-dashboard payload's tile list from the
 * dashboard's current layout (saiku#910).
 *
 * Reuses effectiveQueryFor — the SAME filter-merge builder ChartTile /
 * TableTile use before posting to /ai/query — so the narrative is computed
 * from exactly what the viewer currently sees, not a server-side
 * re-derivation of dashboard filter state (the dashboard layer stays
 * layout-only on the backend; see DashboardResource's own doc comment).
 *
 * Pure: schema lookup is injected (`lookupSchema`) rather than reaching into
 * the schemaCache singleton directly, so this stays testable without a
 * Svelte runtime. The caller (DashboardEditor) is responsible for priming
 * the schema cache and re-deriving on `schemaCache.version` changes, mirroring
 * ChartTile's own two-effect (prime / derive) split.
 */

import type { DashboardTile, CubeRef } from '$lib/api/dashboards';
import type { ActiveFilter } from '$lib/stores/activeFilters.svelte';
import { effectiveQueryFor, type SchemaLike } from '$lib/dashboard/effectiveQuery';
import type { NarrativeTileInput } from '$lib/api/aiNarrateDashboard';

export type SchemaLookup = (cube: CubeRef) => SchemaLike | null;

/** Fallback title mirroring Tile.svelte's defaultTitle() for the types this builder covers. */
function defaultTitleFor(tile: DashboardTile): string {
	if (tile.type === 'chart') return tile.chartType ? `${tile.chartType} chart` : 'Chart';
	if (tile.type === 'table') return 'Table';
	return 'Tile';
}

/**
 * One entry per tile that effectiveQueryFor can resolve right now — chart /
 * table / custom tiles with an INLINE query body. A tile whose schema
 * hasn't finished loading yet still contributes its BASE query (unfiltered
 * — effectiveQueryFor can't merge active filters without the schema, so it
 * returns the base unchanged); a reference-query tile, or a non-query type
 * (kpi/text/filter/image), is skipped — same "skip, don't fail" posture the
 * server endpoint takes for a malformed tile.
 */
export function resolveNarrativeTiles(
	tiles: DashboardTile[],
	activeFiltersList: ActiveFilter[],
	lookupSchema: SchemaLookup
): NarrativeTileInput[] {
	const out: NarrativeTileInput[] = [];
	for (const tile of tiles) {
		if (tile.type !== 'chart' && tile.type !== 'table' && tile.type !== 'custom') continue;
		const schema = tile.cube ? lookupSchema(tile.cube) : null;
		const query = effectiveQueryFor(tile, activeFiltersList, schema);
		if (!query) continue;
		out.push({
			title: tile.title?.trim() || defaultTitleFor(tile),
			query: query as unknown as Record<string, unknown>
		});
	}
	return out;
}
