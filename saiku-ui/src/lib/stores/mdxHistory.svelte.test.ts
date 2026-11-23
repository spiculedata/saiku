/*
 * Unit tests for mdxHistory (saiku#1106 phase 3), mirroring the
 * recentDashboards.svelte.test.ts harness: mock the session module (the
 * store reads session.current.username per call) and swap in a fake
 * localStorage.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SaikuCube } from '$lib/api/discover';

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

const cube: SaikuCube = {
	connection: 'foodmart',
	catalog: 'FoodMart',
	schema: 'FoodMart',
	name: 'Sales',
	caption: 'Sales',
	uniqueName: '[Sales]',
	visible: true
};

const otherCube: SaikuCube = { ...cube, name: 'Warehouse', caption: 'Warehouse' };

let mdxHistory: typeof import('./mdxHistory.svelte').mdxHistory;
let MDX_HISTORY_CAP: number;

beforeEach(async () => {
	installFakeLocalStorage();
	currentUsername = 'alice';
	vi.resetModules();
	const mod = await import('./mdxHistory.svelte');
	mdxHistory = mod.mdxHistory;
	MDX_HISTORY_CAP = mod.MDX_HISTORY_CAP;
});

describe('mdxHistory', () => {
	it('starts empty for a fresh user', () => {
		expect(mdxHistory.all()).toEqual([]);
	});

	it('push() adds an entry to the front', () => {
		mdxHistory.push({ mdx: 'SELECT FROM [Sales]', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		expect(mdxHistory.all()).toEqual([
			{ mdx: 'SELECT FROM [Sales]', cube, ranAt: '2026-01-01T00:00:00.000Z' }
		]);
	});

	it('multiple push() calls keep most-recent-first ordering, no dedup on identical mdx', () => {
		mdxHistory.push({ mdx: 'A', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		mdxHistory.push({ mdx: 'A', cube, ranAt: '2026-01-01T00:00:01.000Z' });
		const all = mdxHistory.all();
		expect(all).toHaveLength(2);
		expect(all[0].ranAt).toBe('2026-01-01T00:00:01.000Z');
		expect(all[1].ranAt).toBe('2026-01-01T00:00:00.000Z');
	});

	it('caps at MDX_HISTORY_CAP entries', () => {
		for (let i = 0; i < MDX_HISTORY_CAP + 5; i++) {
			mdxHistory.push({
				mdx: `Q${i}`,
				cube,
				ranAt: `2026-01-01T00:00:${String(i).padStart(2, '0')}.000Z`
			});
		}
		expect(mdxHistory.all()).toHaveLength(MDX_HISTORY_CAP);
		expect(mdxHistory.all()[0].mdx).toBe(`Q${MDX_HISTORY_CAP + 4}`);
	});

	it('ignores blank mdx', () => {
		mdxHistory.push({ mdx: '   ', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		expect(mdxHistory.all()).toEqual([]);
	});

	it('no-ops when there is no current user', () => {
		currentUsername = null;
		mdxHistory.push({ mdx: 'A', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		expect(mdxHistory.all()).toEqual([]);
	});

	it('isolates history by username', () => {
		mdxHistory.push({ mdx: 'alice query', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		currentUsername = 'bob';
		expect(mdxHistory.all()).toEqual([]);
		mdxHistory.push({ mdx: 'bob query', cube: otherCube, ranAt: '2026-01-01T00:00:00.000Z' });
		expect(mdxHistory.all()).toHaveLength(1);
		expect(mdxHistory.all()[0].mdx).toBe('bob query');
		currentUsername = 'alice';
		expect(mdxHistory.all()).toHaveLength(1);
		expect(mdxHistory.all()[0].mdx).toBe('alice query');
	});

	it('drops corrupt entries instead of throwing', () => {
		window.localStorage.setItem('saiku:mdxHistory:alice', JSON.stringify(['not json-valid-{']));
		expect(mdxHistory.all()).toEqual([]);
	});

	it('clear() drops everything', () => {
		mdxHistory.push({ mdx: 'A', cube, ranAt: '2026-01-01T00:00:00.000Z' });
		mdxHistory.clear();
		expect(mdxHistory.all()).toEqual([]);
	});
});
