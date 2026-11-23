/*
 * Unit tests for the per-axis number-format rules (saiku#1779).
 *
 * The rule under test: key PRESENCE, not activeness, is the intent. An absent
 * side key inherits the chart-level format; a present side key owns its text; a
 * present-but-inert key means RAW values on that side. The chart builder resolves
 * through this module, so these are the guarantees the dual-axis bug fix rests on.
 */

import { describe, test, expect } from 'vitest';
import {
	AXIS_SIDES,
	axisFormatOrEmpty,
	isOwnAxisFormat,
	patchAxisFormat,
	resolveAxisFormats,
	setAxisFormatMode
} from '$lib/charts/axisNumberFormat';

const CHART_FMT = { suffix: 'u' };

describe('resolveAxisFormats', () => {
	test('a legacy chart (no format at all) resolves to nothing anywhere', () => {
		expect(resolveAxisFormats({})).toEqual({ chart: undefined, left: undefined, right: undefined });
	});

	test('an all-empty chart format is inert — no axis gets a formatter', () => {
		const r = resolveAxisFormats({ numberFormat: {} });
		expect(r.chart).toBeUndefined();
		expect(r.left).toBeUndefined();
		expect(r.right).toBeUndefined();
	});

	test('the chart-level format reaches both sides when neither overrides', () => {
		const r = resolveAxisFormats({ numberFormat: CHART_FMT });
		expect(r.chart).toEqual(CHART_FMT);
		expect(r.left).toEqual(CHART_FMT);
		expect(r.right).toEqual(CHART_FMT);
	});

	test('an override on one side leaves the other inheriting', () => {
		const r = resolveAxisFormats({
			numberFormat: CHART_FMT,
			axisNumberFormat: { right: { percent: true, decimals: 0 } }
		});
		expect(r.left).toEqual(CHART_FMT);
		expect(r.right).toEqual({ percent: true, decimals: 0 });
	});

	test('an override works with NO chart-level format', () => {
		const r = resolveAxisFormats({ axisNumberFormat: { left: { prefix: '£' } } });
		expect(r.chart).toBeUndefined();
		expect(r.left).toEqual({ prefix: '£' });
		expect(r.right).toBeUndefined();
	});

	test('a present-but-INERT side resolves to undefined: raw values on that axis alone', () => {
		const r = resolveAxisFormats({ numberFormat: CHART_FMT, axisNumberFormat: { right: {} } });
		expect(r.right).toBeUndefined();
		expect(r.left).toEqual(CHART_FMT);
	});

	test('only the two known sides are ever consulted', () => {
		expect(AXIS_SIDES).toEqual(['left', 'right']);
		const r = resolveAxisFormats({
			numberFormat: CHART_FMT,
			axisNumberFormat: { left: { suffix: 'L' }, right: { suffix: 'R' } }
		});
		expect(r.left).toEqual({ suffix: 'L' });
		expect(r.right).toEqual({ suffix: 'R' });
	});
});

describe('isOwnAxisFormat / axisFormatOrEmpty', () => {
	test('an absent key inherits; a present key is the side’s own', () => {
		expect(isOwnAxisFormat({}, 'right')).toBe(false);
		expect(isOwnAxisFormat({ axisNumberFormat: {} }, 'right')).toBe(false);
		expect(isOwnAxisFormat({ axisNumberFormat: { right: undefined } }, 'right')).toBe(false);
		expect(isOwnAxisFormat({ axisNumberFormat: { right: {} } }, 'right')).toBe(true);
	});

	test('an empty override reads as an empty format (form fields can bind to it)', () => {
		expect(axisFormatOrEmpty({}, 'left')).toEqual({});
		expect(axisFormatOrEmpty({ axisNumberFormat: { left: { suffix: '%' } } }, 'left')).toEqual({
			suffix: '%'
		});
	});
});

describe('setAxisFormatMode', () => {
	test('taking ownership writes an empty format for that side only', () => {
		const next = setAxisFormatMode({ numberFormat: CHART_FMT }, 'right', true);
		expect(next).toEqual({ right: {} });
		expect(next?.left).toBeUndefined();
	});

	test('releasing a side drops its key', () => {
		const next = setAxisFormatMode(
			{ axisNumberFormat: { right: { suffix: '%' } } },
			'right',
			false
		);
		expect(next).toBeUndefined(); // nothing left → no object persisted
	});

	test('releasing one side keeps the other', () => {
		const next = setAxisFormatMode(
			{ axisNumberFormat: { left: { suffix: 'L' }, right: { suffix: 'R' } } },
			'right',
			false
		);
		expect(next).toEqual({ left: { suffix: 'L' } });
	});

	test('a round trip returns to the chart default exactly', () => {
		const base = { numberFormat: CHART_FMT };
		const owned = setAxisFormatMode(base, 'right', true)!;
		expect(resolveAxisFormats({ ...base, axisNumberFormat: owned }).right).toBeUndefined();
		const released = setAxisFormatMode({ ...base, axisNumberFormat: owned }, 'right', false);
		expect(resolveAxisFormats({ ...base, axisNumberFormat: released }).right).toEqual(CHART_FMT);
	});
});

describe('patchAxisFormat', () => {
	test('creates the side on first edit and leaves the other side alone', () => {
		const next = patchAxisFormat({ axisNumberFormat: { left: { suffix: 'L' } } }, 'right', {
			percent: true
		});
		expect(next).toEqual({ left: { suffix: 'L' }, right: { percent: true } });
	});

	test('merges into an existing side rather than replacing it', () => {
		const next = patchAxisFormat(
			{ axisNumberFormat: { right: { percent: true, decimals: 1 } } },
			'right',
			{ decimals: 0 }
		);
		expect(next.right).toEqual({ percent: true, decimals: 0 });
	});
});
