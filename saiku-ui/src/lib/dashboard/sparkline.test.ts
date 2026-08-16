/*
 * Unit tests for the pure sparkline geometry helpers (issue #920).
 *
 * Covers: numeric coercion / extraction, point normalisation (even x
 * spacing, inverted y with max at top), flat-series centring, polyline /
 * path serialisation, bar geometry, and the renderable / degenerate
 * handling (fewer than two finite points).
 */

import { describe, expect, test } from 'vitest';

import {
	numericValues,
	pointsToPath,
	pointsToPolyline,
	sparklineBars,
	sparklineGeometry,
	sparklinePoints,
	toNumber
} from './sparkline';

describe('toNumber', () => {
	test('passes finite numbers through', () => {
		expect(toNumber(42)).toBe(42);
		expect(toNumber(-3.5)).toBe(-3.5);
		expect(toNumber(0)).toBe(0);
	});
	test('rejects non-finite numbers', () => {
		expect(toNumber(NaN)).toBeNull();
		expect(toNumber(Infinity)).toBeNull();
	});
	test('parses numeric strings, stripping spaces and commas', () => {
		expect(toNumber('1,234')).toBe(1234);
		expect(toNumber(' 12 ')).toBe(12);
		expect(toNumber('3.14')).toBeCloseTo(3.14);
	});
	test('rejects null, undefined, and non-numeric strings', () => {
		expect(toNumber(null)).toBeNull();
		expect(toNumber(undefined)).toBeNull();
		expect(toNumber('')).toBeNull();
		expect(toNumber('abc')).toBeNull();
	});
});

describe('numericValues', () => {
	test('keeps only finite numbers, preserving order', () => {
		expect(numericValues([1, 'x', '2', null, 3, NaN])).toEqual([1, 2, 3]);
	});
	test('empty input → empty output', () => {
		expect(numericValues([])).toEqual([]);
	});
});

describe('sparklinePoints', () => {
	test('returns empty for fewer than two values', () => {
		expect(sparklinePoints([])).toEqual([]);
		expect(sparklinePoints([5])).toEqual([]);
	});

	test('spaces x evenly across the padded width', () => {
		const pts = sparklinePoints([0, 1, 2], { width: 100, height: 24, pad: 2 });
		expect(pts).toHaveLength(3);
		// inner width = 100 - 2*2 = 96; step = 48.
		expect(pts[0].x).toBe(2);
		expect(pts[1].x).toBe(50);
		expect(pts[2].x).toBe(98);
	});

	test('inverts y so the max sits at the top (smallest y)', () => {
		const pts = sparklinePoints([0, 10], { width: 100, height: 24, pad: 2 });
		// min (0) → bottom = height - pad = 22; max (10) → top = pad = 2.
		expect(pts[0].y).toBe(22);
		expect(pts[1].y).toBe(2);
	});

	test('flat series maps all points to the vertical centre', () => {
		const pts = sparklinePoints([7, 7, 7], { width: 100, height: 24, pad: 2 });
		// centre = pad + 0.5 * innerH = 2 + 0.5*20 = 12.
		for (const p of pts) expect(p.y).toBe(12);
	});

	test('handles negative values via min/max range', () => {
		const pts = sparklinePoints([-10, 0, 10], { width: 100, height: 20, pad: 0 });
		// innerH = 20; -10 → bottom (20), 0 → mid (10), 10 → top (0).
		expect(pts[0].y).toBe(20);
		expect(pts[1].y).toBe(10);
		expect(pts[2].y).toBe(0);
	});
});

describe('pointsToPolyline / pointsToPath', () => {
	const pts = [
		{ x: 2, y: 22 },
		{ x: 50, y: 12 },
		{ x: 98, y: 2 }
	];
	test("polyline joins as 'x,y x,y …'", () => {
		expect(pointsToPolyline(pts)).toBe('2,22 50,12 98,2');
	});
	test('path moves to first then lines to the rest', () => {
		expect(pointsToPath(pts)).toBe('M 2 22 L 50 12 L 98 2');
	});
	test('empty points → empty strings', () => {
		expect(pointsToPolyline([])).toBe('');
		expect(pointsToPath([])).toBe('');
	});
});

describe('sparklineBars', () => {
	test('empty input → empty output', () => {
		expect(sparklineBars([])).toEqual([]);
	});

	test('evenly slots bars across the width with positive heights', () => {
		const bars = sparklineBars([1, 2, 4], { width: 90, height: 20, pad: 0 });
		expect(bars).toHaveLength(3);
		// slot = 30; barW = 21; gap = 4.5.
		expect(bars[0].x).toBe(4.5);
		expect(bars[1].x).toBe(34.5);
		expect(bars[2].x).toBe(64.5);
		// base = 0, top = 4, range = 4; innerH = 20.
		expect(bars[0].height).toBe(5); // 1/4 * 20
		expect(bars[2].height).toBe(20); // 4/4 * 20
		// bars grow upward: tallest has smallest y.
		expect(bars[2].y).toBe(0);
	});

	test('all-equal non-zero series → full-height bars', () => {
		const bars = sparklineBars([5, 5], { width: 100, height: 24, pad: 2 });
		// range = top(5) - base(0) = 5; magnitudes all equal but range != 0
		// so heights are proportional: |5-0|/5 * innerH = innerH.
		const innerH = 24 - 2 * 2;
		for (const b of bars) expect(b.height).toBe(innerH);
	});
});

describe('sparklineGeometry', () => {
	test('not renderable with fewer than two finite values', () => {
		const g = sparklineGeometry(['x', null]);
		expect(g.renderable).toBe(false);
		expect(g.polyline).toBe('');
		expect(g.path).toBe('');
		expect(g.first).toBeNull();
		expect(g.last).toBeNull();
		expect(g.min).toBeNull();
	});

	test('drops non-numeric cells and renders from the rest', () => {
		const g = sparklineGeometry([1, 'bad', '3', null, 5], { width: 100, height: 24, pad: 2 });
		expect(g.renderable).toBe(true);
		expect(g.points).toHaveLength(3); // 1, 3, 5
		expect(g.min).toBe(1);
		expect(g.max).toBe(5);
		expect(g.first).toEqual(g.points[0]);
		expect(g.last).toEqual(g.points[g.points.length - 1]);
		expect(g.polyline).toBe(pointsToPolyline(g.points));
		expect(g.path).toBe(pointsToPath(g.points));
	});

	test('uses default dimensions when no options given', () => {
		const g = sparklineGeometry([1, 2, 3]);
		expect(g.width).toBe(100);
		expect(g.height).toBe(24);
	});
});
