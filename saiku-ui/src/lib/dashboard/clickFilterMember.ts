/*
 * Pure member-resolution helpers for chart click-to-filter (issue #1166).
 *
 * A chart category click gives us only the leaf *caption* the user clicked
 * (e.g. "Beer") plus the tile's row axis (dimension / hierarchy / level).
 * The dashboard filter contract, however, wants a real MDX *unique name*
 * (e.g. "[Product].[Product].[Drink].[Alcoholic Beverages].[Beer]") — the
 * server rejects a bare caption ("Beer" doesn't match the bracketed-ref
 * regex) and can't hand-resolve it. Worse, we can't reliably rebuild the
 * unique name on the client: it embeds the member's *ancestors* (which a
 * single-level chart category doesn't carry) and the tile may store display
 * aliases for the dim/hier that aren't the canonical MDX names.
 *
 * So instead of assembling a name, we ask the server for the level's real
 * members (GET /ai/members/search) and pick the one whose caption matches
 * the click — its `uniqueName` is the server's own, guaranteed to round-trip
 * back into an /ai/query filter. This module owns only the pure matching so
 * it's unit-testable; the fetch + caching live in the tile component.
 */

/** Minimal shape of a member-search hit this matcher needs. */
export interface MemberHitLike {
	uniqueName: string;
	caption: string;
}

/** Normalise a caption for comparison: trimmed, case-folded, inner
 *  whitespace collapsed (member captions occasionally arrive padded). */
function norm(s: string): string {
	return s.trim().replace(/\s+/g, ' ').toLowerCase();
}

/** The trailing bracketed segment of an MDX unique name, unwrapped:
 *  "[Product].[Drink].[Beer]" -> "Beer". Returns "" when there's no
 *  bracketed tail (so it never accidentally matches a blank caption). */
export function leafSegment(uniqueName: string): string {
	const matches = uniqueName.match(/\[([^\]]*)\]/g);
	if (!matches || matches.length === 0) return '';
	const last = matches[matches.length - 1];
	return last.slice(1, -1);
}

/**
 * Pick the unique name of the member whose caption matches `caption`,
 * preferring an exact (normalised) caption match and falling back to a
 * match on the unique name's leaf segment. Returns null when nothing
 * matches — the caller should then NOT emit a filter rather than push a
 * member the server will reject (which would break every consuming tile).
 *
 * An exact caption match wins over a leaf match, and earlier hits win over
 * later ones, so a substring search that returns both "Beer" and "Root
 * Beer" resolves a "Beer" click to "Beer".
 */
export function pickMemberUniqueName(hits: MemberHitLike[], caption: string): string | null {
	const target = norm(caption);
	if (!target) return null;
	let leafFallback: string | null = null;
	for (const h of hits) {
		if (!h || typeof h.uniqueName !== 'string' || !h.uniqueName) continue;
		if (typeof h.caption === 'string' && norm(h.caption) === target) {
			return h.uniqueName;
		}
		if (leafFallback === null && norm(leafSegment(h.uniqueName)) === target) {
			leafFallback = h.uniqueName;
		}
	}
	return leafFallback;
}

/** The (normalised) bracketed segments of a unique name:
 *  "[Time].[Time].[Year].[Q2]" -> ["time","time","year","q2"]. */
function segments(uniqueName: string): string[] {
	return (uniqueName.match(/\[([^\]]*)\]/g) ?? []).map((s) => norm(s.slice(1, -1)));
}

/**
 * Like {@link pickMemberUniqueName} but disambiguates an ambiguous leaf
 * caption using the full ancestor caption path (last element = the clicked
 * leaf). Tables carry the parent captions, so a "Q2" click can be pinned to
 * "Q2 of 1997" rather than "Q2 of 1998" when both exist at the level:
 *
 *   - match candidates on the leaf caption (then leaf-segment fallback),
 *   - if more than one and parent captions are known, prefer the candidate
 *     whose unique name contains every parent caption as a segment,
 *   - otherwise return the first candidate (stable).
 *
 * Returns null when nothing matches.
 */
export function pickMemberUniqueNameForPath(
	hits: MemberHitLike[],
	captionPath: string[]
): string | null {
	const leaf = captionPath.length ? captionPath[captionPath.length - 1] : '';
	const target = norm(leaf);
	if (!target) return null;
	const valid = hits.filter((h) => h && typeof h.uniqueName === 'string' && h.uniqueName);
	let pool = valid.filter((h) => typeof h.caption === 'string' && norm(h.caption) === target);
	if (pool.length === 0) pool = valid.filter((h) => norm(leafSegment(h.uniqueName)) === target);
	if (pool.length === 0) return null;
	if (pool.length === 1) return pool[0].uniqueName;

	const parents = captionPath.slice(0, -1).map(norm).filter(Boolean);
	if (parents.length) {
		const disambiguated = pool.find((h) => {
			const segs = segments(h.uniqueName);
			return parents.every((p) => segs.includes(p));
		});
		if (disambiguated) return disambiguated.uniqueName;
	}
	return pool[0].uniqueName;
}
