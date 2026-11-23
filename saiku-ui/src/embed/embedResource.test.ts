/*
 * saiku#1938 — unit coverage for the embed surface's author-controlled-value
 * guards.
 *
 * Item 1 (`safeEmbedImageSrc`): the app doc is served verbatim, so an app author
 * could point `logo` at an arbitrary origin and every host-page visitor would
 * fetch it — an IP + Referer beacon, and inconsistent with the no-remote-
 * subresource rule the option/plugin tiles already follow.
 *
 * Item 2 (`coerceGridInt`): the DTO types a tile's x/y/w/h as `int`, but the
 * document is a raw JsonNode, so a string really can arrive and EmbedGrid
 * interpolates those values into an inline `style`. Svelte sets the attribute,
 * so this is CSS injection, not HTML breakout — worst case a `url()` beacon.
 */
import { describe, test, expect } from 'vitest';
import { safeEmbedImageSrc, coerceGridInt, MAX_GRID_SPAN } from './embedResource';

describe('safeEmbedImageSrc', () => {
	test('empty / missing / control-only values render no logo', () => {
		expect(safeEmbedImageSrc(undefined)).toBeNull();
		expect(safeEmbedImageSrc(null)).toBeNull();
		expect(safeEmbedImageSrc('')).toBeNull();
		expect(safeEmbedImageSrc('   ')).toBeNull();
		expect(safeEmbedImageSrc('\u0000')).toBeNull();
	});

	test('relative and same-origin paths pass through unchanged', () => {
		expect(safeEmbedImageSrc('logo.png')).toBe('logo.png');
		expect(safeEmbedImageSrc('/ui/assets/logo.svg')).toBe('/ui/assets/logo.svg');
		expect(safeEmbedImageSrc('./img/logo.png')).toBe('./img/logo.png');
		expect(safeEmbedImageSrc('../shared/brand.png')).toBe('../shared/brand.png');
		// Surrounding whitespace is trimmed, not passed through to the URL parser.
		expect(safeEmbedImageSrc('  logo.png \n')).toBe('logo.png');
	});

	test('raster data: images are allowed', () => {
		expect(safeEmbedImageSrc('data:image/png;base64,iVBORw0KGgo=')).toContain('data:image/png');
		expect(safeEmbedImageSrc('data:image/jpeg;base64,/9j/4AAQ')).toContain('data:image/jpeg');
		expect(safeEmbedImageSrc('data:image/gif;base64,R0lGOD')).toContain('data:image/gif');
		expect(safeEmbedImageSrc('data:image/webp;base64,UklGR')).toContain('data:image/webp');
	});

	test('remote origins are rejected — the beacon this issue closes', () => {
		expect(safeEmbedImageSrc('https://evil.example/track.gif')).toBeNull();
		expect(safeEmbedImageSrc('http://evil.example/track.gif?u=1')).toBeNull();
		// Case + whitespace tricks must not slip past the anchored scheme test.
		expect(safeEmbedImageSrc('HTTPS://evil.example/x.png')).toBeNull();
		expect(safeEmbedImageSrc('  https://evil.example/x.png  ')).toBeNull();
		// Protocol-relative is an origin swap dressed as a path.
		expect(safeEmbedImageSrc('//evil.example/track.gif')).toBeNull();
	});

	test('non-raster / scripting schemes are rejected', () => {
		expect(safeEmbedImageSrc('javascript:alert(1)')).toBeNull();
		expect(safeEmbedImageSrc('data:text/html,<script>alert(1)</script>')).toBeNull();
		// SVG carries script/animation payloads even when loaded via <img>.
		expect(safeEmbedImageSrc('data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=')).toBeNull();
		expect(safeEmbedImageSrc('blob:https://evil.example/uuid')).toBeNull();
		expect(safeEmbedImageSrc('file:///etc/passwd')).toBeNull();
	});

	test('a scheme split by an embedded control byte cannot dodge the check', () => {
		expect(safeEmbedImageSrc('ht\u0001tps://evil.example/x.png')).toBeNull();
		expect(safeEmbedImageSrc('ht\ntps://evil.example/x.png')).toBeNull();
		expect(safeEmbedImageSrc('ht\u0001tp://evil.example/x.png')).toBeNull();
	});
});

describe('coerceGridInt', () => {
	test('passes finite integers through', () => {
		expect(coerceGridInt(0, 12)).toBe(0);
		expect(coerceGridInt(5, 12)).toBe(5);
		expect(coerceGridInt('7', 12)).toBe(7);
	});

	test('truncates fractional and exponent values', () => {
		expect(coerceGridInt(3.9, 12)).toBe(3);
		expect(coerceGridInt('4.2', 12)).toBe(4);
		expect(coerceGridInt('1e2', 12)).toBe(100);
		// Exponent notation is still a number, so it is clamped like any other
		// out-of-range value rather than rejected.
		expect(coerceGridInt(1e3, 12)).toBe(MAX_GRID_SPAN - 1);
	});

	test('falls back on non-numeric values', () => {
		expect(coerceGridInt('abc', 12)).toBe(12);
		expect(coerceGridInt('', 12)).toBe(12);
		expect(coerceGridInt('   ', 12)).toBe(12);
		expect(coerceGridInt(undefined, 12)).toBe(12);
		expect(coerceGridInt(NaN, 12)).toBe(12);
		expect(coerceGridInt(Infinity, 12)).toBe(12);
	});

	test('rejects CSS-injection strings outright rather than keeping their digits', () => {
		// `parseInt` would return 1 here; `Number()` returns NaN → fallback.
		expect(coerceGridInt('1; background: url(https://evil.example/b.gif)', 12)).toBe(12);
		expect(coerceGridInt('2} .tile{display:none', 12)).toBe(12);
		expect(coerceGridInt('0; --x:url(//evil.example)', 12)).toBe(12);
	});

	test('non-primitives take the fallback (Number(null)/Number([]) are 0)', () => {
		expect(coerceGridInt(null, 12)).toBe(12);
		expect(coerceGridInt([], 12)).toBe(12);
		expect(coerceGridInt({}, 12)).toBe(12);
		expect(coerceGridInt(true, 12)).toBe(12);
	});

	test('clamps to [min, min + MAX_GRID_SPAN)', () => {
		expect(coerceGridInt(-4, 0)).toBe(0);
		expect(coerceGridInt(-4, 12, 1)).toBe(1);
		// A span floor of 1 means a zero/absent span still renders a tile.
		expect(coerceGridInt(0, 1, 1)).toBe(1);
		expect(coerceGridInt(10 ** 9, 12)).toBe(MAX_GRID_SPAN - 1);
		expect(coerceGridInt(10 ** 9, 1, 1)).toBe(MAX_GRID_SPAN);
	});
});
