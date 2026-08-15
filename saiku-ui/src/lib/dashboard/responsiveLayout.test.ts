/*
 * Unit tests for the mobile auto-stack helpers (issue #932). Covers:
 *   - isNarrow breakpoint boundary + unmeasured-width guard
 *   - isPinnedToTop only matches filter tiles
 *   - compareByPosition orders by (y, x) then id
 *   - stackedOrder puts filters first then (y, x) order, immutably
 *   - stackOrderMap maps ids to their stacked index
 */

import { describe, test, expect } from 'vitest';
import {
	DEFAULT_STACK_BREAKPOINT,
	isNarrow,
	isPinnedToTop,
	compareByPosition,
	stackedOrder,
	stackOrderMap,
	stackedCellHeight,
	STACK_MIN_PX
} from '$lib/dashboard/responsiveLayout';
import type { DashboardTile, TileType } from '$lib/api/dashboards';

function tile(id: string, x: number, y: number, type: TileType = 'chart'): DashboardTile {
	return { id, x, y, w: 6, h: 4, type };
}

describe('isNarrow', () => {
	test('default breakpoint is 768', () => {
		expect(DEFAULT_STACK_BREAKPOINT).toBe(768);
	});

	test('at and below the breakpoint is narrow', () => {
		expect(isNarrow(640, 640)).toBe(true);
		expect(isNarrow(500, 640)).toBe(true);
	});

	test('above the breakpoint is wide', () => {
		expect(isNarrow(641, 640)).toBe(false);
		expect(isNarrow(1200, 640)).toBe(false);
	});

	test('unmeasured / non-positive width is treated as wide', () => {
		expect(isNarrow(0, 640)).toBe(false);
		expect(isNarrow(-10, 640)).toBe(false);
		expect(isNarrow(Number.NaN, 640)).toBe(false);
	});

	test('uses the default breakpoint when none is supplied', () => {
		expect(isNarrow(768)).toBe(true);
		expect(isNarrow(769)).toBe(false);
	});
});

describe('isPinnedToTop', () => {
	test('only filter tiles pin to the top', () => {
		expect(isPinnedToTop(tile('f', 0, 5, 'filter'))).toBe(true);
		expect(isPinnedToTop(tile('c', 0, 0, 'chart'))).toBe(false);
		expect(isPinnedToTop(tile('t', 0, 0, 'table'))).toBe(false);
		expect(isPinnedToTop(tile('k', 0, 0, 'kpi'))).toBe(false);
	});
});

describe('compareByPosition', () => {
	test('sorts by row then column', () => {
		expect(compareByPosition(tile('a', 0, 0), tile('b', 0, 1))).toBeLessThan(0);
		expect(compareByPosition(tile('a', 6, 0), tile('b', 0, 0))).toBeGreaterThan(0);
		expect(compareByPosition(tile('a', 0, 0), tile('b', 6, 0))).toBeLessThan(0);
	});

	test('breaks ties on id for determinism', () => {
		expect(compareByPosition(tile('a', 0, 0), tile('b', 0, 0))).toBeLessThan(0);
		expect(compareByPosition(tile('b', 0, 0), tile('a', 0, 0))).toBeGreaterThan(0);
		expect(compareByPosition(tile('a', 0, 0), tile('a', 0, 0))).toBe(0);
	});
});

describe('stackedOrder', () => {
	test('orders non-filter tiles top-to-bottom then left-to-right', () => {
		const tiles = [
			tile('br', 6, 1), // row 1, right
			tile('tl', 0, 0), // row 0, left
			tile('tr', 6, 0), // row 0, right
			tile('bl', 0, 1) // row 1, left
		];
		expect(stackedOrder(tiles).map((t) => t.id)).toEqual(['tl', 'tr', 'bl', 'br']);
	});

	test('filter tiles stick at the top regardless of saved position', () => {
		const tiles = [
			tile('chart-top', 0, 0, 'chart'),
			tile('filter-bottom', 0, 9, 'filter'),
			tile('chart-mid', 0, 1, 'chart')
		];
		expect(stackedOrder(tiles).map((t) => t.id)).toEqual([
			'filter-bottom',
			'chart-top',
			'chart-mid'
		]);
	});

	test('multiple filters keep their own (y, x) order at the top', () => {
		const tiles = [
			tile('f2', 0, 2, 'filter'),
			tile('c1', 0, 1, 'chart'),
			tile('f1', 0, 0, 'filter')
		];
		expect(stackedOrder(tiles).map((t) => t.id)).toEqual(['f1', 'f2', 'c1']);
	});

	test('does not mutate the input array or its order', () => {
		const tiles = [tile('b', 6, 0), tile('a', 0, 0)];
		const before = tiles.map((t) => t.id);
		stackedOrder(tiles);
		expect(tiles.map((t) => t.id)).toEqual(before);
	});

	test('empty input yields empty output', () => {
		expect(stackedOrder([])).toEqual([]);
	});
});

describe('stackOrderMap', () => {
	test('maps each id to its stacked index', () => {
		const tiles = [tile('chart', 0, 0, 'chart'), tile('filter', 0, 5, 'filter')];
		const map = stackOrderMap(tiles);
		expect(map.get('filter')).toBe(0);
		expect(map.get('chart')).toBe(1);
	});
});

describe('stackedCellHeight', () => {
	test('scales with the saved row span (80px/row + 8px gaps)', () => {
		expect(stackedCellHeight(4)).toBe(4 * 80 + 3 * 8); // 344
		expect(stackedCellHeight(3)).toBe(3 * 80 + 2 * 8); // 256
	});
	test('floors a 1-row tile at STACK_MIN_PX so it stays readable', () => {
		expect(stackedCellHeight(1)).toBe(STACK_MIN_PX); // 80 → floored to 160
		expect(stackedCellHeight(2)).toBe(2 * 80 + 8); // 168, above the floor
	});
	test('guards non-finite / zero spans', () => {
		expect(stackedCellHeight(0)).toBe(STACK_MIN_PX);
		expect(stackedCellHeight(NaN)).toBe(STACK_MIN_PX);
	});
});
