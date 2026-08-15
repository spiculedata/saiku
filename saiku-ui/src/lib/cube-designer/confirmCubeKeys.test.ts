/*
 * saiku#1842 — a keyed `{#each}` must not key on a value that can legitimately
 * repeat.
 *
 * The Confirm cube outline listed a measure group's columns with
 * `{#each mg.measureColumns as m (m)}` — keyed on the bare column name. A
 * measure group may expose several measures over the SAME column (a count and
 * a distinct-count on one key; a sum and an average on one amount), so the key
 * collided. Svelte throws `each_key_duplicate` DURING RENDER, which tore the
 * whole ConfirmCubePane down and propagated to root.svelte — the entire app
 * went blank and stayed blank until reload. The FoodMart-style Pharma cube hits
 * it immediately: its Pharma group lists `rxkey` twice.
 *
 * This asserts on the SOURCE because the failure is structural. A unit test
 * over the data wouldn't catch it — the data was always valid; it was the
 * template's uniqueness assumption that was wrong.
 */

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const src = readFileSync('src/lib/cube-designer/ConfirmCubePane.svelte', 'utf8');

/** Every `{#each … (key)}` in the file, as [expression, key]. */
function keyedEachBlocks(): { expr: string; key: string }[] {
	const out: { expr: string; key: string }[] = [];
	for (const m of src.matchAll(/\{#each\s+([^}]*?)\s+as\s+([^}]*?)\(([^)]*)\)\}/g)) {
		out.push({ expr: m[1].trim(), key: m[3].trim() });
	}
	return out;
}

describe('ConfirmCubePane keyed each blocks (saiku#1842)', () => {
	it('does not key the measure-column list on the bare column name', () => {
		const measureCols = keyedEachBlocks().filter((b) => /measureColumns/.test(b.expr));
		expect(measureCols.length).toBeGreaterThan(0);
		for (const b of measureCols) {
			// `(m)` — the column name alone — is the bug. Anything incorporating
			// the index is fine.
			expect(b.key).not.toMatch(/^m$/);
			expect(b.key).toMatch(/mi|index|\$\{/);
		}
	});

	it('keys every other list on something genuinely unique', () => {
		// Ids are unique by construction; a bare non-id scalar is the hazard.
		const suspicious = keyedEachBlocks().filter(
			(b) => !/\.id\b/.test(b.key) && !/\$\{/.test(b.key) && !/mi|index/.test(b.key)
		);
		expect(
			suspicious,
			`keyed on a possibly-repeating value: ${JSON.stringify(suspicious)}`
		).toEqual([]);
	});
});
