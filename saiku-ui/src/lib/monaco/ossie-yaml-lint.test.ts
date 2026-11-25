/*
 * Tests for the Ossie semantic-model YAML structural lint (#1428 Model IDE).
 *
 * The fixtures mirror the two envelope shapes the real seeds use (`flights.ossie.yaml`'s
 * `ontology_mappings[].semantic_model` and `tpcds.ossie.yaml`'s bare `semantic_model` list),
 * plus the single mistakes the linter exists to catch before Save does.
 */
import { describe, expect, test } from 'vitest';
import { lintOssieYaml } from './ossie-yaml-lint';

const VALID_ONTOLOGY = `
version: 0.2.0.dev0
ontology_mappings:
- name: flights_map
  semantic_model:
    name: Flights
    datasets:
    - name: flight
      source: FLIGHT
      primary_key: [FLIGHT_ID]
      fields:
      - name: flight_id
        expression: {dialects: [{dialect: ANSI_SQL, expression: FLIGHT_ID}]}
      - name: tail_num
        expression: {dialects: [{dialect: ANSI_SQL, expression: TAIL_NUM}]}
    - name: carrier
      source: CARRIER
      fields:
      - name: carrier_id
        expression: {dialects: [{dialect: ANSI_SQL, expression: CARRIER_ID}]}
    metrics:
    - name: flight_count
      expression:
        dialects:
        - dialect: ANSI_SQL
          expression: COUNT(*)
      aggregation_kind: count
    relationships:
    - name: flight_to_carrier
      from: flight
      to: carrier
      from_columns: [CARRIER_ID]
      to_columns: [CARRIER_ID]
`;

describe('lintOssieYaml', () => {
	test('accepts the ontology_mappings envelope clean', () => {
		expect(lintOssieYaml(VALID_ONTOLOGY)).toEqual([]);
	});

	test('accepts the bare top-level semantic_model envelope clean', () => {
		const doc = `
semantic_model:
- name: TPCDS
  datasets:
  - name: store_sales
    source: STORE_SALES
    fields:
    - name: ss_item_sk
      expression: {dialects: [{dialect: ANSI_SQL, expression: SS_ITEM_SK}]}
`;
		expect(lintOssieYaml(doc)).toEqual([]);
	});

	test('positions a YAML syntax error', () => {
		const doc = 'semantic_model:\n- name: X\n  datasets: [1,\n  2\n';
		const issues = lintOssieYaml(doc);
		expect(issues.length).toBeGreaterThan(0);
		expect(issues[0].severity).toBe('error');
		expect(issues[0].message).toContain('YAML syntax');
		expect(issues[0].startLine).toBeGreaterThanOrEqual(1);
	});

	test('flags a document with no semantic model at all', () => {
		const issues = lintOssieYaml('something: else\n');
		expect(issues).toHaveLength(1);
		expect(issues[0].severity).toBe('error');
		expect(issues[0].message).toContain('semantic_model');
	});

	test('flags a dataset missing its name', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - source: T
    fields:
    - name: id
`;
		const issues = lintOssieYaml(doc);
		expect(issues.some((i) => i.severity === 'error' && i.message.includes('dataset'))).toBe(true);
	});

	test('flags duplicate dataset names within one model', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - name: flight
    fields:
    - name: id
  - name: flight
    fields:
    - name: id
`;
		const issues = lintOssieYaml(doc);
		expect(
			issues.some((i) => i.severity === 'error' && i.message.includes('Duplicate dataset'))
		).toBe(true);
	});

	test('flags duplicate field names within one dataset but not across datasets', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - name: a
    fields:
    - name: id
    - name: id
  - name: b
    fields:
    - name: id
`;
		const issues = lintOssieYaml(doc);
		const dupes = issues.filter((i) => i.message.includes('Duplicate field'));
		expect(dupes).toHaveLength(1);
	});

	test('flags a metric without an expression as a warning', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - name: a
    fields:
    - name: id
  metrics:
  - name: m1
    description: no expression here
`;
		const issues = lintOssieYaml(doc);
		const warn = issues.find((i) => i.severity === 'warning' && i.message.includes('m1'));
		expect(warn?.message).toContain('no expression');
	});

	test('flags a relationship pointing at an undeclared dataset', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - name: flight
    fields:
    - name: id
  relationships:
  - name: r
    from: flight
    to: ghost
`;
		const issues = lintOssieYaml(doc);
		expect(issues.some((i) => i.severity === 'warning' && i.message.includes('ghost'))).toBe(true);
	});

	test('flags a relationship missing its from/to as an error', () => {
		const doc = `
semantic_model:
- name: X
  datasets:
  - name: flight
    fields:
    - name: id
  relationships:
  - name: r
    to: flight
`;
		const issues = lintOssieYaml(doc);
		expect(issues.some((i) => i.severity === 'error' && i.message.includes('"from"'))).toBe(true);
	});

	test('warns when a model declares no datasets', () => {
		const doc = `
semantic_model:
- name: X
  datasets: []
`;
		const issues = lintOssieYaml(doc);
		expect(issues.some((i) => i.severity === 'warning' && i.message.includes('no datasets'))).toBe(
			true
		);
	});

	test('flags a semantic_model missing its name', () => {
		const doc = `
semantic_model:
- description: anonymous
  datasets:
  - name: a
    fields:
    - name: id
`;
		const issues = lintOssieYaml(doc);
		expect(
			issues.some((i) => i.severity === 'error' && i.message.includes('missing a "name"'))
		).toBe(true);
	});
});
