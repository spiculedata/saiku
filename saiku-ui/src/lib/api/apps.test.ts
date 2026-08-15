import { describe, expect, test } from 'vitest';
import { emptyApp, appFromDashboard, normaliseApp, type SaikuApp } from './apps';

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
});
