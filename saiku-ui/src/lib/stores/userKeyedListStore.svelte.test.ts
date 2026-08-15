/*
 * Unit tests for the generic createUserKeyedListStore (#1162).
 *
 * Mirrors the favourites / recents harness — mocks session.current and
 * installs an in-memory localStorage shim before importing the store —
 * and exercises the generic abstraction directly across its two
 * configurations (uncapped set-toggle vs capped dedupe-to-front list),
 * covering cap behaviour, dedupe, and per-user isolation.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

let currentUsername: string | null = 'alice';

vi.mock('$lib/stores/session.svelte', () => ({
	session: {
		get current() {
			return currentUsername == null ? null : { username: currentUsername };
		}
	}
}));

function installFakeLocalStorage(): void {
	const store = new Map<string, string>();
	const fakeStorage: Storage = {
		getItem: (k) => store.get(k) ?? null,
		setItem: (k, v) => {
			store.set(k, v);
		},
		removeItem: (k) => {
			store.delete(k);
		},
		clear: () => store.clear(),
		get length() {
			return store.size;
		},
		key: (i) => Array.from(store.keys())[i] ?? null
	};
	vi.stubGlobal('window', { localStorage: fakeStorage });
}

let createUserKeyedListStore: typeof import('./userKeyedListStore.svelte').createUserKeyedListStore;

beforeEach(async () => {
	installFakeLocalStorage();
	currentUsername = 'alice';
	vi.resetModules();
	const mod = await import('./userKeyedListStore.svelte');
	createUserKeyedListStore = mod.createUserKeyedListStore;
});

describe('createUserKeyedListStore — cap behaviour', () => {
	it('trims to cap on addFront, keeping most-recent first', () => {
		const s = createUserKeyedListStore({ prefix: 't:cap:', cap: 3, dedupe: true });
		for (let i = 0; i < 6; i++) s.addFront(`item-${i}`);
		expect(s.all()).toEqual(['item-5', 'item-4', 'item-3']);
	});

	it('an uncapped store keeps every entry', () => {
		const s = createUserKeyedListStore({ prefix: 't:uncapped:' });
		for (let i = 0; i < 25; i++) s.toggle(`item-${i}`);
		expect(s.all()).toHaveLength(25);
	});

	it('clamps an over-length stored blob on read', () => {
		const key = 't:clamp:alice';
		window.localStorage.setItem(key, JSON.stringify(['a', 'b', 'c', 'd', 'e']));
		const s = createUserKeyedListStore({ prefix: 't:clamp:', cap: 2 });
		expect(s.all()).toEqual(['a', 'b']);
	});
});

describe('createUserKeyedListStore — dedupe', () => {
	it('dedupe:true moves an existing entry to the front', () => {
		const s = createUserKeyedListStore({ prefix: 't:dedup:', dedupe: true });
		s.addFront('a');
		s.addFront('b');
		s.addFront('a');
		expect(s.all()).toEqual(['a', 'b']);
	});

	it('dedupe:false leaves an already-present entry untouched', () => {
		const s = createUserKeyedListStore({ prefix: 't:nodedup:' });
		s.addFront('a');
		s.addFront('b');
		s.addFront('a');
		expect(s.all()).toEqual(['b', 'a']);
	});

	it('toggle adds then removes (set membership)', () => {
		const s = createUserKeyedListStore({ prefix: 't:toggle:' });
		s.toggle('x');
		expect(s.has('x')).toBe(true);
		s.toggle('x');
		expect(s.has('x')).toBe(false);
		expect(s.all()).toEqual([]);
	});
});

describe('createUserKeyedListStore — dedupeOnLoad (favourites Set parity)', () => {
	it('dedupeOnLoad:true collapses duplicates from a corrupt blob, keeping first-occurrence order', () => {
		window.localStorage.setItem('t:dol:alice', JSON.stringify(['a', 'a', 'b', 'a', 'c']));
		const s = createUserKeyedListStore({ prefix: 't:dol:', dedupeOnLoad: true });
		expect(s.all()).toEqual(['a', 'b', 'c']);
	});

	it('default (no dedupeOnLoad) preserves duplicates from a corrupt blob', () => {
		window.localStorage.setItem('t:nodol:alice', JSON.stringify(['a', 'a', 'b']));
		const s = createUserKeyedListStore({ prefix: 't:nodol:' });
		expect(s.all()).toEqual(['a', 'a', 'b']);
	});
});

describe('createUserKeyedListStore — per-user isolation', () => {
	it("keeps each user's list under a distinct key", () => {
		const s = createUserKeyedListStore({ prefix: 't:iso:', cap: 5, dedupe: true });
		s.addFront('alice-1');
		currentUsername = 'bob';
		expect(s.all()).toEqual([]);
		s.addFront('bob-1');
		expect(s.all()).toEqual(['bob-1']);
		currentUsername = 'alice';
		expect(s.all()).toEqual(['alice-1']);
		expect(s.has('bob-1')).toBe(false);
	});

	it('no-ops every mutation when there is no current user', () => {
		const s = createUserKeyedListStore({ prefix: 't:nouser:', dedupe: true });
		currentUsername = null;
		s.addFront('a');
		s.toggle('b');
		s.remove('c');
		s.clear();
		expect(s.all()).toEqual([]);
	});

	it('ignores empty items on addFront and toggle', () => {
		const s = createUserKeyedListStore({ prefix: 't:empty:' });
		s.addFront('');
		s.toggle('');
		expect(s.all()).toEqual([]);
	});
});
