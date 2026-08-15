import { describe, it, expect } from 'vitest';
import { chartDrillTarget } from './chartDrillCoord';
import { parseCellset, deriveLeafRows } from '$lib/views/cellsetUtils';
import type { CellEntry, QueryResult } from '$lib/api/query';

// Helpers to assemble a cellset the same way the server does, so we exercise
// the real parseCellset → chartDrillTarget mapping rather than a hand-rolled
// ParsedCellset.
const ch = (value: string): CellEntry => ({ value, type: 'COLUMN_HEADER' });
const rhh = (value: string): CellEntry => ({ value, type: 'ROW_HEADER_HEADER' });
const rh = (value: string, uniquename?: string): CellEntry => ({
	value,
	type: 'ROW_HEADER',
	...(uniquename ? { properties: { uniquename } } : {})
});
const dc = (value: string): CellEntry => ({ value, type: 'DATA_CELL' });

// Single-level rows × two measure columns:
//   header:  [ "", Sales, Cost ]
//   1997  :  [ 1997, 100, 40 ]
//   1998  :  [ 1998, 200, 80 ]
function simpleResult(): QueryResult {
	const cellset: CellEntry[][] = [
		[rhh(''), ch('Unit Sales'), ch('Store Cost')],
		[rh('1997'), dc('100'), dc('40')],
		[rh('1998'), dc('200'), dc('80')]
	];
	return { cellset };
}

// Multi-level hierarchy on rows (Year > Quarter) so deriveLeafRows reindexes:
//   1997 (rollup), 1997/Q1, 1997/Q2 — leaves are the two quarter rows.
function hierarchyResult(): QueryResult {
	const cellset: CellEntry[][] = [
		[rhh(''), ch('Unit Sales')],
		[rh('1997', '[Time].[Time].[1997]'), dc('300')],
		[rh('Q1', '[Time].[Time].[1997].[Q1]'), dc('120')],
		[rh('Q2', '[Time].[Time].[1997].[Q2]'), dc('180')]
	];
	return { cellset };
}

describe('chartDrillTarget', () => {
	it('maps a bar click (category + series) to absolute cellset coords', () => {
		const parsed = parseCellset(simpleResult());
		// headerRowCount = 1, rowHeaderColCount = 1.
		// Click second category (1998), first series (Unit Sales).
		expect(chartDrillTarget(parsed, 1, 0)).toEqual({ row: 2, col: 1 });
		// First category (1997), second series (Store Cost).
		expect(chartDrillTarget(parsed, 0, 1)).toEqual({ row: 1, col: 2 });
	});

	it('treats index 0 on both axes as valid (first bar, first series)', () => {
		const parsed = parseCellset(simpleResult());
		expect(chartDrillTarget(parsed, 0, 0)).toEqual({ row: 1, col: 1 });
	});

	it('returns null for negative / non-integer / non-finite indices', () => {
		const parsed = parseCellset(simpleResult());
		expect(chartDrillTarget(parsed, -1, 0)).toBeNull();
		expect(chartDrillTarget(parsed, 0, -1)).toBeNull();
		expect(chartDrillTarget(parsed, 1.5, 0)).toBeNull();
		expect(chartDrillTarget(parsed, NaN, 0)).toBeNull();
		expect(chartDrillTarget(parsed, 0, Infinity)).toBeNull();
	});

	it('returns null when the category index is out of range', () => {
		const parsed = parseCellset(simpleResult());
		expect(chartDrillTarget(parsed, 2, 0)).toBeNull(); // only 2 body rows
	});

	it('returns null when the series index addresses a non-existent column', () => {
		const parsed = parseCellset(simpleResult());
		expect(chartDrillTarget(parsed, 0, 2)).toBeNull(); // only 2 data cols
	});

	it('returns null on an empty cellset (background click safety)', () => {
		const parsed = parseCellset({ cellset: [] });
		expect(chartDrillTarget(parsed, 0, 0)).toBeNull();
	});

	it('remaps the category through leafIndices when rollup rows are hidden', () => {
		const parsed = parseCellset(hierarchyResult());
		const leaf = deriveLeafRows(parsed);
		// Leaves are the two quarter rows → original body indices [1, 2].
		expect(leaf.indices).toEqual([1, 2]);
		// Chart category 0 == Q1 == body row 1 == absolute cellset row 2.
		expect(chartDrillTarget(parsed, 0, 0, leaf.indices)).toEqual({ row: 2, col: 1 });
		// Chart category 1 == Q2 == body row 2 == absolute cellset row 3.
		expect(chartDrillTarget(parsed, 1, 0, leaf.indices)).toEqual({ row: 3, col: 1 });
	});

	it('returns null when the chart category exceeds the leaf set', () => {
		const parsed = parseCellset(hierarchyResult());
		const leaf = deriveLeafRows(parsed);
		expect(chartDrillTarget(parsed, 2, 0, leaf.indices)).toBeNull(); // only 2 leaves
	});

	it('ignores leafIndices when rollups are shown (chart index == body index)', () => {
		const parsed = parseCellset(hierarchyResult());
		// No leafIndices → chart category 1 is the rollup's first child row.
		expect(chartDrillTarget(parsed, 1, 0)).toEqual({ row: 2, col: 1 });
	});
});
