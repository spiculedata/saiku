/*
 * Theme presets + token resolution for the App Builder (Phase A of graphical
 * authoring). A preset is a curated token set; the resolver layers
 *   DEFAULTS  <  preset tokens  <  the app's explicit token overrides
 * and the result is what appTheme.ts serialises to `--saiku-app-*` CSS vars.
 *
 * This is the data layer behind the Brand & Theme inspector: the picker writes
 * token fields onto AppTheme; the gallery writes `preset`. No component reads
 * this directly — they read the CSS vars.
 */

import type { AppTheme } from "$lib/api/apps";

/** The fully-resolved token set — every field present, ready to serialise. */
export interface ResolvedTokens {
  ground: string;
  surface: string;
  fg: string;
  muted: string;
  accent: string;
  accent2: string;
  accentSoft: string;
  accentStrong: string;
  danger: string;
  positive: string;
  cardBorder: string;
  railBg: string;
  railFg: string;
  fontDisplay: string; // font-allowlist key
  fontBody: string; // font-allowlist key
  /** Font-allowlist key figures use (KPI headline, numeric table cells). */
  numerals: "body" | "display" | "mono";
  radius: "none" | "sm" | "md" | "lg" | "xl";
  shadow: "none" | "sm" | "md" | "lg";
  density: "compact" | "cozy" | "comfortable";
  /** Coloured edge bar on KPI tiles, keyed to the delta direction. */
  kpiAccent: "none" | "tone";
}

/** Neutral light default — a considered off-white/ink base, not stark. */
export const DEFAULT_TOKENS: ResolvedTokens = {
  ground: "#f6f5f2",
  surface: "#ffffff",
  fg: "#1c2430",
  muted: "#75808f",
  accent: "#2f6fed",
  accent2: "#2f6fed",
  accentSoft: "#e8effd",
  accentStrong: "#2456c6",
  danger: "#d0533f",
  positive: "#2e7d55",
  cardBorder: "#e7e6e1",
  railBg: "#1b2431",
  railFg: "#9aa6b6",
  fontDisplay: "sans-1",
  fontBody: "sans-1",
  numerals: "body",
  radius: "md",
  shadow: "sm",
  density: "cozy",
  kpiAccent: "none",
};

/** One entry in the preset gallery. */
export interface ThemePreset {
  key: string;
  label: string;
  /** One-line character note shown under the swatch. */
  note: string;
  tokens: ResolvedTokens;
}

export const THEME_PRESETS: ThemePreset[] = [
  {
    key: "editorial",
    label: "Editorial",
    note: "Warm cream ground, serif headings, green accent — the FoodMart Ops look.",
    tokens: {
      ground: "#f2eee4",
      surface: "#ffffff",
      fg: "#1f3529",
      muted: "#8a7f68",
      accent: "#2e5e43",
      accent2: "#c85a3a",
      accentSoft: "#eaf3ec",
      accentStrong: "#2e5e43",
      danger: "#c85a3a",
      positive: "#2e5e43",
      cardBorder: "#ece7db",
      railBg: "#1f352a",
      railFg: "#9fb4a5",
      fontDisplay: "serif-1",
      fontBody: "sans-1",
      numerals: "mono",
      radius: "lg",
      shadow: "md",
      density: "cozy",
      kpiAccent: "tone",
    },
  },
  {
    key: "minimal",
    label: "Minimal",
    note: "White ground, hairline borders, no shadows, tight ink accent.",
    tokens: {
      ...DEFAULT_TOKENS,
      ground: "#ffffff",
      surface: "#ffffff",
      fg: "#111418",
      muted: "#8a9099",
      accent: "#111418",
      accent2: "#111418",
      accentSoft: "#eef0f2",
      accentStrong: "#111418",
      cardBorder: "#e6e8ea",
      railBg: "#111418",
      railFg: "#9aa0a8",
      radius: "sm",
      shadow: "none",
      density: "comfortable",
    },
  },
  {
    key: "dark-ops",
    label: "Dark Ops",
    note: "Charcoal ground, elevated surfaces, cyan accent — a control-room feel.",
    tokens: {
      ground: "#0f141b",
      surface: "#18202b",
      fg: "#e6edf3",
      muted: "#8b97a6",
      accent: "#37c2c9",
      accent2: "#e8935f",
      accentSoft: "#12313a",
      accentStrong: "#5fe0e6",
      danger: "#e26d5a",
      positive: "#4fd18b",
      cardBorder: "#243040",
      railBg: "#0a0e13",
      railFg: "#7c8896",
      fontDisplay: "sans-1",
      fontBody: "sans-1",
      numerals: "mono",
      radius: "md",
      shadow: "lg",
      density: "cozy",
      kpiAccent: "tone",
    },
  },
  {
    key: "corporate",
    label: "Corporate",
    note: "Cool grey ground, blue accent, crisp cards — a familiar BI baseline.",
    tokens: {
      ...DEFAULT_TOKENS,
      ground: "#eef1f5",
      surface: "#ffffff",
      fg: "#1c2430",
      accent: "#2f6fed",
      accentSoft: "#e8effd",
      cardBorder: "#e2e6ec",
      railBg: "#182233",
      railFg: "#9aa6b6",
      radius: "md",
      shadow: "md",
      density: "cozy",
    },
  },
];

