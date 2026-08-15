/*
 * Bridge from the App Builder's `--saiku-app-*` design tokens to the ECharts
 * theme tokens the chart builders already consume.
 *
 * Why this exists: chart tiles used to colour themselves from
 * `resolveThemeTokens()`, which reads `:root` — i.e. the *Saiku UI* theme, not
 * the app's. Inside a branded app that is simply the wrong palette, and it is
 * invisible until you switch preset: flipping FoodMart Ops to "Dark Ops" turned
 * the shell dark and left the chart with dark-green-on-dark titles, unreadable
 * axis labels and cream gridlines. An app's charts have to read the app's
 * tokens.
 *
 * Two layers, both pure and DOM-free except where noted:
 *
 *   appChartTokens()          brand tokens -> ThemeTokens (for the Chart tile,
 *                             which builds its whole option from tokens)
 *   withAppEchartsDefaults()  fills a hand-authored ECharts option's gaps from
 *                             those tokens, author always winning (for the raw
 *                             "ECharts option" tile — so an author who omits
 *                             colours inherits the theme instead of having to
 *                             hard-code hex, which is what made the seed app
 *                             un-rethemeable)
 *
 * resolveChartTokensFor() is the only DOM-touching export.
 */

import {
	CHART_FALLBACK_COLORS,
	applyColorBlindSafe,
	resolveThemeTokens,
	type ThemeTokens
} from '$lib/views/chartTheme';
import { theme } from '$lib/stores/theme.svelte';

/** The subset of `--saiku-app-*` that drives chart appearance. */
export interface AppBrandTokens {
	fg: string;
	muted: string;
	surface: string;
	ground: string;
	cardBorder: string;
	accent: string;
	accent2: string;
	accentStrong: string;
	positive: string;
	danger: string;
	fontBody: string;
	fontDisplay: string;
}

/** Brand token -> CSS custom property. Also the read list for the DOM resolver. */
export const APP_CHART_VARS: Record<keyof AppBrandTokens, string> = {
	fg: '--saiku-app-fg',
	muted: '--saiku-app-muted',
	surface: '--saiku-app-surface',
	ground: '--saiku-app-ground',
	cardBorder: '--saiku-app-card-border',
	accent: '--saiku-app-accent',
	accent2: '--saiku-app-accent-2',
	accentStrong: '--saiku-app-accent-strong',
	positive: '--saiku-app-positive',
	danger: '--saiku-app-danger',
	fontBody: '--saiku-app-font-body',
	fontDisplay: '--saiku-app-font-display'
};

const HEX = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i;

/** Parse #rgb / #rrggbb to [r,g,b]; null for anything else (incl. rgb()/oklch,
 *  which we deliberately don't try to interpolate). */
export function parseHex(v: string): [number, number, number] | null {
	const s = v.trim();
	if (!HEX.test(s)) return null;
	const h = s.length === 4 ? `#${s[1]}${s[1]}${s[2]}${s[2]}${s[3]}${s[3]}` : s;
	return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
}

function toHex(c: [number, number, number]): string {
	return `#${c
		.map((n) =>
			Math.max(0, Math.min(255, Math.round(n)))
				.toString(16)
				.padStart(2, '0')
		)
		.join('')}`;
}

/** Linear blend of two hex colours. `t` is the weight of `b` (0 = a, 1 = b).
 *  Returns `a` unchanged when either side isn't parseable. */
export function mixHex(a: string, b: string, t: number): string {
	const ca = parseHex(a);
	const cb = parseHex(b);
	if (!ca || !cb) return a;
	const k = Math.max(0, Math.min(1, t));
	return toHex([0, 1, 2].map((i) => ca[i] + (cb[i] - ca[i]) * k) as [number, number, number]);
}

/** Number of categorical series colours a palette must supply — matches the
 *  `--chart-1..8` contract the global theme uses. */
export const PALETTE_SIZE = 8;

/**
 * Build a categorical palette from the brand colours.
 *
 * The brand only names five hues, so the first slots are the real brand
 * colours (deduped, in decreasing prominence) and the remainder are tints of
 * those same hues blended toward the card surface. That keeps a many-series
 * chart inside the brand rather than falling back to the stock indigo ramp
 * half way through, which would look like two palettes stitched together.
 *
 * Falls back wholesale to CHART_FALLBACK_COLORS when no brand colour parses.
 */
