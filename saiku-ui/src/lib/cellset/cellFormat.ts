/*
 * Parse Mondrian's inline cell-formatting markers.
 *
 * Mondrian renders conditional-format hints by wrapping the formatted
 * value in pipe characters with a trailing `style=<css>` token, e.g.
 *
 *   |($2,561.57)|style=red
 *
 * Schema-defined calculated members (FoodMart Profit being the canonical
 * example) emit this whenever their format string carries a coloured
 * negative branch. The legacy Saiku UI rendered the inner value in the
 * declared colour; the rewrite was passing the whole string through
 * verbatim, so cells looked like literal markup.
 *
 * Conservative parser: only matches the canonical
 *   ^\|(.*)\|style=<colour>$
 * shape. Anything else returns { display: raw } so non-conforming text
 * (or future Mondrian markers we don't yet know about) renders as-is.
 */

/** Hex (#fff, #ffffff) or CSS colour keyword. Mondrian only ever
 *  emits one of these — no rgba()/hsl() in the wild. Keep the regex
 *  conservative so we don't accidentally swallow ordinary pipe-bracketed
 *  text. */
const MARKER_RE = /^\|(.*)\|style=(#[0-9a-fA-F]{3,8}|[a-zA-Z]+)$/;

export interface ParsedCell {
	/** What to render in the cell. */
	display: string;
	/** Inline CSS colour to apply, if the marker carried one. */
	color?: string;
}

export function parseFormattedCell(raw: string | null | undefined): ParsedCell {
	if (raw == null) return { display: '' };
	const m = MARKER_RE.exec(raw);
	if (!m) return { display: raw };
	return { display: m[1], color: m[2] };
}
