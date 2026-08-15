/*
 * Semantic filter bindings (saiku#1803).
 *
 * A page can mix tiles bound to a Mondrian cube with tiles bound to an Ossie
 * semantic model. The two address their data in vocabularies that share no
 * fields — dimension / hierarchy / level vs dataset / field — so a filter
 * expressed in one is meaningless to the other.
 *
 * The model chosen here is SEMANTIC MAPPING: one filter names a concept
 * ("Country") and carries a BINDING per source saying how that concept is
 * addressed there. One control, one selection, both kinds of tile narrowed.
 *
 *   Country = Mexico
 *     ├─ on cube  Store    →  Store / Stores / Store Country
 *     └─ on model Flights  →  airport.country_code
 *
 * ## The selection is captions, not member unique names
 *
 * An MDX filter selects `[Store].[Stores].[Mexico]`; an Ossie filter selects the
 * string `Mexico`. Neither is meaningful to the other, so the SOURCE-NEUTRAL
 * selection is the caption, and each binding resolves it in its own vocabulary
 * at query time. That is the same trick the App Builder's context pill already
 * uses (an option with a blank `member` resolves its label as a caption), so it
 * is an established rule here rather than a new one.
 *
 * A consequence worth stating: if a caption exists in one source and not the
 * other ("Mexico" vs "MX"), the tile whose source doesn't know it returns no
 * rows. That is the honest outcome — it says the filter found nothing rather
 * than silently showing everything — and since saiku#1804 the empty state names
 * the cube, so the reader can see WHICH source came up empty.
 *
 * ## Back-compat
 *
 * A filter with no `bindings` behaves exactly as it did before: its own
 * dimension / hierarchy / level target every MDX tile, and it reaches no Ossie
 * tile. Every dashboard and app already saved is in that state.
 *
 * Pure: no DOM, no fetches.
 */

import type {
	CubeRef,
	DashboardFilter,
	FilterBinding,
	MdxFilterBinding,
	OssieFilterBinding
} from '$lib/api/dashboards';
import { leafSegment } from '$lib/dashboard/clickFilterMember';
import { isOssieSource, sameSource } from '$lib/dashboard/tileSource';

/* The binding shapes are part of the PERSISTED document, so they live with the
 * rest of the schema in $lib/api/dashboards and are re-exported here for the
 * resolution logic's callers. */
export type MdxBinding = MdxFilterBinding;
export type OssieBinding = OssieFilterBinding;
export type { FilterBinding };

/** One predicate in an Ossie query body — mirrors OssieAiQueryRequest.FilterExpr.
 *  `op` is EQ for a single value and IN for several, which is the whole range a
 *  select-style panel filter can produce. */
export interface OssieFilterExpr {
	dataset: string;
	field: string;
	op: 'EQ' | 'IN';
	value?: string;
	values?: string[];
}

/** The filter shape this module reads. DashboardFilter already carries the
 *  #1803 fields (all optional), so a legacy filter satisfies it as-is; the alias
 *  exists to say "read as a semantic filter" at a call site. */
export type SemanticFilter = DashboardFilter;

/**
 * The selection as captions.
 *
 * Prefers an explicit `captions` list; otherwise takes the leaf segment of each
 * MDX member unique name, so a filter authored before #1803 — which only ever
 * stored members — can still drive an Ossie binding without being re-authored.
 */
export function selectedCaptions(filter: SemanticFilter): string[] {
	if (filter.captions && filter.captions.length > 0) return [...filter.captions];
	return (filter.members ?? []).map(leafSegment).filter((s) => s.length > 0);
}

/** What the chip and the panel call this filter. */
export function filterLabel(filter: SemanticFilter): string {
	return filter.label?.trim() || filter.level || filter.dimension || 'Filter';
}

/**
 * The binding that addresses `cube`, or null when this filter cannot reach it.
 *
 * The legacy fallback is deliberately asymmetric: a filter with no bindings
 * targets ANY mdx source (that is what it meant before bindings existed, and
 * applicability was decided downstream by whether the level resolved in the
 * tile's schema) but reaches NO ossie source, because nothing in it names a
 * dataset or field. Inventing one from the level name would guess at the
 * author's data model.
 */
export function bindingFor(
	filter: SemanticFilter,
	cube: CubeRef | null | undefined
): FilterBinding | null {
	if (!cube) return null;
	const explicit = (filter.bindings ?? []).find((b) => sameSource(b.cube, cube));
	if (explicit) return explicit;
	if (filter.bindings && filter.bindings.length > 0) return null;
	if (isOssieSource(cube)) return null;
	return {
		kind: 'mdx',
		cube,
		dimension: filter.dimension,
		hierarchy: filter.hierarchy,
		level: filter.level
	};
}

/** Does this filter address `cube` at all? */
export function filterReachesSource(
	filter: SemanticFilter,
	cube: CubeRef | null | undefined
): boolean {
	return bindingFor(filter, cube) !== null;
}

/**
 * The MDX filter to merge into a cube tile's query, or null when this filter
 * doesn't address that cube.
 *
 * Members are carried through when the binding is the tile's own legacy target
 * (they are already unique names for that hierarchy). For an explicit binding
 * they are NOT: a unique name is scoped to the hierarchy it came from, so
 * reusing one against a different level would filter on a member that doesn't
 * exist there. Those are left to be resolved from captions by the caller, which
 * has the member catalogue.
 */
export function mdxFilterFor(
	filter: SemanticFilter,
	cube: CubeRef | null | undefined
): (DashboardFilter & { captions?: string[] }) | null {
	const b = bindingFor(filter, cube);
	if (!b || b.kind !== 'mdx') return null;
	const isLegacyTarget =
		b.dimension === filter.dimension &&
		b.hierarchy === filter.hierarchy &&
		b.level === filter.level;
	return {
		dimension: b.dimension,
		hierarchy: b.hierarchy,
		level: b.level,
		members: isLegacyTarget ? [...(filter.members ?? [])] : [],
		captions: selectedCaptions(filter)
	};
}

/**
 * The Ossie predicate for a model tile, or null when this filter doesn't
 * address that model (or selects nothing, which is a no-op rather than a
 * filter matching zero rows).
 */
export function ossieFilterFor(
	filter: SemanticFilter,
	cube: CubeRef | null | undefined
): OssieFilterExpr | null {
	const b = bindingFor(filter, cube);
	if (!b || b.kind !== 'ossie') return null;
	const captions = selectedCaptions(filter);
	if (captions.length === 0) return null;
	return captions.length === 1
		? { dataset: b.dataset, field: b.field, op: 'EQ', value: captions[0] }
		: { dataset: b.dataset, field: b.field, op: 'IN', values: captions };
}

/** Every Ossie predicate the active filter set contributes to one model tile. */
export function ossieFiltersFor(
	filters: readonly SemanticFilter[],
	cube: CubeRef | null | undefined
): OssieFilterExpr[] {
	const out: OssieFilterExpr[] = [];
	for (const f of filters) {
		const expr = ossieFilterFor(f, cube);
		if (expr) out.push(expr);
	}
	return out;
}

/** Replace (or add) the binding for one source, leaving the others alone. */
export function withBinding(filter: SemanticFilter, binding: FilterBinding): SemanticFilter {
	const rest = (filter.bindings ?? []).filter((b) => !sameSource(b.cube, binding.cube));
	return { ...filter, bindings: [...rest, binding] };
}

/** Drop the binding for one source. */
export function withoutBinding(filter: SemanticFilter, cube: CubeRef): SemanticFilter {
	return { ...filter, bindings: (filter.bindings ?? []).filter((b) => !sameSource(b.cube, cube)) };
}
