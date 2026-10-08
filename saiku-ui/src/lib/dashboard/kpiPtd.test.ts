/*
 * Unit tests for the KPI tile's period-to-date comparison (saiku#1749).
 *
 * The scenario throughout is FoodMart's weekly series, which ends on a stub
 * week 52: two days against week 51's seven, so an unadjusted prior-period
 * delta reads about −84%. saiku#1748 withheld that comparison; these are the
 * tests for the replacement that keeps it and makes it like-for-like.
 *
 * No DOM and no fetch: the module is imported bare so the suite runs in
 * vitest's "node" environment, the same contract kpiYoy.ts keeps.
 */

import { describe, expect, it } from 'vitest';

import {
	finerTimeLevel,
	grainUnitLabel,
	measureValues,
	periodToDatePair,
	type PtdSchema
} from './kpiPtd';

/** FoodMart's Time/Weekly: Year → Week → Day, each with a saiku#818 grain
 *  annotation. */
const weeklySchema: PtdSchema = {
	dimensions: {
		time: {
			hierarchies: {
				weekly: {
					levels: {
						year: { name: 'Year', grain: 'year' },
						week: { name: 'Week', grain: 'week' },
						day: { name: 'Day', grain: 'day' }
					}
				}
			}
		}
	}
};

/** An unannotated cube — no saiku#818 grain tags anywhere. Position in the
 *  levels map is the only signal available. */
const bareSchema: PtdSchema = {
	dimensions: {
		date: {
			hierarchies: {
				main: {
					levels: {
						year: { name: 'Year' },
						month: { name: 'Month' },
						day: { name: 'Day' }
					}
				}
			}
		}
	}
};

const weeklyRef = { dimension: 'Time', hierarchy: 'Weekly', level: 'Week' };

describe('finerTimeLevel', () => {
	it('picks the level declared immediately below the tile’s time level', () => {
		expect(finerTimeLevel(weeklySchema, weeklyRef)).toEqual({ name: 'Day', grain: 'day' });
	});

	it('is case-insensitive on the dimension / hierarchy / level names', () => {
		expect(
			finerTimeLevel(weeklySchema, { dimension: 'time', hierarchy: 'WEEKLY', level: 'week' })
		).toEqual({ name: 'Day', grain: 'day' });
	});

	it('is unaffected by an "(All)" pseudo-level at the head of the map', () => {
		// Some hierarchies surface one and some don't. The lookup is positional,
		// so both shapes have to resolve the same sub-period level.
		const withAll: PtdSchema = {
			dimensions: {
				time: {
					hierarchies: {
						weekly: {
							levels: {
								'(all)': { name: '(All)' },
								year: { name: 'Year', grain: 'year' },
								week: { name: 'Week', grain: 'week' },
								day: { name: 'Day', grain: 'day' }
							}
						}
					}
				}
			}
		};
		expect(finerTimeLevel(withAll, weeklyRef)).toEqual({ name: 'Day', grain: 'day' });
	});

	it('falls back to position alone when the cube carries no grain tags', () => {
		expect(
			finerTimeLevel(bareSchema, { dimension: 'Date', hierarchy: 'Main', level: 'Month' })
		).toEqual({ name: 'Day', grain: null });
	});

	it('returns null at the deepest level — nothing finer to truncate with', () => {
		// The "cube with no usable day grain" case saiku#1749 raised. Null is the
		// tile's cue to degrade to #1748, never to compare against a whole period.
		expect(finerTimeLevel(weeklySchema, { ...weeklyRef, level: 'Day' })).toBeNull();
	});

	it('returns null when the level is not in the hierarchy', () => {
		expect(finerTimeLevel(weeklySchema, { ...weeklyRef, level: 'Fiscal Quarter' })).toBeNull();
	});

	it('returns null when the dimension or hierarchy is unknown', () => {
		expect(finerTimeLevel(weeklySchema, { ...weeklyRef, hierarchy: 'Time' })).toBeNull();
		expect(finerTimeLevel(weeklySchema, { ...weeklyRef, dimension: 'Order' })).toBeNull();
	});

	it('returns null when the next level is not a FINER grain', () => {
		// A hierarchy that steps sideways (Year → Quarter after a re-used name)
		// would hand back a level that makes the comparison meaningless.
		const sideways: PtdSchema = {
			dimensions: {
				time: {
					hierarchies: {
						main: {
							levels: {
								year: { name: 'Year', grain: 'year' },
								bucket: { name: 'Bucket', grain: 'year' }
							}
						}
					}
				}
			}
		};
		expect(
			finerTimeLevel(sideways, { dimension: 'Time', hierarchy: 'Main', level: 'Year' })
		).toBeNull();
	});

	it('returns null for a missing schema or a missing time-level ref', () => {
		expect(finerTimeLevel(null, weeklyRef)).toBeNull();
		expect(finerTimeLevel(weeklySchema, null)).toBeNull();
		expect(finerTimeLevel(undefined, undefined)).toBeNull();
	});
});

