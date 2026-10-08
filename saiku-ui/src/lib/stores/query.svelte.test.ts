/**
 * Unit tests for the named-sets store mutators added for saiku#826
 * (`upsertNamedSet` / `removeNamedSet`). Mirrors the calculated-measure
 * upsert-by-name behaviour in DimensionList's onCalculatedSave, but
 * centralised on the store the way addMeasure/removeMeasure already are.
 */
import { describe, expect, it } from 'vitest';
import { query } from './query.svelte';
import type { SaikuCube } from '$lib/api/discover';

const CUBE: SaikuCube = {
	connection: 'test',
	catalog: 'FoodMart',
	schema: 'FoodMart',
	name: 'Sales',
	caption: 'Sales',
	uniqueName: 'Sales',
	visible: true
};

describe('QueryStore named sets (saiku#826)', () => {
	it('starts with an empty namedSets list on a fresh query', () => {
		query.initFor(CUBE);
		expect(query.current?.queryModel?.namedSets).toEqual([]);
	});

	it('upsertNamedSet appends a new set', () => {
		query.initFor(CUBE);
		query.upsertNamedSet({ name: 'Premium', expression: 'TopCount([Customer].Members, 10)' });
		expect(query.current?.queryModel?.namedSets).toEqual([
			{ name: 'Premium', expression: 'TopCount([Customer].Members, 10)' }
		]);
	});

	it('upsertNamedSet replaces an existing entry matched by name, not appends', () => {
		query.initFor(CUBE);
		query.upsertNamedSet({ name: 'Premium', expression: 'A' });
		query.upsertNamedSet({ name: 'Premium', expression: 'B', caption: 'Premium Customers' });
		const sets = query.current?.queryModel?.namedSets ?? [];
		expect(sets).toHaveLength(1);
		expect(sets[0]).toEqual({ name: 'Premium', expression: 'B', caption: 'Premium Customers' });
	});

	it('upsertNamedSet leaves other sets untouched', () => {
		query.initFor(CUBE);
		query.upsertNamedSet({ name: 'A', expression: '1' });
		query.upsertNamedSet({ name: 'B', expression: '2' });
		expect(query.current?.queryModel?.namedSets?.map((s) => s.name)).toEqual(['A', 'B']);
	});

	it('removeNamedSet drops the named entry only', () => {
		query.initFor(CUBE);
		query.upsertNamedSet({ name: 'A', expression: '1' });
		query.upsertNamedSet({ name: 'B', expression: '2' });
		query.removeNamedSet('A');
		expect(query.current?.queryModel?.namedSets?.map((s) => s.name)).toEqual(['B']);
	});

	it('removeNamedSet on an unknown name is a no-op', () => {
		query.initFor(CUBE);
		query.upsertNamedSet({ name: 'A', expression: '1' });
		query.removeNamedSet('does-not-exist');
		expect(query.current?.queryModel?.namedSets?.map((s) => s.name)).toEqual(['A']);
	});

	it('mutators no-op when there is no active query', () => {
		query.current = null;
		expect(() => query.upsertNamedSet({ name: 'A', expression: '1' })).not.toThrow();
		expect(() => query.removeNamedSet('A')).not.toThrow();
		expect(query.current).toBeNull();
	});
});
