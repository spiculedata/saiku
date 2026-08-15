import type { Component } from 'svelte';
import { describe, expect, it } from 'vitest';
import {
	getTileRenderer,
	listTileRenderers,
	registerTileRenderer,
	type TileRenderer
} from './tileRegistry';

/** A minimal fake renderer. The component is never mounted in these unit
 *  tests, so a dummy cast is fine. `id` is caller-supplied so each test can
 *  use ids unique to itself — the registry is module-global, and tests must
 *  not bleed into one another. */
function fakeRenderer(id: string, overrides: Partial<TileRenderer> = {}): TileRenderer {
	return {
		id,
		label: `Fake ${id}`,
		component: {} as unknown as Component,
		isQueryable: false,
		validateOptions: (o) => ({ ok: true, value: (o ?? {}) as Record<string, unknown> }),
		...overrides
	};
}

describe('tileRegistry', () => {
	it('register then get returns the registered renderer', () => {
		const r = fakeRenderer('test-basic-1');
		registerTileRenderer(r);
		expect(getTileRenderer('test-basic-1')).toBe(r);
	});

	it('get returns undefined for an unknown id', () => {
		expect(getTileRenderer('test-unknown-does-not-exist')).toBeUndefined();
	});

	it('list returns all registered renderers and reflects later registrations', () => {
		const a = fakeRenderer('test-list-a');
		registerTileRenderer(a);
		const afterA = listTileRenderers();
		expect(afterA).toContain(a);

		const b = fakeRenderer('test-list-b');
		registerTileRenderer(b);
		const afterB = listTileRenderers();
		expect(afterB).toContain(a);
		expect(afterB).toContain(b);
		expect(afterB.length).toBe(afterA.length + 1);
	});

	it('register replaces an existing renderer under the same id', () => {
		const first = fakeRenderer('test-replace');
		const second = fakeRenderer('test-replace', { label: 'Replaced' });
		registerTileRenderer(first);
		registerTileRenderer(second);
		expect(getTileRenderer('test-replace')).toBe(second);
	});

	it('validateOptions rejects bad options with {ok:false,error}', () => {
		const r = fakeRenderer('test-validate-reject', {
			validateOptions: (o) => {
				if (typeof o !== 'object' || o === null || !('threshold' in o)) {
					return { ok: false, error: 'threshold is required' };
				}
				return { ok: true, value: o as Record<string, unknown> };
			}
		});
		registerTileRenderer(r);
		const result = getTileRenderer('test-validate-reject')!.validateOptions({ nope: 1 });
		expect(result).toEqual({ ok: false, error: 'threshold is required' });
	});

	it('validateOptions accepts good options with {ok:true,value}', () => {
		const r = fakeRenderer('test-validate-accept', {
			validateOptions: (o) => {
				if (typeof o !== 'object' || o === null || !('threshold' in o)) {
					return { ok: false, error: 'threshold is required' };
				}
				return { ok: true, value: o as Record<string, unknown> };
			}
		});
		registerTileRenderer(r);
		const good = { threshold: 42 };
		const result = getTileRenderer('test-validate-accept')!.validateOptions(good);
		expect(result).toEqual({ ok: true, value: good });
	});
});
