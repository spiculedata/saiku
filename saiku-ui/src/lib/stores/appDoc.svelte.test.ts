/*
 * Unit tests for the App Builder document store (appDoc.svelte.ts) and its
 * REST client (apps.ts). No network — global fetch is stubbed per test.
 *
 * The store owns the .saikuapp envelope: an ordered list of pages plus nav /
 * theme. Every mutator MUST be immutable (new objects, never in-place) so
 * Svelte 5 reactivity and undo/redo behave — the immutability assertions below
 * pin that contract.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { appDoc } from './appDoc.svelte';
import type { SaikuApp } from '$lib/api/apps';

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'Content-Type': 'application/json' }
	});
}

/** A minimal raw app doc as the backend would return it (partial → normalised on load). */
function rawApp(name = 'Loaded app'): Record<string, unknown> {
	return {
		id: 'server-id',
		name,
		version: 1,
		theme: { mode: 'dark' },
		nav: { position: 'top' },
		pages: [
			{ id: 'p-a', title: 'First', grid: { cols: 12, tiles: [] } },
			{ id: 'p-b', title: 'Second', grid: { cols: 12, tiles: [] } }
		]
	};
}

describe('appDoc store', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
		appDoc.reset();
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
		vi.restoreAllMocks();
	});

	describe('newApp / newAppFromDashboard', () => {
		test('newApp seeds a single-page app and selects page 0', () => {
			appDoc.newApp('Fresh');
			expect(appDoc.current?.name).toBe('Fresh');
			expect(appDoc.current?.pages.length).toBe(1);
			expect(appDoc.activePageId).toBe(appDoc.current?.pages[0].id);
		});

		test('newAppFromDashboard wraps the layout as page 0', () => {
			const layout = { cols: 12, tiles: [{ id: 't1' }] };
			appDoc.newAppFromDashboard('Wrapped', layout);
			expect(appDoc.current?.pages.length).toBe(1);
			expect(appDoc.current?.pages[0].grid).toEqual(layout);
			expect(appDoc.activePageId).toBe(appDoc.current?.pages[0].id);
		});
	});

	describe('page CRUD immutability', () => {
		beforeEach(() => {
			appDoc.newApp('Base');
			appDoc.addPage('Two');
			appDoc.addPage('Three');
		});

		test('addPage produces a NEW app + pages array and appends', () => {
			const before = appDoc.current!;
			const beforePages = before.pages;
			appDoc.addPage('Four');
			expect(appDoc.current).not.toBe(before); // new app object
			expect(appDoc.current!.pages).not.toBe(beforePages); // new array
			expect(beforePages.length).toBe(3); // prior array untouched
			expect(appDoc.current!.pages.length).toBe(4);
			expect(appDoc.current!.pages[3].title).toBe('Four');
		});

		test('renamePage returns a new object and does not mutate the prior', () => {
			const before = appDoc.current!;
			const target = before.pages[1];
			appDoc.renamePage(target.id, 'Renamed');
			expect(appDoc.current).not.toBe(before);
			expect(target.title).toBe('Two'); // prior page object untouched
			expect(appDoc.current!.pages[1].title).toBe('Renamed');
		});

		test('reorderPage moves a page immutably', () => {
			const before = appDoc.current!;
			const ids = before.pages.map((p) => p.id);
			appDoc.reorderPage(0, 2); // move first to last
			expect(appDoc.current).not.toBe(before);
			expect(before.pages.map((p) => p.id)).toEqual(ids); // prior untouched
			expect(appDoc.current!.pages.map((p) => p.id)).toEqual([ids[1], ids[2], ids[0]]);
		});

		test('deletePage removes a page immutably', () => {
			const before = appDoc.current!;
			const targetId = before.pages[1].id;
			appDoc.deletePage(targetId);
			expect(appDoc.current).not.toBe(before);
			expect(before.pages.length).toBe(3); // prior untouched
			expect(appDoc.current!.pages.length).toBe(2);
			expect(appDoc.current!.pages.some((p) => p.id === targetId)).toBe(false);
		});

		test('deleting the active page reselects a surviving page', () => {
			const before = appDoc.current!;
			const activeId = before.pages[1].id;
			appDoc.setActivePage(activeId);
			appDoc.deletePage(activeId);
			expect(appDoc.activePageId).not.toBe(activeId);
			expect(appDoc.current!.pages.some((p) => p.id === appDoc.activePageId)).toBe(true);
		});
	});

	describe('deletePage refuses the last remaining page', () => {
		test('a one-page app keeps its page', () => {
			appDoc.newApp('Solo');
			const onlyId = appDoc.current!.pages[0].id;
			const before = appDoc.current!;
			appDoc.deletePage(onlyId);
			expect(appDoc.current!.pages.length).toBe(1);
			expect(appDoc.current!.pages[0].id).toBe(onlyId);
			expect(appDoc.current).toBe(before); // no-op: same object, nothing changed
		});
	});

	describe('setActivePage', () => {
		test('sets the active page id', () => {
			appDoc.newApp('Base');
			appDoc.addPage('Two');
			const secondId = appDoc.current!.pages[1].id;
			appDoc.setActivePage(secondId);
			expect(appDoc.activePageId).toBe(secondId);
		});
	});

	describe('setNav / setTheme immutability', () => {
		beforeEach(() => appDoc.newApp('Base'));

		test('setNav returns a new app + nav object', () => {
			const before = appDoc.current!;
			const beforeNav = before.nav;
			appDoc.setNav('top');
			expect(appDoc.current).not.toBe(before);
			expect(appDoc.current!.nav).not.toBe(beforeNav);
			expect(beforeNav.position).toBe('rail'); // prior untouched
			expect(appDoc.current!.nav.position).toBe('top');
		});

		test('setTheme merges a patch into a new theme object', () => {
			const before = appDoc.current!;
			const beforeTheme = before.theme;
			appDoc.setTheme({ mode: 'dark', primary: '#123456' });
			expect(appDoc.current).not.toBe(before);
			expect(appDoc.current!.theme).not.toBe(beforeTheme);
			expect(beforeTheme.mode).toBe('auto'); // prior untouched
			expect(appDoc.current!.theme.mode).toBe('dark');
			expect(appDoc.current!.theme.primary).toBe('#123456');
		});
	});

	describe('undo / redo', () => {
		test('undo restores the prior app; redo re-applies', () => {
			appDoc.newApp('Base');
			appDoc.addPage('Two');
			expect(appDoc.current!.pages.length).toBe(2);
			appDoc.undo();
			expect(appDoc.current!.pages.length).toBe(1);
			appDoc.redo();
			expect(appDoc.current!.pages.length).toBe(2);
		});
	});

	describe('loadApp', () => {
		test('GETs the right URL and normalises the raw doc', async () => {
			const fetchMock = vi.fn().mockResolvedValue(jsonResponse(rawApp('Loaded')));
			globalThis.fetch = fetchMock;

			await appDoc.loadApp('homes/admin/demo.saikuapp');

			expect(fetchMock).toHaveBeenCalledTimes(1);
			const url = fetchMock.mock.calls[0][0] as string;
			expect(url).toBe('/rest/saiku/api/apps/homes/admin/demo.saikuapp');
			// normalised: name preserved, defaults filled, activePageId = page 0
			expect(appDoc.current?.name).toBe('Loaded');
			expect(appDoc.current?.tags).toEqual([]); // default filled by normaliseApp
			expect(appDoc.current?.pages.length).toBe(2);
			expect(appDoc.activePageId).toBe(appDoc.current?.pages[0].id);
			expect(appDoc.savedPath).toBe('homes/admin/demo.saikuapp');
			expect(appDoc.error).toBeNull();
		});

		test('sets error on a non-ok response', async () => {
			globalThis.fetch = vi.fn().mockResolvedValue(jsonResponse({ error: 'nope' }, 404));
			await appDoc.loadApp('homes/admin/missing.saikuapp');
			expect(appDoc.current).toBeNull();
			expect(appDoc.error).toBeTruthy();
		});
	});

	describe('saveApp', () => {
		test('POSTs the app JSON to the right URL', async () => {
			appDoc.newApp('To save');
			const fetchMock = vi
				.fn()
				.mockResolvedValue(jsonResponse({ status: 'OK', path: 'homes/admin/x.saikuapp' }));
			globalThis.fetch = fetchMock;

			await appDoc.saveApp('homes/admin/x.saikuapp', 'To save');

			expect(fetchMock).toHaveBeenCalledTimes(1);
			const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
			expect(url).toBe('/rest/saiku/api/apps/homes/admin/x.saikuapp');
			expect(init.method).toBe('POST');
			const sent = JSON.parse(init.body as string) as SaikuApp;
			expect(sent.name).toBe('To save');
			expect(sent.pages.length).toBe(1);
			expect(appDoc.savedPath).toBe('homes/admin/x.saikuapp');
		});
	});

	describe('theme / chrome mutators (graphical authoring)', () => {
		beforeEach(() => {
			appDoc.newApp('Brandable');
		});

		test('setTheme merges immutably', () => {
			const before = appDoc.current!.theme;
			appDoc.setTheme({ accent: '#123456' });
			expect(appDoc.current!.theme.accent).toBe('#123456');
			expect(appDoc.current!.theme).not.toBe(before); // new object
		});

		test('applyPreset sets preset and drops explicit token overrides', () => {
			appDoc.setTheme({ accent: '#123456', ground: '#000000' });
			appDoc.setTheme({ customCss: '.x{}' });
			appDoc.applyPreset('editorial');
			expect(appDoc.current!.theme.preset).toBe('editorial');
			expect(appDoc.current!.theme.accent).toBeUndefined(); // override cleared
			expect(appDoc.current!.theme.ground).toBeUndefined();
			expect(appDoc.current!.theme.customCss).toBe('.x{}'); // escape hatch preserved
		});

		test('updateNav / updateAssistant / updateHeader merge immutably', () => {
			appDoc.updateNav({ railCollapsed: true, footer: { settings: true, avatar: 'RM' } });
			expect(appDoc.current!.nav.railCollapsed).toBe(true);
			expect(appDoc.current!.nav.footer?.avatar).toBe('RM');

			appDoc.updateAssistant({ enabled: true, persona: 'Analyst' });
			expect(appDoc.current!.assistantSlot.enabled).toBe(true);
			expect(appDoc.current!.assistantSlot.persona).toBe('Analyst');

			appDoc.updateHeader({ wordmarkAccent: 'Mart', liveBadge: 'Live' });
			expect(appDoc.current!.header?.wordmarkAccent).toBe('Mart');
			expect(appDoc.current!.header?.liveBadge).toBe('Live');
		});

		test('rename / setLogo update the envelope', () => {
			appDoc.rename('Renamed');
			expect(appDoc.current!.name).toBe('Renamed');
			appDoc.setLogo('data:image/png;base64,AAAA');
			expect(appDoc.current!.logo).toBe('data:image/png;base64,AAAA');
			appDoc.setLogo(null);
			expect(appDoc.current!.logo).toBeNull();
		});

		test('updatePageMeta patches a page by id; unknown id is a no-op', () => {
			const id = appDoc.current!.pages[0].id;
			appDoc.updatePageMeta(id, { heading: 'Portland #14 · Today', icon: 'home' });
			expect(appDoc.current!.pages[0].heading).toBe('Portland #14 · Today');
			expect(appDoc.current!.pages[0].icon).toBe('home');
			const snapshot = appDoc.current;
			appDoc.updatePageMeta('nope', { heading: 'x' });
			expect(appDoc.current).toBe(snapshot); // no-op → same reference
		});
	});
});
