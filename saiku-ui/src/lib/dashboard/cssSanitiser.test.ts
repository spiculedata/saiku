import { describe, expect, test } from 'vitest';
import { sanitiseAndScopeCss } from './cssSanitiser';

const ROOT = '[data-saiku-app="app1"]';

describe('sanitiseAndScopeCss', () => {
	test('scopes every selector under the app root', () => {
		const out = sanitiseAndScopeCss('.card { color: red } h1 { margin: 0 }', ROOT);
		expect(out).toContain(`${ROOT} .card`);
		expect(out).toContain(`${ROOT} h1`);
		expect(out).not.toMatch(/^\s*\.card/m);
	});
	test('drops @import entirely', () => {
		const out = sanitiseAndScopeCss('@import url("//evil");\n.a{color:red}', ROOT);
		expect(out).not.toContain('@import');
		expect(out).toContain(`${ROOT} .a`);
	});
	test('strips remote url() but keeps data: and same-origin refs', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:url(https://evil/x.png)} .b{background:url(data:image/png;base64,AA)}',
			ROOT
		);
		expect(out).not.toContain('evil');
		expect(out).toContain('data:image/png');
	});
	test('removes position:fixed, expression(), behavior, -moz-binding', () => {
		const css =
			'.a{position:fixed} .b{width:expression(alert(1))} .c{behavior:url(x.htc)} .d{-moz-binding:url(x)}';
		const out = sanitiseAndScopeCss(css, ROOT);
		expect(out).not.toMatch(/position\s*:\s*fixed/i);
		expect(out).not.toMatch(/expression\s*\(/i);
		expect(out).not.toMatch(/behavior\s*:/i);
		expect(out).not.toMatch(/-moz-binding/i);
	});
	test('fails closed: unparseable CSS yields empty string', () => {
		expect(sanitiseAndScopeCss('this is { not ; valid ) css {{{', ROOT)).toBe('');
	});
	test('empty / nullish input yields empty string', () => {
		expect(sanitiseAndScopeCss('', ROOT)).toBe('');
		expect(sanitiseAndScopeCss(undefined as unknown as string, ROOT)).toBe('');
	});

	// Regression: remote url() hidden inside a CSS custom property, weaponised
	// via var() substitution. css-tree parses --* / var() args as Raw tokens
	// with no Url child, so the Url walk never saw them.
	test('strips remote url() hidden in a custom property (var weaponisation)', () => {
		const out = sanitiseAndScopeCss(
			'.a{--bg:url(https://evil/track.png)} .b{background:hsl(var(--bg))}',
			ROOT
		);
		expect(out).not.toContain('evil');
		expect(out).not.toContain('https://');
	});
	test('strips remote url() hidden in a var() fallback', () => {
		const out = sanitiseAndScopeCss('.a{background:var(--x,url(https://evil))}', ROOT);
		expect(out).not.toContain('evil');
		expect(out).not.toContain('https://');
	});
	test('keeps data: and relative url() inside custom properties', () => {
		const out = sanitiseAndScopeCss(
			'.a{--ok:url(data:image/png;base64,AA)} .b{--rel:url(x.png)}',
			ROOT
		);
		expect(out).toContain('data:image/png');
		expect(out).toContain('x.png');
	});

	// Regression: @keyframes stops (from/to/%) must not be scoped, or the whole
	// animation is invalid and browsers drop it.
	test('does not scope @keyframes stops', () => {
		const out = sanitiseAndScopeCss('@keyframes k{from{opacity:0}to{opacity:1}}', ROOT);
		expect(out).toContain('@keyframes k');
		expect(out).toMatch(/(^|[{;\s])from\s*\{/);
		expect(out).toMatch(/(^|[{;\s}])to\s*\{/);
		expect(out).not.toContain(`${ROOT} from`);
		expect(out).not.toContain(`${ROOT} to`);
	});

	// Regression: CSS escape-sequence bypass — \65 -> "e", \62 -> "b".
	test('drops escaped expression() bypass', () => {
		const out = sanitiseAndScopeCss('.a{width:\\65 xpression(alert(1))}', ROOT);
		expect(out).not.toContain('xpression');
		expect(out).not.toMatch(/expression\s*\(/i);
	});
	test('drops escaped behavior bypass', () => {
		const out = sanitiseAndScopeCss('.a{\\62 ehavior:url(x.htc)}', ROOT);
		expect(out).not.toContain('ehavior');
		expect(out).not.toMatch(/behavior\s*:/i);
	});

	// Minor polish: @page is dropped; nested :is()/:where() selectors are not
	// individually prefixed (only the top-level selector is scoped).
	test('drops @page', () => {
		const out = sanitiseAndScopeCss('@page{margin:0} .a{color:red}', ROOT);
		expect(out).not.toContain('@page');
		expect(out).toContain(`${ROOT} .a`);
	});
	test('scopes only the top-level selector, not those inside :is()', () => {
		const out = sanitiseAndScopeCss('.a:is(.b,.c){color:red}', ROOT);
		expect(out).toContain(`${ROOT} .a:is(.b,.c)`);
		expect(out).not.toContain(`:is(${ROOT}`);
	});
});

/* ---------------------------------------------------------------------- *
 * saiku#1942 — control-char-split url() scheme bypass.                    *
 *                                                                          *
 * `urlIsAllowed` used to detect a scheme with `raw.trim()` + an anchored  *
 * `^[a-z][a-z0-9+.-]*:` regex — the same shape saiku#1940 fixed in the    *
 * echarts-option validator. `trim()` only strips whitespace at the ends   *
 * and never touches an EMBEDDED control character, so a scheme split by   *
 * an inner tab (or a CSS numeric escape that decodes to one) doesn't      *
 * match that regex and was waved through as "no scheme, must be a         *
 * relative/same-origin reference".                                        *
 *                                                                          *
 * Both bypass SPELLINGS — a literal control byte typed into the source,   *
 * and a CSS numeric escape (`\9`, `\a`, `\d`, ...) — collapse to the SAME  *
 * decoded string before `urlIsAllowed` ever sees it: `sanitiseAndScopeCss` *
 * already runs `decodeCssEscapes` on the whole declaration value before   *
 * extracting url() targets, and (confirmed against the actual css-tree    *
 * behaviour) css-tree re-serialises a literal control char right back     *
 * into its numeric-escape form when generating the value, so a literal    *
 * tab in the source round-trips through `\9` and comes out the other side *
 * as the identical decoded tab. So a single `normalizeUrlLike` pass in    *
 * `urlIsAllowed` — the same WHATWG-URL-parser-shaped normalisation        *
 * saiku#1940 added to the echarts-option validator, now shared via        *
 * `./urlNormalise` — closes every spelling of this bypass; there is       *
 * nothing CSS-escape-specific left to decode at that layer.               *
 *                                                                          *
 * Reversion-sensitive: stashing the `normalizeUrlLike` step in            *
 * `urlIsAllowed` turns every "DROPPED" case below into a case where the   *
 * remote host survives sanitisation.                                      *
 * ---------------------------------------------------------------------- */
describe('sanitiseAndScopeCss — saiku#1942 control-char-split url() scheme bypass', () => {
	test('baseline: a normal remote url(https://...) is dropped', () => {
		const out = sanitiseAndScopeCss('.a{background:url(https://evil.example/x.png)}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote url() whose scheme is split by a LITERAL TAB', () => {
		const out = sanitiseAndScopeCss('.a{background:url("ht\ttps://evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote url() whose scheme is split by a \\9 CSS escape (decodes to TAB)', () => {
		const out = sanitiseAndScopeCss('.a{background:url("ht\\9 tps://evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote url() whose scheme is split by an \\a CSS escape (decodes to LF)', () => {
		const out = sanitiseAndScopeCss('.a{background:url("ht\\a tps://evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a protocol-relative //host url() split by a literal tab', () => {
		const out = sanitiseAndScopeCss('.a{background:url("/\t/evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a protocol-relative //host url() split by a \\9 CSS escape', () => {
		const out = sanitiseAndScopeCss('.a{background:url("/\\9 /evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote url() preceded by a leading control byte', () => {
		const out = sanitiseAndScopeCss('.a{background:url("\x01https://evil.example/x.png")}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote url() whose scheme is split by a 6-digit hex \\000009 escape (decodes to TAB)', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:url("ht\\000009tps://evil.example/x.png")}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('drops an UNQUOTED url() whose scheme is split by a \\9 CSS escape', () => {
		const out = sanitiseAndScopeCss('.a{background:url(ht\\9 tps://evil.example/x.png)}', ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a control-char-split scheme hidden in a var() fallback', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:var(--x,url("ht\\9 tps://evil.example/x.png"))}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('anti-regression: a legitimate relative url() is still allowed', () => {
		const out = sanitiseAndScopeCss('.a{background:url(images/logo.png)}', ROOT);
		expect(out).toContain('images/logo.png');
	});

	test('anti-regression: a data:image/png url() is still allowed', () => {
		const out = sanitiseAndScopeCss('.a{background:url(data:image/png;base64,AA)}', ROOT);
		expect(out).toContain('data:image/png');
	});

	// saiku#1944 F1 — css-tree does not decode CSS escapes in an at-rule NAME,
	// so an escaped at-keyword reached the browser as @import while the
	// denylist compared the raw `\69 mport`.
	test('drops @import hidden behind a CSS-escaped at-keyword (url form)', () => {
		const out = sanitiseAndScopeCss(
			'@\\69 mport url(https://evil.example/x.css);\n.a{color:red}',
			ROOT
		);
		expect(out.toLowerCase()).not.toContain('@import');
		expect(out.toLowerCase()).not.toContain('evil.example');
		expect(out).toContain(`${ROOT} .a`);
	});

	test('drops @import hidden behind a CSS-escaped at-keyword (string form)', () => {
		const out = sanitiseAndScopeCss(
			'@\\69 mport "https://evil.example/x.css";\n.a{color:red}',
			ROOT
		);
		expect(out.toLowerCase()).not.toContain('@import');
		expect(out.toLowerCase()).not.toContain('evil.example');
	});

	test('drops @import whose at-keyword is split by a 6-digit hex escape', () => {
		const out = sanitiseAndScopeCss(
			'@\\000069mport url(https://evil.example/x.css);\n.a{color:red}',
			ROOT
		);
		expect(out.toLowerCase()).not.toContain('@import');
		expect(out.toLowerCase()).not.toContain('evil.example');
	});

	test('drops @font-face hidden behind a CSS-escaped at-keyword', () => {
		const out = sanitiseAndScopeCss(
			'@\\66 ont-face{font-family:x;src:url(https://evil.example/f.woff2)}',
			ROOT
		);
		expect(out.toLowerCase()).not.toContain('@font-face');
		expect(out.toLowerCase()).not.toContain('evil.example');
	});

	test('anti-regression: a conditional at-rule is kept and its rules still scoped', () => {
		const out = sanitiseAndScopeCss('@media (min-width:1px){.a{color:red}}', ROOT);
		expect(out.toLowerCase()).toContain('@media');
		expect(out).toContain(`${ROOT} .a`);
	});

	// saiku#1944 F2 — image-set() takes a bare <string>, so a remote URL in one
	// is a remote load the url() layer never sees.
	test('drops a remote bare string inside image-set()', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:image-set("https://evil.example/x.png" 1x)}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote bare string inside -webkit-image-set()', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:-webkit-image-set("https://evil.example/x.png" 1x)}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('drops a protocol-relative bare string inside image-set()', () => {
		const out = sanitiseAndScopeCss(".a{background:image-set('//evil.example/x.png' 1x)}", ROOT);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote bare string inside image() / src()', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:image("https://evil.example/x.png")} .b{cursor:src("https://evil.example/x.cur")}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('drops a remote bare string hidden behind a ) inside an earlier string', () => {
		const out = sanitiseAndScopeCss(
			'.a{content:")" ; background:image-set("https://evil.example/x.png" 1x)}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('drops a custom property weaponised as an image-set() string', () => {
		const out = sanitiseAndScopeCss(
			'.a{--x:"https://evil.example/x.png";background:image-set(var(--x) 1x)}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	test('anti-regression: a data:image bare string inside image-set() is allowed', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:image-set("data:image/png;base64,AA" 1x)}',
			ROOT
		);
		expect(out).toContain('data:image/png');
	});

	test('anti-regression: a relative bare string inside image-set() is allowed', () => {
		const out = sanitiseAndScopeCss('.a{background:image-set("img/logo.png" 1x)}', ROOT);
		expect(out).toContain('img/logo.png');
	});

	test('anti-regression: a non-URL string in a custom property is allowed', () => {
		const out = sanitiseAndScopeCss('.a{--label:"Hello, world";content:var(--label)}', ROOT);
		expect(out).toContain('Hello, world');
	});

	test('anti-regression: label copy that looks scheme-ish stays in a custom property', () => {
		const out = sanitiseAndScopeCss(
			'.a{--kpi-label:"Revenue: total";content:var(--kpi-label)}',
			ROOT
		);
		expect(out).toContain('Revenue: total');
	});

	test('drops a custom property holding a protocol-relative URL string', () => {
		const out = sanitiseAndScopeCss(
			'.a{--x:"//evil.example/x.png";background:image-set(var(--x) 1x)}',
			ROOT
		);
		expect(out).not.toContain('evil.example');
	});

	// saiku#1944 F3 — align with echartsOption's ALLOWED_DATA_IMAGE: a `data:`
	// payload that is not a raster image is not allowed in a stylesheet.
	test('drops a non-raster data: url()', () => {
		const out = sanitiseAndScopeCss(
			'.a{background:url("data:image/svg+xml,<svg/>")} .b{background:url("data:text/html,<b>")}',
			ROOT
		);
		expect(out).not.toContain('data:');
	});
});
