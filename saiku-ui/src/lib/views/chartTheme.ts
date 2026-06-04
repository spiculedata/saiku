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
 */

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
}

export const CHART_FALLBACK_COLORS = [
  "#4f46e5", "#0ea5e9", "#10b981", "#f59e0b",
  "#ef4444", "#a855f7", "#ec4899", "#14b8a6",
];

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
};

/** Resolve the active theme tokens from :root's computed style. Falls back
 *  to DEFAULT_THEME_TOKENS per-token (and wholesale when there's no document,
 *  e.g. during SSR), so callers always get a complete token set. */
export function resolveThemeTokens(): ThemeTokens {
  if (typeof document === "undefined") return DEFAULT_THEME_TOKENS;
  const cs = getComputedStyle(document.documentElement);
  const get = (name: string, fallback: string) => cs.getPropertyValue(name).trim() || fallback;
  const chartColors = CHART_FALLBACK_COLORS.map((fallback, i) => get(`--chart-${i + 1}`, fallback));
  return {
    fg: get("--fg", DEFAULT_THEME_TOKENS.fg),
    fgMuted: get("--fg-muted", DEFAULT_THEME_TOKENS.fgMuted),
    bg: get("--bg", DEFAULT_THEME_TOKENS.bg),
    bgMuted: get("--bg-muted", DEFAULT_THEME_TOKENS.bgMuted),
    border: get("--border", DEFAULT_THEME_TOKENS.border),
    accent: get("--accent", DEFAULT_THEME_TOKENS.accent),
    chartColors,
  };
}
