/*
 * Pure helpers for the App Builder shell.
 *
 * Extracted out of AppShell.svelte so the load-bearing logic — the theme-var
 * → inline-style serialisation, the rail-vs-top nav choice, the active-page
 * fallback, and (critically) the custom-CSS scoping path — is unit-testable
 * without mounting a Svelte component. The `.svelte` stays a thin presentation
 * layer over these.
 */

import type { SaikuApp } from "$lib/api/apps";
import { themeVars } from "$lib/dashboard/appTheme";
import { sanitiseAndScopeCss } from "$lib/dashboard/cssSanitiser";

/** Serialise a CSS-custom-property map (as returned by {@link themeVars}) into
 *  an inline `style` attribute string — `--k:v;--k2:v2;`. Empty map → "". */
export function styleVarsToString(vars: Record<string, string>): string {
  return Object.entries(vars)
    .map(([k, v]) => `${k}:${v};`)
    .join("");
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
  return app.id || "preview";
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
export function navPosition(app: SaikuApp): "rail" | "top" {
  return app.nav?.position === "top" ? "top" : "rail";
}

/** True when the shell should render the left rail (vs the top tab bar). */
export function isRailNav(app: SaikuApp): boolean {
  return navPosition(app) === "rail";
}

/** Resolve which page is active: honour the store's {@code activePageId} when
 *  it still points at a real page, otherwise fall back to the first page. Null
 *  only when the app has no pages at all. */
export function resolveActivePageId(app: SaikuApp, storeActiveId: string | null): string | null {
  if (storeActiveId && app.pages.some((p) => p.id === storeActiveId)) return storeActiveId;
  return app.pages[0]?.id ?? null;
}
