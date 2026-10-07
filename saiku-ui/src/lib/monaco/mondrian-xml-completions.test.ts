import { describe, expect, test } from 'vitest';
import {
	computeCompletionCandidates,
	isElementOpenContext,
	parseAttributeContext
} from './mondrian-xml-completions';
import { MondrianCatalogue } from './mondrian-catalogue';

describe('isElementOpenContext', () => {
	test('true right after a bare <', () => {
		expect(isElementOpenContext('  <')).toBe(true);
	});
	test('true while typing an element name', () => {
		expect(isElementOpenContext('  <Cu')).toBe(true);
	});
	test('false once past the element name into whitespace', () => {
		expect(isElementOpenContext('  <Cube ')).toBe(false);
	});
	test('false with no tag open at all', () => {
		expect(isElementOpenContext('hello world')).toBe(false);
	});
});

describe('parseAttributeContext', () => {
	test('null outside a tag', () => {
		expect(parseAttributeContext('hello world')).toBeNull();
	});
	test('attr name position: element + no attr, not in a value', () => {
		expect(parseAttributeContext('<Cube ')).toEqual({
			element: 'Cube',
			attr: null,
			inValue: false
		});
	});
	test('mid attribute value: element + attr name, in a value', () => {
		expect(parseAttributeContext('<Cube name="Sal')).toEqual({
			element: 'Cube',
			attr: 'name',
			inValue: true
		});
	});
	test('after a closed attribute value, back to attr-name position', () => {
		expect(parseAttributeContext('<Cube name="Sales" ')).toEqual({
			element: 'Cube',
			attr: null,
			inValue: false
		});
	});
});

function catalogueWithSales(): MondrianCatalogue {
	const cat = new MondrianCatalogue();
	cat.setSummaries([
		{
			connectionName: 'foodmart',
			catalog: 'FoodMart',
			schema: 'FoodMart',
			cubeName: 'Sales',
			measureCount: 1
		},
		{
			connectionName: 'foodmart',
			catalog: 'FoodMart',
			schema: 'FoodMart',
			cubeName: 'Inventory',
			measureCount: 1
		}
	]);
	cat.setDetail('Sales', {
		measures: { 'Unit Sales': {}, 'Store Sales': {} },
		dimensions: { Time: { hierarchies: { Time: { levels: { Year: {}, Quarter: {} } } } } }
	});
	return cat;
}

describe('computeCompletionCandidates', () => {
	test('element completion when typing "<"', () => {
		const candidates = computeCompletionCandidates({
			lineToCursor: '  <',
			fullText: '  <',
			offset: 3,
			catalogue: new MondrianCatalogue()
		});
		expect(candidates.some((c) => c.label === 'Cube' && c.kind === 'element')).toBe(true);
		expect(candidates.some((c) => c.label === 'Measure' && c.kind === 'element')).toBe(true);
	});

	test('attribute-name completion for a known element', () => {
		const candidates = computeCompletionCandidates({
			lineToCursor: '  <Measure ',
			fullText: '  <Measure ',
			offset: 11,
			catalogue: new MondrianCatalogue()
		});
		const labels = candidates.map((c) => c.label);
		expect(labels).toContain('column');
		expect(labels).toContain('aggregator');
		expect(candidates.every((c) => c.kind === 'attribute')).toBe(true);
		expect(candidates.find((c) => c.label === 'column')?.insertText).toBe('column="$1"');
	});

	test('unknown element yields no attribute suggestions', () => {
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Frobnicate ',
			fullText: '<Frobnicate ',
			offset: 12,
			catalogue: new MondrianCatalogue()
		});
		expect(candidates).toEqual([]);
	});

	test('Cube name="…" value position suggests live cube names', () => {
		const text = '<Schema name="S"><Cube name="';
		const candidates = computeCompletionCandidates({
			lineToCursor: text,
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		const labels = candidates.map((c) => c.label);
		expect(labels).toEqual(expect.arrayContaining(['Sales', 'Inventory']));
		expect(candidates.every((c) => c.kind === 'value')).toBe(true);
	});

	test('Measure name="…" inside a cube block suggests that cube\'s live measures', () => {
		const text = '<Schema name="S"><Cube name="Sales"><Measure name="';
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Measure name="',
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		const labels = candidates.map((c) => c.label);
		expect(labels).toEqual(['Unit Sales', 'Store Sales']);
	});

	test('Measure name="…" outside any cube block suggests nothing (no live scope)', () => {
		const text = '<Schema name="S"><Measure name="';
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Measure name="',
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		expect(candidates).toEqual([]);
	});

	test('Dimension name="…" inside a cube suggests that cube\'s live dimensions', () => {
		const text = '<Schema name="S"><Cube name="Sales"><Dimension name="';
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Dimension name="',
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		expect(candidates.map((c) => c.label)).toEqual(['Time']);
	});

	test('Level name="…" inside a cube suggests that cube\'s live (flattened) levels', () => {
		const text = '<Schema name="S"><Cube name="Sales"><Level name="';
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Level name="',
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		expect(candidates.map((c) => c.label)).toEqual(['Year', 'Quarter']);
	});

	test('a non-catalogue attribute (e.g. Cube caption="…") yields no value suggestions', () => {
		const text = '<Schema name="S"><Cube caption="';
		const candidates = computeCompletionCandidates({
			lineToCursor: '<Cube caption="',
			fullText: text,
			offset: text.length,
			catalogue: catalogueWithSales()
		});
		expect(candidates).toEqual([]);
	});
});
