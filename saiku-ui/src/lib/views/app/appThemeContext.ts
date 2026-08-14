/*
 * Lets a chart tile deep inside an app know when the app's theme changed.
 *
 * Chart tiles paint onto a canvas, so unlike CSS-styled surfaces they do not
 * repaint when a custom property changes — something has to call setOption
 * again. The tiles read the app's tokens off the DOM (appChartTheme.ts), which
 * gives them no reactive dependency to hang a re-render on. This context hands
 * them one: AppShell provides a getter returning the serialised theme vars, and
 * a tile's render effect simply reads it, registering the dependency the same
 * way it registers `theme.effective` for the global light/dark flip.
 *
 * Absent outside the App Builder (plain dashboards, the workspace), where the
 * global theme store is already the right signal — hence the null-returning
 * accessor rather than a required context.
 */

import { getContext, setContext } from "svelte";

const KEY = Symbol.for("saiku.app.themeSignature");

/** Called by AppShell during init. `get` must READ the reactive theme state so
 *  consumers calling it inside an $effect pick up the dependency. */
export function provideAppThemeSignature(get: () => string): void {
  setContext(KEY, get);
}

/** The app-theme signature getter, or null when not inside an app.
 *  Must be called during component init (getContext rules). */
export function getAppThemeSignature(): (() => string) | null {
  return getContext<(() => string) | undefined>(KEY) ?? null;
}
