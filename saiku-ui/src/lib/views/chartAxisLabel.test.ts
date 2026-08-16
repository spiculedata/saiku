/*
 * Unit tests for the shared chart axis-label truncation helpers used by both
 * the workspace ChartView and the dashboard chartOptions builder.
 */

import { describe, test, expect } from 'vitest';
import {
	ELLIPSIS,
	DEFAULT_AXIS_LABEL_WIDTH,
	MIN_AXIS_LABEL_WIDTH,
	MAX_AXIS_LABEL_WIDTH,
	truncateAxisLabel,
	deriveAxisLabelWidth,
	axisLabelConfig
} from '$lib/views/chartAxisLabel';

describe('truncateAxisLabel', () => {
	test('leaves short labels unchanged', () => {
		expect(truncateAxisLabel('Q3 2026', 20)).toBe('Q3 2026');
	});

	test('returns the label unchanged when it exactly fits', () => {
		expect(truncateAxisLabel('abcde', 5)).toBe('abcde');
	});

	test('truncates long labels and appends a single ellipsis', () => {
		const long = 'Quarter 3 Fiscal Year 2026 - Europe Region - Premium Tier';
		const out = truncateAxisLabel(long, 12);
		expect(out.length).toBe(12);
		expect(out.endsWith(ELLIPSIS)).toBe(true);
		expect(out).toBe('Quarter 3 F' + ELLIPSIS);
	});

	test('max <= 0 disables truncation', () => {
		const long = 'a very long label indeed';
		expect(truncateAxisLabel(long, 0)).toBe(long);
		expect(truncateAxisLabel(long, -5)).toBe(long);
	});

	test('max smaller than the ellipsis collapses to the ellipsis', () => {
		expect(truncateAxisLabel('abcdef', 1)).toBe(ELLIPSIS);
	});
});

describe('deriveAxisLabelWidth', () => {
	test('falls back to default when geometry is unknown', () => {
		expect(deriveAxisLabelWidth(0, 5)).toBe(DEFAULT_AXIS_LABEL_WIDTH);
		expect(deriveAxisLabelWidth(600, 0)).toBe(DEFAULT_AXIS_LABEL_WIDTH);
		expect(deriveAxisLabelWidth(-1, -1)).toBe(DEFAULT_AXIS_LABEL_WIDTH);
	});

	test('divides available width across categories', () => {
		// 600 / 6 = 100, within [MIN, MAX]
		expect(deriveAxisLabelWidth(600, 6)).toBe(100);
	});

	test('clamps to the maximum when few wide categories', () => {
		// 2000 / 2 = 1000 -> clamped to MAX
		expect(deriveAxisLabelWidth(2000, 2)).toBe(MAX_AXIS_LABEL_WIDTH);
	});

	test('clamps to the minimum when many crowded categories', () => {
		// 300 / 100 = 3 -> clamped to MIN
		expect(deriveAxisLabelWidth(300, 100)).toBe(MIN_AXIS_LABEL_WIDTH);
	});
});

describe('axisLabelConfig', () => {
	test('produces an ECharts truncate fragment with the default width', () => {
		const cfg = axisLabelConfig();
		expect(cfg.overflow).toBe('truncate');
		expect(cfg.hideOverlap).toBe(true);
		expect(cfg.ellipsis).toBe(ELLIPSIS);
		expect(cfg.width).toBe(DEFAULT_AXIS_LABEL_WIDTH);
	});

	test('honours an explicit width within range', () => {
		expect(axisLabelConfig(90).width).toBe(90);
	});

	test('clamps out-of-range widths', () => {
		expect(axisLabelConfig(5).width).toBe(MIN_AXIS_LABEL_WIDTH);
		expect(axisLabelConfig(9999).width).toBe(MAX_AXIS_LABEL_WIDTH);
	});

	test('falls back to default for invalid widths', () => {
		expect(axisLabelConfig(0).width).toBe(DEFAULT_AXIS_LABEL_WIDTH);
		expect(axisLabelConfig(Number.NaN).width).toBe(DEFAULT_AXIS_LABEL_WIDTH);
	});
});
