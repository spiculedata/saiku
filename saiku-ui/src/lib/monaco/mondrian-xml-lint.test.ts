import { describe, expect, test } from 'vitest';
import { lintMondrianXml } from './mondrian-xml-lint';

describe('lintMondrianXml', () => {
	test('a well-formed schema with named elements produces no issues', () => {
		const xml = `<?xml version="1.0"?>
<Schema name="FoodMart">
  <Cube name="Sales">
    <Table name="sales_fact"/>
    <Dimension name="Time">
      <Hierarchy hasAll="true">
        <Level name="Year" column="the_year"/>
      </Hierarchy>
    </Dimension>
    <Measure name="Unit Sales" column="unit_sales" aggregator="sum"/>
  </Cube>
</Schema>`;
		expect(lintMondrianXml(xml)).toEqual([]);
	});

	test('flags a mismatched closing tag', () => {
		const xml = '<Schema name="S"><Cube name="C"></Dimension></Cube></Schema>';
		const issues = lintMondrianXml(xml);
		expect(issues.some((i) => i.severity === 'error' && /Expected <\/Cube>/.test(i.message))).toBe(
			true
		);
	});

	test('flags an unclosed tag at end of file', () => {
		const xml = '<Schema name="S"><Cube name="C">';
		const issues = lintMondrianXml(xml);
		expect(
			issues.some((i) => i.severity === 'error' && i.message === '<Cube> is never closed.')
		).toBe(true);
		expect(
			issues.some((i) => i.severity === 'error' && i.message === '<Schema> is never closed.')
		).toBe(true);
	});

	test('flags a stray closing tag with no matching open', () => {
		const xml = '<Schema name="S"></Cube></Schema>';
		const issues = lintMondrianXml(xml);
		expect(issues.some((i) => /Unexpected closing tag/.test(i.message))).toBe(true);
	});

	test('flags a Cube missing its name attribute', () => {
		const xml = '<Schema name="S"><Cube><Table name="t"/></Cube></Schema>';
		const issues = lintMondrianXml(xml);
		expect(
			issues.some(
				(i) =>
					i.severity === 'error' && i.message === '<Cube> is missing a required "name" attribute.'
			)
		).toBe(true);
	});

	test('does not flag Schema missing a name as an error (warning only)', () => {
		const xml = '<Schema><Cube name="C"/></Schema>';
		const issues = lintMondrianXml(xml);
		const schemaIssue = issues.find((i) => i.message.includes('<Schema> is missing'));
		expect(schemaIssue?.severity).toBe('warning');
	});

	test('an empty document is flagged as missing a root Schema element', () => {
		expect(lintMondrianXml('')).toEqual([
			{
				severity: 'error',
				message: 'Empty document — expected a Mondrian <Schema> root element.',
				startLine: 1,
				startColumn: 1,
				endLine: 1,
				endColumn: 1
			}
		]);
	});

	test('a non-Schema root is flagged as a warning', () => {
		const xml = '<NotASchema/>';
		const issues = lintMondrianXml(xml);
		expect(issues.some((i) => i.severity === 'warning' && /Root element/.test(i.message))).toBe(
			true
		);
	});

	test('self-closing tags do not require a matching close tag', () => {
		const xml = '<Schema name="S"><Cube name="C"><Table name="t"/></Cube></Schema>';
		expect(lintMondrianXml(xml)).toEqual([]);
	});

	test('comments and CDATA are ignored, not parsed as tags', () => {
		const xml =
			'<Schema name="S"><!-- <Cube> in a comment --><Cube name="C"><![CDATA[<Level/>]]></Cube></Schema>';
		expect(lintMondrianXml(xml)).toEqual([]);
	});

	test('reports accurate line/column for a multi-line document', () => {
		const xml = '<Schema name="S">\n  <Cube>\n  </Cube>\n</Schema>';
		const issues = lintMondrianXml(xml);
		const cubeIssue = issues.find((i) => i.message.includes('<Cube> is missing'));
		expect(cubeIssue).toMatchObject({ startLine: 2, startColumn: 3 });
	});
});
