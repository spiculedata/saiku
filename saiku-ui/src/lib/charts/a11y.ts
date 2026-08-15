/*
 * Screen-reader accessibility for charts (#1090).
 *
 * An ECharts chart renders to a <canvas> that is invisible to assistive tech.
 * Both surfaces (workspace ChartView, dashboard ChartTile) therefore hide the
 * canvas from screen readers (aria-hidden) and expose the SAME data as a
 * visually-hidden HTML <table> — a navigable, robust representation a screen
 * reader can read row-by-row. This module builds that table model + a concise
 * caption from the shared {@link ChartProjection}, so the logic is pure and
 * unit-tested in one place.
 *
 * Pure: no DOM, no ECharts. Tests live alongside.
 */

import { CHART_TYPES } from '$lib/views/chartTypes';
import type { ChartProjection } from '$lib/charts/build';

const TYPE_LABEL = new Map<string, string>(CHART_TYPES.map((c) => [c.id, c.label]));

/** Human chart-type label (e.g. "bar" → "Bar"), falling back to "Chart". */
export function chartTypeLabel(type: string): string {
	return TYPE_LABEL.get(type) ?? 'Chart';
}

/** Accessible data-table model + caption for one chart. */
export interface ChartSummary {
	/** One-line summary used as the table caption / accessible name. */
	caption: string;
	/** Column headers — first cell is the (blank) category corner, then the
	 *  series (column-category) names. */
	headers: string[];
	/** One row per category: [categoryLabel, ...formatted values]. */
	rows: string[][];
	/** True when there's nothing to summarise (no categories or no series). */
	empty: boolean;
}

/** Default value formatter — locale number, em-dash for missing. */
function defaultFormat(v: number | null): string {
	if (v == null || !Number.isFinite(v)) return '—';
	return v.toLocaleString();
}

/**
 * Build the accessible summary (caption + data table) for a chart.
 *
 * @param type    chart type id (bar, line, …)
 * @param title   optional user title (empty string when none)
 * @param p       the projected {rowCategories, columnCategories, matrix}
 * @param format  value formatter (defaults to locale number)
 */
export function chartSummary(
	type: string,
	title: string,
	p: ChartProjection,
	format: (v: number | null) => string = defaultFormat
): ChartSummary {
	const tl = chartTypeLabel(type);
	const cats = p.rowCategories;
	const series = p.columnCategories;
	const empty = cats.length === 0 || series.length === 0;
	const titlePart = title ? `“${title}”: ` : '';
	const caption = empty
		? `${titlePart}${tl} chart with no data.`
		: `${titlePart}${tl} chart of ${series.join(', ')} across ${cats.length} ` +
			`${cats.length === 1 ? 'category' : 'categories'}.`;
	const headers = ['', ...series];
	const rows = cats.map((cat, i) => [
		cat,
		...series.map((_, c) => format(p.matrix[i]?.[c] ?? null))
	]);
	return { caption, headers, rows, empty };
}

/** Concise accessible name for the chart region (the summary caption). */
export function chartAriaLabel(type: string, title: string, p: ChartProjection): string {
	return chartSummary(type, title, p).caption;
}
