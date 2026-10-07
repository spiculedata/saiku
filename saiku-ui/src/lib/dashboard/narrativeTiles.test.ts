/*
 * Unit tests for resolveNarrativeTiles (saiku#910).
 */
import { describe, test, expect, vi } from 'vitest';
import { resolveNarrativeTiles } from '$lib/dashboard/narrativeTiles';
import type { SchemaLike } from '$lib/dashboard/effectiveQuery';
import type { ActiveFilter } from '$lib/stores/activeFilters.svelte';
import type { DashboardTile, CubeRef } from '$lib/api/dashboards';

const CUBE: CubeRef = {
	connectionName: 'foodmart',
	catalog: 'FoodMart',
	schema: 'FoodMart',
	cubeName: 'Sales'
};

function sampleSchema(): SchemaLike {
	return {
		dimensions: {
			time: {
				name: 'Time',
				hierarchies: { time: { name: 'Time', levels: { year: { name: 'Year' } } } }
			}
		}
	};
}

function chartTile(overrides: Partial<DashboardTile> = {}): DashboardTile {
	return {
		id: 't1',
		x: 0,
		y: 0,
		w: 4,
		h: 4,
		type: 'chart',
		title: 'Sales by Year',
		cube: CUBE,
		chartType: 'bar',
		query: { kind: 'inline', body: { cube: CUBE, measures: [{ name: 'Store Sales' }] } },
		...overrides
	};
}

describe('resolveNarrativeTiles', () => {
	test('resolves an inline chart tile with a cached schema', () => {
		const lookup = vi.fn().mockReturnValue(sampleSchema());
		const out = resolveNarrativeTiles([chartTile()], [], lookup);

		expect(out).toHaveLength(1);
		expect(out[0].title).toBe('Sales by Year');
		expect(out[0].query).toMatchObject({ measures: [{ name: 'Store Sales' }] });
		expect(lookup).toHaveBeenCalledWith(CUBE);
	});

	test('falls back to a positional title when the tile has none', () => {
		const out = resolveNarrativeTiles(
			[chartTile({ title: undefined, chartType: 'line' })],
			[],
			() => sampleSchema()
		);
		expect(out[0].title).toBe('line chart');
	});

	test('skips non-query tile types (kpi, text, filter, image)', () => {
		const kpi = chartTile({ type: 'kpi', query: undefined });
		const text = chartTile({ type: 'text', query: undefined, cube: undefined });
		const out = resolveNarrativeTiles([kpi, text], [], () => sampleSchema());
		expect(out).toHaveLength(0);
	});

	test('still includes a tile whose schema has not resolved yet — base query unfiltered (matches effectiveQueryFor)', () => {
		const out = resolveNarrativeTiles([chartTile()], [], () => null);
		expect(out).toHaveLength(1);
		expect(out[0].query).toMatchObject({ measures: [{ name: 'Store Sales' }] });
	});

	test('skips a reference-query tile (caller has not resolved the .saiku file)', () => {
		const referenceTile = chartTile({
			query: { kind: 'reference', path: '/homes/admin/sales.saiku' }
		});
		const out = resolveNarrativeTiles([referenceTile], [], () => sampleSchema());
		expect(out).toHaveLength(0);
	});

	test('merges active filters into the effective query, same as ChartTile', () => {
		const filters: ActiveFilter[] = [
			{
				id: 'f1',
				source: { kind: 'panel', filterId: 'w1' },
				filter: {
					dimension: 'Time',
					hierarchy: 'Time',
					level: 'Year',
					members: ['[Time].[Time].[Year].&[1997]']
				}
			}
		];
		const out = resolveNarrativeTiles([chartTile()], filters, () => sampleSchema());
		expect(out).toHaveLength(1);
		const filtersOnQuery = (out[0].query as { filters?: unknown[] }).filters ?? [];
		expect(filtersOnQuery).toHaveLength(1);
	});

	test('includes a queryable custom tile, excludes an unsupported one', () => {
		const custom = chartTile({ type: 'custom' });
		const out = resolveNarrativeTiles([custom], [], () => sampleSchema());
		expect(out).toHaveLength(1);
	});

	test('empty tile list returns an empty array', () => {
		expect(resolveNarrativeTiles([], [], () => sampleSchema())).toEqual([]);
	});
});
