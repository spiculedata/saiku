/*
 * Shared axis-label truncation helpers for chart category axes.
 *
 * Long member captions (e.g. "Quarter 3 Fiscal Year 2026 - Europe Region -
 * Premium Tier") overflow the chart area and break the layout. Both the
 * workspace chart (ChartView.svelte) and the dashboard chart tiles
 * (chartOptions.ts) feed their category axis through axisLabelConfig() so the
 * truncation behaviour is identical and unit-tested in one place.
 *
 * ECharts handles the actual rendering truncation via `overflow: 'truncate'`
 * + a pixel `width`; it shows the full untruncated label in the axis-pointer
 * tooltip on hover by default. truncateAxisLabel() is a pure character-based
 * fallback used by tests (and available for any non-ECharts consumer) that
 * mirrors the intent: cut to `max` characters and append an ellipsis.
 *
 * Pure: no DOM, no fetches.
 */

/** Single-character ellipsis (U+2026) appended to truncated labels. */
export const ELLIPSIS = '…';

/** Default per-label width cap (px) when no width can be derived. */
export const DEFAULT_AXIS_LABEL_WIDTH = 120;

/** Lower / upper bounds for a width derived from chart geometry. */
export const MIN_AXIS_LABEL_WIDTH = 40;
export const MAX_AXIS_LABEL_WIDTH = 160;

/**
 * Truncate `label` to at most `max` characters, appending an ellipsis when
 * the label is longer. Pure string helper — `max` is a character count, not
 * pixels. Returns the original label unchanged when it already fits or when
 * `max` is non-positive (treated as "no truncation").
 */
export function truncateAxisLabel(label: string, max: number): string {
	if (max <= 0) return label;
	if (label.length <= max) return label;
	if (max <= ELLIPSIS.length) return ELLIPSIS;
	return label.slice(0, max - ELLIPSIS.length) + ELLIPSIS;
}

/**
 * Derive a sensible per-label pixel width from the available axis width and
 * the number of categories, clamped to [MIN, MAX]. More categories → less
 * room per label. Falls back to DEFAULT_AXIS_LABEL_WIDTH when geometry isn't
 * known (chartWidth <= 0 or categoryCount <= 0).
 */
export function deriveAxisLabelWidth(chartWidth: number, categoryCount: number): number {
	if (chartWidth <= 0 || categoryCount <= 0) return DEFAULT_AXIS_LABEL_WIDTH;
	const per = Math.floor(chartWidth / categoryCount);
	return Math.max(MIN_AXIS_LABEL_WIDTH, Math.min(MAX_AXIS_LABEL_WIDTH, per));
}

/** ECharts axisLabel fragment that truncates long labels with an ellipsis. */
export interface AxisLabelTruncation {
	overflow: 'truncate';
	width: number;
	hideOverlap: true;
	ellipsis: string;
}

/**
 * Build the ECharts `axisLabel` truncation fragment for a category axis.
 * Spread this into an existing axisLabel object (e.g. `{ color, ...config }`).
 * `width` defaults to DEFAULT_AXIS_LABEL_WIDTH and is clamped to a sane range.
 */
export function axisLabelConfig(width: number = DEFAULT_AXIS_LABEL_WIDTH): AxisLabelTruncation {
	const w = Number.isFinite(width) && width > 0 ? Math.round(width) : DEFAULT_AXIS_LABEL_WIDTH;
	return {
		overflow: 'truncate',
		width: Math.max(MIN_AXIS_LABEL_WIDTH, Math.min(MAX_AXIS_LABEL_WIDTH, w)),
		hideOverlap: true,
		ellipsis: ELLIPSIS
	};
}
