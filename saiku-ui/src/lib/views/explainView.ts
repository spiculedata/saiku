/*
 * Formatting helpers for the "explain this number" panel (saiku#1118).
 *
 * Kept out of the component so the numbers the panel prints can be unit-tested without
 * mounting Svelte — a driver whose share renders as "NaN%" is exactly the kind of thing
 * that only shows up in a browser otherwise.
 */

import type { ExplainDriver, ExplainDriverKind, ExplainResponse } from '$lib/api/explain';

/** i18n key per driver kind, resolved by the panel. */
export const DRIVER_LABEL_KEYS: Record<ExplainDriverKind, string> = {
	SHARE_OF_COLUMN: 'cellset.explain.driver.shareOfColumn',
	SHARE_OF_ROW: 'cellset.explain.driver.shareOfRow',
	RANK_IN_COLUMN: 'cellset.explain.driver.rankInColumn',
	PREVIOUS_COLUMN: 'cellset.explain.driver.previousColumn',
	ROW_PEAK: 'cellset.explain.driver.rowPeak'
};

/** "50.0%" — a share arrives as a fraction, not as a percentage. */
export function formatShare(share: number | undefined): string {
	if (share === undefined || !Number.isFinite(share)) return '—';
	return `${(share * 100).toFixed(1)}%`;
}

/** "+25.0%" / "-8.3%", with an explicit sign because the direction is the point. */
export function formatDeltaPct(deltaPct: number | undefined): string {
	if (deltaPct === undefined || !Number.isFinite(deltaPct)) return '—';
	const pct = deltaPct * 100;
	return `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`;
}

/** Grouped, two-decimal rendering of a delta in the measure's own units. */
export function formatDelta(delta: number | undefined): string {
	if (delta === undefined || !Number.isFinite(delta)) return '—';
	return delta.toLocaleString(undefined, { maximumFractionDigits: 2, minimumFractionDigits: 2 });
}

/**
 * The one-line value a driver contributes to the panel: a share, a rank, a change, or a
 * comparison point. Empty when the driver carries nothing renderable, so the panel can skip
 * the row rather than print "—".
 */
export function formatDriverValue(driver: ExplainDriver): string {
	switch (driver.kind) {
		case 'SHARE_OF_COLUMN':
		case 'SHARE_OF_ROW':
			return formatShare(driver.share);
		case 'RANK_IN_COLUMN':
			return driver.detail;
		case 'PREVIOUS_COLUMN':
			return `${formatDelta(driver.delta)} (${formatDeltaPct(driver.deltaPct)})`;
		case 'ROW_PEAK':
			return formatDelta(driver.value);
		default:
			return '';
	}
}

/**
 * The heading of the panel: what number is being explained. Falls back through measure → row →
 * column so a cell on an axis-less result still reads sensibly.
 */
export function explainTitle(result: ExplainResponse): string {
	const parts = [result.measure, result.rowPath, result.columnPath].filter(
		(part): part is string => !!part && part.trim().length > 0
	);
	if (parts.length === 0) return '';
	return parts.join(' · ');
}
