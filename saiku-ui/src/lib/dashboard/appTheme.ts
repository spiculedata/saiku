import type { AppTheme } from '$lib/api/apps';
import {
	resolveTokens,
	RADIUS_SCALE,
	SHADOW_SCALE,
	DENSITY_PAD
} from '$lib/dashboard/appThemePresets';

export const FONT_ALLOWLIST = [
	{
		key: 'sans-1',
		label: 'System sans',
		stack: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif'
	},
	{ key: 'serif-1', label: 'Editorial serif', stack: "Georgia, 'Times New Roman', serif" },
	{ key: 'mono-1', label: 'Monospace', stack: 'ui-monospace, SFMono-Regular, Menlo, monospace' }
] as const;

export function resolveFont(key: string | undefined): string {
	const hit = FONT_ALLOWLIST.find((f) => f.key === key);
	return (hit ?? FONT_ALLOWLIST[0]).stack;
}

/** Options for the "Numbers" type control — which stack figures are set in. */
export const NUMERAL_CHOICES = [
	{ key: 'body', label: 'Same as body' },
	{ key: 'display', label: 'Same as headings' },
	{ key: 'mono', label: 'Monospace' }
] as const;

/** Resolve the numerals choice to a concrete font stack. "mono" is the
 *  allowlist's monospace entry; the others alias the display/body stacks so
 *  changing those carries through without a second edit. */
export function numeralStack(
	numerals: 'body' | 'display' | 'mono',
	fontDisplay: string,
	fontBody: string
): string {
	if (numerals === 'mono') return resolveFont('mono-1');
	return resolveFont(numerals === 'display' ? fontDisplay : fontBody);
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
	setColour('--saiku-app-ground', t.ground);
	setColour('--saiku-app-surface', t.surface);
	setColour('--saiku-app-card', t.surface);
	setColour('--saiku-app-fg', t.fg);
	setColour('--saiku-app-muted', t.muted);
	setColour('--saiku-app-accent', t.accent);
	setColour('--saiku-app-accent-2', t.accent2);
	setColour('--saiku-app-accent-soft', t.accentSoft);
	setColour('--saiku-app-accent-strong', t.accentStrong);
	setColour('--saiku-app-danger', t.danger);
	setColour('--saiku-app-positive', t.positive);
	setColour('--saiku-app-card-border', t.cardBorder);
	setColour('--saiku-app-rail-bg', t.railBg);
	setColour('--saiku-app-rail-fg', t.railFg);
	out['--saiku-app-font-display'] = resolveFont(t.fontDisplay);
	out['--saiku-app-font-body'] = resolveFont(t.fontBody);
	out['--saiku-app-font-numeric'] = numeralStack(t.numerals, t.fontDisplay, t.fontBody);
	out['--saiku-app-radius'] = RADIUS_SCALE[t.radius];
	out['--saiku-app-shadow'] = SHADOW_SCALE[t.shadow];
	out['--saiku-app-pad'] = DENSITY_PAD[t.density];
	// Expressed as a width rather than a display toggle so the skin can keep one
	// unconditional rule and let the token decide whether the bar is visible.
	out['--saiku-app-kpi-bar'] = t.kpiAccent === 'tone' ? '3px' : '0px';

	// Legacy aliases — keep old components + author CSS working.
	setColour('--saiku-app-primary', colour(theme.primary) ?? t.accent);
	setColour('--saiku-app-bg', t.ground);
	out['--saiku-app-font'] = resolveFont(t.fontBody);
	return out;
}
