/*
 * Unit tests for the Ossie effective-query builder (saiku#1803).
 */

import { describe, expect, it } from 'vitest';
import { ossieSource } from '$lib/dashboard/tileSource';
import type { SemanticFilter } from '$lib/dashboard/semanticFilter';
import {
	mergeOssieFilters,
	ossieEffectiveQueryFor,
	type OssieQueryBody
} from '$lib/dashboard/ossieEffectiveQuery';

const FLIGHTS = ossieSource('unknown_Flights', 'Flights');

function base(): OssieQueryBody {
	return {
		rows: [{ dataset: 'carrier', field: 'carrier_name' }],
		values: [{ metric: 'flight_count' }],
		filters: [{ dataset: 'flight', field: 'year', op: 'EQ', value: '1998' }]
	};
}

function countryFilter(captions: string[]): SemanticFilter {
	return {
		dimension: 'Geo',
		hierarchy: 'Geo',
		level: 'Country',
		members: [],
		label: 'Country',
		captions,
		bindings: [{ kind: 'ossie', cube: FLIGHTS, dataset: 'airport', field: 'country_code' }]
	};
}

describe('mergeOssieFilters', () => {
	it("appends an active predicate alongside the tile's own", () => {
		const out = mergeOssieFilters(base(), [countryFilter(['US'])], FLIGHTS);
		expect(out.filters).toEqual([
			{ dataset: 'flight', field: 'year', op: 'EQ', value: '1998' },
			{ dataset: 'airport', field: 'country_code', op: 'EQ', value: 'US' }
		]);
	});

	it('an active predicate REPLACES a baked-in one on the same dataset+field', () => {
		// Last-wins, same rule the MDX path applies per hierarchy: an author's
		// default must not fight the reader's selection.
		const f: SemanticFilter = {
			...countryFilter(['1997']),
			bindings: [{ kind: 'ossie', cube: FLIGHTS, dataset: 'flight', field: 'year' }]
		};
		const out = mergeOssieFilters(base(), [f], FLIGHTS);
		expect(out.filters).toHaveLength(1);
		expect(out.filters?.[0].value).toBe('1997');
	});

	it('matches dataset+field case-insensitively when overriding', () => {
		const f: SemanticFilter = {
			...countryFilter(['1997']),
			bindings: [{ kind: 'ossie', cube: FLIGHTS, dataset: 'FLIGHT', field: 'Year' }]
		};
		expect(mergeOssieFilters(base(), [f], FLIGHTS).filters).toHaveLength(1);
	});

	it('leaves the body alone when nothing addresses this model', () => {
		const legacyMdxFilter: SemanticFilter = {
			dimension: 'Store',
			hierarchy: 'Stores',
			level: 'Store Country',
			members: ['[Store].[Stores].[Mexico]']
		};
		const out = mergeOssieFilters(base(), [legacyMdxFilter], FLIGHTS);
		expect(out.filters).toEqual(base().filters);
	});

	it('does not mutate the input body', () => {
		const b = base();
		mergeOssieFilters(b, [countryFilter(['US'])], FLIGHTS);
		expect(b.filters).toHaveLength(1);
	});

	it('a selection of nothing is a no-op, not a match-zero-rows predicate', () => {
		const out = mergeOssieFilters(base(), [countryFilter([])], FLIGHTS);
		expect(out.filters).toEqual(base().filters);
	});
});

describe('ossieEffectiveQueryFor', () => {
	const tile = {
		cube: FLIGHTS,
		query: { kind: 'inline', body: base() as Record<string, unknown> }
	};

	it("pins connection + model from the tile's source", () => {
		const out = ossieEffectiveQueryFor(tile, []);
		expect(out?.connection).toBe('unknown_Flights');
		expect(out?.model).toBe('Flights');
	});

	it('overrides a stale connection/model baked into the saved body', () => {
		// The tile's source is the authority; the two drift the moment an author
		// re-points the tile at a different model.
		const stale = {
			cube: FLIGHTS,
			query: {
				kind: 'inline',
				body: { ...base(), connection: 'old', model: 'Old' } as Record<string, unknown>
			}
		};
		const out = ossieEffectiveQueryFor(stale, []);
		expect(out?.connection).toBe('unknown_Flights');
		expect(out?.model).toBe('Flights');
	});

	it('merges the active filters', () => {
		const out = ossieEffectiveQueryFor(tile, [countryFilter(['US'])]);
		expect(out?.filters).toHaveLength(2);
	});

	it('carries unknown body fields through untouched', () => {
		const withSorts = {
			cube: FLIGHTS,
			query: {
				kind: 'inline',
				body: {
					...base(),
					sorts: [{ metric: 'flight_count', direction: 'desc' }],
					limit: 10
				} as Record<string, unknown>
			}
		};
		const out = ossieEffectiveQueryFor(withSorts, []);
		expect(out?.limit).toBe(10);
		expect(out?.sorts).toEqual([{ metric: 'flight_count', direction: 'desc' }]);
	});

	it('is null for a reference query — a saved .saiku is an MDX artefact', () => {
		expect(ossieEffectiveQueryFor({ cube: FLIGHTS, query: { kind: 'reference' } }, [])).toBeNull();
	});

	it('is null with no source', () => {
		expect(ossieEffectiveQueryFor({ query: { kind: 'inline', body: {} } }, [])).toBeNull();
	});
});
