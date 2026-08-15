import { describe, it, test, expect } from 'vitest';
import {
	computeFilterAffinity,
	isFilterableTile,
	type AffinityTile,
	type FilterTarget
} from '$lib/dashboard/filterAffinity';
import type { SchemaLike } from '$lib/dashboard/effectiveQuery';
import type { CubeRef } from '$lib/api/dashboards';

/* ---- fixtures ---- */

const salesCube: CubeRef = {
	connectionName: 'foodmart',
	catalog: 'FoodMart',
	schema: 'FoodMart',
	cubeName: 'Sales'
};
const hrCube: CubeRef = { ...salesCube, cubeName: 'HR' };

// Sales resolves Product → Product → Product Subcategory; HR does not.
const salesSchema: SchemaLike = {
	dimensions: {
		product: {
			name: 'Product',
			hierarchies: {
				product: {
					name: 'Product',
					levels: { 'product subcategory': { name: 'Product Subcategory' } }
				}
			}
		}
	}
};
const hrSchema: SchemaLike = {
	dimensions: {
		employees: {
			name: 'Employees',
			hierarchies: {
				employees: { name: 'Employees', levels: { department: { name: 'Department' } } }
			}
		}
	}
};

function cubeKey(c: CubeRef): string {
	return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
}

/** A schema resolver backed by a fixed map — mirrors schemaCache.peek. */
function schemaResolver(map: Record<string, SchemaLike>) {
	return (cube: CubeRef): SchemaLike | null => map[cubeKey(cube)] ?? null;
}

const inline = { kind: 'inline' as const, body: {} };

const productTarget: FilterTarget = {
	dimension: 'Product',
	hierarchy: 'Product',
	level: 'Product Subcategory'
};

describe('isFilterableTile', () => {
	it('is true for chart/table tiles with a query', () => {
		expect(isFilterableTile({ type: 'chart', query: inline })).toBe(true);
		expect(isFilterableTile({ type: 'table', query: inline })).toBe(true);
	});
	it('is true for a KPI tile with a measure (it slices its cube via active filters)', () => {
		expect(
			isFilterableTile({
				type: 'kpi',
				query: undefined,
				kpi: { measure: '[Measures].[Store Sales]' }
			})
		).toBe(true);
	});
	it('is false for chart/table without a query, a measure-less KPI, and text/image', () => {
		expect(isFilterableTile({ type: 'chart', query: undefined })).toBe(false);
		expect(isFilterableTile({ type: 'text', query: undefined })).toBe(false);
		expect(isFilterableTile({ type: 'image', query: undefined })).toBe(false);
		expect(isFilterableTile({ type: 'kpi', query: undefined, kpi: undefined })).toBe(false);
	});
});

describe('computeFilterAffinity', () => {
	const resolve = schemaResolver({
		[cubeKey(salesCube)]: salesSchema,
		[cubeKey(hrCube)]: hrSchema
	});

	it('counts a tile as affected when the filter resolves in its cube schema', () => {
		const tiles: AffinityTile[] = [{ id: 'a', type: 'chart', cube: salesCube, query: inline }];
		const r = computeFilterAffinity(productTarget, tiles, resolve);
		expect([...r.affected]).toEqual(['a']);
		expect(r.affectedCount).toBe(1);
		expect(r.totalCount).toBe(1);
	});

	it("does not affect a tile whose cube schema can't resolve the target (different cube)", () => {
		const tiles: AffinityTile[] = [{ id: 'hr', type: 'table', cube: hrCube, query: inline }];
		const r = computeFilterAffinity(productTarget, tiles, resolve);
		expect(r.affected.has('hr')).toBe(false);
		expect(r.affectedCount).toBe(0);
		expect(r.totalCount).toBe(1);
	});

	it('partitions a mixed board and reports N of M against ALL tiles', () => {
		const tiles: AffinityTile[] = [
			{ id: 'chart-sales', type: 'chart', cube: salesCube, query: inline }, // hit
			{ id: 'table-sales', type: 'table', cube: salesCube, query: inline }, // hit
			{
				id: 'kpi-sales',
				type: 'kpi',
				cube: salesCube,
				kpi: { measure: '[Measures].[Store Sales]' }
			}, // hit (KPI slices its cube)
			{ id: 'table-hr', type: 'table', cube: hrCube, query: inline }, // miss (cube)
			{ id: 'text', type: 'text', query: undefined }, // miss (not filterable)
			{ id: 'kpi-blank', type: 'kpi', cube: salesCube, kpi: undefined } // miss (no measure)
		];
		const r = computeFilterAffinity(productTarget, tiles, resolve);
		expect([...r.affected].sort()).toEqual(['chart-sales', 'kpi-sales', 'table-sales']);
		expect(r.affectedCount).toBe(3);
		expect(r.totalCount).toBe(6);
	});

	it('treats a tile with no cube as not-affected', () => {
		const tiles: AffinityTile[] = [{ id: 'nocube', type: 'chart', query: inline }];
		const r = computeFilterAffinity(productTarget, tiles, resolve);
		expect(r.affectedCount).toBe(0);
		expect(r.totalCount).toBe(1);
	});

	it('treats an unloaded schema as not-affected (conservative hint)', () => {
		const tiles: AffinityTile[] = [{ id: 'a', type: 'chart', cube: salesCube, query: inline }];
		const r = computeFilterAffinity(productTarget, tiles, () => null);
		expect(r.affectedCount).toBe(0);
		expect(r.totalCount).toBe(1);
	});

	it('is alias-aware (synonym dimension resolves to canonical)', () => {
		const aliased: SchemaLike = { ...salesSchema, dimensionAliases: { merchandise: 'product' } };
		const resolveAlias = schemaResolver({ [cubeKey(salesCube)]: aliased });
		const tiles: AffinityTile[] = [{ id: 'a', type: 'chart', cube: salesCube, query: inline }];
		const r = computeFilterAffinity(
			{ dimension: 'Merchandise', hierarchy: 'Product', level: 'Product Subcategory' },
			tiles,
			resolveAlias
		);
		expect(r.affected.has('a')).toBe(true);
	});

	it('reports 0 of 0 for an empty dashboard', () => {
		const r = computeFilterAffinity(productTarget, [], resolve);
		expect(r.affectedCount).toBe(0);
		expect(r.totalCount).toBe(0);
	});
});

