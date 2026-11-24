/*
 * Unit tests for the App Builder shell helpers — the theme-var serialisation,
 * nav choice, active-page fallback, and (critically) the custom-CSS scoping
 * path that AppShell relies on.
 */

import { describe, test, expect } from 'vitest';
import * as csstree from 'css-tree';
import { emptyApp, type SaikuApp } from '$lib/api/apps';
import { appSkinCss } from '$lib/views/app/appSkin';
import {
	styleVarsToString,
	themeVarsStyle,
	appScopeId,
	rootSelectorFor,
	scopedCustomCss,
	navPosition,
	isRailNav,
	resolveActivePageId,
	appCubes,
	assistantBlindCubes,
	cubeKey,
	firstAppCube
} from '$lib/views/app/appShell';

function app(patch: Partial<SaikuApp> = {}): SaikuApp {
	return { ...emptyApp('Test'), ...patch };
}

describe('styleVarsToString', () => {
	test('serialises a var map into a k:v; style string', () => {
		expect(styleVarsToString({ '--a': '1', '--b': 'red' })).toBe('--a:1;--b:red;');
	});

	test('empty map serialises to empty string', () => {
		expect(styleVarsToString({})).toBe('');
	});
});

describe('themeVarsStyle', () => {
	test('only valid theme colours reach the inline style', () => {
		const style = themeVarsStyle(
			app({ theme: { mode: 'light', primary: '#ff0000', accent: 'nope' } })
		);
		expect(style).toContain('--saiku-app-primary:#ff0000;');
		// An invalid accent value never lands as the exact --saiku-app-accent var
		// (the trailing colon distinguishes it from --saiku-app-accent-soft/-2).
		expect(style).not.toContain('--saiku-app-accent:nope');
		expect(style).not.toContain('--saiku-app-accent:;');
		// font always resolves (allowlist default)
		expect(style).toContain('--saiku-app-font:');
	});
});

describe('appScopeId / rootSelectorFor', () => {
	test("empty id falls back to 'preview'", () => {
		expect(appScopeId(app({ id: '' }))).toBe('preview');
		expect(rootSelectorFor(app({ id: '' }))).toBe('[data-saiku-app="preview"]');
	});

	test('durable id is used verbatim', () => {
		expect(rootSelectorFor(app({ id: 'sales' }))).toBe('[data-saiku-app="sales"]');
	});
});

