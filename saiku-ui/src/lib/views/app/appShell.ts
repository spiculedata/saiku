/*
 * Pure helpers for the App Builder shell.
 *
 * Extracted out of AppShell.svelte so the load-bearing logic — the theme-var
 * → inline-style serialisation, the rail-vs-top nav choice, the active-page
 * fallback, and (critically) the custom-CSS scoping path — is unit-testable
 * without mounting a Svelte component. The `.svelte` stays a thin presentation
 * layer over these.
 */

import type { SaikuApp } from '$lib/api/apps';
import type { CubeRef } from '$lib/api/dashboards';
import { themeVars } from '$lib/dashboard/appTheme';
import { sanitiseAndScopeCss } from '$lib/dashboard/cssSanitiser';

/** Serialise a CSS-custom-property map (as returned by {@link themeVars}) into
 *  an inline `style` attribute string — `--k:v;--k2:v2;`. Empty map → "". */
export function styleVarsToString(vars: Record<string, string>): string {
	return Object.entries(vars)
		.map(([k, v]) => `${k}:${v};`)
		.join('');
}

/** The inline `style` string for an app's theme vars. Thin composition of
 *  {@link themeVars} + {@link styleVarsToString} so the shell root can bind it
 *  directly. */
export function themeVarsStyle(app: SaikuApp): string {
	return styleVarsToString(themeVars(app.theme));
}

/** The DOM scoping key for an app: `preview` before the first save (empty id),
 *  otherwise the durable id. Used both for the `data-saiku-app` attribute and
 *  as the root selector the custom CSS is scoped under. */
export function appScopeId(app: SaikuApp): string {
	return app.id || 'preview';
}

/** The attribute-selector every author custom-CSS rule is scoped beneath. */
export function rootSelectorFor(app: SaikuApp): string {
	return `[data-saiku-app="${appScopeId(app)}"]`;
}

/** Compute the SAFE, scoped custom CSS string for an app. Always routes the
 *  author CSS through {@link sanitiseAndScopeCss} (scoped + fail-closed) — this
 *  is the security contract; the shell sets it via `textContent`, never
 *  `{@html}`. Returns "" when there's no custom CSS or it fails to parse. */
export function scopedCustomCss(app: SaikuApp): string {
	return sanitiseAndScopeCss(app.theme.customCss, rootSelectorFor(app));
}

/** Nav position with the documented default (rail) when the field is absent. */
export function navPosition(app: SaikuApp): 'rail' | 'top' {
	return app.nav?.position === 'top' ? 'top' : 'rail';
}

/** True when the shell should render the left rail (vs the top tab bar). */
export function isRailNav(app: SaikuApp): boolean {
	return navPosition(app) === 'rail';
}

/** Resolve which page is active: honour the store's {@code activePageId} when
 *  it still points at a real page, otherwise fall back to the first page. Null
 *  only when the app has no pages at all. */
export function resolveActivePageId(app: SaikuApp, storeActiveId: string | null): string | null {
	if (storeActiveId && app.pages.some((p) => p.id === storeActiveId)) return storeActiveId;
	return app.pages[0]?.id ?? null;
}

/** First cube bound to any tile across the app.
 *
 *  Several surfaces need "the cube this app is about" without the author having
 *  named one: the assistant's fallback scope, and the header context selector
 *  when it reads its options from a level. Shared here so the shell and the
 *  inspector agree on which cube that is. */
export function firstAppCube(app: SaikuApp): CubeRef | null {
	return appCubes(app)[0] ?? null;
}

/** Fully-qualified identity of a cube reference — the key two tiles must share
 *  to count as "the same cube". */
export function cubeKey(c: CubeRef): string {
	return [c.connectionName, c.catalog, c.schema, c.cubeName].join('/');
}

/**
 * Every distinct cube the app's tiles are bound to, in page-then-tile order
 * (saiku#1804).
 *
 * Tiles carry their own cube, so an app can legitimately span several — an
 * estate app pairing a footprint cube that has no time dimension with a
 * replenishment cube that does. Surfaces that used to assume ONE cube need to
 * know when that assumption doesn't hold, rather than silently describing the
 * whole app by whichever cube happened to be first.
 */
export function appCubes(app: SaikuApp): CubeRef[] {
	const seen = new Set<string>();
	const out: CubeRef[] = [];
	for (const p of app.pages) {
		const grid = p.grid as { tiles?: Array<{ cube?: CubeRef }> } | null;
		for (const t of grid?.tiles ?? []) {
			const c = t.cube;
			if (!c?.connectionName || !c?.cubeName) continue;
			const key = cubeKey(c);
			if (seen.has(key)) continue;
			seen.add(key);
			out.push(c);
		}
	}
	return out;
}

/**
 * The cubes in this app that the assistant CANNOT see, given the cube it is
 * bound to. Empty for a single-cube app — the common case, where the assistant's
 * scope note is the whole truth.
 */
export function assistantBlindCubes(app: SaikuApp, bound: CubeRef | null): CubeRef[] {
	if (!bound) return [];
	const key = cubeKey(bound);
	return appCubes(app).filter((c) => cubeKey(c) !== key);
}
