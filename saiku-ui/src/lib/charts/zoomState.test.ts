import { describe, it, expect } from 'vitest';
import {
	captureZoomState,
	identityOf,
	shouldReapply,
	reconcileZoomState
} from '$lib/charts/zoomState';

/* Shapes mimic what echarts' getOption() returns: components are normalised to
 * arrays, dataZoom carries start/end percentages, xAxis[0].data holds the
 * category labels. */
function optionWithZoom(categories: string[], start = 0, end = 100) {
	return {
		xAxis: [{ data: categories }],
		series: [{ type: 'bar', data: categories.map((_, i) => i) }],
		dataZoom: [{ type: 'inside', start, end }]
	};
}

describe('captureZoomState', () => {
	it('returns the dataZoom slice when present', () => {
		const s = captureZoomState(optionWithZoom(['a', 'b', 'c'], 20, 80));
		expect(s).not.toBeNull();
		expect(s?.dataZoom).toEqual([{ type: 'inside', start: 20, end: 80 }]);
	});

	it('returns the brush slice when present', () => {
		const s = captureZoomState({ brush: { brushType: 'rect' }, series: [] });
		expect(s?.brush).toEqual({ brushType: 'rect' });
	});

	it('returns null when there is nothing to preserve', () => {
		expect(captureZoomState(null)).toBeNull();
		expect(captureZoomState(undefined)).toBeNull();
		expect(captureZoomState({ series: [{ type: 'pie', data: [] }] })).toBeNull();
		expect(captureZoomState({ dataZoom: [] })).toBeNull();
	});

	it('normalises a bare (non-array) dataZoom object into an array', () => {
		const s = captureZoomState({ dataZoom: { type: 'inside', start: 10, end: 50 } });
		expect(Array.isArray(s?.dataZoom)).toBe(true);
		expect(s?.dataZoom).toEqual([{ type: 'inside', start: 10, end: 50 }]);
	});
});

describe('identityOf', () => {
	it('counts categories from xAxis[0].data', () => {
		expect(identityOf(optionWithZoom(['a', 'b', 'c']), 'bar')).toEqual({
			type: 'bar',
			categoryCount: 3
		});
	});

	it("falls back to the first series' data length when no xAxis", () => {
		expect(identityOf({ series: [{ type: 'pie', data: [1, 2, 3, 4] }] }, 'pie')).toEqual({
			type: 'pie',
			categoryCount: 4
		});
	});

	it('reports zero when nothing is countable', () => {
		expect(identityOf({}, 'bar').categoryCount).toBe(0);
		expect(identityOf(null, 'bar').categoryCount).toBe(0);
	});
});

describe('shouldReapply', () => {
	it('re-applies when type and category count are unchanged (resize / theme flip)', () => {
		expect(
			shouldReapply({ type: 'line', categoryCount: 12 }, { type: 'line', categoryCount: 12 })
		).toBe(true);
	});

	it('does NOT re-apply on a chart-type switch', () => {
		expect(
			shouldReapply({ type: 'bar', categoryCount: 12 }, { type: 'line', categoryCount: 12 })
		).toBe(false);
	});

	it('does NOT re-apply when the category count changes (new dataset)', () => {
		expect(
			shouldReapply({ type: 'bar', categoryCount: 12 }, { type: 'bar', categoryCount: 30 })
		).toBe(false);
	});
});

describe('reconcileZoomState', () => {
	const prev = optionWithZoom(['a', 'b', 'c', 'd'], 25, 75);

	it('returns the preserved slices when the chart identity is unchanged', () => {
		const next = optionWithZoom(['a', 'b', 'c', 'd'], 0, 100); // freshly built, full range
		const out = reconcileZoomState(prev, next, 'bar');
		expect(out?.dataZoom).toEqual([{ type: 'inside', start: 25, end: 75 }]);
	});

	it('returns null when the category count differs (new data → fresh zoom)', () => {
		const next = optionWithZoom(['a', 'b', 'c', 'd', 'e', 'f'], 0, 100);
		expect(reconcileZoomState(prev, next, 'bar')).toBeNull();
	});

	it('returns null when there was no prior zoom/brush to preserve', () => {
		const noZoom = { xAxis: [{ data: ['a', 'b'] }], series: [{ type: 'bar', data: [1, 2] }] };
		const next = { xAxis: [{ data: ['a', 'b'] }], series: [{ type: 'bar', data: [3, 4] }] };
		expect(reconcileZoomState(noZoom, next, 'bar')).toBeNull();
	});

	it('preserves a brush selection alongside the zoom window', () => {
		const withBrush = {
			...optionWithZoom(['a', 'b', 'c', 'd'], 10, 90),
			brush: { brushType: 'rect', areas: [{ coordRange: [1, 2] }] }
		};
		const next = optionWithZoom(['a', 'b', 'c', 'd'], 0, 100);
		const out = reconcileZoomState(withBrush, next, 'bar');
		expect(out?.dataZoom).toEqual([{ type: 'inside', start: 10, end: 90 }]);
		expect(out?.brush).toEqual({ brushType: 'rect', areas: [{ coordRange: [1, 2] }] });
	});
});