// saiku#2545: the id is interpolated into the attribute selector, so a `"]{}`
// in it could close the selector and inject rules the sanitiser never sees.
describe('rootSelectorFor cannot be escaped (saiku#2545)', () => {
	/** The issue's breakout payload — a full-viewport overlay plus an https:
	 *  beacon, all through the closed selector. */
	const PAYLOAD_ID =
		'x"]{} body::before{content:\'injected\'} html{background-image:url(https://attacker.example/beacon)!important} [x="';

	test('rootSelectorFor escapes its input instead of closing the selector', () => {
		const selector = rootSelectorFor(app({ id: PAYLOAD_ID }));
		// The value stays a single quoted string: both embedded quotes are
		// backslash-escaped, so the injected `]{} body::before…` tail is inert
		// attribute-value text rather than a selector boundary.
		expect(selector.startsWith('[data-saiku-app="')).toBe(true);
		expect(selector.endsWith('"]')).toBe(true);
		expect(selector.match(/\\"/g)).toHaveLength(2);
	});

	test('the skin stylesheet for a hostile id contains no rule outside the app scope', () => {
		const scope = rootSelectorFor(app({ id: PAYLOAD_ID }));
		const css = appSkinCss(scope);
		const ast = csstree.parse(css);
		const outOfScope: string[] = [];
		csstree.walk(ast, {
			visit: 'Rule',
			enter(rule) {
				const prelude = csstree.generate(rule.prelude);
				if (!prelude.includes(scope)) outOfScope.push(prelude);
			}
		});
		expect(outOfScope).toEqual([]);
	});

	test('scopedCustomCss for a hostile id also stays inside the scope', () => {
		const out = scopedCustomCss(
			app({ id: PAYLOAD_ID, theme: { mode: 'light', customCss: 'body { background: red }' } })
		);
		const ast = csstree.parse(out);
		const scope = rootSelectorFor(app({ id: PAYLOAD_ID }));
		const outOfScope: string[] = [];
		csstree.walk(ast, {
			visit: 'Rule',
			enter(rule) {
				const prelude = csstree.generate(rule.prelude);
				if (!prelude.includes(scope)) outOfScope.push(prelude);
			}
		});
		expect(outOfScope).toEqual([]);
	});
});

describe('scopedCustomCss (security path)', () => {
	test('scopes author rules under the app root selector', () => {
		const out = scopedCustomCss(
			app({ id: 'sales', theme: { mode: 'light', customCss: '.x { color: red }' } })
		);
		expect(out).toContain('[data-saiku-app="sales"]');
		expect(out).toContain('.x');
	});

	test('fails closed on unparseable CSS', () => {
		const out = scopedCustomCss(
			app({ theme: { mode: 'light', customCss: 'this is { not ; valid ) css {{{' } })
		);
		expect(out).toBe('');
	});

	test('strips hostile declarations (position: fixed)', () => {
		const out = scopedCustomCss(
			app({ theme: { mode: 'light', customCss: '.x { position: fixed; color: red }' } })
		);
		expect(out).not.toContain('fixed');
		expect(out).toContain('color');
	});

	test('no custom CSS yields empty string', () => {
		expect(scopedCustomCss(app())).toBe('');
	});
});

describe('navPosition / isRailNav', () => {
	test('defaults to rail', () => {
		expect(navPosition(app())).toBe('rail');
		expect(isRailNav(app())).toBe(true);
	});

	test('top position selects the top nav', () => {
		const a = app({ nav: { position: 'top' } });
		expect(navPosition(a)).toBe('top');
		expect(isRailNav(a)).toBe(false);
	});
});

describe('resolveActivePageId', () => {
	test('honours a store id that points at a real page', () => {
		const a = app();
		const id = a.pages[0].id;
		expect(resolveActivePageId(a, id)).toBe(id);
	});

	test('falls back to page 0 when the store id is stale or null', () => {
		const a = app();
		expect(resolveActivePageId(a, 'gone')).toBe(a.pages[0].id);
		expect(resolveActivePageId(a, null)).toBe(a.pages[0].id);
	});

	test('null when the app has no pages', () => {
		expect(resolveActivePageId(app({ pages: [] }), null)).toBeNull();
	});
});

/* ====================================================================
 * saiku#1804 — an app can span cubes, and the surfaces that assumed one
 * need to know when that assumption doesn't hold.
 * ==================================================================== */
describe('appCubes / assistantBlindCubes', () => {
	const cube = (name: string) => ({
		connectionName: 'unknown_foodmart',
		catalog: 'FoodMart',
		schema: 'FoodMart',
		cubeName: name
	});

	const multiCubeApp = () =>
		({
			...app(),
			pages: [
				{
					id: 'p1',
					title: 'Portfolio',
					grid: {
						cols: 12,
						tiles: [
							{ id: 'a', cube: cube('Store') },
							{ id: 'b', cube: cube('Store') },
							{ id: 'c', cube: cube('Warehouse') },
							{ id: 't', type: 'text' } // no cube — text tiles bind to nothing
						]
					}
				},
				{
					id: 'p2',
					title: 'Supply',
					grid: { cols: 12, tiles: [{ id: 'd', cube: cube('Warehouse') }] }
				}
			]
		}) as unknown as Parameters<typeof appCubes>[0];

	test('lists each distinct cube once, in page-then-tile order', () => {
		expect(appCubes(multiCubeApp()).map((c) => c.cubeName)).toEqual(['Store', 'Warehouse']);
	});

	test('skips tiles with no cube of their own', () => {
		expect(appCubes(multiCubeApp())).toHaveLength(2);
	});

	test('firstAppCube still answers with the first one', () => {
		expect(firstAppCube(multiCubeApp())?.cubeName).toBe('Store');
	});

	test("names the cubes the assistant can't see", () => {
		const blind = assistantBlindCubes(multiCubeApp(), cube('Store'));
		expect(blind.map((c) => c.cubeName)).toEqual(['Warehouse']);
	});

	test('a single-cube app leaves the assistant with no blind spot', () => {
		const oneCube = {
			...app(),
			pages: [
				{ id: 'p1', title: 'One', grid: { cols: 12, tiles: [{ id: 'a', cube: cube('Store') }] } }
			]
		} as unknown as Parameters<typeof appCubes>[0];
		expect(assistantBlindCubes(oneCube, cube('Store'))).toEqual([]);
	});

	test('no bound cube means nothing to report as blind', () => {
		expect(assistantBlindCubes(multiCubeApp(), null)).toEqual([]);
	});

	test('cubeKey separates same-named cubes in different catalogs', () => {
		expect(cubeKey(cube('Sales'))).not.toBe(cubeKey({ ...cube('Sales'), catalog: 'Other' }));
	});
});
