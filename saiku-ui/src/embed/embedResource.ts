/*
 * Author-controlled-value guards for the embed surface (saiku#1938).
 *
 * The embed bundle is self-contained (no `$lib` alias, single IIFE), so these
 * helpers live here rather than being imported from the main app — but the
 * RULES deliberately mirror the ones the main app already enforces:
 *
 *   - `safeEmbedImageSrc` is the embed-side twin of `resourceRefAllowed` in
 *     `src/lib/dashboard/custom/echartsOption.ts` / `urlIsAllowed` in
 *     `src/lib/dashboard/cssSanitiser.ts`: relative/same-origin or
 *     `data:image/<raster>` only. The option tile and the plugin tile already
 *     refuse remote subresources; the app header's `logo` did not, so an app
 *     author could point it at an arbitrary origin and every host-page visitor
 *     would fetch it (leaking IP + Referer as a beacon).
 *   - `coerceGridInt` closes the other half: the dashboard/app DTO types a
 *     tile's `x`/`y`/`w`/`h` as `int`, but the document is a raw `JsonNode`, so
 *     a string really can arrive. `EmbedGrid` interpolated those straight into
 *     an inline `style`, which is CSS injection (Svelte sets the attribute, so
 *     no HTML breakout — worst case a `url()` beacon).
 *
 * Pure (vitest env=node) — no DOM, no `window`, so both are SSR/test-safe.
 */

/** Raster `data:` images only. `data:image/svg+xml` is deliberately absent:
 *  an SVG document carries script/animation payloads that `<img>` would still
 *  render from a data: origin, and no legitimate logo needs it. */
const ALLOWED_DATA_IMAGE = /^data:image\/(?:png|jpe?g|gif|webp|avif);/i;

/**
 * Strip C0 control characters and surrounding whitespace so a scheme cannot be
 * split by an embedded control byte (`ht<0x01>tps://…`) to dodge the anchored
 * scheme test. Mirrors `normalizeUrlLike` in the main app's dashboard
 * sanitiser. Tab/LF/CR go too — they have no use in a logo reference.
 */
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\x00-\x20\x7F]+/g;

/**
 * Return `raw` unchanged when it is a safe embed image src, else null.
 *
 * Safe = empty-safe (returns null), a `data:image/<raster>` URI, or a
 * relative/same-origin path. Rejected: every explicit scheme
 * (`http:`, `https:`, `javascript:`, `blob:`, `file:`, a non-image `data:`)
 * and protocol-relative `//host`. Fails closed.
 *
 * Returning the original string (rather than a resolved absolute URL) lets the
 * browser resolve a relative path against the real document origin — which, for
 * an embed host page, is the host's own origin, exactly the intent.
 */
export function safeEmbedImageSrc(raw: string | null | undefined): string | null {
	const s = (raw ?? '').replace(CONTROL_CHARS, '').trim();
	if (!s) return null;
	if (ALLOWED_DATA_IMAGE.test(s)) return s;
	// Protocol-relative host reference — an origin swap dressed as a path.
	if (s.startsWith('//')) return null;
	// Any explicit scheme is rejected outright.
	if (/^[a-z][a-z0-9+.-]*:/i.test(s)) return null;
	// No scheme and not protocol-relative → relative / same-origin → allowed.
	return s;
}

/** Tile spans are clamped to a sane grid so a hostile document cannot ask for a
 *  10^9-column track count and wedge the host page's layout. */
export const MAX_GRID_SPAN = 512;

/**
 * Coerce an author-controlled grid integer to a safe, finite integer in
 * `[min, min + MAX_GRID_SPAN)`. Anything that is not a finite number after
 * `Number()` coercion — `null`, `undefined`, a boolean-free object, `''`,
 * `'abc'`, `Infinity`, `NaN` — falls back to `fallback`.
 *
 * `Number()` (not `parseInt`) is deliberate: `parseInt('1; background:url(x)')`
 * yields `1` while `Number()` yields `NaN`, so the whole hostile string is
 * rejected rather than its leading digits being silently kept. Non-primitives
 * (null, booleans, arrays, objects) take the fallback before coercion —
 * `Number(null)` and `Number([])` are both `0`, which would silently render a
 * zero-width span instead of the authored default.
 */
export function coerceGridInt(value: unknown, fallback: number, min = 0): number {
	if (typeof value !== 'number' && typeof value !== 'string') return fallback;
	// `Number('')` and `Number('   ')` are both 0 — an empty string is an absent
	// value, not a zero, so it takes the fallback.
	if (typeof value === 'string' && value.trim() === '') return fallback;
	const n = Number(value);
	if (!Number.isFinite(n)) return fallback;
	const i = Math.trunc(n);
	if (i < min) return min;
	if (i > min + MAX_GRID_SPAN - 1) return min + MAX_GRID_SPAN - 1;
	return i;
}
