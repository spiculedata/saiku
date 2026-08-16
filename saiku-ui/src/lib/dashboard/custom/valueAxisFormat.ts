/*
 * Declarative value-axis number formatting for the `echarts-option` tile.
 *
 * ECharts formats an axis through `axisLabel.formatter`, which for numbers has
 * to be a FUNCTION — and the option validator rejects functions anywhere in the
 * tree (they're an exec/XSS vector; see echartsOption.ts). The consequence was
 * that a hand-authored chart simply could not format its value axis at all:
 * FoodMart Ops' trend card was stuck showing a bare "60,000" where the design
 * called for "$149k".
 *
 * So the author states the format DECLARATIVELY — the same pattern vocabulary
 * the KPI tile already uses ("$c1", "€0", "2%", "0") — and the renderer compiles
 * it to a formatter at render time. The author never supplies code, the
 * validator keeps rejecting functions, and the axis gets formatted.
 *
 * Pure and `$lib`-free (relative import only) so the embed bundle can use it.
 */

import { formatKpi } from '../kpi';

/** Compile a KPI-style pattern into an ECharts axis-label formatter.
 *  Non-finite values render blank rather than "NaN". */
export function axisFormatterFor(pattern: string): (value: number) => string {
	const p = pattern.trim();
	return (value: number) => {
		if (typeof value !== 'number' || !Number.isFinite(value)) return '';
		return formatKpi(value, 'custom', p);
	};
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
	return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Every axis entry declared under `key`, normalised to a list we can mutate. */
function axisEntries(option: Record<string, unknown>, key: string): Record<string, unknown>[] {
	const a = option[key];
	if (Array.isArray(a)) return a.filter(isPlainObject);
	return isPlainObject(a) ? [a] : [];
}

/**
 * Attach the compiled formatter to every VALUE axis in the option.
 *
 * Only `type: "value"` axes are touched — formatting a category axis would
 * mangle its labels, and a category axis's own `formatter` string template
 * (e.g. `"W{value}"`) is the author's business. Mutates in place; the caller
 * owns a fresh clone from applyDataToEchartsOption.
 *
 * A blank pattern is a no-op, so clearing the field in the editor restores
 * ECharts' default labels.
 */
export function applyValueAxisFormat(
	option: Record<string, unknown>,
	pattern: string | undefined
): void {
	if (!pattern || !pattern.trim()) return;
	const formatter = axisFormatterFor(pattern);
	for (const key of ['yAxis', 'xAxis']) {
		for (const axis of axisEntries(option, key)) {
			// Default-typed axes are categories in ECharts, so require the explicit
			// "value" type rather than guessing.
			if (axis.type !== 'value') continue;
			const label = isPlainObject(axis.axisLabel) ? { ...axis.axisLabel } : {};
			// An author who wrote their own string template keeps it.
			if (label.formatter === undefined) label.formatter = formatter;
			axis.axisLabel = label;
		}
	}
}