describe('periodToDatePair', () => {
	it('truncates the prior period to the width the current one has reached', () => {
		// The FoodMart case, made concrete: week 52 holds two days (1,856 in
		// total), so the baseline is week 51's FIRST two days, not all seven.
		const pair = periodToDatePair([1200, 656], [1000, 1500, 2000, 2500, 3000, 2000, 1880]);
		expect(pair).not.toBeNull();
		expect(pair!.current).toBe(1856);
		expect(pair!.prior).toBe(2500); // 1000 + 1500 — two days, not 13,880
		expect(pair!.offset).toBe(2);
		expect(pair!.baselineCount).toBe(2);
		expect(pair!.clamped).toBe(false);
	});

	it('is a no-op when both periods are the same width', () => {
		// The property that makes this safe to leave on: a complete period
		// compared period-to-date returns exactly the prior-period answer.
		const days = [1, 2, 3, 4, 5, 6, 7];
		const pair = periodToDatePair(
			days,
			days.map((d) => d * 10)
		);
		expect(pair!.offset).toBe(7);
		expect(pair!.current).toBe(28);
		expect(pair!.prior).toBe(280);
		expect(pair!.clamped).toBe(false);
	});

	it('counts a bucket with no figure towards the offset but not towards the sum', () => {
		// A day member exists because the day happened, whether or not anything
		// was sold on it. Counting it keeps the two windows the same WIDTH, which
		// is the whole point; skipping it would compare 2 days against 1.
		const pair = periodToDatePair([100, null, 300], [50, 60, 70, 80]);
		expect(pair!.offset).toBe(3);
		expect(pair!.current).toBe(400);
		expect(pair!.prior).toBe(180);
	});

	it('clamps to the whole prior period when it is SHORTER than the offset', () => {
		// Day 30 of the month against a 28-day February: there is no 30th of
		// February, so its whole self is the closest like-for-like figure. The
		// clamp is reported so the tile can say the windows differ, rather than
		// implying they are the same width.
		const feb = new Array(28).fill(10);
		const pair = periodToDatePair(new Array(30).fill(1), feb);
		expect(pair!.offset).toBe(30);
		expect(pair!.baselineCount).toBe(28);
		expect(pair!.clamped).toBe(true);
		expect(pair!.prior).toBe(280);
	});

	it('ignores non-finite buckets in the sum', () => {
		// The NaN / Infinity still occupy a slot, so the offset is 3 and the
		// baseline window is 3 buckets wide — the sum just skips what it can't
		// add. A bad bucket must not quietly narrow the comparison.
		const pair = periodToDatePair([10, Number.NaN, Number.POSITIVE_INFINITY], [1, 2, 3]);
		expect(pair!.offset).toBe(3);
		expect(pair!.current).toBe(10);
		expect(pair!.prior).toBe(6);
	});

	it('returns null when the current period has no sub-periods', () => {
		expect(periodToDatePair([], [1, 2, 3])).toBeNull();
		expect(periodToDatePair(null, [1, 2, 3])).toBeNull();
		expect(periodToDatePair(undefined, undefined)).toBeNull();
	});

	it('returns null when the prior period has no sub-periods at all', () => {
		// No baseline means WITHHOLD, not "compare against a whole period".
		expect(periodToDatePair([1, 2], [])).toBeNull();
		expect(periodToDatePair([1, 2], null)).toBeNull();
	});
});

describe('measureValues', () => {
	it('pulls the measure out of a records row and skips the row header', () => {
		expect(
			measureValues([
				{ 'Week 51': '51', 'Store Sales': { value: 13880, formatted: '13,880' } },
				{ 'Week 52': '52', 'Store Sales': { value: 1856, formatted: '1,856' } }
			])
		).toEqual([13880, 1856]);
	});

	it('reports a cell with no figure as null rather than dropping the row', () => {
		// Dropping it would silently shrink the offset and make the baseline
		// window narrower than the current one.
		expect(
			measureValues([
				{ Day: '16', 'Store Sales': { value: null, formatted: '—' } },
				{ Day: '17', 'Store Sales': { value: 656, formatted: '656' } }
			])
		).toEqual([null, 656]);
	});

	it('returns null for a row with no measure cell at all', () => {
		expect(measureValues([{ Day: '16' }])).toEqual([null]);
	});

	it('returns an empty list for an absent payload', () => {
		expect(measureValues(undefined)).toEqual([]);
		expect(measureValues(null)).toEqual([]);
	});
});

describe('grainUnitLabel', () => {
	it('pluralises the sub-period grain', () => {
		expect(grainUnitLabel(2, 'day', 'Day')).toBe('2 days');
		expect(grainUnitLabel(1, 'day', 'Day')).toBe('1 day');
		expect(grainUnitLabel(3, 'week', 'Week')).toBe('3 weeks');
		expect(grainUnitLabel(1, 'quarter', 'Quarter')).toBe('1 quarter');
	});

	it('falls back to the level name when the cube has no grain annotation', () => {
		expect(grainUnitLabel(4, null, 'Day')).toBe('4 Day');
		expect(grainUnitLabel(4, undefined, null)).toBe('4 sub-period');
	});
});
