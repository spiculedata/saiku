/*
 * saiku#1792 — the Pages inspector gained a remove control, and it warns with the
 * number of tiles the removal discards. That count comes out of AppPage.grid,
 * which the app schema keeps opaque, so it's read structurally: these tests pin
 * that the read is total (no input shape throws) rather than pinning the grid
 * schema, which belongs to $lib/api/dashboards.
 */
import { describe, expect, test } from 'vitest';
import { pageTileCount, tilesPhrase } from '$lib/views/app/pageTiles';

describe('pageTileCount', () => {
	test('counts the tiles on a well-formed grid', () => {
		expect(pageTileCount({ cols: 12, tiles: [{ id: 'a' }, { id: 'b' }, { id: 'c' }] })).toBe(3);
	});

	test('an empty page reports zero', () => {
		expect(pageTileCount({ cols: 12, tiles: [] })).toBe(0);
	});

	for (const [label, grid] of [
		['null', null],
		['undefined', undefined],
		['a bare string', 'not a grid'],
		['a number', 7],
		['an array', [{ id: 'a' }]],
		['an object with no tiles key', { cols: 12 }],
		["tiles that isn't an array", { tiles: { a: 1 } }]
	] as const) {
		test(`reports 0 for ${label} instead of throwing`, () => {
			expect(() => pageTileCount(grid)).not.toThrow();
			expect(pageTileCount(grid)).toBe(0);
		});
	}
});

describe('tilesPhrase', () => {
	test('singular and plural read naturally', () => {
		expect(tilesPhrase(1)).toBe('1 tile');
		expect(tilesPhrase(4)).toBe('4 tiles');
	});

	test('an empty page has no phrase, so the confirm can drop the clause', () => {
		expect(tilesPhrase(0)).toBe('');
		expect(tilesPhrase(-1)).toBe('');
	});
});
