/*
 * Brand-brief → theme generator (Phase D of graphical authoring). Turns a
 * plain-English brand description ("dark control-room look, cyan accent,
 * rounded cards") into a theme patch the Brand & Theme inspector applies.
 *
 * This is a deterministic, rules-based parser — fully offline + unit-testable.
 * It's deliberately shaped as `(brief) => Partial<AppTheme>` so a real LLM call
 * can be swapped in later behind the same signature (the inspector doesn't care
 * how the patch was produced).
 */

import type { AppTheme } from '$lib/api/apps';

/** Named colours a brief might mention → a token-friendly hex. */
const COLOUR_WORDS: Record<string, string> = {
	green: '#2e7d55',
	emerald: '#1f7a4d',
	forest: '#2e5e43',
	blue: '#2f6fed',
	navy: '#1e3a8a',
	teal: '#2f9e94',
	cyan: '#37c2c9',
	red: '#d0533f',
	crimson: '#c02d3a',
	orange: '#e0703a',
	terracotta: '#c85a3a',
	amber: '#e0a020',
	gold: '#c9a227',
	purple: '#7a5af0',
	violet: '#6d4bd6',
	indigo: '#4b52d6',
	pink: '#d6538f',
	magenta: '#c02b8a',
	slate: '#4a5568',
	charcoal: '#2b2f36',
	black: '#111418'
};

/** Every colour mentioned in the brief, as hex, in order of first appearance
 *  and de-duplicated. Covers explicit hex codes and known colour words. */
function coloursInOrder(brief: string): string[] {
	const found: { pos: number; hex: string }[] = [];
	const hexRe = /#(?:[0-9a-f]{3}|[0-9a-f]{6})\b/gi;
	for (let m = hexRe.exec(brief); m; m = hexRe.exec(brief)) {
		found.push({ pos: m.index, hex: m[0] });
	}
	for (const [word, value] of Object.entries(COLOUR_WORDS)) {
		const wm = brief.match(new RegExp(`\\b${word}\\b`, 'i'));
		if (wm && wm.index != null) found.push({ pos: wm.index, hex: value });
	}
	found.sort((a, b) => a.pos - b.pos);
	const out: string[] = [];
	for (const f of found) if (!out.includes(f.hex)) out.push(f.hex);
	return out;
}

function has(brief: string, ...words: string[]): boolean {
	return words.some((w) => new RegExp(`\\b${w}\\b`, 'i').test(brief));
}

/**
 * Parse a brand brief into a theme patch. Always returns at least a `preset`
 * (the closest base), plus any colour / type / form overrides the words imply.
 * Never throws; an empty/vague brief yields a sensible default.
 */
export function themeFromBrief(brief: string): Partial<AppTheme> {
	const b = (brief ?? '').toLowerCase();
	const patch: Partial<AppTheme> = {};

	// 1. Base preset from vibe words.
	if (has(b, 'dark', 'night', 'control ?room', 'ops', 'terminal')) patch.preset = 'dark-ops';
	else if (has(b, 'minimal', 'clean', 'simple', 'spare', 'understated')) patch.preset = 'minimal';
	else if (has(b, 'editorial', 'magazine', 'serif', 'warm', 'cream', 'elegant', 'premium'))
		patch.preset = 'editorial';
	else if (has(b, 'corporate', 'enterprise', 'business', 'professional', 'bank'))
		patch.preset = 'corporate';
	else patch.preset = 'corporate'; // neutral default

	patch.mode = patch.preset === 'dark-ops' ? 'dark' : 'light';

	// 2. Accent colour, and a distinct second colour → brand-mark accent2.
	const colours = coloursInOrder(brief ?? '');
	if (colours[0]) patch.accent = colours[0];
	if (colours[1]) patch.accent2 = colours[1];

	// 3. Type.
	if (has(b, 'serif', 'editorial', 'magazine', 'elegant')) patch.fontDisplay = 'serif-1';
	else if (has(b, 'sans', 'modern', 'clean')) patch.fontDisplay = 'sans-1';
	/* saiku#1763: scope the monospace request to the NOUN it modifies. It used to
	 * land on headings whatever was asked for, so "monospace figures" — the
	 * request a data product actually makes — set monospace headings and left the
	 * figures in the body sans, the exact opposite. Numbers has its own token,
	 * and its own help text describing this case. */
	if (has(b, 'mono', 'monospace', 'technical', 'code')) {
		if (has(b, 'figures', 'numbers', 'numerals', 'digits')) {
			patch.numerals = 'mono';
		} else if (has(b, 'body', 'copy', 'paragraph', 'prose')) {
			patch.fontBody = 'mono-1';
		} else {
			// Unqualified — headings, as before.
			patch.fontDisplay = 'mono-1';
			patch.fontBody = 'sans-1';
		}
	}

	// 4. Form.
	if (has(b, 'rounded', 'friendly', 'soft', 'pill')) patch.radius = 'xl';
	else if (has(b, 'sharp', 'square', 'crisp', 'hard')) patch.radius = 'none';
	// saiku#1763: "hairline" describes a 1px border with no elevation — it was
	// falling through to the "card" branch below and coming out with a shadow.
	if (has(b, 'flat', 'no shadow', 'borderless', 'hairline')) patch.shadow = 'none';
	else if (has(b, 'elevated', 'floating', 'depth', 'card')) patch.shadow = 'lg';
	if (has(b, 'compact', 'dense', 'tight')) patch.density = 'compact';
	else if (has(b, 'airy', 'spacious', 'roomy', 'comfortable')) patch.density = 'comfortable';

	return patch;
}