/* ====================================================================
 * saiku#1821 — the "affects N of M" count must include model-backed tiles.
 *
 * The check resolved the target's dim/hier/level against the tile's MDX
 * schema, which a semantic-model tile does not have. Once a filter could
 * genuinely reach a model through a binding, the badge started under-counting:
 * a filter narrowing three cube tiles and three model tiles read
 * "Affects 3 of 7", and the three it disowned moved anyway.
 * ==================================================================== */
describe('computeFilterAffinity — semantic bindings (saiku#1821)', () => {
	const cube: CubeRef = {
		connectionName: 'c',
		catalog: 'FoodMart',
		schema: 'FoodMart',
		cubeName: 'Store'
	};
	const model: CubeRef = {
		kind: 'ossie',
		connectionName: 'unknown_Flights',
		modelName: 'Flights',
		catalog: 'Flights',
		schema: 'Flights',
		cubeName: 'Flights'
	};

	const schema = {
		dimensions: {
			store: {
				name: 'Store',
				hierarchies: {
					stores: { name: 'Stores', levels: { 'store state': { name: 'Store State' } } }
				}
			}
		}
	} as unknown as SchemaLike;

	const tiles: AffinityTile[] = [
		{ id: 'cube-chart', type: 'chart', cube, query: { kind: 'inline', body: {} } },
		{ id: 'model-chart', type: 'chart', cube: model, query: { kind: 'inline', body: {} } },
		{ id: 'model-kpi', type: 'kpi', cube: model, kpi: { measure: 'flight_count' } },
		{ id: 'text', type: 'text' }
	];

	const target = {
		dimension: 'Store',
		hierarchy: 'Stores',
		level: 'Store State',
		bindings: [
			{ kind: 'ossie' as const, cube: model, dataset: 'airport', field: 'airport_state' },
			{ kind: 'mdx' as const, cube, dimension: 'Store', hierarchy: 'Stores', level: 'Store State' }
		]
	};

	test('counts the model tiles the filter reaches through its binding', () => {
		const a = computeFilterAffinity(target, tiles, () => schema);
		expect(a.affected.has('model-chart')).toBe(true);
		expect(a.affected.has('model-kpi')).toBe(true);
		expect(a.affectedCount).toBe(3);
		expect(a.totalCount).toBe(4);
	});

	test('still counts the cube tile via schema resolution', () => {
		expect(computeFilterAffinity(target, tiles, () => schema).affected.has('cube-chart')).toBe(
			true
		);
	});

	test('a model tile the filter has no binding for is NOT counted', () => {
		const other: CubeRef = { ...model, modelName: 'TPCDS', cubeName: 'TPCDS' };
		const a = computeFilterAffinity(
			target,
			[{ id: 'other', type: 'chart', cube: other, query: { kind: 'inline', body: {} } }],
			() => schema
		);
		expect(a.affectedCount).toBe(0);
	});

	test('a model tile is judged without needing a schema at all', () => {
		// schemaFor returns null for a model — the old path bailed there.
		const a = computeFilterAffinity(target, tiles, () => null);
		expect(a.affected.has('model-chart')).toBe(true);
		expect(a.affected.has('cube-chart')).toBe(false);
	});

	test('text tiles never count', () => {
		expect(computeFilterAffinity(target, tiles, () => schema).affected.has('text')).toBe(false);
	});
});