export function appChartPalette(b: AppBrandTokens): string[] {
	const seeds: string[] = [];
	for (const c of [b.accent, b.accent2, b.positive, b.danger, b.accentStrong]) {
		if (parseHex(c) && !seeds.includes(c)) seeds.push(c);
	}
	if (seeds.length === 0) return [...CHART_FALLBACK_COLORS];

	const out = [...seeds];
	const surface = parseHex(b.surface) ? b.surface : '#ffffff';
	// Successive passes lighten a little more, so slot 6 and slot 11 of the same
	// hue stay distinguishable.
	for (let pass = 1; out.length < PALETTE_SIZE; pass++) {
		for (const seed of seeds) {
			if (out.length >= PALETTE_SIZE) break;
			out.push(mixHex(seed, surface, Math.min(0.72, 0.3 * pass)));
		}
	}
	return out.slice(0, PALETTE_SIZE);
}

/** Number of stops in the sequential ramp handed to a heatmap / choropleth
 *  visualMap. Three is enough for ECharts to interpolate a readable gradient
 *  without the mid-tone washing out against the card. */
const RAMP_STOPS = 3;

/**
 * A low → high sequential ramp in the brand's own hue (saiku#1799).
 *
 * Built by blending the accent toward the card surface rather than toward white,
 * so the pale end of the ramp sits on the card instead of hovering above it —
 * on a dark app that means the low end goes DARKER, which is what "low" should
 * look like there. Returns null when the accent isn't a parseable hex, and the
 * caller then falls back to a named ramp rather than inventing a gradient.
 */
export function appSequentialRamp(b: AppBrandTokens): string[] | null {
	if (!parseHex(b.accent)) return null;
	const surface = parseHex(b.surface) ? b.surface : '#ffffff';
	const stops: string[] = [];
	for (let i = 0; i < RAMP_STOPS; i++) {
		// i = 0 is almost all surface (the "no value here" end), the last stop is
		// the accent at full strength.
		const towardSurface = 1 - i / (RAMP_STOPS - 1);
		stops.push(mixHex(b.accent, surface, towardSurface * 0.88));
	}
	return stops;
}

/** Map the app's brand tokens onto the ECharts theme tokens the chart builders
 *  consume. Pure — callers overlay the colour-blind-safe preference. */
export function appChartTokens(b: AppBrandTokens): ThemeTokens {
	const ramp = appSequentialRamp(b);
	return {
		fg: b.fg,
		fgMuted: b.muted,
		// A tile's chart sits on the card, not the page ground — the card is what
		// the tooltip and any solid backdrop must match.
		bg: b.surface,
		bgMuted: b.ground,
		border: b.cardBorder,
		accent: b.accent,
		chartColors: appChartPalette(b),
		highContrast: false,
		// saiku#1799: the sign-encoding and magnitude-encoding charts (waterfall,
		// heatmap, choropleth) had no theme colour to reach for and shipped
		// literals. Only set when the brand names something parseable, so the
		// builder's own fallbacks stay in charge otherwise.
		...(parseHex(b.positive) ? { positive: b.positive } : {}),
		...(parseHex(b.danger) ? { danger: b.danger } : {}),
		...(ramp ? { sequentialRamp: ramp } : {})
	};
}

/* ------------------------------------------------------------------ *
 * DOM resolution
 * ------------------------------------------------------------------ */

/** Read the brand tokens off the nearest `[data-saiku-app]` ancestor of `el`.
 *  Returns null when `el` is outside an App Builder app (a plain dashboard, the
 *  workspace) or there's no DOM — callers then use the global theme. */
export function readAppBrandTokens(el: Element | null | undefined): AppBrandTokens | null {
	if (!el || typeof window === 'undefined' || typeof getComputedStyle !== 'function') return null;
	const root = el.closest('[data-saiku-app]');
	if (!root) return null;
	const cs = getComputedStyle(root);
	const out = {} as AppBrandTokens;
	for (const [key, cssVar] of Object.entries(APP_CHART_VARS) as [keyof AppBrandTokens, string][]) {
		out[key] = cs.getPropertyValue(cssVar).trim();
	}
	// A root with no resolvable accent isn't themed — don't fabricate a palette.
	return out.accent ? out : null;
}

/** The chart tokens for a tile: the app's when the tile is inside an app, the
 *  global Saiku theme otherwise. The colour-blind-safe preference overlays
 *  either — accessibility outranks branding. */
