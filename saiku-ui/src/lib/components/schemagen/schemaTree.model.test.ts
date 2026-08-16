/*
 * Unit tests for the pure schema-tree model helpers.
 *
 * Exercises buildTree against a representative DraftView fixture (1 cube with
 * 1 dimension → 1 hierarchy → 1 level, 1 measure, plus 1 shared dimension),
 * plus findNode, resolveDraftNode, and the provenance badge label map.
 */

import { describe, expect, it } from 'vitest';

import type { DraftView } from '$lib/api/schemaGen';
import { buildTree, findNode, provenanceBadgeLabel, resolveDraftNode } from './schemaTree.model';

function fixture(): DraftView {
	return {
		schemaName: 'Sales',
		cubes: [
			{
				name: 'Orders',
				factTable: 'fact_orders',
				provenance: { source: 'RULE', ruleId: 'rule.cube.fact' },
				dimensions: [
					{
						name: 'Customer',
						type: 'STANDARD',
						sourceTable: 'dim_customer',
						foreignKey: 'customer_id',
						provenance: { source: 'LLM', ruleId: 'llm.dim.customer' },
						hierarchies: [
							{
								name: 'Geography',
								primaryKey: 'customer_id',
								provenance: { source: 'USER', ruleId: null },
								levels: [
									{
										name: 'Country',
										column: 'country',
										type: 'String',
										provenance: { source: 'RULE', ruleId: 'rule.level' }
									}
								]
							}
						]
					}
				],
				measures: [
					{
						name: 'Revenue',
						column: 'amount',
						aggregator: 'SUM',
						provenance: { source: 'LLM', ruleId: 'llm.measure' }
					}
				]
			}
		],
		sharedDimensions: [
			{
				name: 'Time',
				type: 'TIME',
				sourceTable: 'dim_time',
				provenance: { source: 'RULE', ruleId: 'rule.shared' },
				hierarchies: [
					{
						name: 'Calendar',
						primaryKey: 'time_id',
						provenance: null,
						levels: [
							{
								name: 'Year',
								column: 'year',
								type: 'Numeric',
								provenance: null
							}
						]
					}
				]
			}
		]
	};
}

describe('buildTree', () => {
	it('returns [] for null/undefined drafts', () => {
		expect(buildTree(null)).toEqual([]);
		expect(buildTree(undefined)).toEqual([]);
	});

	it('produces the expected shape for a populated draft', () => {
		const tree = buildTree(fixture());
		expect(tree).toHaveLength(1);
		const root = tree[0];
		expect(root.kind).toBe('schema');
		expect(root.label).toBe('Sales');

		// root has shared-dims group (because shared dims exist) + cubes group
		expect(root.children.map((c) => c.kind)).toEqual(['sharedDimsGroup', 'cubesGroup']);

		const cubesGroup = root.children[1];
		expect(cubesGroup.children).toHaveLength(1);
		const cube = cubesGroup.children[0];
		expect(cube.path).toBe('cubes/Orders');
		expect(cube.kind).toBe('cube');
		expect(cube.provenance).toBe('RULE');

		// cube has 1 dim + 1 measure
		expect(cube.children.map((c) => c.kind)).toEqual(['dimension', 'measure']);

		const dim = cube.children[0];
		expect(dim.path).toBe('cubes/Orders/dimensions/Customer');
		expect(dim.provenance).toBe('LLM');

		const hier = dim.children[0];
		expect(hier.path).toBe('cubes/Orders/dimensions/Customer/hierarchies/Geography');
		expect(hier.provenance).toBe('USER');

		const level = hier.children[0];
		expect(level.path).toBe(
			'cubes/Orders/dimensions/Customer/hierarchies/Geography/levels/Country'
		);
		expect(level.provenance).toBe('RULE');

		const measure = cube.children[1];
		expect(measure.path).toBe('cubes/Orders/measures/Revenue');
		expect(measure.provenance).toBe('LLM');

		const sharedGroup = root.children[0];
		expect(sharedGroup.children[0].path).toBe('sharedDimensions/Time');
		expect(sharedGroup.children[0].children[0].path).toBe(
			'sharedDimensions/Time/hierarchies/Calendar'
		);
		expect(sharedGroup.children[0].children[0].children[0].path).toBe(
			'sharedDimensions/Time/hierarchies/Calendar/levels/Year'
		);
	});

	it('omits the shared-dimensions group when there are none', () => {
		const draft: DraftView = { ...fixture(), sharedDimensions: [] };
		const tree = buildTree(draft);
		expect(tree[0].children.map((c) => c.kind)).toEqual(['cubesGroup']);
	});
});

describe('findNode', () => {
	const tree = buildTree(fixture());

	it('finds nodes by exact path', () => {
		const cube = findNode(tree, 'cubes/Orders');
		expect(cube?.kind).toBe('cube');
		const level = findNode(
			tree,
			'cubes/Orders/dimensions/Customer/hierarchies/Geography/levels/Country'
		);
		expect(level?.kind).toBe('level');
		expect(level?.label).toBe('Country');
	});

	it('returns null for unknown paths', () => {
		expect(findNode(tree, 'cubes/Nope')).toBeNull();
		expect(findNode(tree, '')).not.toBeNull(); // root path is ""
		expect(findNode(tree, 'totally/made/up')).toBeNull();
	});
});

describe('provenanceBadgeLabel', () => {
	it('maps known sources to lowercase tokens', () => {
		expect(provenanceBadgeLabel('RULE')).toBe('rule');
		expect(provenanceBadgeLabel('LLM')).toBe('llm');
		expect(provenanceBadgeLabel('USER')).toBe('user');
	});

	it('returns an empty string for null/unknown', () => {
		expect(provenanceBadgeLabel(null)).toBe('');
	});
});

describe('resolveDraftNode', () => {
	const draft = fixture();

	it('resolves cube, dim, hierarchy, level, measure, and shared dim paths', () => {
		expect(resolveDraftNode(draft, 'cubes/Orders')?.kind).toBe('cube');
		expect(resolveDraftNode(draft, 'cubes/Orders/dimensions/Customer')?.kind).toBe('dimension');
		expect(
			resolveDraftNode(draft, 'cubes/Orders/dimensions/Customer/hierarchies/Geography')?.kind
		).toBe('hierarchy');
		expect(
			resolveDraftNode(
				draft,
				'cubes/Orders/dimensions/Customer/hierarchies/Geography/levels/Country'
			)?.kind
		).toBe('level');
		expect(resolveDraftNode(draft, 'cubes/Orders/measures/Revenue')?.kind).toBe('measure');
		expect(resolveDraftNode(draft, 'sharedDimensions/Time')?.kind).toBe('sharedDim');
		expect(resolveDraftNode(draft, 'sharedDimensions/Time/hierarchies/Calendar')?.kind).toBe(
			'hierarchy'
		);
	});

	it('returns null for missing or nonsense paths', () => {
		expect(resolveDraftNode(draft, '')).toBeNull();
		expect(resolveDraftNode(null, 'cubes/Orders')).toBeNull();
		expect(resolveDraftNode(draft, 'cubes/Nope')).toBeNull();
		expect(resolveDraftNode(draft, 'cubes/Orders/measures/Nope')).toBeNull();
	});
});
