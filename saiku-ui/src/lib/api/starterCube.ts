/**
 * Starter-cube URL bootstrap (saiku-cloud#450 / saiku-ui contract).
 *
 * <p>When Saiku Studio is launched with the URL params:
 * <pre>
 *   ?starterCubeConnection=&lt;c&gt;
 *   &amp;starterCubeCatalog=&lt;cat&gt;
 *   &amp;starterCubeSchema=&lt;s&gt;
 *   &amp;starterCubeName=&lt;n&gt;
 * </pre>
 * the {@link Workspace} mount hook resolves the cube via the
 * existing discover endpoint, picks the first measure + first
 * dimension level (preferring a time-typed hierarchy if any), and
 * hydrates the workbench so the customer sees a populated query
 * model immediately.
 *
 * <p>Critically: the workbench loads a <strong>query model</strong>,
 * not a flat MDX result. Customer can drag a different measure,
 * drill on the dim, or add filters — Studio re-fires the query on
 * every interaction. Pre-baked MDX (which Studio can't round-trip
 * into draggable chips because there's no MDX → model inverse
 * parser) would freeze the workbench on the starter output.
 *
 * <p>The dashboard's Saiku Cloud `/saiku/launch?cube=&lt;id&gt;` is
 * the typical caller; this contract is generic enough that any
 * embedder can use it.
 */

import type {
	SaikuConnection,
	SaikuCube,
	SaikuDimension,
	SaikuLevel,
	SaikuMeasure
} from '$lib/api/discover';
import type { ThinMeasure } from '$lib/api/query';
import type { LevelDrop } from '$lib/stores/query.svelte';

/** Inputs the URL handler extracts from `?starterCube*=...` params. */
export interface StarterCubeRef {
	connection: string;
	catalog: string;
	schema: string;
	name: string;
}

/**
 * Parse the 4 `starterCube*` URL params from a {@link URLSearchParams}.
 * Returns null when any required field is missing — leaving the
 * Workspace bootstrap to fall through to the empty-workbench default.
 */
export function parseStarterCubeRef(params: URLSearchParams): StarterCubeRef | null {
	const connection = params.get('starterCubeConnection')?.trim();
	const catalog = params.get('starterCubeCatalog')?.trim();
	const schema = params.get('starterCubeSchema')?.trim();
	const name = params.get('starterCubeName')?.trim();
	if (!connection || !catalog || !schema || !name) return null;
	return { connection, catalog, schema, name };
}

/**
 * Walk the loaded connection tree to find the cube matching the ref.
 * Returns null when no cube matches — the caller falls through to
 * the empty-workbench default. We match on all 4 fields because a
 * fresh tenant can have multiple connections with overlapping cube
 * names (e.g. two FoodMart instances; or a customer testing two
 * versions of the same warehouse).
 */
export function findCubeByRef(
	connections: SaikuConnection[],
	ref: StarterCubeRef
): SaikuCube | null {
	for (const conn of connections) {
		if (conn.name !== ref.connection) continue;
		for (const cat of conn.catalogs) {
			if (cat.name !== ref.catalog) continue;
			for (const sch of cat.schemas) {
				if (sch.name !== ref.schema) continue;
				for (const cube of sch.cubes) {
					if (cube.name === ref.name) return cube;
				}
			}
		}
	}
	return null;
}

/**
 * Pick the first measure + first dimension level to seed the starter
 * query. Heuristic priority for the dimension (most-informative-first):
 *
 * <ol>
 *   <li>A hierarchy with a level whose caption / name signals time
 *       (Year / Month / Day / Date / Time substring). Date hierarchies
 *       are the highest-information default for a single-axis query.</li>
 *   <li>A hierarchy whose dimension's caption signals time (e.g. a
 *       dim named "Date" with a single level "Date").</li>
 *   <li>The first dimension's first hierarchy's first level — pure
 *       fallback so we always land somewhere if the cube has any
 *       dimension at all.</li>
 * </ol>
 *
 * <p>Returns null when the cube has no measures (defensive — Mondrian
 * requires ≥1 measure per cube but the contract permits the call) or
 * no dimensions at all. The caller falls through to the empty-workbench
 * default rather than render an obviously-broken starter.
 */
