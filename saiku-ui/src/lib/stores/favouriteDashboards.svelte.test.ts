/*
 * Unit tests for favouriteDashboards (#936).
 *
 * Mirrors recentDashboards.svelte.test.ts — mocks session.current and
 * installs an in-memory localStorage shim before importing the store.
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

let favouriteDashboards: typeof import('./favouriteDashboards.svelte').favouriteDashboards;

beforeEach(async () => {
	installFakeLocalStorage();
	currentUsername = 'alice';
	vi.resetModules();
	const mod = await import('./favouriteDashboards.svelte');
	favouriteDashboards = mod.favouriteDashboards;
});

describe('favouriteDashboards', () => {
	it('starts empty for a fresh user', () => {
		expect(favouriteDashboards.all()).toEqual([]);
		expect(favouriteDashboards.isFavourite('anything.saikudash')).toBe(false);
	});

	it('toggle() turns a path on', () => {
		favouriteDashboards.toggle('a.saikudash');
		expect(favouriteDashboards.isFavourite('a.saikudash')).toBe(true);
		expect(favouriteDashboards.all()).toEqual(['a.saikudash']);
	});

	it('toggle() called twice turns the same path off again', () => {
		favouriteDashboards.toggle('a.saikudash');
		favouriteDashboards.toggle('a.saikudash');
		expect(favouriteDashboards.isFavourite('a.saikudash')).toBe(false);
		expect(favouriteDashboards.all()).toEqual([]);
	});

	it('multiple distinct toggles accumulate', () => {
		favouriteDashboards.toggle('a.saikudash');
		favouriteDashboards.toggle('b.saikudash');
		favouriteDashboards.toggle('c.saikudash');
		expect(favouriteDashboards.all().sort()).toEqual(['a.saikudash', 'b.saikudash', 'c.saikudash']);
	});

	it('ignores empty paths', () => {
		favouriteDashboards.toggle('');
		expect(favouriteDashboards.all()).toEqual([]);
	});

	it('no-ops when there is no current user', () => {
		currentUsername = null;
		favouriteDashboards.toggle('a.saikudash');
		expect(favouriteDashboards.all()).toEqual([]);
	});

	it('isolates favourites by username', () => {
		favouriteDashboards.toggle('alice-fav.saikudash');
		currentUsername = 'bob';
		expect(favouriteDashboards.all()).toEqual([]);
		favouriteDashboards.toggle('bob-fav.saikudash');
		currentUsername = 'alice';
		expect(favouriteDashboards.all()).toEqual(['alice-fav.saikudash']);
		expect(favouriteDashboards.isFavourite('bob-fav.saikudash')).toBe(false);
	});

	it('remove() drops a single favourite', () => {
		favouriteDashboards.toggle('a.saikudash');
		favouriteDashboards.toggle('b.saikudash');
		favouriteDashboards.remove('a.saikudash');
		expect(favouriteDashboards.all()).toEqual(['b.saikudash']);
	});

	it('remove() is a no-op for an unknown path', () => {
		favouriteDashboards.toggle('a.saikudash');
		favouriteDashboards.remove('never-favourited.saikudash');
		expect(favouriteDashboards.all()).toEqual(['a.saikudash']);
	});
});
