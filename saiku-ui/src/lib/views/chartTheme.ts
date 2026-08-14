/*
 * Shared ECharts theme tokens.
 *
 * The workspace chart (ChartView.svelte) and the dashboard chart tiles
 * (chartOptions.ts / ChartTile.svelte) both colour their charts from the
 * same CSS custom properties so a theme flip (light/dark/system) repaints
 * both identically. This module is the single source of those tokens —
 * extracted from ChartView so the dashboard builder stays in sync rather
 * than re-deriving its own palette.
 *
 * resolveThemeTokens() reads the live computed style off :root, so it must
 * run client-side. DEFAULT_THEME_TOKENS is the pure (DOM-free) light
 * fallback used both as a per-token backstop and by callers (tests, the
 * pure builder) that have no document to read from.
 *
 * #1091: a global "colour-blind-safe / high-contrast" preference (theme store)
 * overlays the Okabe-Ito categorical palette and sets `highContrast`, which the
 * pure chart builder reads to thicken strokes/borders and swap the waterfall
 * pos/neg colours. The preference lives in the theme store (localStorage), so
 * both the workspace and the dashboard inherit it via resolveThemeTokens().
 */

import { theme } from "$lib/stores/theme.svelte";

export interface ThemeTokens {
  fg: string;
  fgMuted: string;
  bg: string;
  bgMuted: string;
  border: string;
  accent: string;
  /** Categorical series palette read from --chart-1..8 tokens.
   *  Falls back to Indigo-anchored defaults harmonised with --accent
   *  if the tokens are absent for any reason. */
  chartColors: string[];
  /** #1091: when true, charts apply stronger contrast (thicker line/series
   *  borders, larger markers) and the waterfall uses the safe palette instead
   *  of the hard-coded blue/red. Set by the colour-blind-safe global pref.
   *  Optional (defaults to off) so token literals predating #1091 stay valid. */
  highContrast?: boolean;
  /** saiku#1799: the theme's growth / decline colours, for the charts that
   *  encode a SIGN rather than a category — the waterfall's rise and fall bars.
   *  Optional: absent (the global Saiku theme, whose tokens are HSL triplets
   *  rather than colours ECharts can parse) keeps the historical blue/red, so
   *  plain dashboards are unchanged and only a branded app repaints. */
  positive?: string;
  danger?: string;
  /** saiku#1799: a low → high sequential ramp derived from the theme, for the
   *  charts that encode MAGNITUDE as colour (heatmap, choropleth). Optional for
   *  the same reason as above; callers fall back to a named ramp. */
  sequentialRamp?: string[];
}

export const CHART_FALLBACK_COLORS = [
  "#4f46e5", "#0ea5e9", "#10b981", "#f59e0b",
  "#ef4444", "#a855f7", "#ec4899", "#14b8a6",
];

/* #1091: Okabe-Ito 8-colour qualitative palette — the de-facto standard for
 * colour-blind-safe categorical data (distinguishable under protanopia,
 * deuteranopia and tritanopia). Order is the canonical one; the first entry is
 * a neutral dark grey/black so the highest-priority series reads strongly even
 * in monochrome. Source: Okabe & Ito, "Color Universal Design" (2008).
 *   https://jfly.uni-koeln.de/color/ */
export const COLORBLIND_SAFE_COLORS = [
  "#000000", // black
  "#e69f00", // orange
  "#56b4e9", // sky blue
  "#009e73", // bluish green
  "#f0e442", // yellow
  "#0072b2", // blue
  "#d55e00", // vermillion
  "#cc79a7", // reddish purple
];

/* #1081: named categorical palettes the user can pick per chart (ChartOptions.
 * palette). "default" is intentionally absent here — it resolves to the active
 * theme tokens (tk.chartColors), which is the existing CHART_FALLBACK_COLORS in
 * light/dark unless overridden by --chart-* CSS tokens — so picking "default"
 * (the inert ChartOptions default) reproduces the pre-#1081 colour cycle exactly.
 * The other palettes are fixed hex sets that read acceptably on both light and
 * dark backgrounds (mid-saturation, no near-white / near-black extremes). */
