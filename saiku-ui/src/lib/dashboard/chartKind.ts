/*
 * Chart-kind resolution for dashboard tiles — issue #1481.
 *
 * Every chart type the workspace can draw (the whole of `CHART_TYPES` in
 * `$lib/views/chartTypes`) renders as a dashboard tile: since #1076 the tile
 * path builds its ECharts option with the SAME shared builder the workspace
 * uses, so there is no per-type support matrix to keep in step and nothing left
 * to "implement" for a type. What used to end in a "Chart type X not yet
 * supported in dashboards" overlay was simply a tile whose stored `chartType`
 * string wasn't in the palette — a hand-edited dashboard JSON, a legacy
 * spelling, or an id an older build wrote.
 *
 * So the remaining gap is RESOLUTION, not rendering. `resolveChartKind` turns
 * whatever a tile carries into a type the builder definitely draws:
 *
 *   1. absent / blank            → the default (`bar`) — matches the backend's
 *                                  `coerceChartType` and the AI assembler's
 *                                  `DEFAULT_CHART_TYPE`, so all three
 *                                  entry points degrade identically.
 *   2. an exact palette id       → itself (`'donut'`, `'stackedBar'`, …).
 *   3. a legacy / decorated id   → the nearest supported equivalent. Matching
 *                                  ignores case, spaces, dashes and underscores
 *                                  (`'Stacked Bar'`, `'stacked_bar'`,
 *                                  `'stacked-bar'` all land on `stackedBar`),
 *                                  and a trailing `Chart` is dropped, which is
 *                                  what classic Saiku spelled them (`BarChart`,
 *                                  `'column'` → `bar`).
 *   4. anything else             → the default again. A tile must always draw
 *                                  something: a silent, correct-looking bar
 *                                  beats a dead canvas, and the tile editor
 *                                  still offers the full palette to pick the
 *                                  right type.
 *
 * Pure and DOM-free; the tests live alongside. Callers resolve ONCE per tile
 * and use the result everywhere (feature gating, small multiples, the a11y
 * mirror, the option builder) so a tile can't gate on one spelling and render
 * another.
 */

import { CHART_TYPES, isChartType, type ChartType } from '$lib/views/chartTypes';

/** Kind a tile falls back to when its stored `chartType` is absent or
 *  unrecognised. Matches `aiDashboardAssembler`'s DEFAULT_CHART_TYPE and the
 *  backend's `coerceChartType` fallback, so an AI-built, hand-edited and
 *  corrupt tile all degrade to the same chart. */
export const DEFAULT_CHART_KIND: ChartType = 'bar';

/** Lowercase, punctuation-free form used as the lookup key — `'Stacked Bar'`,
 *  `'stacked_bar'` and `'stacked-bar'` all collapse to `'stackedbar'`. */
function normalizeKey(raw: string): string {
	return raw.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** Palette ids keyed by their normalised form, so the resolution table can
 *  never drift from `CHART_TYPES` (a new type is reachable the moment it is
 *  added there). Built once — the palette is a module-level constant. */
const KIND_BY_KEY: ReadonlyMap<string, ChartType> = new Map(
	CHART_TYPES.map((c) => [normalizeKey(c.id), c.id])
);

/** Explicit legacy spellings → the nearest supported equivalent. Kept
 *  deliberately short: only ids that existed in an earlier Saiku (or that a
 *  hand-edited dashboard is likely to carry) get a named target, so a typo'd
 *  or genuinely-unknown id still falls through to the default rather than
 *  being silently upgraded to something surprising. The generic normalisation
 *  in step 3 already covers case/punctuation and the `…Chart` suffix, so these
 *  are the spellings normalisation alone cannot reach. */
const LEGACY_KINDS: ReadonlyMap<string, ChartType> = new Map([
	// Classic Saiku's vertical bar chart was called "column"; it is the same
	// series shape the UI calls `bar`.
	['column', 'bar'],
	['barcolumn', 'bar'],
	// Single-measure part-to-whole under its older names.
	['circle', 'pie'],
	['ring', 'donut'],
	// Hierarchical part-to-whole: both fall back to the tile-able treemap.
	['multilevelpie', 'treemap'],
	['sunburstchart', 'sunburst']
]);

/**
 * Resolve a tile's stored `chartType` to a chart kind the tile renderer and
 * the shared builder both draw. Never returns a value outside `CHART_TYPES`.
 *
 * @param raw the tile's `chartType` — may be undefined, null, blank, a legacy
 *   spelling, or something entirely unknown (all normalise to a drawable kind).
 */
export function resolveChartKind(raw: string | null | undefined): ChartType {
	if (raw == null) return DEFAULT_CHART_KIND;
	const key = normalizeKey(raw);
	if (key.length === 0) return DEFAULT_CHART_KIND;
	if (isChartType(key)) return key;
	const exact = KIND_BY_KEY.get(key);
	if (exact) return exact;
	const legacy = LEGACY_KINDS.get(key);
	if (legacy) return legacy;
	// Classic Saiku spelled every type `<Name>Chart`; drop the suffix and retry.
	if (key.endsWith('chart')) {
		const trimmed = KIND_BY_KEY.get(key.slice(0, -'chart'.length));
		if (trimmed) return trimmed;
	}
	return DEFAULT_CHART_KIND;
}
