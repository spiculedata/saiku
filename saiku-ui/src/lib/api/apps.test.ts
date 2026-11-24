import { describe, expect, test } from 'vitest';
import { emptyApp, appFromDashboard, isValidAppId, normaliseApp, type SaikuApp } from './apps';

describe('saikuapp model', () => {
	test('emptyApp has one blank page and default nav/theme', () => {
		const app = emptyApp('My App');
		expect(app.name).toBe('My App');
		expect(app.pages).toHaveLength(1);
		expect(app.nav.position).toBe('rail');
		expect(app.theme.mode).toBe('auto');
	});

	test('appFromDashboard wraps a dashboard layout as page 0 (parity/back-compat)', () => {
		const layout = { cols: 12, tiles: [{ id: 't1', type: 'kpi', x: 0, y: 0, w: 3, h: 2 }] };
		const app = appFromDashboard('Sales', layout);
		expect(app.pages).toHaveLength(1);
		expect(app.pages[0].grid).toEqual(layout);
		expect(app.pages[0].title).toBe('Sales');
	});

	test('normaliseApp is idempotent and fills missing fields', () => {
		const raw = {
			name: 'X',
			pages: [{ title: 'P1', grid: { cols: 12, tiles: [] } }]
		} as unknown as SaikuApp;
		const once = normaliseApp(raw);
		expect(normaliseApp(once)).toEqual(once);
		expect(once.nav.position).toBe('rail');
		expect(once.theme.mode).toBe('auto');
		expect(once.pages[0].id).toBeTruthy();
	});

	// saiku#2545: the id becomes the CSS attribute-selector value the custom-CSS
	// scope is built under, so only ^[A-Za-z0-9_-]{1,64}$ (or empty = preview)
	// may reach the shell.
	test('isValidAppId accepts generated ids and the preview sentinel, nothing else', () => {
		for (const ok of ['', 'app-1', 'sales', 'a_-0', 'a'.repeat(64)]) {
			expect(isValidAppId(ok)).toBe(true);
		}
		for (const bad of ['x"]{}', 'html{}[x="', 'a b', 'a/b', '.' + 'a'.repeat(64), 'ü', ' ']) {
			expect(isValidAppId(bad)).toBe(bad === '' ? true : false);
		}
	});

	test('normaliseApp rejects a document whose id closes the selector', () => {
		const hostile = 'x"]{} body::before{content:\'injected\'} [x="';
		expect(() => normaliseApp({ id: hostile } as unknown as SaikuApp)).toThrow(/Invalid app id/);
	});

	test('existing apps with generated ids load unchanged', () => {
		const raw = { id: 'app-1', name: 'FoodMart Ops', pages: [] } as unknown as SaikuApp;
		expect(normaliseApp(raw)).toMatchObject({ id: 'app-1', name: 'FoodMart Ops' });
	});
});
