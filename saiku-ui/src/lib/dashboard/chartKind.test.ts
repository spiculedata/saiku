import { describe, expect, test } from 'vitest';
import { DEFAULT_CHART_KIND, resolveChartKind } from './chartKind';
import { CHART_TYPES } from '$lib/views/chartTypes';

describe('resolveChartKind', () => {
	test('every palette id resolves to itself — no type can land unsupported', () => {
		for (const c of CHART_TYPES) {
			expect(resolveChartKind(c.id)).toBe(c.id);
		}
	});

	test('absent / blank / whitespace chartType falls back to the default', () => {
		for (const raw of [undefined, null, '', '   ']) {
			expect(resolveChartKind(raw)).toBe(DEFAULT_CHART_KIND);
		}
		expect(DEFAULT_CHART_KIND).toBe('bar');
	});

	test('unknown ids degrade to the default rather than failing to render', () => {
		for (const raw of ['totally-made-up', 'gauge', 'boxplot', '3d-globe', 'hologram']) {
			expect(resolveChartKind(raw)).toBe(DEFAULT_CHART_KIND);
		}
	});

	test('case, punctuation and the classic …Chart suffix normalise to the id', () => {
		expect(resolveChartKind('Bar')).toBe('bar');
		expect(resolveChartKind('STACKEDBAR')).toBe('stackedBar');
		expect(resolveChartKind('stacked_bar')).toBe('stackedBar');
		expect(resolveChartKind('Stacked Bar')).toBe('stackedBar');
		expect(resolveChartKind('stacked-bar')).toBe('stackedBar');
		expect(resolveChartKind('TreemapChart')).toBe('treemap');
		expect(resolveChartKind('lineChart')).toBe('line');
		expect(resolveChartKind('DonutChart')).toBe('donut');
		expect(resolveChartKind('bubblechart')).toBe('bubble');
	});

	test('legacy single-measure spellings map to their supported equivalent', () => {
		expect(resolveChartKind('column')).toBe('bar');
		expect(resolveChartKind('BarColumn')).toBe('bar');
		expect(resolveChartKind('circle')).toBe('pie');
		expect(resolveChartKind('ring')).toBe('donut');
		expect(resolveChartKind('multilevelPie')).toBe('treemap');
	});

	test('a "…chart"-suffixed unknown id still falls back rather than throwing', () => {
		expect(resolveChartKind('gaugechart')).toBe(DEFAULT_CHART_KIND);
	});
});
