import type { AppTheme } from "$lib/api/apps";

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

export function themeVars(theme: AppTheme): Record<string, string> {
  const out: Record<string, string> = {};
  const set = (k: string, v: string | undefined) => {
    if (v !== undefined) out[k] = v;
  };
  set("--saiku-app-primary", colour(theme.primary));
  set("--saiku-app-accent", colour(theme.accent));
  set("--saiku-app-bg", colour(theme.bg));
  set("--saiku-app-fg", colour(theme.fg));
  set("--saiku-app-font", resolveFont(theme.font));
  return out;
}
