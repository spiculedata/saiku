/*
 * #1103 fast-follow — unit coverage for the embed chart's APPLIER
 * (buildEmbedChartOption), extracted from EmbedChart.svelte. The reader
 * (embedChartTheme.test.ts) proves the CSS vars parse correctly; this proves
 * the parsed theme maps onto the ECharts option AND — the crux — that an
 * unstyled embed emits the same option as pre-#1103 (no color/fg/axisLine
 * keys). Without this, a future buildOption refactor could silently break the
 * byte-identical back-compat guarantee with no red test.
 */
import { describe, test, expect } from 'vitest';
import { buildEmbedChartOption } from './embedChartOption';
import type { EmbedRow } from './types';
import type { EmbedChartTheme } from './embedChartTheme';

// A non-numeric `value` makes the column a category axis (isNumericColumn → false);
// row-header cells carry the caption. Type it loosely for the fixture.
const cat = (label: string) => ({ value: label as unknown as number, formatted: label });
const num = (n: number) => ({ value: n, formatted: String(n) });

const ROWS: EmbedRow[] = [
	{ Country: cat('France'), Sales: num(100), Units: num(5) },
	{ Country: cat('Spain'), Sales: num(200), Units: num(8) }
];

const UNSTYLED: EmbedChartTheme = { palette: [] };

/* eslint-disable @typescript-eslint/no-explicit-any */
describe('buildEmbedChartOption', () => {
	test('unstyled (no vars) emits NO color/fg/axisLine keys — byte-identical to pre-#1103', () => {
		const opt = buildEmbedChartOption(ROWS, 'bar', UNSTYLED) as any;
		expect(opt.color).toBeUndefined(); // no host palette → no `color` key
		expect(opt.legend.textStyle).toBeUndefined(); // no fg → no legend textStyle
		expect(opt.xAxis.axisLabel.color).toBeUndefined(); // no fg → no x label colour
		expect(opt.xAxis.axisLine).toBeUndefined(); // no axisLine theme on x
		expect(opt.yAxis.axisLabel).toBeUndefined(); // no fg → no y label colour
		expect(opt.yAxis.axisLine).toBeUndefined();
		expect(opt.yAxis.splitLine).toBeUndefined();
	});

	test('each numeric column becomes its own bar series; the non-numeric column is the category axis', () => {
		const opt = buildEmbedChartOption(ROWS, 'bar', UNSTYLED) as any;
		expect(opt.series.map((s: any) => s.name)).toEqual(['Sales', 'Units']);
		expect(opt.series.every((s: any) => s.type === 'bar')).toBe(true);
		expect(opt.xAxis.data).toEqual(['France', 'Spain']);
	});

	test('host palette → ECharts `color` cycle', () => {
		const opt = buildEmbedChartOption(ROWS, 'bar', {
			palette: ['#2563eb', '#16a34a', '#dc2626']
		}) as any;
		expect(opt.color).toEqual(['#2563eb', '#16a34a', '#dc2626']);
	});

	test('fg themes axis labels + legend text', () => {
		const opt = buildEmbedChartOption(ROWS, 'bar', { palette: [], fg: '#ffffff' }) as any;
		expect(opt.legend.textStyle.color).toBe('#ffffff');
		expect(opt.xAxis.axisLabel.color).toBe('#ffffff');
		expect(opt.yAxis.axisLabel.color).toBe('#ffffff');
	});

	test('axisLine (muted) themes both axis lines + the y splitLine', () => {
		const opt = buildEmbedChartOption(ROWS, 'bar', { palette: [], axisLine: '#cccccc' }) as any;
		expect(opt.xAxis.axisLine.lineStyle.color).toBe('#cccccc');
		expect(opt.yAxis.axisLine.lineStyle.color).toBe('#cccccc');
		expect(opt.yAxis.splitLine.lineStyle.color).toBe('#cccccc');
	});

	test('line mode → line series', () => {
		const opt = buildEmbedChartOption(ROWS, 'line', UNSTYLED) as any;
		expect(opt.series.every((s: any) => s.type === 'line')).toBe(true);
	});

	test('pie mode → a single pie series on the first numeric column, honouring the palette', () => {
		const opt = buildEmbedChartOption(ROWS, 'pie', { palette: ['#abc'] }) as any;
		expect(opt.series).toHaveLength(1);
		expect(opt.series[0].type).toBe('pie');
		expect(opt.color).toEqual(['#abc']); // palette still applies in pie mode
		expect(opt.series[0].data.map((d: any) => d.value)).toEqual([100, 200]); // first numeric col = Sales
	});

	test("empty rows → 'No data' title, still no color key when unstyled", () => {
		const opt = buildEmbedChartOption([], 'bar', UNSTYLED) as any;
		expect(opt.title.text).toBe('No data');
		expect(opt.color).toBeUndefined();
	});
});
