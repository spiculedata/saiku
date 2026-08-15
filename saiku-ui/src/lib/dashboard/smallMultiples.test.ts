/*
 * Unit tests for the small-multiples grid geometry helper.
 * Pure (vitest env=node) — no DOM, no ECharts.
 */

import { describe, test, expect } from 'vitest';
import {
	cellRadiusPct,
	gridCells,
	isSingleMeasureKind,
	smallMultipleRowCount,
	type GridCell
} from '$lib/dashboard/smallMultiples';

/** True when two cell rects overlap (touching at gutters is fine). */
function overlaps(a: GridCell, b: GridCell): boolean {
	const aRight = a.leftPct + a.widthPct;
	const aBottom = a.topPct + a.heightPct;
	const bRight = b.leftPct + b.widthPct;
	const bBottom = b.topPct + b.heightPct;
	return a.leftPct < bRight && b.leftPct < aRight && a.topPct < bBottom && b.topPct < aBottom;
}

describe('isSingleMeasureKind', () => {
	test('true for pie/donut/treemap/sunburst', () => {
		for (const k of ['pie', 'donut', 'treemap', 'sunburst']) {
			expect(isSingleMeasureKind(k)).toBe(true);
		}
	});
	test('false for multi-measure / unknown kinds', () => {
		for (const k of ['bar', 'line', 'heatmap', 'radar', 'scatter', 'waterfall', '', 'made-up']) {
			expect(isSingleMeasureKind(k)).toBe(false);
		}
	});
});

describe('gridCells', () => {
	test('n=0 → empty', () => {
		expect(gridCells(0)).toEqual([]);
		expect(gridCells(-3)).toEqual([]);
	});

	test('n=1 → one full-box cell centered ~50/50', () => {
		const cells = gridCells(1);
		expect(cells).toHaveLength(1);
		const c = cells[0];
		expect(c.centerXPct).toBeCloseTo(50, 0);
		// Center is biased slightly below 50 to leave title headroom at the top.
		expect(c.centerYPct).toBeGreaterThan(45);
		expect(c.centerYPct).toBeLessThan(62);
	});

	test('n=2 → two non-overlapping cells (1×2 or 2×1)', () => {
		const cells = gridCells(2);
		expect(cells).toHaveLength(2);
		expect(overlaps(cells[0], cells[1])).toBe(false);
		// Up to 3/row; 2 charts fill a single 2-wide row.
		expect(cells[0].row).toBe(0);
		expect(cells[1].row).toBe(0);
		expect(cells[0].col).toBe(0);
		expect(cells[1].col).toBe(1);
	});

	test('n=3 → single row of 3 (3 per row)', () => {
		const cells = gridCells(3);
		expect(cells).toHaveLength(3);
		expect(cells.map((c) => [c.row, c.col])).toEqual([
			[0, 0],
			[0, 1],
			[0, 2]
		]);
		for (let i = 0; i < cells.length; i++) {
			for (let j = i + 1; j < cells.length; j++) {
				expect(overlaps(cells[i], cells[j])).toBe(false);
			}
		}
	});

	test('n=4 → 3 in the first row, 1 in the second', () => {
		const cells = gridCells(4);
		expect(cells).toHaveLength(4);
		expect(cells.map((c) => [c.row, c.col])).toEqual([
			[0, 0],
			[0, 1],
			[0, 2],
			[1, 0]
		]);
		for (let i = 0; i < cells.length; i++) {
			for (let j = i + 1; j < cells.length; j++) {
				expect(overlaps(cells[i], cells[j])).toBe(false);
			}
		}
	});

	test('caps at 3 columns: n=6 → 3 cols × 2 rows', () => {
		const cells = gridCells(6);
		expect(cells).toHaveLength(6);
		expect(Math.max(...cells.map((c) => c.col))).toBe(2);
		expect(Math.max(...cells.map((c) => c.row))).toBe(1);
		expect(cells.map((c) => [c.row, c.col])).toEqual([
			[0, 0],
			[0, 1],
			[0, 2],
			[1, 0],
			[1, 1],
			[1, 2]
		]);
	});

	test('smallMultipleRowCount matches the grid: ≤3 → 1 row, then ceil(n/3)', () => {
		expect(smallMultipleRowCount(1)).toBe(1);
		expect(smallMultipleRowCount(2)).toBe(1);
		expect(smallMultipleRowCount(3)).toBe(1);
		expect(smallMultipleRowCount(4)).toBe(2);
		expect(smallMultipleRowCount(6)).toBe(2);
		expect(smallMultipleRowCount(7)).toBe(3);
		// Must agree with the actual grid.
		for (const n of [1, 2, 3, 4, 6, 7, 9]) {
			const rowsInGrid = Math.max(...gridCells(n).map((c) => c.row)) + 1;
			expect(smallMultipleRowCount(n)).toBe(rowsInGrid);
		}
	});

	test('all cells stay inside the 0–100% box', () => {
		for (const n of [1, 2, 3, 4, 5, 9]) {
			for (const c of gridCells(n)) {
				expect(c.leftPct).toBeGreaterThanOrEqual(0);
				expect(c.topPct).toBeGreaterThanOrEqual(0);
				expect(c.leftPct + c.widthPct).toBeLessThanOrEqual(100);
				expect(c.topPct + c.heightPct).toBeLessThanOrEqual(100);
			}
		}
	});
});

describe('cellRadiusPct', () => {
	test('positive and bounded (< 50%) for typical cells/aspects', () => {
		for (const n of [1, 2, 4]) {
			for (const cell of gridCells(n)) {
				for (const aspect of [0.5, 1, 2, 3]) {
					const r = cellRadiusPct(cell, aspect);
					expect(r).toBeGreaterThan(0);
					expect(r).toBeLessThan(50);
				}
			}
		}
	});

	test('non-positive / NaN aspect is treated as 1 (no NaN out)', () => {
		const cell = gridCells(1)[0];
		expect(cellRadiusPct(cell, 0)).toBeCloseTo(cellRadiusPct(cell, 1), 6);
		expect(cellRadiusPct(cell, -5)).toBeCloseTo(cellRadiusPct(cell, 1), 6);
		expect(Number.isFinite(cellRadiusPct(cell, NaN))).toBe(true);
	});

	test('keeps on-screen size consistent: one row vs two rows (px)', () => {
		// The % differs by design (relative to min(canvasW,canvasH)); the PIXEL
		// radius should match. Model a wide 3:1 tile (tileW=900, tileH=300):
		//   3-up → 1 row, canvas 900×300 (aspect 3, min=300)
		//   6-up → 2 rows, canvas 900×600 (aspect 1.5, min=600)
		const tileW = 900;
		const tileH = 300;
		const r3 = (cellRadiusPct(gridCells(3)[0], tileW / tileH) / 100) * tileH;
		const r6 = (cellRadiusPct(gridCells(6)[0], tileW / (2 * tileH)) / 100) * (2 * tileH);
		expect(Math.abs(r3 - r6)).toBeLessThan(tileH * 0.15);
	});
});