export function presetByKey(key: string | undefined): ThemePreset | undefined {
  return key ? THEME_PRESETS.find((p) => p.key === key) : undefined;
}

/** Layer DEFAULTS < preset < explicit token overrides (+ legacy field mapping)
 *  into a fully-resolved token set. */
export function resolveTokens(theme: AppTheme): ResolvedTokens {
  const base = presetByKey(theme.preset)?.tokens ?? DEFAULT_TOKENS;
  const legacy: Partial<ResolvedTokens> = {};
  // Map pre-token fields onto the new tokens so old apps still theme.
  if (theme.bg) legacy.ground = theme.bg;
  if (theme.font) {
    legacy.fontDisplay = theme.font;
    legacy.fontBody = theme.font;
  }
  const pick = <K extends keyof ResolvedTokens>(k: K): ResolvedTokens[K] =>
    (theme[k as keyof AppTheme] as ResolvedTokens[K] | undefined) ??
    (legacy[k] as ResolvedTokens[K] | undefined) ??
    base[k];
  return {
    ground: pick("ground"),
    surface: pick("surface"),
    fg: pick("fg"),
    muted: pick("muted"),
    accent: pick("accent"),
    accent2: pick("accent2"),
    accentSoft: pick("accentSoft"),
    accentStrong: pick("accentStrong"),
    danger: pick("danger"),
    positive: pick("positive"),
    cardBorder: pick("cardBorder"),
    railBg: pick("railBg"),
    railFg: pick("railFg"),
    fontDisplay: pick("fontDisplay"),
    fontBody: pick("fontBody"),
    numerals: pick("numerals"),
    radius: pick("radius"),
    shadow: pick("shadow"),
    density: pick("density"),
    kpiAccent: pick("kpiAccent"),
  };
}

/** Named form-scale → concrete CSS value. */
export const RADIUS_SCALE: Record<ResolvedTokens["radius"], string> = {
  none: "0",
  sm: "6px",
  md: "10px",
  lg: "14px",
  xl: "20px",
};
export const SHADOW_SCALE: Record<ResolvedTokens["shadow"], string> = {
  none: "none",
  sm: "0 1px 2px rgba(20,28,40,0.06)",
  md: "0 1px 2px rgba(20,28,40,0.05), 0 8px 24px rgba(20,32,40,0.07)",
  lg: "0 2px 4px rgba(0,0,0,0.18), 0 16px 40px rgba(0,0,0,0.28)",
};
/** Density → tile/card inner padding. */
export const DENSITY_PAD: Record<ResolvedTokens["density"], string> = {
  compact: "0.5rem",
  cozy: "0.75rem",
  comfortable: "1rem",
};
