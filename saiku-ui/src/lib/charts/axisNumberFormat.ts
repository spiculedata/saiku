/*
 * Per-AXIS number-format resolution for dual-axis charts (saiku#1779).
 *
 * A dual-axis chart carries two measures — by definition different magnitudes
 * and usually different units — so the single chart-level `numberFormat` is
 * always wrong for one of them. Reported case: `Units Ordered` as bars on the
 * left, `Sell Through %` as a line forced right. The fraction (1.284) came back
 * with `formatString="0.0%"` and the right axis read `0 … 2`, so a "%" suffix in
 * the one shared Number format would have stamped itself over the left axis's
 * unit counts too.
 *
 * The rule is deliberately about KEY PRESENCE, not about whether the format is
 * "active":
 *
 *   - side key absent            → inherit the chart-level format
 *   - side key present, active   → that side owns its text
 *   - side key present, inert    → that side renders RAW values
 *
 * so a chart can format one axis and not the other. Both surfaces of that rule
 * live here — the builder (resolution) and the editor modal (editing) — so the
 * modal can never offer a combination the chart can't honour.
 *
 * Pure: no DOM, no stores. Legacy charts (no `axisNumberFormat`) resolve exactly
 * as they did before, and an inert side keeps the plain axisLabel it had.
 */

import type { AxisNumberFormatOptions, NumberFormatOptions } from '$lib/views/chartTypes';
import { isFormatActive, type NumberFormat } from '$lib/charts/numberFormat';

/** The two value axes of a cartesian chart. Keyed, not positional. */
export const AXIS_SIDES = ['left', 'right'] as const;
export type AxisSide = (typeof AXIS_SIDES)[number];

/** The slice of ChartOptions this module reads. */
export interface AxisFormatSource {
	/** The chart-level format (issue #1082) every side falls back to. */
	numberFormat?: NumberFormatOptions;
	/** Per-side overrides (saiku#1779). */
	axisNumberFormat?: AxisNumberFormatOptions;
}

/** The three formats the builder actually renders with — each `undefined` when
 *  that surface must keep its prior (raw / unformatted) rendering. */
export interface ResolvedAxisFormats {
	/** The chart-level format, active or not. Used by every surface that has no
	 *  side of its own (heatmap visualMap text, single-axis labels, an unknown
	 *  series name in a tooltip). */
	chart?: NumberFormat;
	/** Effective left-axis format. */
	left?: NumberFormat;
	/** Effective right-axis format. */
	right?: NumberFormat;
}

/** Resolve the effective format for each axis. `undefined` means "render as
 *  before" — never "render with an inert format", so the builder can leave the
 *  axisLabel object byte-for-byte untouched. */
export function resolveAxisFormats(o: AxisFormatSource): ResolvedAxisFormats {
	const chart = isFormatActive(o.numberFormat) ? o.numberFormat : undefined;
	return {
		chart,
		left: resolveSide(o, 'left', chart),
		right: resolveSide(o, 'right', chart)
	};
}

function resolveSide(
	o: AxisFormatSource,
	side: AxisSide,
	chart: NumberFormat | undefined
): NumberFormat | undefined {
	const own = o.axisNumberFormat?.[side];
	// Absent key → inherit. Present key → this side decides, and an inert format
	// means raw values here (which resolve to undefined, i.e. "no formatter").
	if (own === undefined) return chart;
	return isFormatActive(own) ? own : undefined;
}

/** Does this side carry its own format (key present, whatever it holds)? Drives
 *  the editor's "Use chart default" / "Custom" picker. */
export function isOwnAxisFormat(o: AxisFormatSource, side: AxisSide): boolean {
	return o.axisNumberFormat?.[side] !== undefined;
}

/** The side's own format, or `{}` when it inherits (so form fields can bind to
 *  it unconditionally without writing anything). */
export function axisFormatOrEmpty(o: AxisFormatSource, side: AxisSide): NumberFormatOptions {
	return o.axisNumberFormat?.[side] ?? {};
}

/**
 * Toggle a side between inheriting the chart format (`own: false` — drops the
 * key) and owning one (`own: true` — writes an empty format to fill in).
 *
 * Returns the next `axisNumberFormat`, or `undefined` when the last side was
 * released, so a chart that never overrides anything persists no empty object.
 */
export function setAxisFormatMode(
	o: AxisFormatSource,
	side: AxisSide,
	own: boolean
): AxisNumberFormatOptions | undefined {
	const next: AxisNumberFormatOptions = { ...o.axisNumberFormat };
	if (own) next[side] = {};
	else delete next[side];
	return Object.keys(next).length > 0 ? next : undefined;
}

/** Merge a patch into one side's own format. Creates the side if the author
 *  typed into an inheriting axis's fields. */
export function patchAxisFormat(
	o: AxisFormatSource,
	side: AxisSide,
	patch: Partial<NumberFormatOptions>
): AxisNumberFormatOptions {
	return { ...o.axisNumberFormat, [side]: { ...axisFormatOrEmpty(o, side), ...patch } };
}
