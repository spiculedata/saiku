/*
 * AiQueryResponse → ECharts option adapter for dashboard chart tiles.
 *
 * Since #1076 the actual ECharts option for all 15 chart types is built by the
 * single shared builder in `$lib/charts/build` — the same one the workspace
 * (ChartView.svelte) uses. This module is now just the dashboard *adapter*:
 *   1. projectFromAiQueryResponse() — turn an AiQueryResponse into the shared
 *      {rowCategories, columnCategories, matrix} projection.
 *   2. buildChartOption() — project, then call the shared builder in `compact`
 *      mode (tile-tight cosmetics) with the dashboard's current baseline
 *      ChartOptions, so tiles render exactly as before.
 *
 * Tile-specific chart options (dual-axis, trend lines, title, …) flow in once
 * the chart-tile editor (#1077) is wired; today the baseline reproduces the
 * pre-#1076 dashboard look (single axis, no trend, bottom scroll legend).
 *
 * Pure: no DOM, no fetches. Tests live alongside.
 */

import type { AiCell, AiQueryResponse } from '$lib/api/aiQuery';
import type { ChartOptions } from '$lib/views/chartTypes';
import { DEFAULT_CHART_OPTIONS, isChartType } from '$lib/views/chartTypes';
import { type ThemeTokens, DEFAULT_THEME_TOKENS } from '$lib/views/chartTheme';
import {
	buildChartOption as buildSharedChartOption,
	type ChartProjection
} from '$lib/charts/build';
import { applySortLimit } from '$lib/charts/sortLimit';
import { partitionRollups } from '$lib/dashboard/rollupRows';

/** All chart kinds the workspace exposes. Kept as an alias to the canonical
 *  ChartType so existing imports (`import type { ChartKind }`) still resolve. */
export type { ChartType as ChartKind } from '$lib/views/chartTypes';

/** Re-exported supported-kind guard (single source of truth in chartTypes.ts). */
export const isSupportedChartKind = isChartType;

/** Dashboard baseline chart options — reproduces the pre-#1076 tile look:
 *  single y-axis, no trend line, no overall title, bottom scroll legend. Used
 *  for legacy tiles that have no per-tile {@link ChartOptions}; the chart-tile
 *  editor (#1077) overrides these per tile via the `options` arg below. */
const DASHBOARD_BASELINE: ChartOptions = {
	...DEFAULT_CHART_OPTIONS,
	title: '',
	trendLine: 'none',
	dualAxis: false,
	hideRollupRows: false,
	// Tiles render the legend at the bottom (the compact builder keeps it a
	// scroll legend); baseline must say "bottom" so legacy tiles are unchanged.
	legendPosition: 'bottom'
};

function isAiCell(v: unknown): v is AiCell {
	return typeof v === 'object' && v !== null && 'formatted' in (v as object);
}

/** Normalise an AiQueryResponse into the shared {rowCategories,
 *  columnCategories, matrix} projection — the same shape ChartView's
 *  parseCellset path produces, so both surfaces feed the builder identically.
 *
 *  `hideRollupRows` (saiku#1797) mirrors ChartView's deriveLeafRows step. A
 *  multi-level row axis comes back with the rollup rows interleaved among the
 *  leaves; in `records` format a rollup is the row whose DEEPER header cells are
 *  blank ({"Store State": "BC", "Store Name": ""}). Rollups are sums of their own
 *  children, so on a chart they dwarf every leaf. Off by default so callers that
 *  want the raw shape (the a11y mirror's row count, the geo-coverage notice) opt
 *  in explicitly. */
