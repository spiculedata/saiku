/*
 * Period-to-date (PTD) comparison for the KPI tile (saiku#1749).
 *
 * saiku#1748 shipped the honest floor: an author declares `partialTrailing`,
 * the newest periods keep their real values, they are labelled `partial`, and
 * the COMPARISON is withheld — because two days measured against seven
 * describes the calendar, not the business. What that costs the tile is the
 * one thing a KPI is for.
 *
 * This is the comparison that fixes it: the newest period SO FAR against the
 * same portion of the period before it. Week 52's first two days against week
 * 51's first two days. The headline stays the real period-to-date total, so
 * nothing is hidden and nothing is dropped — only the baseline changes, from
 * "all of the prior period" to "the first N sub-periods of it", where N is
 * however far the current period has actually got.
 *
 * ## Where N comes from, and why it is not an inference
 *
 * saiku#1749 listed four reasons this could not be done client-side. Two of
 * them were closed before this landed and two were answered rather than
 * dodged:
 *
 *   1. "No position information" — still true of a period's row metadata, and
 *      irrelevant here. N is the number of sub-period members the current
 *      period actually HAS, read from the time hierarchy's own finer level
 *      (Week → Day). That is presence, not arithmetic: a day member exists
 *      because the day happened, whether or not anything was sold on it.
 *   2. "Captions can't be parsed for it" — agreed, and not attempted. The
 *      baseline is a Mondrian `Descendants()` set (saiku#1774) scoped to one
 *      named period, so the server does the truncation boundary and the
 *      captions are never read.
 *   3. "KpiTile drops the slicer" — the same saiku#1774 Descendants() support
 *      that the tile's own comment was waiting on is what makes this
 *      expressible.
 *   4. "Inference from values is not acceptable" — upheld, and load-bearing.
 *      Nothing here compares a figure against its neighbours to decide whether
 *      a period is finished. N is a member COUNT, and a real collapse leaves
 *      it untouched. The failure direction is the safe one: if the finer level
 *      is missing or unqueryable, {@link periodToDatePair} is simply never
 *      called and the tile falls back to #1748's withhold-the-comparison
 *      behaviour rather than inventing a number.
 *
 * ## Ordering, the one assumption this makes
 *
 * The prior period's sub-periods are truncated from the FRONT, which requires
 * the finer level's members to come back in chronological order. Mondrian
 * gives that for a time dimension: the level's member list is the attribute's
 * declared order, and time attributes carry an OrderBy (`time_id` on
 * FoodMart's Day, which is the whole reason its stub week 52 truncates
 * cleanly). A hand-rolled non-time level on a time hierarchy could violate it;
 * the grain check in {@link finerTimeLevel} is what keeps the author from
 * picking one by accident.
 *
 * Kept DOM/fetch-free and free of `$lib` imports so it is unit-testable
 * (vitest "node" env), the same contract kpiYoy.ts keeps.
 */

/* ------------------------------ schema shape ---------------------------- */

interface PtdLevel {
	name?: string | null;
	/** saiku#818 time-grain tag: year | quarter | month | week | day | hour |
	 *  minute. Optional — unannotated cubes leave it null. */
	grain?: string | null;
}

interface PtdHierarchy {
	/** Level name (lower-cased) → level. The server builds this from the
	 *  hierarchy's declared levels, so iteration order is root-most first —
	 *  the only ordering this module relies on. It reads positionally and never
	 *  assumes whether an "(All)" pseudo-level is present. */
	levels?: Record<string, PtdLevel> | null;
}

interface PtdDimension {
	hierarchies?: Record<string, PtdHierarchy> | null;
}

/** The slice of AiSchema this module reads. `schemaCache.get()` returns a
 *  loose record; this is the part that matters. */
export interface PtdSchema {
	dimensions?: Record<string, PtdDimension> | null;
}

/** The tile's time-level reference, narrowed to what is read here. */
export interface PtdTimeLevel {
	dimension: string;
	hierarchy: string;
	level: string;
}

/* --------------------------- finer level lookup ------------------------- */

/** Coarse → fine. Index order IS finer-ness, which is the only thing the
 *  comparison needs. */
const GRAIN_ORDER = ['year', 'quarter', 'month', 'week', 'day', 'hour', 'minute'] as const;

function grainIndex(grain: string | null | undefined): number | null {
	const i = GRAIN_ORDER.indexOf((grain ?? '').trim().toLowerCase() as never);
	return i < 0 ? null : i;
}

function key(s: string | undefined | null): string {
	return (s ?? '').trim().toLowerCase();
}

/** The sub-period level to truncate with — the level declared immediately
 *  below {@code ref.level} in the same hierarchy.
 *
 *  Returns `null` when the tile cannot support a like-for-like comparison, and
 *  the caller degrades to #1748. That happens when:
 *
 *   - the level isn't in this hierarchy (stale or mismatched `timeLevel`)
 *   - it is the deepest level — there is no finer grain to truncate with,
 *     which is the "cube with no usable day grain" case saiku#1749 raised
 *   - the next level is tagged with a grain that is NOT finer (a sideways or
 *     coarser step in a hierarchy that reuses names); position alone would
 *     happily hand back a level that makes the comparison meaningless
 *
 *  A `null` grain on either side is not treated as a failure — unannotated
 *  cubes are the norm — the position check stands on its own there.
 */
