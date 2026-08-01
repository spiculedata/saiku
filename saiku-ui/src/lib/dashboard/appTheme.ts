import type { AppTheme } from "$lib/api/apps";
import { resolveTokens, RADIUS_SCALE, SHADOW_SCALE, DENSITY_PAD } from "$lib/dashboard/appThemePresets";

export const FONT_ALLOWLIST = [
  { key: "sans-1", label: "System sans", stack: "system-ui, -apple-system, Segoe UI, Roboto, sans-serif" },
  { key: "serif-1", label: "Editorial serif", stack: "Georgia, 'Times New Roman', serif" },
  { key: "mono-1", label: "Monospace", stack: "ui-monospace, SFMono-Regular, Menlo, monospace" },
] as const;

export function resolveFont(key: string | undefined): string {
  const hit = FONT_ALLOWLIST.find((f) => f.key === key);
  return (hit ?? FONT_ALLOWLIST[0]).stack;
}

const COLOUR = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
function colour(v: string | undefined): string | undefined {
  return v && COLOUR.test(v) ? v : undefined;
}

/**
 * Serialise a theme to the full `--saiku-app-*` CSS-var set the shell renders
 * from. Resolves DEFAULTS < preset < explicit tokens (see appThemePresets),
 * maps the named form scales to concrete values, and keeps the legacy
 * bg/fg/accent/font vars pointing at the resolved tokens so pre-token
 * components (and any customCss referencing them) keep working.
 */
export function themeVars(theme: AppTheme): Record<string, string> {
  const t = resolveTokens(theme);
  const out: Record<string, string> = {};
  const setColour = (k: string, v: string) => {
    const c = colour(v);
    if (c) out[k] = c;
  };
  setColour("--saiku-app-ground", t.ground);
  setColour("--saiku-app-surface", t.surface);
  setColour("--saiku-app-card", t.surface);
  setColour("--saiku-app-fg", t.fg);
  setColour("--saiku-app-muted", t.muted);
  setColour("--saiku-app-accent", t.accent);
  setColour("--saiku-app-accent-2", t.accent2);
  setColour("--saiku-app-accent-soft", t.accentSoft);
  setColour("--saiku-app-accent-strong", t.accentStrong);
  setColour("--saiku-app-danger", t.danger);
  setColour("--saiku-app-positive", t.positive);
  setColour("--saiku-app-card-border", t.cardBorder);
  setColour("--saiku-app-rail-bg", t.railBg);
  setColour("--saiku-app-rail-fg", t.railFg);
  out["--saiku-app-font-display"] = resolveFont(t.fontDisplay);
  out["--saiku-app-font-body"] = resolveFont(t.fontBody);
  out["--saiku-app-radius"] = RADIUS_SCALE[t.radius];
  out["--saiku-app-shadow"] = SHADOW_SCALE[t.shadow];
  out["--saiku-app-pad"] = DENSITY_PAD[t.density];

  // Legacy aliases — keep old components + author CSS working.
  setColour("--saiku-app-primary", colour(theme.primary) ?? t.accent);
  setColour("--saiku-app-bg", t.ground);
  out["--saiku-app-font"] = resolveFont(t.fontBody);
  return out;
}
