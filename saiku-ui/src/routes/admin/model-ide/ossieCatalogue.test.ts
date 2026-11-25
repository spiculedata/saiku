import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { catalogueFor, isStillSelected, EMPTY_OSSIE_CATALOGUE } from './ossieCatalogue';
import { mondrianKey, ossieKey, type IdeSelection } from './bufferKey';

const catA = { datasets: ['orders'], datasetFields: { orders: ['email'] }, metrics: ['revenue'] };
const ossie = (name: string): IdeSelection => ({ kind: 'ossie', datasource: { name } });

describe('catalogueFor (PR #2490 review: completion must not offer another datasource)', () => {
	it('returns the catalogue only when it mirrors the open Ossie file', () => {
		expect(catalogueFor(ossie('A'), 'A', catA)).toBe(catA);
	});

	it('A → B → A: with the catalogue still holding B, completion in A gets nothing foreign', () => {
		// After switching back to A, until A's refresh lands the catalogue still mirrors B.
		expect(catalogueFor(ossie('A'), 'B', catA)).toBe(EMPTY_OSSIE_CATALOGUE);
	});

	it('is empty for a Mondrian selection, no selection, or an unknown catalogue', () => {
		expect(catalogueFor({ kind: 'mondrian', schema: { name: 'A' } }, 'A', catA)).toBe(
			EMPTY_OSSIE_CATALOGUE
		);
		expect(catalogueFor(null, 'A', catA)).toBe(EMPTY_OSSIE_CATALOGUE);
		expect(catalogueFor(ossie('A'), null, catA)).toBe(EMPTY_OSSIE_CATALOGUE);
	});
});

describe('isStillSelected (PR #2490 review: a late prefetch must not land on another file)', () => {
	it('accepts a response for the datasource that is still open', () => {
		expect(isStillSelected(ossie('A'), 'A')).toBe(true);
	});

	it('drops a late response for A once B is open', () => {
		expect(isStillSelected(ossie('B'), 'A')).toBe(false);
		expect(isStillSelected({ kind: 'mondrian', schema: { name: 'A' } }, 'A')).toBe(false);
		expect(isStillSelected(null, 'A')).toBe(false);
	});
});

describe('Model IDE page buffer keys (PR #2490 review: pin the write sites)', () => {
	const page = readFileSync(fileURLToPath(new URL('./+page.svelte', import.meta.url)), 'utf8');

	it('never spells a buffer/baseline key literally — every site goes through bufferKey.ts', () => {
		// A literal spelled differently from the read site opened Mondrian schemas empty and
		// dropped edits on save. Only the helpers may build `xml:` / `ossie:` keys.
		expect(page).not.toMatch(/(buffers|baselines)\[`(xml|ossie|mondrian):/);
		expect(page).toMatch(/buffers\[mondrianKey\(/);
		expect(page).toMatch(/buffers\[ossieKey\(/);
	});

	it('the helpers produce the keys bufferKeyFor reads', () => {
		expect(mondrianKey('Foodmart')).toBe('xml:Foodmart');
		expect(ossieKey('ds')).toBe('ossie:ds');
	});
});
