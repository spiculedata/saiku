/*
 * Unit tests for the workspace decimal-places preference (saiku#1988).
 *
 * Same shape as the hidden-measures toggle test (#834): stub sessionStorage
 * with an in-memory Map and reset modules between tests, so the store's
 * $state is re-seeded from storage on every case without needing jsdom.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

function installFakeSessionStorage(): Map<string, string> {
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
	vi.stubGlobal('sessionStorage', fakeStorage);
	return store;
}

type StoreModule = typeof import('./decimalPlaces.svelte');

let mod: StoreModule;
let backing: Map<string, string>;

async function loadFreshModule(): Promise<void> {
	vi.resetModules();
	mod = await import('./decimalPlaces.svelte');
}

beforeEach(async () => {
	backing = installFakeSessionStorage();
	await loadFreshModule();
});

describe('parseStoredDecimals', () => {
	it('treats empty and non-numeric entries as auto', () => {
		expect(mod.parseStoredDecimals(null)).toBeNull();
		expect(mod.parseStoredDecimals('')).toBeNull();
		expect(mod.parseStoredDecimals('lots')).toBeNull();
	});

	it('accepts the supported range and rejects outside it', () => {
		expect(mod.parseStoredDecimals('0')).toBe(0);
		expect(mod.parseStoredDecimals('4')).toBe(4);
		expect(mod.parseStoredDecimals('-1')).toBeNull();
		expect(mod.parseStoredDecimals('21')).toBeNull();
	});
});

describe('decimalOptions', () => {
	it('offers auto plus 0…max', () => {
		expect(mod.decimalOptions()).toEqual([null, 0, 1, 2, 3, 4]);
		expect(mod.decimalOptions(2)).toEqual([null, 0, 1, 2]);
	});
});

describe('decimalPlaces store', () => {
	it('defaults to auto when sessionStorage is empty', () => {
		expect(mod.decimalPlaces.decimals).toBeNull();
	});

	it('persists an explicit choice for the session', () => {
		mod.decimalPlaces.set(0);
		expect(mod.decimalPlaces.decimals).toBe(0);
		expect(backing.get(mod.DECIMAL_PLACES_STORAGE_KEY)).toBe('0');

		mod.decimalPlaces.set(2);
		expect(backing.get(mod.DECIMAL_PLACES_STORAGE_KEY)).toBe('2');
	});

	it('drops the storage entry when switching back to auto', () => {
		mod.decimalPlaces.set(1);
		mod.decimalPlaces.useAuto();
		expect(mod.decimalPlaces.decimals).toBeNull();
		expect(backing.has(mod.DECIMAL_PLACES_STORAGE_KEY)).toBe(false);
	});

	it('ignores an out-of-range request rather than throwing', () => {
		mod.decimalPlaces.set(99);
		expect(mod.decimalPlaces.decimals).toBeNull();
		mod.decimalPlaces.set(-2);
		expect(mod.decimalPlaces.decimals).toBeNull();
	});

	it('re-seeds from storage when the module is reloaded', async () => {
		mod.decimalPlaces.set(0);
		await loadFreshModule();
		expect(mod.decimalPlaces.decimals).toBe(0);
	});
});