export const NAMED_PALETTES: Record<string, string[]> = {
  vibrant: ["#ef4444", "#f97316", "#eab308", "#22c55e", "#06b6d4", "#3b82f6", "#8b5cf6", "#ec4899"],
  cool: ["#0ea5e9", "#06b6d4", "#14b8a6", "#22c55e", "#3b82f6", "#6366f1", "#8b5cf6", "#0891b2"],
  warm: ["#ef4444", "#f97316", "#f59e0b", "#eab308", "#dc2626", "#ea580c", "#d97706", "#b45309"],
  earth: ["#92400e", "#b45309", "#a16207", "#4d7c0f", "#15803d", "#0f766e", "#78716c", "#57534e"],
};

/** #1081: stable list of selectable palette ids for the editor dropdown —
 *  "default" first (theme-derived), then the named palettes. */
export const PALETTE_IDS: string[] = ["default", ...Object.keys(NAMED_PALETTES)];

/**
 * #1081: resolve a named-palette id to its colour array. "default" (and any
 * unknown / undefined id) returns the active theme palette (tk.chartColors), so
 * the result is theme-aware (light + dark) and legacy charts are unchanged. This
 * is the NAMED-palette layer only — the builder applies the higher-priority
 * colour-blind-safe palette and per-series overrides on top of this. Pure (no
 * DOM, no store read) so it can be unit-tested directly.
 */
export function resolvePalette(id: string | undefined, tk: ThemeTokens): string[] {
  if (!id || id === "default") return tk.chartColors;
  return NAMED_PALETTES[id] ?? tk.chartColors;
}

/** #1091: waterfall pos/neg colours for high-contrast mode. Picked from the
 *  Okabe-Ito palette (bluish-green up, vermillion down) so they stay
 *  distinguishable across the common colour-vision deficiencies — unlike the
 *  default hard-coded blue/red the builder uses otherwise. */
export const COLORBLIND_WATERFALL = { positive: "#009e73", negative: "#d55e00" } as const;

/** Light-theme defaults — mirror the :root token values in app.css. Used as
 *  the per-token fallback and whenever there's no DOM to read (SSR/tests). */
export const DEFAULT_THEME_TOKENS: ThemeTokens = {
  fg: "#0f172a",
  fgMuted: "#475569",
  bg: "#ffffff",
  bgMuted: "#f6f7f9",
  border: "#e2e8f0",
  accent: "#4f46e5",
  chartColors: CHART_FALLBACK_COLORS,
  highContrast: false,
};

/**
 * #1091: pure overlay applying the colour-blind-safe palette + high-contrast
 * flag onto a resolved token set. Kept side-effect-free (no DOM, no store read)
 * so it can be unit-tested directly. resolveThemeTokens() calls it with the
 * live global preference; tests call it with an explicit flag.
 */
export function applyColorBlindSafe(tokens: ThemeTokens, enabled: boolean): ThemeTokens {
  if (!enabled) return tokens;
  return { ...tokens, chartColors: COLORBLIND_SAFE_COLORS, highContrast: true };
}

/** Resolve the active theme tokens from :root's computed style. Falls back
 *  to DEFAULT_THEME_TOKENS per-token (and wholesale when there's no document,
 *  e.g. during SSR), so callers always get a complete token set. When the
 *  global colour-blind-safe pref is on (#1091), overlays the safe palette and
 *  the high-contrast flag so both chart surfaces inherit it. */
export function resolveThemeTokens(): ThemeTokens {
  if (typeof document === "undefined") {
    return applyColorBlindSafe(DEFAULT_THEME_TOKENS, theme.colorBlindSafe);
  }
  const cs = getComputedStyle(document.documentElement);
  const get = (name: string, fallback: string) => cs.getPropertyValue(name).trim() || fallback;
  const chartColors = CHART_FALLBACK_COLORS.map((fallback, i) => get(`--chart-${i + 1}`, fallback));
  const tokens: ThemeTokens = {
    fg: get("--fg", DEFAULT_THEME_TOKENS.fg),
    fgMuted: get("--fg-muted", DEFAULT_THEME_TOKENS.fgMuted),
    bg: get("--bg", DEFAULT_THEME_TOKENS.bg),
    bgMuted: get("--bg-muted", DEFAULT_THEME_TOKENS.bgMuted),
    border: get("--border", DEFAULT_THEME_TOKENS.border),
    accent: get("--accent", DEFAULT_THEME_TOKENS.accent),
    chartColors,
    highContrast: false,
  };
  return applyColorBlindSafe(tokens, theme.colorBlindSafe);
}
