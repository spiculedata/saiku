/*
 * Unit tests for the pure number-format helper (#1082).
 */

import { describe, test, expect } from 'vitest';
import { formatNumber, type NumberFormat } from '$lib/charts/numberFormat';

describe('formatNumber — inert / null handling', () => {
	test('no format renders the raw value exactly as String() did before', () => {
		expect(formatNumber(565238.13)).toBe('565238.13');
		expect(formatNumber(0)).toBe('0');
		expect(formatNumber(-42)).toBe('-42');
	});

	test('an all-empty format is treated as inert (raw value)', () => {
		expect(formatNumber(1234.5, {})).toBe('1234.5');
		expect(
			formatNumber(1234.5, {
				prefix: '',
				suffix: '',
				decimals: null,
				thousands: false,
				abbreviate: false
			})
		).toBe('1234.5');
	});

	test('null / undefined / NaN / Infinity render as the em-dash', () => {
		const f: NumberFormat = { decimals: 2 };
		expect(formatNumber(null, f)).toBe('—');
		expect(formatNumber(undefined, f)).toBe('—');
		expect(formatNumber(NaN, f)).toBe('—');
		expect(formatNumber(Infinity, f)).toBe('—');
		// Even with no format, null/NaN must not stringify to "null"/"NaN".
		expect(formatNumber(null)).toBe('—');
		expect(formatNumber(NaN)).toBe('—');
	});
});

describe('formatNumber — decimals', () => {
	test('decimals pins the fractional digits via toFixed', () => {
		expect(formatNumber(3.14159, { decimals: 2 })).toBe('3.14');
		expect(formatNumber(3, { decimals: 2 })).toBe('3.00');
		expect(formatNumber(3.7, { decimals: 0 })).toBe('4');
	});

	test('decimals null leaves the natural precision', () => {
		expect(formatNumber(3.14159, { decimals: null, prefix: '$' })).toBe('$3.14159');
	});

	test('negative / out-of-range decimals are clamped', () => {
		expect(formatNumber(3.14159, { decimals: -2 })).toBe('3');
	});
});

describe('formatNumber — thousands grouping', () => {
	test('groups the integer part', () => {
		// toLocaleString grouping is locale-aware; assert the digits + a separator.
		const out = formatNumber(1234567, { thousands: true });
		expect(out.replace(/[^0-9]/g, '')).toBe('1234567');
		expect(out).not.toBe('1234567'); // a separator was inserted
	});

	test('thousands + decimals together', () => {
		const out = formatNumber(1234567.5, { thousands: true, decimals: 2 });
		expect(out.replace(/[^0-9]/g, '')).toBe('123456750');
	});
});

describe('formatNumber — abbreviate', () => {
	test('k / M / B / T suffixes on large magnitudes', () => {
		expect(formatNumber(1500, { abbreviate: true })).toBe('1.5k');
		expect(formatNumber(2_500_000, { abbreviate: true })).toBe('2.5M');
		expect(formatNumber(3_200_000_000, { abbreviate: true })).toBe('3.2B');
		expect(formatNumber(1_000_000_000_000, { abbreviate: true })).toBe('1.0T');
	});

	test('negatives abbreviate by magnitude', () => {
		expect(formatNumber(-2_500_000, { abbreviate: true })).toBe('-2.5M');
	});

	test('values below 1k are not abbreviated (rendered with natural precision)', () => {
		// No abbreviation kicks in below 1k, and without an explicit decimals the
		// value keeps its natural precision (no forced ".0").
		expect(formatNumber(999, { abbreviate: true })).toBe('999');
		expect(formatNumber(999.5, { abbreviate: true })).toBe('999.5');
		expect(formatNumber(12, { abbreviate: true, decimals: 0 })).toBe('12');
	});

	test('explicit decimals overrides the abbreviate default of 1', () => {
		// 2.5 → toFixed(0) → "3" (JS rounds half away from zero).
		expect(formatNumber(2_500_000, { abbreviate: true, decimals: 0 })).toBe('3M');
		expect(formatNumber(2_300_000, { abbreviate: true, decimals: 0 })).toBe('2M');
		expect(formatNumber(2_550_000, { abbreviate: true, decimals: 2 })).toBe('2.55M');
	});
});

describe('formatNumber — prefix / suffix', () => {
	test('prefix and suffix wrap the formatted core', () => {
		expect(
			formatNumber(1234.5, { prefix: '£', decimals: 2, thousands: true }).startsWith('£')
		).toBe(true);
		expect(formatNumber(42, { suffix: '%' })).toBe('42%');
		expect(formatNumber(0.5, { prefix: '$', suffix: ' USD', decimals: 2 })).toBe('$0.50 USD');
	});

	test('prefix + suffix combine with abbreviate', () => {
		expect(formatNumber(2_500_000, { prefix: '$', abbreviate: true })).toBe('$2.5M');
	});
});
