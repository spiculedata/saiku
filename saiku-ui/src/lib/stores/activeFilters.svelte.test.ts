/*
 * Unit tests for the brush cross-filter mutators on the active-filters store
 * (saiku#1085). Panel filters derive from the dashboard store (empty here), so
 * these focus on the transient click/cross buffers and the merged `all` set.
 */

import { beforeEach, describe, expect, test } from 'vitest';
import { activeFilters, panelFilterToActive, targetKey } from './activeFilters.svelte';
import type { DashboardFilter } from '$lib/api/dashboards';

function filter(members: string[], level = 'Product Family'): DashboardFilter {
	return { dimension: 'Product', hierarchy: 'Products', level, members };
}

beforeEach(() => {
	activeFilters.resetTransient(); // clear clicks + crosses between tests
});

describe('pushCross', () => {
	test('adds a cross-filter tagged with the source tile id', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		expect(activeFilters.crosses).toHaveLength(1);
		expect(activeFilters.crosses[0].source).toEqual({
			kind: 'cross',
			tileId: 'tile-a'
		});
		expect(activeFilters.crosses[0].filter.members).toEqual(['[Product].[Products].[Drink]']);
	});

	test('a fresh brush on the SAME source tile replaces its previous selection', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.pushCross(
			filter(['[Product].[Products].[Food]', '[Product].[Products].[Drink]']),
			'tile-a'
		);
		expect(activeFilters.crosses).toHaveLength(1);
		expect(activeFilters.crosses[0].filter.members).toHaveLength(2);
	});

	test('different source tiles each keep their own cross-filter', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.pushCross(filter(['[Product].[Products].[Food]']), 'tile-b');
		expect(activeFilters.crosses).toHaveLength(2);
	});

	test("empty members[] clears the source tile's cross-filter", () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.pushCross(filter([]), 'tile-a');
		expect(activeFilters.crosses).toHaveLength(0);
	});
});

describe('clearCrossesFrom', () => {
	test("drops only the named source tile's cross-filter", () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.pushCross(filter(['[Product].[Products].[Food]']), 'tile-b');
		activeFilters.clearCrossesFrom('tile-a');
		expect(activeFilters.crosses).toHaveLength(1);
		expect(activeFilters.crosses[0].source).toEqual({
			kind: 'cross',
			tileId: 'tile-b'
		});
	});

	test('is a no-op for a tile with no cross-filter', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.clearCrossesFrom('tile-z');
		expect(activeFilters.crosses).toHaveLength(1);
	});
});

describe('all + clearChip', () => {
	test('merged `all` includes the cross-filter (no dashboard panel here)', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		const crossInAll = activeFilters.all.find((f) => f.source.kind === 'cross');
		expect(crossInAll).toBeTruthy();
		expect(crossInAll?.filter.members).toEqual(['[Product].[Products].[Drink]']);
	});

	test('clearChip removes a cross-filter by id', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		const id = activeFilters.crosses[0].id;
		activeFilters.clearChip(id);
		expect(activeFilters.crosses).toHaveLength(0);
	});
});

describe('resetTransient', () => {
	test('wipes cross-filters (dashboard swap)', () => {
		activeFilters.pushCross(filter(['[Product].[Products].[Drink]']), 'tile-a');
		activeFilters.resetTransient();
		expect(activeFilters.crosses).toHaveLength(0);
	});
});

/*
 * App-level filters (saiku#1754). The App Builder's header context pill is app
 * chrome, not page state: its selection must survive the per-page hydrate that
 * wipes clicks/crosses, or a page shows national numbers under a header that
 * claims a region.
 */
