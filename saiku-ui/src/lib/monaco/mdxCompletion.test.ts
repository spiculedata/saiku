import { describe, expect, it } from 'vitest';
import { buildMdxCompletions, type MdxCompletionContext } from './mdxCompletion';
import type { SaikuDimension, SaikuMeasure } from '$lib/api/discover';

const measures: SaikuMeasure[] = [
	{ name: 'Sales', caption: 'Sales', uniqueName: '[Measures].[Sales]' },
	{ name: 'Profit', caption: 'Profit', uniqueName: '[Measures].[Profit]' }
];

const dimensions: SaikuDimension[] = [
	{
		name: 'Time',
		caption: 'Time',
		uniqueName: '[Time]',
		hierarchies: [
			{
				name: 'Time',
				caption: 'Time',
				uniqueName: '[Time].[Time]',
				levels: [
					{ name: 'Year', caption: 'Year', uniqueName: '[Time].[Time].[Year]' },
					{ name: 'Quarter', caption: 'Quarter', uniqueName: '[Time].[Time].[Quarter]' }
				]
			}
		]
	},
	{
		name: 'Customers',
		caption: 'Customers',
		uniqueName: '[Customers]',
		hierarchies: [
			{
				name: 'Customers',
				caption: 'Customers',
				uniqueName: '[Customers].[Customers]',
				levels: [
					{ name: 'Country', caption: 'Country', uniqueName: '[Customers].[Customers].[Country]' }
				]
			}
		]
	}
];

const ctx: MdxCompletionContext = { measures, dimensions };

describe('buildMdxCompletions', () => {
	it('returns nothing for plain text with no bracket/dot context', () => {
		expect(buildMdxCompletions('SELECT ', ctx)).toEqual({ replacePrefixLength: 0, candidates: [] });
	});

	it('offers Measures + top-level dimensions right after a fresh [', () => {
		const result = buildMdxCompletions('SELECT [', ctx);
		expect(result.replacePrefixLength).toBe(0);
		const labels = result.candidates.map((c) => c.label);
		expect(labels).toEqual(['Measures', 'Time', 'Customers']);
		expect(result.candidates[0].insertText).toBe('Measures]');
	});

	it('filters top-level candidates by the partial bracket text', () => {
		const result = buildMdxCompletions('SELECT [Cu', ctx);
		expect(result.replacePrefixLength).toBe(2);
		expect(result.candidates.map((c) => c.label)).toEqual(['Customers']);
		expect(result.candidates[0].insertText).toBe('Customers]');
	});

	it('lists measures after [Measures].', () => {
		const result = buildMdxCompletions('SELECT {[Measures].', ctx);
		expect(result.replacePrefixLength).toBe(0);
		expect(result.candidates).toEqual([
			{ kind: 'measure', label: 'Sales', detail: 'Sales', insertText: '[Sales]' },
			{ kind: 'measure', label: 'Profit', detail: 'Profit', insertText: '[Profit]' }
		]);
	});

	it('lists measures after [Measures].[ (bracket already opened)', () => {
		const result = buildMdxCompletions('SELECT {[Measures].[', ctx);
		expect(result.replacePrefixLength).toBe(0);
		expect(result.candidates.map((c) => c.insertText)).toEqual(['Sales]', 'Profit]']);
	});

	it('filters measures by bare text typed after the dot (no bracket yet)', () => {
		const result = buildMdxCompletions('SELECT {[Measures].Pro', ctx);
		expect(result.replacePrefixLength).toBe(3);
		expect(result.candidates).toEqual([
			{ kind: 'measure', label: 'Profit', detail: 'Profit', insertText: '[Profit]' }
		]);
	});

	it('is case-insensitive on the Measures segment', () => {
		const result = buildMdxCompletions('SELECT {[measures].', ctx);
		expect(result.candidates.map((c) => c.label)).toEqual(['Sales', 'Profit']);
	});

	it('lists hierarchies after a dimension name', () => {
		const result = buildMdxCompletions('SELECT [Time].', ctx);
		expect(result.candidates).toEqual([
			{ kind: 'hierarchy', label: 'Time', detail: 'Time', insertText: '[Time]' }
		]);
	});

	it('lists levels after dimension.hierarchy', () => {
		const result = buildMdxCompletions('SELECT [Time].[Time].', ctx);
		expect(result.candidates).toEqual([
			{ kind: 'level', label: 'Year', detail: 'Year', insertText: '[Year]' },
			{ kind: 'level', label: 'Quarter', detail: 'Quarter', insertText: '[Quarter]' }
		]);
	});

	it('filters levels by partial text mid-bracket', () => {
		const result = buildMdxCompletions('SELECT [Time].[Time].[Qu', ctx);
		expect(result.replacePrefixLength).toBe(2);
		expect(result.candidates).toEqual([
			{ kind: 'level', label: 'Quarter', detail: 'Quarter', insertText: 'Quarter]' }
		]);
	});

	it('returns nothing for an unknown dimension name', () => {
		const result = buildMdxCompletions('SELECT [NoSuchDim].', ctx);
		expect(result.candidates).toEqual([]);
	});

	it('returns nothing past dimension.hierarchy.level (member navigation out of scope)', () => {
		const result = buildMdxCompletions('SELECT [Time].[Time].[Year].', ctx);
		expect(result.candidates).toEqual([]);
	});

	it('returns an empty context gracefully (no cube selected yet)', () => {
		const result = buildMdxCompletions('SELECT [', { measures: [], dimensions: [] });
		expect(result.candidates.map((c) => c.label)).toEqual(['Measures']);
	});
});
