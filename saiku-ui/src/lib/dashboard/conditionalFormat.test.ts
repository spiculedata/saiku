/*
 * Unit tests for the pure conditional-formatting engine (issue #919).
 *
 * Covers: numeric coercion, percentile maths, relative + absolute band
 * classification, bar-width clamping, font/icon by sign and by threshold,
 * empty / NaN / null cell handling, the no-rules pass-through, and the
 * style-string serialiser.
 */

import { describe, expect, test } from 'vitest';

import type { ConditionalFormatRule } from '$lib/api/dashboards';
import {
	barWidthPct,
	bandColor,
	cellFormatToStyle,
	classifyByCuts,
	DEFAULT_BAND_COLORS,
	evaluateCell,
	formatCell,
	numericValues,
	percentile,
	resolveCuts,
	ruleForColumn,
	toNumber
} from './conditionalFormat';

/** Compact rule constructor. */
function rule(over: Partial<ConditionalFormatRule>): ConditionalFormatRule {
	return {
		column: 'Sales',
		type: 'background',
		thresholdMode: 'absolute',
		...over
	};
}

describe('toNumber', () => {
	test('passes finite numbers through', () => {
		expect(toNumber(42)).toBe(42);
		expect(toNumber(-3.5)).toBe(-3.5);
		expect(toNumber(0)).toBe(0);
	});
	test('parses numeric strings, stripping commas/spaces', () => {
		expect(toNumber('1,234.5')).toBe(1234.5);
		expect(toNumber(' 99 ')).toBe(99);
		expect(toNumber('-7')).toBe(-7);
	});
	test('returns null for null/undefined/NaN/empty/non-numeric', () => {
		expect(toNumber(null)).toBeNull();
		expect(toNumber(undefined)).toBeNull();
		expect(toNumber(NaN)).toBeNull();
		expect(toNumber(Infinity)).toBeNull();
		expect(toNumber('')).toBeNull();
		expect(toNumber('   ')).toBeNull();
		expect(toNumber('abc')).toBeNull();
		expect(toNumber({})).toBeNull();
	});
});

describe('numericValues', () => {
	test('filters to the finite numeric subset, preserving order', () => {
		expect(numericValues([3, 'x', '2', null, 1, NaN])).toEqual([3, 2, 1]);
	});
	test('returns empty for an all-non-numeric column', () => {
		expect(numericValues([null, 'x', undefined])).toEqual([]);
	});
});

describe('percentile', () => {
	test('null on empty input', () => {
		expect(percentile([], 50)).toBeNull();
	});
	test('single element returns that element for any p', () => {
		expect(percentile([7], 0)).toBe(7);
		expect(percentile([7], 100)).toBe(7);
	});
	test('p=0 is min, p=100 is max', () => {
		expect(percentile([10, 20, 30, 40], 0)).toBe(10);
		expect(percentile([10, 20, 30, 40], 100)).toBe(40);
	});
	test('p=50 is the median (linear interpolation)', () => {
		expect(percentile([1, 2, 3, 4], 50)).toBeCloseTo(2.5, 6);
		expect(percentile([1, 2, 3], 50)).toBe(2);
	});
	test('interpolates between ranks', () => {
		// 4 elements, p=25 → rank = 0.75 → 10 + 0.75*(20-10) = 17.5
		expect(percentile([10, 20, 30, 40], 25)).toBeCloseTo(17.5, 6);
	});
	test('sorts unsorted input first', () => {
		expect(percentile([40, 10, 30, 20], 0)).toBe(10);
	});
	test('clamps out-of-range p', () => {
		expect(percentile([1, 2, 3], -10)).toBe(1);
		expect(percentile([1, 2, 3], 999)).toBe(3);
	});
});

describe('classifyByCuts', () => {
	test('low / mid / high boundaries (upper-exclusive on lower band)', () => {
		expect(classifyByCuts(5, 10, 20)).toBe('low');
		expect(classifyByCuts(10, 10, 20)).toBe('mid'); // inclusive lower edge of mid
		expect(classifyByCuts(15, 10, 20)).toBe('mid');
		expect(classifyByCuts(20, 10, 20)).toBe('high'); // inclusive lower edge of high
		expect(classifyByCuts(25, 10, 20)).toBe('high');
	});
	test('normalises inverted cuts', () => {
		expect(classifyByCuts(5, 20, 10)).toBe('low');
		expect(classifyByCuts(25, 20, 10)).toBe('high');
	});
	test('none for null value or null cut', () => {
		expect(classifyByCuts(null, 10, 20)).toBe('none');
		expect(classifyByCuts(5, null, 20)).toBe('none');
		expect(classifyByCuts(5, 10, null)).toBe('none');
	});
});

