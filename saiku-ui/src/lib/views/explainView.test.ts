/*
 * Unit tests for explainView.ts — the numbers the "explain this number" panel prints.
 * No network, no Svelte.
 */

import { describe, expect, test } from 'vitest';
import type { ExplainDriver, ExplainResponse } from '$lib/api/explain';
import {
	DRIVER_LABEL_KEYS,
	explainTitle,
	formatDelta,
	formatDeltaPct,
	formatDriverValue,
	formatShare
} from './explainView';

const base: ExplainDriver = {
	kind: 'SHARE_OF_COLUMN',
	caption: 'Canada',
	detail: 'share of the column total'
};

describe('formatShare', () => {
	test('renders a fraction as a percentage', () => {
		expect(formatShare(0.5)).toBe('50.0%');
		expect(formatShare(0.1254)).toBe('12.5%');
	});

	test('renders a missing or non-finite share as an em dash', () => {
		expect(formatShare(undefined)).toBe('—');
		expect(formatShare(Number.NaN)).toBe('—');
	});
});

describe('formatDeltaPct', () => {
	test('keeps the sign, because the direction is the finding', () => {
		expect(formatDeltaPct(0.25)).toBe('+25.0%');
		expect(formatDeltaPct(-0.0833)).toBe('-8.3%');
		expect(formatDeltaPct(0)).toBe('+0.0%');
	});

	test('renders a missing change as an em dash rather than NaN', () => {
		expect(formatDeltaPct(undefined)).toBe('—');
	});
});

describe('formatDelta', () => {
	test('groups thousands and keeps two decimals', () => {
		expect(formatDelta(-1234.5)).toMatch(/-1,234\.50$/);
		expect(formatDelta(0)).toMatch(/0\.00$/);
	});

	test('renders a missing delta as an em dash', () => {
		expect(formatDelta(undefined)).toBe('—');
	});
});

describe('formatDriverValue', () => {
	test('share drivers print the share', () => {
		expect(formatDriverValue({ ...base, share: 0.5 })).toBe('50.0%');
	});

	test('rank drivers print the server-rendered rank text', () => {
		expect(
			formatDriverValue({
				kind: 'RANK_IN_COLUMN',
				caption: 'Canada',
				detail: '2 of 3 rows with a value in this column'
			})
		).toBe('2 of 3 rows with a value in this column');
	});

	test('period drivers print the change and the percentage', () => {
		expect(
			formatDriverValue({
				kind: 'PREVIOUS_COLUMN',
				caption: 'Q1',
				detail: 'change against the previous column of the same row',
				delta: 20,
				deltaPct: 0.25
			})
		).toBe('20.00 (+25.0%)');
	});

	test('row-peak drivers print the peak value', () => {
		expect(
			formatDriverValue({ kind: 'ROW_PEAK', caption: 'Q2', detail: 'largest', value: 400 })
		).toBe('400.00');
	});

	test('an unknown kind renders nothing rather than a broken row', () => {
		expect(formatDriverValue({ kind: 'SOMETHING_NEW' as never, caption: 'x', detail: '' })).toBe(
			''
		);
	});
});

describe('explainTitle', () => {
	const result = (over: Partial<ExplainResponse>): ExplainResponse =>
		({ value: 1, drivers: [], ...over }) as ExplainResponse;

	test('joins measure, row and column', () => {
		expect(explainTitle(result({ measure: 'Store Sales', rowPath: 'USA', columnPath: 'Q1' }))).toBe(
			'Store Sales · USA · Q1'
		);
	});

	test('skips the parts a result does not have', () => {
		expect(explainTitle(result({ measure: 'Store Sales' }))).toBe('Store Sales');
		expect(explainTitle(result({ rowPath: 'USA', columnPath: 'Q1' }))).toBe('USA · Q1');
	});

	test('treats blank strings as absent', () => {
		expect(explainTitle(result({ measure: '  ', rowPath: '', columnPath: undefined }))).toBe('');
	});
});

describe('DRIVER_LABEL_KEYS', () => {
	test('covers every driver kind the server can send', () => {
		expect(Object.keys(DRIVER_LABEL_KEYS).sort()).toEqual([
			'PREVIOUS_COLUMN',
			'RANK_IN_COLUMN',
			'ROW_PEAK',
			'SHARE_OF_COLUMN',
			'SHARE_OF_ROW'
		]);
	});
});
