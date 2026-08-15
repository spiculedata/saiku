/*
 * Image-tile src safety + fit coercion (issue #918).
 *
 * Pure (vitest env=node) — no DOM. The src guard is the client-side XSS
 * defence for the image tile: only http(s) absolute URLs or same-origin
 * relative paths (e.g. the upload endpoint's download path) are honoured;
 * javascript:/data:/vbscript:/etc. are rejected. <img> never executes script
 * from src and SVG loaded via <img> is non-scripting, so an http(s) allowlist
 * is a sufficient client guard (the upload endpoint hardens the server side).
 */

export const IMAGE_FITS = ['contain', 'cover', 'fill', 'scale-down'] as const;
export type ImageFitValue = (typeof IMAGE_FITS)[number];

/**
 * Return `raw` unchanged when it's a safe image src, else null.
 *
 * Safe = resolves to an http: or https: URL. Parsing against a fixed base
 * (no `window`, so it's SSR/test-safe) lets same-origin relative paths
 * through (they resolve to http:) while rejecting javascript:/data:/etc.
 * Returning the original string means the browser resolves a relative path
 * against the real document origin at render time.
 */
export function safeImageSrc(raw: string | null | undefined): string | null {
	const s = (raw ?? '').trim();
	if (!s) return null;
	try {
		const u = new URL(s, 'http://localhost/');
		return u.protocol === 'http:' || u.protocol === 'https:' ? s : null;
	} catch {
		return null;
	}
}

/** Clamp an arbitrary string to a valid CSS object-fit, defaulting to
 *  "contain". */
export function coerceImageFit(fit: string | null | undefined): ImageFitValue {
	return (IMAGE_FITS as readonly string[]).includes(fit ?? '') ? (fit as ImageFitValue) : 'contain';
}
