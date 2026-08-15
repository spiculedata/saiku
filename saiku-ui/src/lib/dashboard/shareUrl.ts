/*
 * Share-link URL helpers (#941 PR2). The share token is carried in the URL
 * FRAGMENT (after `#`), never a path or query param — the fragment is not sent
 * to the server in the Referer header or access logs, which is why the backend
 * was hardened to read the token from the `X-Saiku-Share-Token` header that the
 * viewer page echoes from `window.location.hash`.
 *
 * Pure (no DOM / SvelteKit imports) so it unit-tests cleanly; callers pass the
 * current origin + SvelteKit base in.
 */

/** Build a shareable link for `token`, e.g.
 *  `https://host/ui/share#<token>`. `origin` + `base` are passed in so this
 *  stays pure; omit them for a root-relative `/share#<token>`. */
export function buildShareUrl(token: string, opts?: { origin?: string; base?: string }): string {
	const origin = opts?.origin ?? '';
	const base = opts?.base ?? '';
	return `${origin}${base}/share#${encodeURIComponent(token)}`;
}

/** Extract the token from a URL fragment (`window.location.hash`), accepting
 *  it with or without the leading `#`. Returns null when absent. */
export function parseShareToken(hash: string): string | null {
	const h = (hash ?? '').replace(/^#/, '');
	return h.length > 0 ? decodeURIComponent(h) : null;
}