describe('resolveCuts', () => {
	test('absolute mode returns thresholds verbatim', () => {
		const r = rule({ thresholdMode: 'absolute', lowThreshold: 100, highThreshold: 500 });
		expect(resolveCuts(r, [1, 2, 3])).toEqual({ lowCut: 100, highCut: 500 });
	});
	test('relative mode maps thresholds through column percentiles', () => {
		const r = rule({ thresholdMode: 'relative', lowThreshold: 0, highThreshold: 100 });
		expect(resolveCuts(r, [10, 20, 30, 40])).toEqual({ lowCut: 10, highCut: 40 });
	});
	test('null cuts when thresholds are missing', () => {
		expect(resolveCuts(rule({ lowThreshold: undefined }), [1, 2])).toEqual({
			lowCut: null,
			highCut: null
		});
	});
});

describe('bandColor', () => {
	test('defaults from the palette', () => {
		const r = rule({});
		expect(bandColor('low', r)).toBe(DEFAULT_BAND_COLORS.low);
		expect(bandColor('high', r)).toBe(DEFAULT_BAND_COLORS.high);
	});
	test('per-rule overrides win', () => {
		const r = rule({ colors: { low: '#000', high: '#fff' } });
		expect(bandColor('low', r)).toBe('#000');
		expect(bandColor('high', r)).toBe('#fff');
		expect(bandColor('mid', r)).toBe(DEFAULT_BAND_COLORS.mid); // not overridden
	});
	test('none → undefined', () => {
		expect(bandColor('none', rule({}))).toBeUndefined();
	});
});

describe('barWidthPct', () => {
	test('scales between column min (0%) and max (100%)', () => {
		expect(barWidthPct(10, [10, 20, 30])).toBe(0);
		expect(barWidthPct(20, [10, 20, 30])).toBe(50);
		expect(barWidthPct(30, [10, 20, 30])).toBe(100);
	});
	test('all-equal column → full bar for any finite value', () => {
		expect(barWidthPct(5, [5, 5, 5])).toBe(100);
	});
	test('clamps values outside the column range to 0–100', () => {
		expect(barWidthPct(0, [10, 20, 30])).toBe(0);
		expect(barWidthPct(40, [10, 20, 30])).toBe(100);
	});
	test('null for non-finite value or empty column', () => {
		expect(barWidthPct(null, [1, 2, 3])).toBeNull();
		expect(barWidthPct(10, [])).toBeNull();
	});
	test('handles negative ranges', () => {
		expect(barWidthPct(-50, [-100, 0])).toBe(50);
	});
});

describe('evaluateCell — bar', () => {
	test('emits clamped barWidthPct + a bar colour', () => {
		const fmt = evaluateCell(rule({ type: 'bar' }), 20, [10, 20, 30]);
		expect(fmt.barWidthPct).toBe(50);
		expect(fmt.barColor).toBeTruthy();
	});
	test('honours a custom bar colour', () => {
		const fmt = evaluateCell(rule({ type: 'bar', barColor: '#abc' }), 30, [10, 30]);
		expect(fmt.barColor).toBe('#abc');
	});
	test('empty object for a non-numeric cell', () => {
		expect(evaluateCell(rule({ type: 'bar' }), 'n/a', [10, 20])).toEqual({});
		expect(evaluateCell(rule({ type: 'bar' }), null, [10, 20])).toEqual({});
	});
});

describe('evaluateCell — background', () => {
	const r = rule({
		type: 'background',
		thresholdMode: 'absolute',
		lowThreshold: 100,
		highThreshold: 500
	});
	test('bands by absolute thresholds', () => {
		expect(evaluateCell(r, 50, []).backgroundColor).toBe(DEFAULT_BAND_COLORS.low);
		expect(evaluateCell(r, 300, []).backgroundColor).toBe(DEFAULT_BAND_COLORS.mid);
		expect(evaluateCell(r, 900, []).backgroundColor).toBe(DEFAULT_BAND_COLORS.high);
	});
	test('bands by relative (percentile) thresholds against the column', () => {
		const rel = rule({
			type: 'background',
			thresholdMode: 'relative',
			lowThreshold: 33,
			highThreshold: 66
		});
		const col = [0, 50, 100];
		// cuts: p33 ≈ 33, p66 ≈ 66 → 10 low, 60 mid, 100 high
		expect(evaluateCell(rel, 10, col).backgroundColor).toBe(DEFAULT_BAND_COLORS.low);
		expect(evaluateCell(rel, 60, col).backgroundColor).toBe(DEFAULT_BAND_COLORS.mid);
		expect(evaluateCell(rel, 100, col).backgroundColor).toBe(DEFAULT_BAND_COLORS.high);
	});
	test('empty object when thresholds are unset', () => {
		expect(evaluateCell(rule({ type: 'background' }), 50, [])).toEqual({});
	});
	test('empty object for a null cell', () => {
		expect(evaluateCell(r, null, [])).toEqual({});
	});
});