export interface StarterPick {
	measure: ThinMeasure;
	drop: LevelDrop;
}

export function pickStarterMeasureAndLevel(
	measures: SaikuMeasure[],
	dimensions: SaikuDimension[]
): StarterPick | null {
	const measure = pickStarterMeasure(measures);
	if (!measure) return null;
	const drop = pickStarterLevelDrop(dimensions);
	if (!drop) return null;
	return { measure, drop };
}

/**
 * First non-calculated measure when one exists; first measure
 * regardless otherwise. Returns a {@link ThinMeasure} ready to pass
 * to {@code query.addMeasure}. Saiku Studio renders calculated
 * measures identically, but for a starter query a base measure is
 * the less-surprising default ("revenue" beats "revenue per order").
 */
export function pickStarterMeasure(measures: SaikuMeasure[]): ThinMeasure | null {
	if (measures.length === 0) return null;
	const base = measures.find((m) => !m.calculated);
	const picked = base ?? measures[0];
	return {
		name: picked.name,
		uniqueName: picked.uniqueName,
		caption: picked.caption || picked.name,
		type: picked.calculated ? 'CALCULATED' : 'EXACT'
	};
}

/**
 * Find a usable level + the hierarchy + dimension it sits inside,
 * wrap as a {@link LevelDrop} the {@link query.svelte.ts} store can
 * splice onto an axis directly via {@code includeLevel}.
 */
export function pickStarterLevelDrop(dimensions: SaikuDimension[]): LevelDrop | null {
	// Pass 1: any hierarchy whose first level has a time-signalling name.
	for (const dim of dimensions) {
		for (const hier of dim.hierarchies ?? []) {
			const levels = hier.levels ?? [];
			for (const lvl of levels) {
				if (isTimeLikeLevel(lvl)) {
					return makeLevelDrop(dim, hier, lvl);
				}
			}
		}
	}
	// Pass 2: any dimension whose caption / name signals time.
	for (const dim of dimensions) {
		if (isTimeLikeDimensionName(dim.caption || dim.name)) {
			const hier = (dim.hierarchies ?? [])[0];
			const lvl = hier?.levels?.[0];
			if (hier && lvl) return makeLevelDrop(dim, hier, lvl);
		}
	}
	// Pass 3: first dim, first hier, first level.
	for (const dim of dimensions) {
		const hier = (dim.hierarchies ?? [])[0];
		const lvl = hier?.levels?.[0];
		if (hier && lvl) return makeLevelDrop(dim, hier, lvl);
	}
	return null;
}

/**
 * Build a {@link LevelDrop} from a discovered triple. Pulled out
 * for symmetry with the existing chip-drop flow in
 * {@link DimensionList} — keep the shape identical so the store's
 * {@code includeLevel} can't tell the difference between this and
 * a real drag.
 */
function makeLevelDrop(
	dim: { name: string; caption: string; uniqueName: string },
	hier: { name: string; caption: string; uniqueName: string },
	lvl: SaikuLevel
): LevelDrop {
	return {
		dimensionName: dim.name,
		dimensionUniqueName: dim.uniqueName,
		hierarchyName: hier.name,
		hierarchyUniqueName: hier.uniqueName,
		hierarchyCaption: hier.caption,
		levelName: lvl.name,
		levelCaption: lvl.caption
	};
}

const TIME_NAME_REGEX = /\b(year|quarter|month|week|day|date|time)\b/i;

function isTimeLikeLevel(level: SaikuLevel): boolean {
	const caption = level.caption ?? '';
	const name = level.name ?? '';
	return TIME_NAME_REGEX.test(caption) || TIME_NAME_REGEX.test(name);
}

function isTimeLikeDimensionName(name: string): boolean {
	return TIME_NAME_REGEX.test(name);
}
