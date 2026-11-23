/*
 * saiku#2009 — a freshly created app opens in edit mode; saved apps open in view.
 */

import { describe, expect, test } from 'vitest';
import { newAppHref, initialAppMode } from './appOpenMode';

const params = (qs: string) => new URLSearchParams(qs);

describe('newAppHref', () => {
	test('points at the app route with the edit-on-open flag', () => {
		expect(newAppHref('', 'homes/admin/Sales.saikuapp')).toBe(
			'/apps/homes/admin/Sales.saikuapp?edit=1'
		);
	});

	test('respects a non-empty base path', () => {
		expect(newAppHref('/ui', 'Sales.saikuapp')).toBe('/ui/apps/Sales.saikuapp?edit=1');
	});

	test('round-trips through initialAppMode', () => {
		const url = new URL(newAppHref('', 'Sales.saikuapp'), 'http://localhost');
		expect(initialAppMode(url.searchParams)).toBe('edit');
	});
});

describe('initialAppMode', () => {
	test('a saved app opens in view mode', () => {
		expect(initialAppMode(params(''))).toBe('view');
	});

	test('the edit-on-open flag opens in edit mode', () => {
		expect(initialAppMode(params('edit=1'))).toBe('edit');
	});

	test('any other edit value is ignored', () => {
		expect(initialAppMode(params('edit=0'))).toBe('view');
		expect(initialAppMode(params('edit=true'))).toBe('view');
	});

	test('kiosk (chrome=none) forces view even with the edit flag', () => {
		expect(initialAppMode(params('edit=1&chrome=none'))).toBe('view');
	});
});