describe('app-level filters (saiku#1754)', () => {
	beforeEach(() => {
		activeFilters.clearApp('app-context-pill');
	});

	test('pushApp registers an app-scoped filter', () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		expect(activeFilters.appLevel).toHaveLength(1);
		expect(activeFilters.appLevel[0].source).toEqual({
			kind: 'app',
			sourceId: 'app-context-pill'
		});
	});

	test('pushApp replaces the previous selection for the same target', () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		activeFilters.pushApp(filter(['[Geography].[Geography].[Midwest]']), 'app-context-pill');
		expect(activeFilters.appLevel).toHaveLength(1);
		expect(activeFilters.appLevel[0].filter.members).toEqual(['[Geography].[Geography].[Midwest]']);
	});

	test('survives resetTransient — this is the page-switch bug', () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		activeFilters.resetTransient();
		expect(activeFilters.appLevel).toHaveLength(1);
		expect(activeFilters.all.some((f) => f.source.kind === 'app')).toBe(true);
	});

	test("clearApp drops the selection (the pill's 'All' entry)", () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		activeFilters.clearApp('app-context-pill');
		expect(activeFilters.appLevel).toHaveLength(0);
	});

	test('a tile click on the same target outranks the app filter', () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		activeFilters.pushClick(filter(['[Geography].[Geography].[South]']), 'tile-a');
		const winner = activeFilters.all.find(
			(f) => targetKey(f.filter) === 'Product/Products/Product Family'
		);
		expect(winner?.filter.members).toEqual(['[Geography].[Geography].[South]']);
	});

	test('clearChip removes an app filter by id', () => {
		activeFilters.pushApp(filter(['[Geography].[Geography].[West]']), 'app-context-pill');
		activeFilters.clearChip(activeFilters.appLevel[0].id);
		expect(activeFilters.appLevel).toHaveLength(0);
	});
});

/* ====================================================================
 * saiku#1803 — the panel → active projection must carry the WHOLE target.
 *
 * It used to name dimension/hierarchy/level/members explicitly, which meant
 * every field added to DashboardFilter afterwards was dropped on the way to
 * the tile. Nothing caught it: these tests build ActiveFilters directly, and
 * the projection lived inline in a $derived. What it broke was only visible on
 * a running page — a semantic filter narrowed the cube tiles and left the
 * semantic-model tiles showing everything.
 * ==================================================================== */
describe('panelFilterToActive — saiku#1803', () => {
	const cube = { connectionName: 'c', catalog: 'cat', schema: 's', cubeName: 'Store' };
	const model = {
		kind: 'ossie' as const,
		connectionName: 'unknown_Flights',
		modelName: 'Flights',
		catalog: 'Flights',
		schema: 'Flights',
		cubeName: 'Flights'
	};

	const panelFilter = () => ({
		id: 'f-state',
		widget: 'single-select' as const,
		cube,
		label: 'State',
		dimension: 'Store',
		hierarchy: 'Stores',
		level: 'Store State',
		members: ['[Store].[Stores].[USA].[CA]'],
		captions: ['CA'],
		bindings: [{ kind: 'ossie' as const, cube: model, dataset: 'airport', field: 'airport_state' }]
	});

	test('carries the semantic mapping fields through to the tile', () => {
		const a = panelFilterToActive(panelFilter());
		expect(a.filter.label).toBe('State');
		expect(a.filter.captions).toEqual(['CA']);
		expect(a.filter.bindings).toHaveLength(1);
		expect(a.filter.bindings?.[0]).toMatchObject({ dataset: 'airport', field: 'airport_state' });
	});

	test('still carries the classic target', () => {
		const a = panelFilterToActive(panelFilter());
		expect(a.filter.dimension).toBe('Store');
		expect(a.filter.hierarchy).toBe('Stores');
		expect(a.filter.level).toBe('Store State');
		expect(a.filter.members).toEqual(['[Store].[Stores].[USA].[CA]']);
	});

	test('strips the fields that belong to the panel row, not the target', () => {
		const a = panelFilterToActive(panelFilter()) as unknown as {
			filter: Record<string, unknown>;
		};
		for (const k of ['id', 'widget', 'cube', 'cascading', 'topN']) {
			expect(a.filter[k], k).toBeUndefined();
		}
	});

	test('tags the source with the panel filter id', () => {
		const a = panelFilterToActive(panelFilter());
		expect(a.id).toBe('panel-f-state');
		expect(a.source).toEqual({ kind: 'panel', filterId: 'f-state' });
	});

	test('defaults a missing members list to empty', () => {
		const { members: _m, ...noMembers } = panelFilter();
		expect(panelFilterToActive(noMembers as never).filter.members).toEqual([]);
	});
});
