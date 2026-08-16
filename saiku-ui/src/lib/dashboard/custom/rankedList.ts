/*
 * Pure projection + config validator for the `ranked-list` custom tile
 * renderer (saiku#1441).
 *
 * This is the "Movers" card as a first-class tile. FoodMart Ops originally
 * faked it: a plain Table tile plus ~12 lines of app `customCss` using
 * `.tile:has(tbody)` to hide the thead, `counter-reset` to synthesise rank
 * numerals, and `tbody tr:nth-child(n+7){display:none}` to cap the row count.
 * None of that is reachable from the authoring UI, it collided with any other
 * table in the app, and the hand-written `content:'… \00B7 MoM'` even ate its
 * own space (rendering "department ·MoM") — a good illustration of why this
 * belongs in a tile, not a stylesheet.
 *
 * Config is DECLARATIVE (Tier-1, no code):
 *
 *   { labelColumn?, valueColumn?, limit?, sort?, tone?, subtitle?, showRank? }
 *
 * Column names are optional: omitted, the projection picks the first typed
 * measure cell as the value and the first remaining column as the label, so a
 * tile bound to a two-column query works with no configuration at all. See
 * {@link pickColumns} for why that inference is structural rather than a test
 * of whether the text parses as a number (saiku#1756).
 *
 * Self-contained on purpose — no `$lib` imports — because the embed bundle
 * compiles this module without the alias (same constraint as graphTile.ts).
 */

import { formatKpi } from '../kpi';

/** Sort applied to the projected rows. "none" preserves query order. */
export type RankedSort = 'desc' | 'asc' | 'none';

/** How the value is coloured. "signed" is the movers look: positive values take
 *  the positive token, negatives the danger token. */
export type RankedTone = 'signed' | 'none';

/** Declarative config for the `ranked-list` renderer. */
export interface RankedListConfig {
	/** Column holding the row label. Default: the first column that isn't the
	 *  value column. */
	labelColumn?: string;
	/** Column holding the ranked value. Default: the first typed measure cell
	 *  (falling back to the first numeric column when the result has none). */
	valueColumn?: string;
	/** Max rows rendered. Default {@link DEFAULT_LIMIT}. */
	limit?: number;
	/** Default "none" — the query's own order is usually already the ranking. */
	sort?: RankedSort;
	/** Default "signed". */
	tone?: RankedTone;
	/** Muted line under the tile title (e.g. "Product department · MoM"). */
	subtitle?: string;
	/** Optional display pattern for the value, in the same vocabulary the KPI
	 *  tile and the ECharts value axis use ("$c1", "€0", "2%", "0"). Unset, the
	 *  server's own `formatted` string is shown (saiku#1757). */
	valueFormat?: string;
	/** Show the leading rank numeral. Default true. */
	showRank?: boolean;
}

/** One projected row, ready to render. */
export interface RankedRow {
	rank: number;
	label: string;
	/** Numeric value, or null when the cell had none (unparseable / missing). */
	value: number | null;
	/** Server-formatted display string; falls back to the raw value. */
	formatted: string;
	/** Colour class the renderer applies — driven by config.tone + the sign. */
	tone: 'positive' | 'negative' | 'flat';
}

export const DEFAULT_LIMIT = 6;
const MAX_LIMIT = 100;

/** The typed measure-cell envelope the AI query API returns. */
interface CellLike {
	value?: unknown;
	formatted?: unknown;
}

function isCell(v: unknown): v is CellLike {
	return typeof v === 'object' && v !== null && ('value' in v || 'formatted' in v);
}

/** Numeric reading of a record cell, or null when there isn't one. Handles the
 *  plain-number, typed-cell and numeric-string cases. */
export function cellNumber(v: unknown): number | null {
	if (typeof v === 'number') return Number.isFinite(v) ? v : null;
	if (isCell(v)) return cellNumber(v.value);
	if (typeof v === 'string') {
		// Tolerate grouping separators and a trailing %, which is how a cube often
		// hands back a growth measure.
		const cleaned = v.replace(/[\s,%]/g, '');
		if (cleaned === '' || cleaned === '-') return null;
		const n = Number(cleaned);
		return Number.isFinite(n) ? n : null;
	}
	return null;
}

/** Display string for a record cell — the server's `formatted` when present. */
export function cellText(v: unknown): string {
	if (v == null) return '';
	if (typeof v === 'string') return v;
	if (typeof v === 'number') return String(v);
	if (isCell(v)) {
		if (typeof v.formatted === 'string' && v.formatted !== '') return v.formatted;
		return v.value == null ? '' : String(v.value);
	}
	return String(v);
}

/** Clamp a configured limit into range; anything unusable falls back to the
 *  default rather than rendering an unbounded list. */
export function normaliseLimit(limit: unknown): number {
	const n = typeof limit === 'number' ? Math.floor(limit) : Number.NaN;
	if (!Number.isFinite(n) || n < 1) return DEFAULT_LIMIT;
	return Math.min(n, MAX_LIMIT);
}

/** Pick the label + value columns: explicit config wins; otherwise the value
 *  column is inferred STRUCTURALLY and the label is what's left.
 *
 *  Structurally, because sniffing whether a cell parses as a number gets it
 *  backwards on any cube whose captions look numeric (saiku#1756) — a
 *  prescriber decile ("10.0"), a year, a store number, a size band. Such a
 *  card rendered the measure as its label and the dimension as its value, with
 *  no error to show for it.
 *
 *  A measure comes back from /ai/query as the typed { value, formatted }
 *  envelope; a row caption is a bare string. That envelope is the signal, and
 *  it can't be confused by the caption's text. Number sniffing stays as the
 *  fallback for record shapes that carry no typed cells at all (plain
 *  number-valued records from a plugin or a hand-built result). */
