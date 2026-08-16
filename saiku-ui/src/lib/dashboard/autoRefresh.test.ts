import { describe, test, expect } from 'vitest';
import {
	REFRESH_INTERVAL_OPTIONS,
	normaliseInterval,
	intervalMs,
	isAutoRefreshOn,
	formatRelativeTime
} from './autoRefresh';

describe('REFRESH_INTERVAL_OPTIONS', () => {
	test('offers Off + the documented cadences (issue #931)', () => {
		expect(REFRESH_INTERVAL_OPTIONS.map((o) => o.minutes)).toEqual([0, 1, 5, 15, 30, 60]);
	});

	test('the first option is the Off sentinel', () => {
		expect(REFRESH_INTERVAL_OPTIONS[0].minutes).toBe(0);
	});

	test('every option carries an i18n key + fallback', () => {
		for (const o of REFRESH_INTERVAL_OPTIONS) {
			expect(o.labelKey.startsWith('dashboard.refresh.')).toBe(true);
			expect(o.labelFallback.length).toBeGreaterThan(0);
		}
	});
});

describe('normaliseInterval', () => {
	test('passes through valid option values', () => {
		expect(normaliseInterval(1)).toBe(1);
		expect(normaliseInterval(5)).toBe(5);
		expect(normaliseInterval(60)).toBe(60);
	});

	test('treats undefined / null / 0 / negative as off', () => {
		expect(normaliseInterval(undefined)).toBe(0);
		expect(normaliseInterval(null)).toBe(0);
		expect(normaliseInterval(0)).toBe(0);
		expect(normaliseInterval(-5)).toBe(0);
	});

	test('rejects off-list values (no smuggled sub-minute loops)', () => {
		expect(normaliseInterval(0.1)).toBe(0);
		expect(normaliseInterval(2)).toBe(0);
		expect(normaliseInterval(7)).toBe(0);
		expect(normaliseInterval(120)).toBe(0);
	});

	test('rejects non-finite values', () => {
		expect(normaliseInterval(NaN)).toBe(0);
		expect(normaliseInterval(Infinity)).toBe(0);
	});
});

describe('intervalMs', () => {
	test('converts valid minutes to milliseconds', () => {
		expect(intervalMs(1)).toBe(60_000);
		expect(intervalMs(5)).toBe(300_000);
		expect(intervalMs(60)).toBe(3_600_000);
	});

	test('off / invalid → 0 ms', () => {
		expect(intervalMs(0)).toBe(0);
		expect(intervalMs(undefined)).toBe(0);
		expect(intervalMs(7)).toBe(0);
	});
});

describe('isAutoRefreshOn', () => {
	test('true only for valid live intervals', () => {
		expect(isAutoRefreshOn(5)).toBe(true);
		expect(isAutoRefreshOn(0)).toBe(false);
		expect(isAutoRefreshOn(undefined)).toBe(false);
		expect(isAutoRefreshOn(7)).toBe(false);
	});
});

describe('formatRelativeTime', () => {
	const NOW = 1_000_000_000_000;

	test('just now within 5 seconds', () => {
		expect(formatRelativeTime(NOW, NOW)).toBe('just now');
		expect(formatRelativeTime(NOW - 4_000, NOW)).toBe('just now');
	});

	test('seconds', () => {
		expect(formatRelativeTime(NOW - 10_000, NOW)).toBe('10 sec ago');
		expect(formatRelativeTime(NOW - 59_000, NOW)).toBe('59 sec ago');
	});

	test('minutes', () => {
		expect(formatRelativeTime(NOW - 60_000, NOW)).toBe('1 min ago');
		expect(formatRelativeTime(NOW - 2 * 60_000, NOW)).toBe('2 min ago');
		expect(formatRelativeTime(NOW - 59 * 60_000, NOW)).toBe('59 min ago');
	});

	test('hours', () => {
		expect(formatRelativeTime(NOW - 60 * 60_000, NOW)).toBe('1 hr ago');
		expect(formatRelativeTime(NOW - 23 * 60 * 60_000, NOW)).toBe('23 hr ago');
	});

	test('days (singular + plural)', () => {
		expect(formatRelativeTime(NOW - 24 * 60 * 60_000, NOW)).toBe('1 day ago');
		expect(formatRelativeTime(NOW - 3 * 24 * 60 * 60_000, NOW)).toBe('3 days ago');
	});

	test('future / non-finite timestamps render just now', () => {
		expect(formatRelativeTime(NOW + 10_000, NOW)).toBe('just now');
		expect(formatRelativeTime(NaN, NOW)).toBe('just now');
		expect(formatRelativeTime(NOW, NaN)).toBe('just now');
	});
});