export function projectFromAiQueryResponse(
	response: AiQueryResponse,
	hideRollupRows = false
): ChartProjection {
	const all = response.data ?? [];
	if (all.length === 0) return { rowCategories: [], columnCategories: [], matrix: [] };

	// Pick row-header columns (plain string keys) vs measure columns (AiCell
	// keys) from the first row's shape — insertion order.
	const firstRow = all[0];
	const headerKeys: string[] = [];
	const measureKeys: string[] = [];
	for (const k of Object.keys(firstRow)) {
		if (isAiCell(firstRow[k])) measureKeys.push(k);
		else headerKeys.push(k);
	}

	// Stand down when dropping rollups would empty the tile — a result that is
	// ALL subtotals is a legitimate shape, not a bug to filter away. Same guard
	// ChartView's `leaf.indices.length > 0 && < matrix.length` applies.
	let rows = all;
	if (hideRollupRows) {
		const { leaves, allRollups } = partitionRollups(all);
		if (!allRollups && leaves.length < all.length) rows = leaves;
	}

	const rowCategories = rows.map((r, i) => {
		const parts = headerKeys.map((k) => String(r[k] ?? '')).filter((s) => s.length > 0);
		return parts.length > 0 ? parts.join(' / ') : `row ${i + 1}`;
	});

	const matrix = rows.map((r) =>
		measureKeys.map((k) => {
			const v = r[k];
			return isAiCell(v) ? v.value : null;
		})
	);

	// #1596: the row-header column keys ARE the row dimension/level names — expose
	// them as the projection's default category-axis title (used by the builder
	// when no explicit xAxisLabel is set). Auto-derived axis titles are drawn in
	// roomy mode; dashboard tiles render compact (tile-tight) so this is the
	// source-of-truth data an explicit per-tile label would otherwise override.
	const categoryAxisName = headerKeys.filter((k) => k.length > 0).join(' / ') || undefined;

	return { rowCategories, columnCategories: measureKeys, matrix, categoryAxisName };
}

/**
 * Build an ECharts option object for a dashboard chart tile from an
 * AiQueryResponse. Returns null when the response has no rows or the kind
 * isn't recognised.
 *
 * `aspect` (canvas w/h) keeps small-multiple radii on-screen-constant; `tk`
 * carries the active theme tokens (chartTheme.ts) so tiles repaint on a
 * light/dark/system flip. `options` are the per-tile {@link ChartOptions} set
 * via the chart-tile editor (#1077); when omitted, the dashboard baseline is
 * used so legacy tiles render exactly as before. All default to keep the pure
 * callers (tests) DOM-free and structurally unchanged.
 */
export function buildChartOption(
	response: AiQueryResponse,
	kind: string,
	aspect = 1,
	tk: ThemeTokens = DEFAULT_THEME_TOKENS,
	options?: ChartOptions
): Record<string, unknown> | null {
	if (!isChartType(kind)) return null;
	const effective = options ?? DASHBOARD_BASELINE;
	const projection = projectForChart(response, effective);
	if (projection.matrix.length === 0) return null;
	return buildSharedChartOption(projection, kind, effective, tk, { aspect, compact: true });
}

/**
 * The projection a chart tile actually DRAWS: rollup rows dropped, then the
 * categories sorted / trimmed per {@link ChartOptions.sortDirection} and
 * {@link ChartOptions.topN} (saiku#1797).
 *
 * Every tile surface that reasons about "which category is at index i" must go
 * through this, not {@link projectFromAiQueryResponse} — the accessible table
 * mirror, the brush cross-filter's dataIndex → caption lookup, and the option
 * builder itself. ECharts hands back indices into the DISPLAYED series, so a
 * caller reading captions out of the unsorted projection cross-filters on the
 * wrong members. That is the same "ONE source of truth" rule ChartView keeps
 * with its leafProjection/sortLimitOrder pair.
 *
 * With no options this is the raw projection, so pre-#1077 tiles (which carry no
 * `chartOptions` at all) render exactly as they did.
 */
export function projectForChart(
	response: AiQueryResponse,
	options?: ChartOptions
): ChartProjection {
	if (!options) return projectFromAiQueryResponse(response);
	return applySortLimit(projectFromAiQueryResponse(response, options.hideRollupRows), {
		direction: options.sortDirection,
		measureIndex: 0,
		topN: options.topN
	});
}