export function pickColumns(
	records: Array<Record<string, unknown>>,
	config: RankedListConfig
): { labelColumn: string | null; valueColumn: string | null } {
	const first = records[0];
	const keys = first ? Object.keys(first) : [];
	const numeric = (k: string) => records.some((r) => cellNumber(r[k]) !== null);
	const typedMeasure = (k: string) => records.some((r) => isCell(r[k]));
	const hasTypedMeasure = keys.some(typedMeasure);
	/** A column that can hold the ranked value: the typed measure envelope when
	 *  the result has any, else anything that reads as a number. */
	const valueCandidate = (k: string) => (hasTypedMeasure ? typedMeasure(k) : numeric(k));

	const valueColumn =
		config.valueColumn && keys.includes(config.valueColumn)
			? config.valueColumn
			: (keys.find((k) => k !== config.labelColumn && valueCandidate(k)) ?? null);
	const labelColumn =
		config.labelColumn && keys.includes(config.labelColumn)
			? config.labelColumn
			: (keys.find((k) => k !== valueColumn && !valueCandidate(k)) ??
				keys.find((k) => k !== valueColumn) ??
				null);

	return { labelColumn, valueColumn };
}

function toneFor(value: number | null, mode: RankedTone): RankedRow['tone'] {
	if (mode === 'none' || value == null || value === 0) return 'flat';
	return value > 0 ? 'positive' : 'negative';
}

/**
 * Project query records into ranked rows.
 *
 * Sorting happens BEFORE the limit, so "top 6 descending" really is the top 6
 * of the whole result rather than the first 6 rows re-ordered. Rank numbers are
 * assigned after both, so they always read 1..n.
 */
export function projectRankedList(
	records: Array<Record<string, unknown>> | null | undefined,
	config: RankedListConfig = {}
): RankedRow[] {
	const rows = Array.isArray(records) ? records : [];
	if (rows.length === 0) return [];

	const { labelColumn, valueColumn } = pickColumns(rows, config);
	if (!labelColumn) return [];

	const tone = config.tone ?? 'signed';
	const pattern = config.valueFormat?.trim();
	const projected = rows.map((r) => {
		const value = valueColumn ? cellNumber(r[valueColumn]) : null;
		// An author pattern wins when there IS a number to apply it to; otherwise
		// fall back to whatever the server formatted, so a non-numeric cell shows
		// its real text ("n/a") rather than a formatted NaN.
		const serverText = valueColumn ? cellText(r[valueColumn]) : '';
		return {
			label: cellText(r[labelColumn]),
			value,
			formatted: pattern && value !== null ? formatKpi(value, 'custom', pattern) : serverText,
			tone: toneFor(value, tone)
		};
	});

	const sort = config.sort ?? 'none';
	if (sort !== 'none') {
		const dir = sort === 'asc' ? 1 : -1;
		projected.sort((a, b) => {
			// Rows with no value sink to the bottom either way — they can't be ranked.
			if (a.value == null && b.value == null) return 0;
			if (a.value == null) return 1;
			if (b.value == null) return -1;
			return (a.value - b.value) * dir;
		});
	}

	return projected.slice(0, normaliseLimit(config.limit)).map((r, i) => ({ rank: i + 1, ...r }));
}

/** Result shape shared by every custom renderer's options validator. */
export type ValidateResult =
	{ ok: true; value: Record<string, unknown> } | { ok: false; error: string };

const SORTS: RankedSort[] = ['desc', 'asc', 'none'];
const TONES: RankedTone[] = ['signed', 'none'];

/**
 * Validate + normalise a ranked-list config. Fail-closed on the wrong SHAPE
 * (so a corrupt document surfaces as an error rather than an empty card), but
 * tolerant of absent fields — every one of them has a working default.
 */
export function validateRankedListConfig(options: unknown): ValidateResult {
	if (options == null) return { ok: true, value: { ...DEFAULT_CONFIG } };
	if (typeof options !== 'object' || Array.isArray(options)) {
		return { ok: false, error: 'Ranked list config must be an object.' };
	}
	const o = options as Record<string, unknown>;

	for (const k of ['labelColumn', 'valueColumn', 'subtitle', 'valueFormat']) {
		if (o[k] !== undefined && typeof o[k] !== 'string') {
			return { ok: false, error: `"${k}" must be a string.` };
		}
	}
	if (o.sort !== undefined && !SORTS.includes(o.sort as RankedSort)) {
		return { ok: false, error: `"sort" must be one of ${SORTS.join(', ')}.` };
	}
	if (o.tone !== undefined && !TONES.includes(o.tone as RankedTone)) {
		return { ok: false, error: `"tone" must be one of ${TONES.join(', ')}.` };
	}
	if (o.showRank !== undefined && typeof o.showRank !== 'boolean') {
		return { ok: false, error: `"showRank" must be a boolean.` };
	}
	if (o.limit !== undefined && typeof o.limit !== 'number') {
		return { ok: false, error: `"limit" must be a number.` };
	}

	return {
		ok: true,
		value: {
			...DEFAULT_CONFIG,
			...o,
			limit: normaliseLimit(o.limit ?? DEFAULT_LIMIT)
		}
	};
}

/** The config a freshly added ranked-list tile starts from. */
export const DEFAULT_CONFIG: RankedListConfig = {
	limit: DEFAULT_LIMIT,
	sort: 'none',
	tone: 'signed',
	showRank: true
};
