/*
 * Effective-query builder for Ossie-backed tiles (saiku#1803).
 *
 * The MDX twin of this is effectiveQuery.ts. The two are deliberately separate
 * rather than one generic builder: the merge rules are genuinely different, and
 * the MDX one carries hard-won behaviour that has nothing to do with a semantic
 * model — the hierarchy-already-on-an-axis rewrite, the last-wins slicer rule
 * for two filters on one hierarchy, alias-aware level resolution. Folding a
 * second vocabulary into it would put that behaviour behind conditionals for no
 * gain.
 *
 * What this one has to do is much smaller, because an Ossie query has no
 * concept of a slicer axis: predicates are a flat list of dataset/field/op, and
 * a field can be on an axis AND filtered at the same time without the engine
 * objecting. So the merge is: keep the tile's own filters, drop any the active
 * set overrides on the same dataset+field, append the rest.
 *
 * Pure: no DOM, no fetches.
 */

import type { CubeRef } from '$lib/api/dashboards';
import {
	ossieFiltersFor,
	type OssieFilterExpr,
	type SemanticFilter
} from '$lib/dashboard/semanticFilter';

/** The subset of OssieAiQueryRequest this module reads and writes. Unknown
 *  fields pass through untouched — the body is forwarded verbatim. */
export interface OssieQueryBody {
	connection?: string;
	model?: string;
	rows?: Array<{ dataset: string; field: string }>;
	columns?: Array<{ dataset: string; field: string }>;
	values?: Array<{ metric: string; aggregation?: string }>;
	filters?: OssieFilterExpr[];
	limit?: number;
	[extra: string]: unknown;
}

function predicateKey(f: { dataset: string; field: string }): string {
	return `${f.dataset.toLowerCase()}/${f.field.toLowerCase()}`;
}

/**
 * Merge the active filter set into an Ossie tile's base query.
 *
 * Never mutates the input. An active predicate REPLACES a baked-in one on the
 * same dataset+field — same last-wins rule the MDX path uses for a hierarchy,
 * so an author's default doesn't fight the reader's selection.
 */
export function mergeOssieFilters(
	base: OssieQueryBody,
	activeFilters: readonly SemanticFilter[],
	cube: CubeRef | null | undefined
): OssieQueryBody {
	const incoming = ossieFiltersFor(activeFilters, cube);
	if (incoming.length === 0) return { ...base };
	const overridden = new Set(incoming.map(predicateKey));
	const kept = (base.filters ?? []).filter((f) => !overridden.has(predicateKey(f)));
	return { ...base, filters: [...kept, ...incoming] };
}

/**
 * The body to POST for an Ossie tile: its inline query, with the connection and
 * model pinned from the tile's source and the active filters merged in.
 *
 * Returns null when the tile has no inline body to work from — a reference
 * (saved `.saiku`) query is an MDX artefact and has no meaning here.
 */
export function ossieEffectiveQueryFor(
	tile: { cube?: CubeRef; query?: { kind: string; body?: Record<string, unknown> } },
	activeFilters: readonly SemanticFilter[]
): OssieQueryBody | null {
	if (!tile.cube || tile.query?.kind !== 'inline' || !tile.query.body) return null;
	const base = tile.query.body as OssieQueryBody;
	const merged = mergeOssieFilters(base, activeFilters, tile.cube);
	return {
		...merged,
		// The tile's source is the authority on which model it reads, not whatever
		// the saved body happens to say — the two drift the moment an author
		// re-points the tile.
		connection: tile.cube.connectionName,
		model: tile.cube.modelName ?? tile.cube.cubeName
	};
}
