/*
 * Unit tests for the #1103 embed chart-theming reader.
 */

import { describe, test, expect } from 'vitest';
import { readEmbedChartTheme, hasEmbedChartTheme } from './embedChartTheme';

/** Build a CSS-var getter from a plain map (missing keys → ""). */
function getter(vars: Record<string, string>): (name: string) => string {
	return (name) => vars[name] ?? '';
}

describe('readEmbedChartTheme', () => {
	test('no vars set → empty palette, no fg/axisLine (unstyled = ECharts default)', () => {
		const t = readEmbedChartTheme(getter({}));
		expect(t.palette).toEqual([]);
		expect(t.fg).toBeUndefined();
		expect(t.axisLine).toBeUndefined();
		expect(hasEmbedChartTheme(t)).toBe(false);
	});

	test('reads a contiguous palette from chart-1..N', () => {
		const t = readEmbedChartTheme(
			getter({
				'--saiku-embed-chart-1': '#2563eb',
				'--saiku-embed-chart-2': '#16a34a',
				'--saiku-embed-chart-3': '#dc2626'
			})
		);
		expect(t.palette).toEqual(['#2563eb', '#16a34a', '#dc2626']);
		expect(hasEmbedChartTheme(t)).toBe(true);
	});

	test("stops at the first gap (a missing index doesn't reorder the cycle)", () => {
		const t = readEmbedChartTheme(
			getter({
				'--saiku-embed-chart-1': '#111',
				'--saiku-embed-chart-2': '#222',
				// 3 unset
				'--saiku-embed-chart-4': '#444'
			})
		);
		expect(t.palette).toEqual(['#111', '#222']);
	});

	test('caps at 8 colours', () => {
		const vars: Record<string, string> = {};
		for (let i = 1; i <= 12; i++) vars[`--saiku-embed-chart-${i}`] = `#${i}`;
		expect(readEmbedChartTheme(getter(vars)).palette).toHaveLength(8);
	});

	test('trims whitespace; blank var is treated as unset', () => {
		const t = readEmbedChartTheme(
			getter({
				'--saiku-embed-chart-1': '  #abc  ',
				'--saiku-embed-fg': '  #1f2937 ',
				'--saiku-embed-muted': '   '
			})
		);
		expect(t.palette).toEqual(['#abc']);
		expect(t.fg).toBe('#1f2937');
		expect(t.axisLine).toBeUndefined(); // whitespace-only → unset
	});

	test('fg / axisLine without a palette still counts as themed', () => {
		const t = readEmbedChartTheme(getter({ '--saiku-embed-fg': '#fff' }));
		expect(t.palette).toEqual([]);
		expect(t.fg).toBe('#fff');
		expect(hasEmbedChartTheme(t)).toBe(true);
	});
});
