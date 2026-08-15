/*
 * Unit tests for the #1084 chart conditional-format evaluator.
 */

import { describe, test, expect } from 'vitest';
import {
	colorForValue,
	matchRule,
	conditionalFormatForMeasure
} from '$lib/charts/chartConditionalFormat';
import type { ChartConditionalFormat } from '$lib/views/chartTypes';

describe('matchRule', () => {
	test('gt / gte / lt / lte boundaries', () => {
		expect(matchRule(1001, { op: 'gt', value: 1000, color: 'r' })).toBe(true);
		expect(matchRule(1000, { op: 'gt', value: 1000, color: 'r' })).toBe(false);
		expect(matchRule(1000, { op: 'gte', value: 1000, color: 'r' })).toBe(true);
		expect(matchRule(999, { op: 'lt', value: 1000, color: 'r' })).toBe(true);
		expect(matchRule(1000, { op: 'lt', value: 1000, color: 'r' })).toBe(false);
		expect(matchRule(1000, { op: 'lte', value: 1000, color: 'r' })).toBe(true);
	});

	test('between is inclusive and order-insensitive', () => {
		expect(matchRule(5, { op: 'between', value: [1, 10], color: 'g' })).toBe(true);
		expect(matchRule(1, { op: 'between', value: [1, 10], color: 'g' })).toBe(true);
		expect(matchRule(10, { op: 'between', value: [1, 10], color: 'g' })).toBe(true);
		expect(matchRule(11, { op: 'between', value: [1, 10], color: 'g' })).toBe(false);
		expect(matchRule(5, { op: 'between', value: [10, 1], color: 'g' })).toBe(true); // swapped
	});

	test('malformed value shapes never match', () => {
		expect(
			matchRule(5, { op: 'between', value: 5 as unknown as [number, number], color: 'g' })
		).toBe(false);
		expect(matchRule(5, { op: 'gt', value: [1, 2] as unknown as number, color: 'g' })).toBe(false);
		expect(matchRule(NaN, { op: 'gt', value: 1, color: 'g' })).toBe(false);
	});
});

describe('colorForValue', () => {
	const cf: ChartConditionalFormat = {
		measureIndex: 0,
		rules: [
			{ op: 'gt', value: 1000, color: 'green' },
			{ op: 'lt', value: 0, color: 'red' }
		],
		fallbackColor: 'grey'
	};

	test('returns the matched rule colour', () => {
		expect(colorForValue(1500, cf)).toBe('green');
		expect(colorForValue(-5, cf)).toBe('red');
	});

	test('first-matched-wins on overlapping rules', () => {
		const overlap: ChartConditionalFormat = {
			measureIndex: 0,
			rules: [
				{ op: 'gt', value: 100, color: 'first' },
				{ op: 'gt', value: 200, color: 'second' }
			]
		};
		expect(colorForValue(300, overlap)).toBe('first'); // both match; earlier wins
	});

	test('fallback applies to a finite value matching no rule', () => {
		expect(colorForValue(500, cf)).toBe('grey');
	});

	test('no fallback → undefined (keep palette) when nothing matches', () => {
		const noFallback: ChartConditionalFormat = {
			measureIndex: 0,
			rules: [{ op: 'gt', value: 1000, color: 'green' }]
		};
		expect(colorForValue(50, noFallback)).toBeUndefined();
	});

	test('null / non-finite / empty-rules → undefined', () => {
		expect(colorForValue(null, cf)).toBeUndefined();
		expect(colorForValue(undefined, cf)).toBeUndefined();
		expect(colorForValue(NaN, cf)).toBeUndefined();
		expect(
			colorForValue(1500, { measureIndex: 0, rules: [], fallbackColor: 'grey' })
		).toBeUndefined();
		expect(colorForValue(1500, undefined)).toBeUndefined();
	});
});

describe('conditionalFormatForMeasure', () => {
	test('finds the band for a measure index, undefined otherwise', () => {
		const formats: ChartConditionalFormat[] = [
			{ measureIndex: 0, rules: [{ op: 'gt', value: 1, color: 'a' }] },
			{ measureIndex: 2, rules: [{ op: 'lt', value: 1, color: 'b' }] }
		];
		expect(conditionalFormatForMeasure(formats, 2)?.rules[0].color).toBe('b');
		expect(conditionalFormatForMeasure(formats, 1)).toBeUndefined();
		expect(conditionalFormatForMeasure(undefined, 0)).toBeUndefined();
	});
});