describe('evaluateCell — font', () => {
	test('sign mode (no thresholds): positive green, negative red, zero none', () => {
		const r = rule({ type: 'font' });
		expect(evaluateCell(r, 5, []).color).toBe(DEFAULT_BAND_COLORS.high);
		expect(evaluateCell(r, -5, []).color).toBe(DEFAULT_BAND_COLORS.low);
		expect(evaluateCell(r, 0, [])).toEqual({});
	});
	test('threshold mode colours by band', () => {
		const r = rule({
			type: 'font',
			thresholdMode: 'absolute',
			lowThreshold: 10,
			highThreshold: 20
		});
		expect(evaluateCell(r, 5, []).color).toBe(DEFAULT_BAND_COLORS.low);
		expect(evaluateCell(r, 25, []).color).toBe(DEFAULT_BAND_COLORS.high);
	});
	test('empty for a non-numeric cell', () => {
		expect(evaluateCell(rule({ type: 'font' }), 'x', [])).toEqual({});
	});
});

describe('evaluateCell — icon', () => {
	test('sign mode glyphs', () => {
		const r = rule({ type: 'icon' });
		expect(evaluateCell(r, 5, []).icon).toBe('↑');
		expect(evaluateCell(r, -5, []).icon).toBe('↓');
		expect(evaluateCell(r, 0, []).icon).toBe('→');
	});
	test('threshold mode glyphs', () => {
		const r = rule({
			type: 'icon',
			thresholdMode: 'absolute',
			lowThreshold: 10,
			highThreshold: 20
		});
		expect(evaluateCell(r, 5, []).icon).toBe('↓');
		expect(evaluateCell(r, 15, []).icon).toBe('→');
		expect(evaluateCell(r, 25, []).icon).toBe('↑');
	});
	test('empty for a non-numeric cell', () => {
		expect(evaluateCell(rule({ type: 'icon' }), null, [])).toEqual({});
	});
});

describe('ruleForColumn / formatCell — no-rules pass-through', () => {
	test('ruleForColumn returns undefined for empty/undefined rules', () => {
		expect(ruleForColumn(undefined, 'Sales')).toBeUndefined();
		expect(ruleForColumn([], 'Sales')).toBeUndefined();
	});
	test('ruleForColumn matches by column caption', () => {
		const rules = [rule({ column: 'Sales' }), rule({ column: 'Cost', type: 'bar' })];
		expect(ruleForColumn(rules, 'Cost')?.type).toBe('bar');
		expect(ruleForColumn(rules, 'Profit')).toBeUndefined();
	});
	test('formatCell returns {} when no rule targets the column', () => {
		expect(formatCell(undefined, 'Sales', 5, [1, 2, 3])).toEqual({});
		expect(formatCell([rule({ column: 'Other' })], 'Sales', 5, [1, 2, 3])).toEqual({});
	});
	test('formatCell delegates to evaluateCell for a matching rule', () => {
		const rules = [rule({ column: 'Sales', type: 'bar' })];
		expect(formatCell(rules, 'Sales', 20, [10, 20, 30]).barWidthPct).toBe(50);
	});
});

describe('cellFormatToStyle', () => {
	test('undefined for an empty format', () => {
		expect(cellFormatToStyle({})).toBeUndefined();
	});
	test('serialises colour + background', () => {
		const s = cellFormatToStyle({ color: '#111', backgroundColor: '#eee' });
		expect(s).toContain('color: #111');
		expect(s).toContain('background-color: #eee');
	});
	test('serialises a bar as a clamped linear-gradient', () => {
		const s = cellFormatToStyle({ barWidthPct: 50, barColor: '#abc' })!;
		expect(s).toContain('linear-gradient');
		expect(s).toContain('#abc 50%');
	});
	test('clamps an out-of-range bar width', () => {
		const s = cellFormatToStyle({ barWidthPct: 150 })!;
		expect(s).toContain('100%');
		expect(s).not.toContain('150%');
	});
	/* saiku#1775 — a table on a cyan/amber App theme grew blue data bars because
     the default fill was a bare hex. It now resolves against the App shell's
     accent token, with the historical blue kept as the fallback so dashboards
     outside an App (where the token is undefined) look exactly as before. */
	test('#1775 defaults the data bar to the app accent token with a blue fallback', () => {
		const s = cellFormatToStyle({ barWidthPct: 50 })!;
		expect(s).toContain('var(--saiku-app-accent, #4c8dff)');
	});

	test('#1775 an explicit barColor still wins over the token', () => {
		const s = cellFormatToStyle({ barWidthPct: 50, barColor: '#ff0000' })!;
		expect(s).toContain('#ff0000');
		expect(s).not.toContain('--saiku-app-accent');
	});
});