export function finerTimeLevel(
	schema: PtdSchema | null | undefined,
	ref: PtdTimeLevel | null | undefined
): { name: string; grain: string | null } | null {
	if (!schema || !ref) return null;
	const dim = schema.dimensions?.[key(ref.dimension)];
	const hier = dim?.hierarchies?.[key(ref.hierarchy)];
	const levels = hier?.levels;
	if (!levels) return null;
	const keys = Object.keys(levels);
	const idx = keys.indexOf(key(ref.level));
	if (idx < 0 || idx >= keys.length - 1) return null;

	const nextKey = keys[idx + 1];
	const next = levels[nextKey] ?? {};
	const current = levels[keys[idx]] ?? {};

	const currentGrain = grainIndex(current.grain);
	const nextGrain = grainIndex(next.grain);
	if (currentGrain != null && nextGrain != null && nextGrain <= currentGrain) return null;

	return { name: (next.name ?? nextKey).trim() || nextKey, grain: next.grain ?? null };
}

/* ------------------------------ the pair -------------------------------- */

export interface PeriodToDatePair {
	/** Every sub-period of the still-filling period, summed. Equal to the
	 *  period's own aggregate — the headline figure is unchanged by PTD. */
	current: number;
	/** The first {@link offset} sub-periods of the comparison period, summed. */
	prior: number;
	/** How many sub-periods the current period has reached. */
	offset: number;
	/** How many of them were actually summed from the comparison period. */
	baselineCount: number;
	/** True when the comparison period ran out before the offset did — the
	 *  short-month case (day 30 of the month against a 28-day February). There
	 *  is no 30th of February, so its whole self is the closest like-for-like
	 *  figure that exists, and the tile says so rather than implying the two
	 *  windows are the same width. */
	clamped: boolean;
}

/**
 * Truncate the comparison period to the width the current period has reached.
 *
 * `currentBuckets` / `priorBuckets` are the finer level's per-bucket values in
 * chronological order (a `null` bucket is a sub-period that exists but carries
 * no figure — it still counts towards the offset, because the day happened
 * whether or not anything was sold on it).
 *
 * Returns `null` when there is nothing comparable: no sub-periods on the
 * current side (the period is empty, or the finer level returned nothing), or
 * none on the baseline side (no preceding period, or it has no sub-periods at
 * all). Both are the tile's cue to withhold the comparison — never to
 * substitute a whole-period figure for a truncated one.
 */
export function periodToDatePair(
	currentBuckets: ReadonlyArray<number | null> | null | undefined,
	priorBuckets: ReadonlyArray<number | null> | null | undefined
): PeriodToDatePair | null {
	const cur = currentBuckets ?? [];
	const offset = cur.length;
	if (offset === 0) return null;

	const take = Math.min(offset, (priorBuckets ?? []).length);
	if (take === 0) return null;

	return {
		current: sumBuckets(cur),
		prior: sumBuckets((priorBuckets ?? []).slice(0, take)),
		offset,
		baselineCount: take,
		clamped: take < offset
	};
}

function sumBuckets(buckets: ReadonlyArray<number | null>): number {
	let total = 0;
	for (const v of buckets) {
		if (v != null && Number.isFinite(v)) total += v;
	}
	return total;
}

/* --------------------------- response plumbing -------------------------- */

/**
 * Pull the single measure's per-row value out of a records-format response, in
 * row order.
 *
 * A row is `{ rowHeaderString, measureCell }`: the header column is a plain
 * string, the measure column an `{value, formatted}` cell. Structural check
 * only, so this stays import-free — `isAiCell` lives on the API module and
 * pulling that in for one `in` test would make this file un-unit-testable
 * without a DOM-ish environment.
 *
 * Order is the caller's contract, not this function's: MDX returns axis rows in
 * the set's order, and the set here is a `Descendants()` of a single period
 * over a time level, which is chronological.
 */
export function measureValues(
	data: ReadonlyArray<Record<string, unknown>> | null | undefined
): (number | null)[] {
	const out: (number | null)[] = [];
	for (const row of data ?? []) {
		let found: number | null = null;
		for (const v of Object.values(row ?? {})) {
			if (typeof v === 'object' && v !== null && 'value' in v) {
				const raw = (v as { value?: unknown }).value;
				found = typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
				break;
			}
		}
		out.push(found);
	}
	return out;
}

/* ------------------------------- wording -------------------------------- */

const GRAIN_PLURALS: Record<string, string> = {
	day: 'days',
	week: 'weeks',
	month: 'months',
	quarter: 'quarters',
	year: 'years',
	hour: 'hours',
	minute: 'minutes'
};

/**
 * "2 days" / "1 day" / "3 Week" — a count of sub-periods for the period
 * label's tooltip. Falls back to the level's own name when the cube carries no
 * grain annotation, so the sentence stays grammatical even untranslated.
 */
export function grainUnitLabel(
	count: number,
	grain: string | null | undefined,
	levelName: string | null | undefined
): string {
	const g = (grain ?? '').trim().toLowerCase();
	if (g && GRAIN_PLURALS[g]) return `${count} ${count === 1 ? g : GRAIN_PLURALS[g]}`;
	return `${count} ${(levelName ?? 'sub-period').trim() || 'sub-period'}`;
}
