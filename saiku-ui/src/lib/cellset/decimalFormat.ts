/*
 * Display-only decimal rounding for workspace cellset cells (saiku#1988).
 *
 * Mondrian hands the UI a *formatted* string for every cell — `$2,561.57`,
 * `1.234,56 €`, `(2,561.57)|style=red` — because the schema's `FORMAT_STRING`
 * owns the presentation. Users often want a lighter view ("give me whole
 * pesos") without touching the schema, the MDX or the underlying numbers.
 *
 * {@link roundCellDisplay} therefore re-renders only the numeric token of the
 * display string with a fixed number of decimals, leaving every affine (sign,
 * currency symbol, thousands separators, trailing `%`, parenthesised negative
 * accounting form) exactly as the server formatted it.
 *
 * It is purely cosmetic: nothing here feeds drillthrough, export, the copy
 * action or the MDX — those keep the server formatting. Only the rendered
 * text of a data cell is affected (see `CellsetTable.svelte`).
 *
 * Pure, dependency-free and unit-testable outside the SvelteKit harness.
 */

/** Max decimals a caller may ask for — matches the chart numberFormat clamp
 *  so both surfaces behave identically. */
export const MAX_DECIMALS = 20;

/** Default upper bound of the picker options offered in the prefs menu. */
export const DEFAULT_MAX_OPTIONS = 4;

/** The numeric token inside a display string, with everything before it
 *  (`prefix`) and after it (`suffix`) captured separately so we can put the
 *  affixes back verbatim. Trailing separators/spaces are re-attached to the
 *  suffix by the caller, so they are not matched here. */
const NUMERIC_RE = /^([^\p{L}\d]*)([-+]?\d[\d.,  ]*\d|\d)([^\p{L}]*)$/u;

interface Parts {
	prefix: string;
	token: string;
	suffix: string;
}

function splitDisplay(display: string): Parts | null {
	// Only touch pure-number-ish text. A cell that carries letters is either
	// a member caption or an already-aggregated label; leave it alone.
	const m = NUMERIC_RE.exec(display.trim());
	if (!m) return null;
	const prefix = m[1];
	const token = m[2];
	const suffix = m[3];
	return { prefix, token, suffix };
}

/** Decide which of `.` / `,` is the decimal separator inside a numeric token,
 *  and whether the token used digit grouping. Handles the en/US form
 *  (`1,234.56`) and the de/fr/es form (`1.234,56`) without a locale
 *  parameter — we only need enough fidelity to round and re-print.
 *
 *  A separator is grouping when it occurs more than once; a lone separator
 *  is read as a decimal point. That keeps the default en-US schema formatting
 *  (`1234.567` → `1235`) exact, at the cost of the rare de/es cell that shows
 *  a lone grouping separator with a one-digit thousands group (`1.234`).
 *
 *  Returns null when the token is not a number at all. */
