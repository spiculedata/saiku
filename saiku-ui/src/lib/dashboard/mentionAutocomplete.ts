/*
 * @-mention autocomplete helpers for the comments box (#942 follow-up). Pure
 * (no DOM) so they unit-test cleanly; the component supplies the textarea
 * value + caret offset.
 */

export interface MentionToken {
	/** Index of the triggering '@' in the text. */
	start: number;
	/** The partial username typed after '@' up to the caret (may be ""). */
	query: string;
}

/**
 * The @-mention currently being typed at {@code caret}, or null. A mention is
 * an '@' that starts the text or follows whitespace, with no whitespace between
 * it and the caret — so `user@example.com` (the '@' is mid-word) is NOT a
 * mention, and a completed `@bob ` (trailing space) is no longer in-progress.
 */
export function currentMentionToken(text: string, caret: number): MentionToken | null {
	if (caret < 0 || caret > text.length) {
		return null;
	}
	const upto = text.slice(0, caret);
	const at = upto.lastIndexOf('@');
	if (at < 0) {
		return null;
	}
	const between = upto.slice(at + 1);
	if (/\s/.test(between)) {
		return null;
	}
	const before = at === 0 ? '' : text[at - 1];
	if (before && !/\s/.test(before)) {
		return null; // '@' is mid-word (e.g. an email address) — not a mention
	}
	return { start: at, query: between };
}

/** Replace the in-progress mention at {@code caret} with `@username ` (trailing
 *  space), returning the new text + caret offset. No-op if not in a mention. */
export function applyMention(
	text: string,
	caret: number,
	username: string
): { text: string; caret: number } {
	const tok = currentMentionToken(text, caret);
	if (!tok) {
		return { text, caret };
	}
	const before = text.slice(0, tok.start);
	const after = text.slice(caret);
	const insert = `@${username} `;
	return { text: before + insert + after, caret: before.length + insert.length };
}

/** Case-insensitive substring filter over usernames, capped at {@code limit}. */
export function matchUsers(usernames: string[], query: string, limit = 6): string[] {
	const q = query.toLowerCase();
	return usernames.filter((u) => u.toLowerCase().includes(q)).slice(0, limit);
}
