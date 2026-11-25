/*
 * Tests for the Ossie YAML completion decision logic (#1428 Model IDE).
 *
 * Context detection is exercised against the two envelope shapes the real seeds use, and
 * value completion is exercised against a live-model catalogue the way the page feeds it
 * (dataset/field names from the discover endpoint).
 */
import { describe, expect, test } from 'vitest';
import { completeOssieYaml, enclosingContext, type OssieCatalogue } from './ossie-yaml-completions';

const CATALOGUE: OssieCatalogue = {
	datasets: ['flight', 'carrier'],
	datasetFields: {
		flight: ['flight_id', 'tail_num', 'dep_delay_min'],
		carrier: ['carrier_id', 'carrier_name']
	},
	metrics: ['flight_count']
};

function completeAtDoc(doc: string, lineIndex: number, linePrefix: string) {
	return completeOssieYaml(doc.split('\n'), lineIndex, linePrefix, CATALOGUE);
}

const FLIGHTS = [
	'version: 0.2.0.dev0',
	'semantic_model:',
	'- name: Flights',
	'  datasets:',
	'  - name: flight',
	'    fields:',
	'    - name: flight_id',
	'  relationships:',
	'  - name: r',
	'    from: '
];

describe('key completion by context', () => {
	test('root context offers the top-level envelope keys', () => {
		const keys = completeAtDoc('version: 0.2.0.dev0\n', 1, '').map((c) => c.label);
		expect(keys).toContain('semantic_model');
		expect(keys).toContain('ontology_mappings');
	});

	test('model context offers datasets/metrics/relationships', () => {
		const doc = 'semantic_model:\n- name: Flights\n  description: demo\n  \n';
		const keys = completeAtDoc(doc, 3, '  ').map((c) => c.label);
		expect(keys).toEqual(
			expect.arrayContaining(['datasets', 'metrics', 'relationships', 'ai_context'])
		);
	});

	test('dataset context offers the dataset keys', () => {
		const doc = 'semantic_model:\n- name: Flights\n  datasets:\n  - name: flight\n    \n';
		const keys = completeAtDoc(doc, 4, '    ').map((c) => c.label);
		expect(keys).toEqual(expect.arrayContaining(['source', 'primary_key', 'fields']));
	});

	test('field context offers the field keys', () => {
		const doc =
			'semantic_model:\n- name: Flights\n  datasets:\n  - name: flight\n    fields:\n    - name: flight_id\n      \n';
		const keys = completeAtDoc(doc, 6, '      ').map((c) => c.label);
		expect(keys).toEqual(expect.arrayContaining(['label', 'expression']));
	});

	test('relationship context offers the relationship keys', () => {
		const doc =
			'semantic_model:\n- name: Flights\n  datasets:\n  - name: flight\n    fields:\n    - name: id\n  relationships:\n  - name: r\n    \n';
		const keys = completeAtDoc(doc, 8, '    ').map((c) => c.label);
		expect(keys).toEqual(expect.arrayContaining(['from', 'to', 'from_columns']));
	});

	test('works through the ontology_mappings envelope', () => {
		const doc = 'ontology_mappings:\n- name: m\n  semantic_model:\n    name: Flights\n    \n';
		const keys = completeAtDoc(doc, 4, '    ').map((c) => c.label);
		expect(keys).toEqual(expect.arrayContaining(['datasets', 'metrics', 'relationships']));
	});
});

describe('value completion', () => {
	test('a key with an empty value asks for value completion, not keys', () => {
		const out = completeAtDoc(FLIGHTS.join('\n'), 9, '    from: ');
		expect(out.map((c) => c.label)).toEqual(expect.arrayContaining(['flight', 'carrier']));
		expect(out.every((c) => c.kind === 'value')).toBe(true);
	});

	test('dialect values offer ANSI_SQL', () => {
		const out = completeAtDoc('expression:\n  dialects:\n  - dialect: \n', 2, '  - dialect: ');
		expect(out.map((c) => c.label)).toContain('ANSI_SQL');
	});

	test('aggregation_kind values offer the aggregation vocabulary', () => {
		const out = completeAtDoc(
			'semantic_model:\n- name: X\n  metrics:\n  - name: m\n    aggregation_kind: \n',
			4,
			'    aggregation_kind: '
		);
		const labels = out.map((c) => c.label);
		expect(labels).toEqual(expect.arrayContaining(['sum', 'count', 'avg', 'count_distinct']));
	});

	test('field name completion offers the live model fields for the enclosing dataset', () => {
		const doc = FLIGHTS.slice(0, 6).join('\n') + '\n    - name: ';
		const out = completeAtDoc(doc, 6, '    - name: ');
		expect(out.map((c) => c.label)).toEqual(CATALOGUE.datasetFields['flight']);
	});

	test('field name completion outside a dataset stays empty (no bogus value list)', () => {
		const out = completeAtDoc('version: 0.2.0.dev0\nname: ', 1, 'name: ');
		expect(out).toEqual([]);
	});

	test('from/to completion outside a relationship stays empty', () => {
		const out = completeAtDoc('semantic_model:\n- name: X\n  from: ', 2, '  from: ');
		expect(out).toEqual([]);
	});
});

describe('enclosingContext stack', () => {
	test('tracks the dataset name seen above the cursor', () => {
		const { dataset } = enclosingContext(FLIGHTS, 6);
		expect(dataset).toBe('flight');
	});

	test('pops frames when a sibling key appears at a shallower indent', () => {
		const doc =
			'semantic_model:\n- name: A\n  datasets:\n  - name: a\n    fields:\n    - name: x\n  metrics:\n  ';
		const { stack } = enclosingContext(doc.split('\n'), 7);
		expect(stack.map((f) => f.key)).toEqual(['semantic_model', 'metrics']);
	});
});
