/*
 * Unit tests for rollup-row detection (saiku#1802).
 */

import { describe, expect, it } from 'vitest';
import { headerKeysOf, isRollupRow, partitionRollups, type RecordRow } from './rollupRows';

const cell = (v: number) => ({ value: v, formatted: String(v) });

/** Two levels of one hierarchy: a subtotal per state, then its stores. */
function stateAndStore(): RecordRow[] {
	return [
		{ 'Store State': 'BC', 'Store Name': '', 'Store Sqft': cell(57564) },
		{ 'Store State': 'BC', 'Store Name': 'Store 19', 'Store Sqft': cell(23112) },
		{ 'Store State': 'BC', 'Store Name': 'Store 20', 'Store Sqft': cell(34452) },
		{ 'Store State': 'DF', 'Store Name': '', 'Store Sqft': cell(36509) },
		{ 'Store State': 'DF', 'Store Name': 'Store 9', 'Store Sqft': cell(36509) }
	];
}

describe('headerKeysOf', () => {
	it('picks the non-measure keys in insertion order', () => {
		expect(headerKeysOf(stateAndStore())).toEqual(['Store State', 'Store Name']);
	});

	it('is empty for an empty result', () => {
		expect(headerKeysOf([])).toEqual([]);
	});
});

describe('isRollupRow', () => {
	const keys = ['Store State', 'Store Name'];

	it('is true when a deeper header cell is blank', () => {
		expect(isRollupRow({ 'Store State': 'BC', 'Store Name': '' }, keys)).toBe(true);
	});

	it('is false when every header cell is filled', () => {
		expect(isRollupRow({ 'Store State': 'BC', 'Store Name': 'Store 19' }, keys)).toBe(false);
	});

	it('is never true on a single-level axis', () => {
		// One header column has no subtotals. A blank caption there is a member
		// genuinely captioned "" — calling it a rollup would drop real data.
		expect(isRollupRow({ 'Store City': '' }, ['Store City'])).toBe(false);
	});

	it('treats a missing key the same as a blank one', () => {
		expect(isRollupRow({ 'Store State': 'BC' }, keys)).toBe(true);
	});
});

describe('partitionRollups', () => {
	it('separates the subtotals from the leaves, order preserved', () => {
		const p = partitionRollups(stateAndStore());
		expect(p.leaves.map((r) => r['Store Name'])).toEqual(['Store 19', 'Store 20', 'Store 9']);
		expect(p.rollups).toHaveLength(2);
		expect(p.allRollups).toBe(false);
	});

	it('flags an all-rollup result so callers can stand down', () => {
		const rows = stateAndStore().filter((r) => r['Store Name'] === '');
		const p = partitionRollups(rows);
		expect(p.allRollups).toBe(true);
		expect(p.leaves).toHaveLength(0);
	});

	it('does not call an empty result all-rollup', () => {
		expect(partitionRollups([]).allRollups).toBe(false);
	});

	it('leaves a single-level result entirely as leaves', () => {
		const rows: RecordRow[] = [
			{ 'Store City': 'Hidalgo', 'Store Sqft': cell(68966) },
			{ 'Store City': 'Salem', 'Store Sqft': cell(27694) }
		];
		const p = partitionRollups(rows);
		expect(p.leaves).toHaveLength(2);
		expect(p.rollups).toHaveLength(0);
	});
});
