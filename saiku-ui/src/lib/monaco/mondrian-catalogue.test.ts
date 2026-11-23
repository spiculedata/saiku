import { describe, expect, test } from 'vitest';
import { MondrianCatalogue, extractCubeNames, findEnclosingCubeName } from './mondrian-catalogue';
import type { AiCubeDetail, AiCubeSummary } from '$lib/api/aiCubes';

describe('MondrianCatalogue', () => {
	test('setSummaries seeds cube names with empty measure/dimension lists', () => {
		const cat = new MondrianCatalogue();
		const summaries: AiCubeSummary[] = [
			{
				connectionName: 'foodmart',
				catalog: 'FoodMart',
				schema: 'FoodMart',
				cubeName: 'Sales',
				measureCount: 3
			}
		];
		cat.setSummaries(summaries);
		expect(cat.cubeNames()).toEqual(['Sales']);
		expect(cat.get('Sales')).toMatchObject({ cubeName: 'Sales', measures: [], dimensions: [] });
	});

	test('setDetail fills in measures, dimensions and flattened levels for a known cube', () => {
		const cat = new MondrianCatalogue();
		cat.setSummaries([
			{
				connectionName: 'foodmart',
				catalog: 'FoodMart',
				schema: 'FoodMart',
				cubeName: 'Sales',
				measureCount: 1
			}
		]);
		const detail: AiCubeDetail = {
			measures: { 'Unit Sales': {} },
			dimensions: {
				Time: {
					hierarchies: {
						Time: { levels: { Year: {}, Quarter: {} } }
					}
				}
			}
		};
		cat.setDetail('Sales', detail);
		const entry = cat.get('Sales');
		expect(entry?.measures).toEqual(['Unit Sales']);
		expect(entry?.dimensions).toEqual(['Time']);
		expect(entry?.levels).toEqual(['Year', 'Quarter']);
	});

	test('setDetail on a cube not seen in setSummaries is a no-op (no live entry to attach to)', () => {
		const cat = new MondrianCatalogue();
		cat.setDetail('Unknown', { measures: {}, dimensions: {} });
		expect(cat.get('Unknown')).toBeUndefined();
	});

	test('isEmpty reflects whether any summaries were loaded', () => {
		const cat = new MondrianCatalogue();
		expect(cat.isEmpty()).toBe(true);
		cat.setSummaries([
			{ connectionName: 'c', catalog: 'c', schema: 's', cubeName: 'X', measureCount: 0 }
		]);
		expect(cat.isEmpty()).toBe(false);
	});
});

describe('findEnclosingCubeName', () => {
	test('returns null outside any Cube block', () => {
		const text = '<Schema name="S">\n</Schema>';
		expect(findEnclosingCubeName(text, 19)).toBeNull();
	});

	test('finds the enclosing cube name for a cursor inside its block', () => {
		const text = '<Schema name="S"><Cube name="Sales"><Measure ';
		expect(findEnclosingCubeName(text, text.length)).toBe('Sales');
	});

	test('returns null again once the cube has been closed', () => {
		const text = '<Schema name="S"><Cube name="Sales"></Cube>\n  ';
		expect(findEnclosingCubeName(text, text.length)).toBeNull();
	});

	test('a self-closing <Cube/> never becomes the enclosing element', () => {
		const text = '<Schema name="S"><Cube name="Empty"/>\n  ';
		expect(findEnclosingCubeName(text, text.length)).toBeNull();
	});

	test('resolves the correct cube among two sibling cubes', () => {
		const text = '<Schema name="S"><Cube name="A"></Cube><Cube name="B">';
		expect(findEnclosingCubeName(text, text.length)).toBe('B');
	});

	test('a Cube tag with no name attribute yields null, not a thrown error', () => {
		const text = '<Schema name="S"><Cube>';
		expect(findEnclosingCubeName(text, text.length)).toBeNull();
	});
});

describe('extractCubeNames', () => {
	test('collects every named cube in document order', () => {
		const text = '<Schema name="S"><Cube name="A"></Cube><Cube name="B"/></Schema>';
		expect(extractCubeNames(text)).toEqual(['A', 'B']);
	});

	test('de-duplicates a repeated name', () => {
		const text = '<Schema name="S"><Cube name="A"></Cube><Cube name="A"/></Schema>';
		expect(extractCubeNames(text)).toEqual(['A']);
	});

	test('skips a Cube tag with no name attribute', () => {
		const text = '<Schema name="S"><Cube></Cube></Schema>';
		expect(extractCubeNames(text)).toEqual([]);
	});

	test('empty document yields no cubes', () => {
		expect(extractCubeNames('')).toEqual([]);
	});
});