function parseToken(
	token: string
): { value: number; decSep: '.' | ',' | null; grouped: boolean } | null {
	const sign = token.startsWith('-') ? -1 : 1;
	const digits = token.replace(/^[-+]/, '').replace(/[\s ]/g, '');
	if (!digits) return null;

	const lastDot = digits.lastIndexOf('.');
	const lastComma = digits.lastIndexOf(',');
	const countOf = (ch: string) => digits.split(ch).length - 1;

	let decSep: '.' | ',' | null = null;
	let groupChar: '.' | ',' | null = null;
	if (lastDot >= 0 && lastComma >= 0) {
		// Both present: the right-most one is the decimal separator.
		decSep = lastDot > lastComma ? '.' : ',';
		groupChar = decSep === '.' ? ',' : '.';
	} else if (lastDot >= 0) {
		if (countOf('.') > 1) groupChar = '.';
		else decSep = '.';
	} else if (lastComma >= 0) {
		if (countOf(',') > 1) groupChar = ',';
		else decSep = ',';
	} else {
		// Plain integer digits: the server printed no separators, so we add
		// none either.
		groupChar = null;
	}

	// Split the token into its integer and (possibly empty) fraction. When the
	// token is grouped but has no decimal part (`1234.567`), everything after
	// the last grouping separator is still integer.
	let intPart: string;
	let fracPart: string;
	if (decSep) {
		const decIdx = digits.indexOf(decSep);
		intPart = digits.slice(0, decIdx);
		fracPart = digits.slice(decIdx + 1);
	} else if (groupChar && digits.includes(groupChar)) {
		intPart = digits.slice(0, digits.lastIndexOf(groupChar));
		fracPart = '';
	} else {
		intPart = digits;
		fracPart = '';
	}
	const grouped = groupChar ? countOf(groupChar) > 0 : false;

	const cleanInt = intPart.replace(/[.,]/g, '');
	const cleanFrac = fracPart.replace(/[.,]/g, '');
	if (!/^\d+$/.test(cleanInt)) return null;
	if (fracPart && !/^\d*$/.test(cleanFrac)) return null;

	const value = sign * Number(fracPart ? `${cleanInt}.${cleanFrac}` : cleanInt);
	if (!Number.isFinite(value)) return null;
	return { value, decSep, grouped };
}

/** Re-apply digit grouping in the same style the server used. */
function group(intDigits: string, groupChar: string): string {
	return intDigits.replace(/\B(?=(\d{3})+(?!\d))/g, groupChar);
}

/**
 * Round a cellset display string to `decimals` places. Display-only.
 *
 * @param display the cell's display text (already run through
 *   `parseFormattedCell`, so no `|…|style=` markers remain)
 * @param decimals fixed decimal places, or null/undefined for "auto" — in
 *   which case the string is returned untouched (current behaviour).
 */
export function roundCellDisplay(
	display: string | null | undefined,
	decimals: number | null | undefined
): string {
	if (display == null) return '';
	if (decimals == null || !Number.isFinite(decimals)) return display;
	const d = Math.max(0, Math.min(MAX_DECIMALS, Math.floor(decimals)));
	const parts = splitDisplay(display);
	if (!parts) return display;

	const parsed = parseToken(parts.token);
	if (!parsed) return display;

	// Preserve the accounting form: `(1.23)` means negative, so re-print the
	// magnitude inside the existing parentheses and never add a minus.
	const parenthesised = /[(]\s*$/.test(parts.prefix) && /^\s*[)]/.test(parts.suffix);
	const tokenSign = parts.token.startsWith('-');
	const negative = parsed.value < 0 || parenthesised;
	const magnitude = Math.abs(parsed.value);

	// `-0` formats as `0`, which would silently drop the sign of a small
	// negative that rounds to zero. Keep it: `($0.00)`.
	const roundedToZero = magnitude > 0 && Number(magnitude.toFixed(d)) === 0;
	const body = (negative && roundedToZero ? -magnitude : magnitude).toFixed(d);

	let out = body;
	// Re-print with the separators the server used, so a de/es cell keeps
	// `1.234,56` rather than turning into `1,234.56`.
	const fracStart = out.indexOf('.');
	const intDigits = fracStart === -1 ? out : out.slice(0, fracStart);
	const fracDigits = fracStart === -1 ? '' : out.slice(fracStart + 1);
	let printedInt = intDigits;
	if (parsed.grouped && parsed.decSep) {
		printedInt = group(intDigits, parsed.decSep === ',' ? '.' : ',');
	}
	out = fracDigits ? `${printedInt}${parsed.decSep ?? '.'}${fracDigits}` : printedInt;

	const prefix =
		negative && roundedToZero && !tokenSign && !parenthesised
			? prefixWithSign(parts.prefix)
			: parts.prefix;
	return `${prefix}${out}${parts.suffix}`;
}

/** Keep the server's sign placement. If it used parentheses, the minus was
 *  never in the token, so add nothing; otherwise restore a leading minus. */
function prefixWithSign(prefix: string): string {
	return prefix.endsWith('-') ? prefix : `-${prefix}`;
}