export function resolveChartTokensFor(el: Element | null | undefined): ThemeTokens {
	const brand = readAppBrandTokens(el);
	if (!brand) return resolveThemeTokens();
	return applyColorBlindSafe(appChartTokens(brand), theme.colorBlindSafe);
}

/* ------------------------------------------------------------------ *
 * Defaults for hand-authored ECharts options
 * ------------------------------------------------------------------ */

/** Axis-level defaults applied to every x/y axis the author declares. */
function axisDefaults(t: ThemeTokens): Record<string, unknown> {
	return {
		axisLabel: { color: t.fgMuted },
		axisLine: { lineStyle: { color: t.border } },
		splitLine: { lineStyle: { color: t.border } }
	};
}

/**
 * Components whose defaults may only be applied when the author declared them.
 *
 * In ECharts, *mentioning* a component switches it on: an option carrying
 * `legend: {}` renders a legend, and a pie chart carrying `xAxis: {}` grows a
 * pair of axes through the middle of it. So these can never be introduced —
 * only decorated when already present.
 */
const CONDITIONAL_COMPONENTS = ['legend', 'tooltip', 'xAxis', 'yAxis'] as const;

/**
 * The themed baseline a hand-authored ECharts option is layered on top of.
 * Everything here is a *default*: `withAppEchartsDefaults` lets the author's
 * value win at every leaf, and drops the conditional components entirely when
 * the author didn't ask for them.
 */
export function appEchartsBase(
	t: ThemeTokens,
	fonts: { body: string; display: string }
): Record<string, unknown> {
	return {
		color: t.chartColors,
		backgroundColor: 'transparent',
		textStyle: { color: t.fg, fontFamily: fonts.body },
		title: {
			textStyle: { color: t.fg, fontFamily: fonts.display },
			subtextStyle: { color: t.fgMuted, fontFamily: fonts.body }
		},
		legend: { textStyle: { color: t.fgMuted } },
		tooltip: {
			backgroundColor: t.bg,
			borderColor: t.border,
			textStyle: { color: t.fg }
		},
		xAxis: axisDefaults(t),
		yAxis: axisDefaults(t)
	};
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
	return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Recursive "fill the gaps" merge. `override` always wins at a leaf; only
 *  plain objects recurse. Never mutates either input. */
function fillFrom(
	base: Record<string, unknown>,
	override: Record<string, unknown>
): Record<string, unknown> {
	const out: Record<string, unknown> = { ...base };
	for (const [k, v] of Object.entries(override)) {
		const b = out[k];
		out[k] = isPlainObject(b) && isPlainObject(v) ? fillFrom(b, v) : v;
	}
	return out;
}

/** A declared component may be a single object or an array of them (ECharts
 *  allows both for axes); the defaults have to reach every entry either way. */
function fillDeclared(baseValue: unknown, authorValue: unknown): unknown {
	if (!isPlainObject(baseValue)) return authorValue;
	if (Array.isArray(authorValue)) {
		return authorValue.map((a) => (isPlainObject(a) ? fillFrom(baseValue, a) : a));
	}
	return isPlainObject(authorValue) ? fillFrom(baseValue, authorValue) : authorValue;
}

/**
 * Layer a hand-authored ECharts option over the themed baseline.
 *
 * The author's option is authoritative wherever it speaks — an author who
 * hard-codes `series[0].lineStyle.color` still gets that colour. What changes
 * is the *silence*: an option that doesn't mention title colour, axis label
 * colour or the series palette now inherits them from the app theme, so
 * re-theming the app re-themes its charts.
 *
 * Silence about a whole component is respected too: see
 * {@link CONDITIONAL_COMPONENTS}. Introducing one would change what the chart
 * *is*, not how it looks.
 */
export function withAppEchartsDefaults(
	option: Record<string, unknown>,
	base: Record<string, unknown>
): Record<string, unknown> {
	const unconditional: Record<string, unknown> = { ...base };
	for (const k of CONDITIONAL_COMPONENTS) delete unconditional[k];

	const merged = fillFrom(unconditional, option);
	for (const k of CONDITIONAL_COMPONENTS) {
		if (option[k] === undefined) {
			delete merged[k];
			continue;
		}
		merged[k] = fillDeclared(base[k], option[k]);
	}
	return merged;
}
