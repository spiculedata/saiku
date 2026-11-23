import * as csstree from 'css-tree';
import { normalizeUrlLike } from './urlNormalise';

/**
 * Scoped + fail-closed custom-CSS sanitiser for the App Builder.
 *
 * App authors may supply custom CSS to brand their app. Before that CSS is
 * injected into the page it is:
 *
 *  - SCOPED: every top-level selector is rewritten to descend from the app
 *    root (`[data-saiku-app="<id>"]`) so it cannot style Saiku chrome outside
 *    the app or leak across embedded apps. Keyframe stops (`from`/`to`/`%`)
 *    and selectors nested inside `:is()/:where()/:not()/:has()` are left
 *    untouched (prefixing them would corrupt the animation / functional
 *    pseudo-class).
 *  - SANITISED: hostile constructs are stripped — `@import`, `@font-face`,
 *    `@charset`, `@namespace`, `@page`, remote `url()` (only raster `data:` and
 *    relative/same-origin are allowed — including url() hidden inside custom
 *    properties and `var()` fallbacks), `position: fixed`, CSS
 *    `expression(...)`, `behavior`, `-moz-binding`, and bare-string remote
 *    loads via `image-set()` / `image()` / `src()`. Detection runs on the
 *    CSS-escape-decoded at-rule name, property name and value so
 *    escape-sequence bypasses (e.g. `@\69 mport`, `\65 xpression(`,
 *    `\62 ehavior`) cannot slip through.
 *
 * It FAILS CLOSED: if the CSS cannot be fully parsed (or any transform step
 * throws) the whole stylesheet is dropped and `""` is returned. It never
 * throws.
 */

/** Declaration properties that are removed outright regardless of value. */
const FORBIDDEN_DECL = /^(behavior|-moz-binding)$/i;

/** At-rules removed outright (name compared case-insensitively). */
const FORBIDDEN_ATRULES = ['import', 'font-face', 'charset', 'namespace', 'page'];

/**
 * Functions that take a bare `<string>` as a resource reference, so a remote
 * URL in one is a remote load with no `url()` for the url() layer to inspect.
 * CSS Images 4 allows a bare string as an `<image-set-option>`; `image()` and
 * `src()` are the CSS Values 4 spellings of the same thing (unimplemented in
 * shipping browsers today, blocked anyway so they cannot start working under
 * us).
 */
const IMAGE_STRING_FUNCTIONS = /(^|[^-\w])(-webkit-)?(image-set|image|src)\s*\(/gi;

/** A string that names a REMOTE resource: an absolute `scheme://host` or a
 *  protocol-relative `//host` reference. Deliberately narrower than
 *  {@link urlIsAllowed}: this is applied to arbitrary custom-property text, so
 *  it must not fire on ordinary label copy such as `"Revenue: total"`. */
const REMOTE_STRING_REF = /^(?:[a-z][a-z0-9+.-]*:)?\/\//i;

/** `data:` payloads that are safe in an image/resource position. Mirrors
 *  `echartsOption`'s `ALLOWED_DATA_IMAGE`: raster formats only — SVG is
 *  excluded on purpose, a `data:` payload is not something a stylesheet needs
 *  beyond an inline raster image. */
const ALLOWED_DATA_IMAGE = /^data:image\/(?:png|jpe?g|gif|webp);/i;

/** At-rules whose child selectors are keyframe stops and must NOT be scoped. */
const KEYFRAMES_ATRULE = /^(-webkit-)?keyframes$/i;

/**
 * Decode CSS escape sequences so detection regexes see the value the browser
 * will ultimately act on. Handles `\<1-6 hex>` (optionally followed by a
 * single whitespace) and `\<char>` literal escapes.
 */
function decodeCssEscapes(input: string): string {
	return input.replace(/\\([0-9a-fA-F]{1,6})[ \t\n\r\f]?|\\([\s\S])/g, (_match, hex, ch) => {
		if (hex !== undefined) {
			try {
				return String.fromCodePoint(parseInt(hex, 16));
			} catch {
				return '';
			}
		}
		return ch ?? '';
	});
}

/**
 * True when a declaration's value is hostile for the given property and the
 * whole declaration must be dropped. `prop`/`value` are expected pre-decoded.
 */
function valueIsHostile(prop: string, value: string): boolean {
	const v = value.toLowerCase();
	if (/expression\s*\(/.test(v)) return true;
	if (prop.toLowerCase() === 'position' && /\bfixed\b/.test(v)) return true;
	return false;
}

/**
 * True when a `url(...)` reference is allowed. Only `data:` URIs (raster
 * images only, see {@link ALLOWED_DATA_IMAGE}) and relative/same-origin
 * references pass; absolute schemes (`https://`, `http://`, `javascript:`,
 * etc.) and protocol-relative (`//host`) references are rejected. An empty
 * target (already-blanked url()) is allowed.
 *
 * saiku#1944: a `data:` payload that is not a raster image is no longer
 * waved through wholesale — this mirrors `echartsOption`'s `ALLOWED_DATA_IMAGE`
 * so both custom-CSS gates agree on what a stylesheet may inline.
 *
 * saiku#1942: the scheme/protocol-relative checks below run on the value
 * AFTER {@link normalizeUrlLike} (mirroring the fix already applied to the
 * `echarts-option` validator for saiku#1940) so a scheme split by an embedded
 * control character can't dodge the anchored regex or the `//` prefix check.
 * `raw` here is always the CSS-escape-DECODED target — the caller
 * (`sanitiseAndScopeCss`) already runs `decodeCssEscapes` on the whole
 * declaration value before extracting url() targets from it, so a CSS numeric
 * escape like `\9` / `\a` / `\d` has already become a literal tab/LF/CR by the
 * time it reaches this function, same as a literal control character typed
 * directly into the source. `normalizeUrlLike` handles both forms identically
 * because both arrive here as the same literal control byte — there is
 * nothing CSS-escape-specific left to decode at this layer.
 */
function urlIsAllowed(raw: string): boolean {
	// Normalise BEFORE stripping quotes too (mirrors echartsOption's
	// `resourceRefAllowed`): a quoted target like `"\x01https://evil"` has its
	// control byte adjacent to the quote character, not the scheme, so the
	// second normalise (after the quote is gone) is what actually exposes it.
	let u = normalizeUrlLike(raw.trim()).replace(/^['"]|['"]$/g, '');
	u = normalizeUrlLike(u);
	if (u === '') return true;
	if (/^data:/i.test(u)) return ALLOWED_DATA_IMAGE.test(u);
	if (u.startsWith('//')) return false;
	if (/^[a-z][a-z0-9+.-]*:/i.test(u)) return false;
	return true;
}

/**
 * Extract every `url(...)` target from a (pre-decoded) value string. Catches
 * url() nested inside custom properties and `var()` fallbacks, which css-tree
 * exposes only as Raw tokens (no `Url` AST node), as well as ordinary url()
 * references.
 */
function extractUrlTargets(value: string): string[] {
	const targets: string[] = [];
	const re = /url\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^)]*)\s*\)/gi;
	let match: RegExpExecArray | null;
	while ((match = re.exec(value)) !== null) {
		targets.push(match[1]);
	}
	return targets;
}

/** True when the value contains at least one disallowed url() target. */
function valueHasDisallowedUrl(value: string): boolean {
	return extractUrlTargets(value).some((target) => !urlIsAllowed(target));
}

/**
 * Every quoted string appearing inside an image function (`image-set(`,
 * `-webkit-image-set(`, `image(`, `src(`) in a (pre-decoded) value, without its
 * quotes. Argument lists are delimited by paren depth so a `)` inside a string
 * cannot cut the scan short and re-open a fake `image-set(` later in the
 * value.
 *
 * saiku#1944: CSS Images 4 accepts a bare `<string>` as an
 * `<image-set-option>`, so `image-set("https://evil/x.png" 1x)` is a remote
 * load that never goes through `url()` — the url() layer above is blind to it.
 */
function extractImageStrings(value: string): string[] {
	const strings: string[] = [];
	const open = new RegExp(IMAGE_STRING_FUNCTIONS.source, IMAGE_STRING_FUNCTIONS.flags);
	let match: RegExpExecArray | null;
	while ((match = open.exec(value)) !== null) {
		// `open.lastIndex` sits just after the opening paren.
		let depth = 1;
		let i = open.lastIndex;
		const argStart = i;
		while (i < value.length && depth > 0) {
			const ch = value[i];
			if (ch === '"' || ch === "'") {
				// Skip over a quoted run so parens inside it don't count.
				const quote = ch;
				i += 1;
				while (i < value.length && value[i] !== quote) {
					i += value[i] === '\\' ? 2 : 1;
				}
				i += 1;
				continue;
			}
			if (ch === '(') depth += 1;
			else if (ch === ')') depth -= 1;
			i += 1;
		}
		if (depth !== 0) return strings.concat(collectStrings(value.slice(argStart)));
		strings.push(...collectStrings(value.slice(argStart, i - 1)));
		open.lastIndex = i;
	}
	return strings;
}

/** Quoted runs in a fragment, unquoted, escapes already decoded. */
function collectStrings(fragment: string): string[] {
	const out: string[] = [];
	const re = /"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'/g;
	let m: RegExpExecArray | null;
	while ((m = re.exec(fragment)) !== null) {
		out.push(m[1] ?? m[2] ?? '');
	}
	return out;
}

/** True when an image function carries a bare string that is not an allowed
 *  resource reference. */
function valueHasDisallowedImageString(value: string): boolean {
	return extractImageStrings(value).some((s) => !urlIsAllowed(s));
}

/**
 * True when a custom-property declaration holds a bare string that names a
 * remote (or non-raster `data:`) resource.
 *
 * saiku#1944: `--x:"https://evil"; background:image-set(var(--x) 1x)` never
 * puts a url() in the sheet, and the `var()` consumer is only resolvable by
 * the browser — so the custom property is dropped, which makes the consumer
 * fall back to its fallback/initial value (fail closed). The cost is a
 * custom property holding a REMOTE URL string (or a non-raster `data:` one).
 *
 * Only *URL-shaped* strings are treated as hostile here, NOT every string
 * that fails {@link urlIsAllowed}: a custom property is a general-purpose
 * value slot, and dashboard authors legitimately park label copy in one
 * (`--kpi-label:"Revenue: total"`). Rejecting that would silently drop real
 * styling for no security gain — the string never becomes a request on its
 * own, and a bare string is only a resource when it sits in an image
 * function, which `valueHasDisallowedImageString` already covers directly.
 */
function customPropertyStringIsHostile(property: string, value: string): boolean {
	if (!property.startsWith('--')) return false;
	return collectStrings(value).some(isRemoteStringRef);
}

/** True when a string is a remote resource reference (`https://host`, `//host`)
 *  or a `data:` payload that is not a raster image. */
function isRemoteStringRef(raw: string): boolean {
	const s = raw.trim();
	if (s === '') return false;
	if (REMOTE_STRING_REF.test(s)) return true;
	if (/^data:/i.test(s)) return !ALLOWED_DATA_IMAGE.test(s);
	return false;
}

export function sanitiseAndScopeCss(css: string | undefined, rootSelector: string): string {
	if (!css || !css.trim()) return '';

	let ast: csstree.CssNode;
	try {
		ast = csstree.parse(css, {
			onParseError: (e) => {
				throw e;
			}
		});
	} catch {
		return '';
	}

	try {
		// Drop hostile at-rules (@import, @font-face, @charset, @namespace, @page).
		csstree.walk(ast, {
			visit: 'Atrule',
			enter(node, item, list) {
				// saiku#1944: css-tree does NOT decode CSS escapes in an
				// at-rule's NAME, so `@\69 mport url(https://evil);` arrives
				// here as the literal `\69 mport` and used to sail past the
				// denylist — while the browser (CSS Syntax 3) decodes it to
				// `@import` and fetches the remote stylesheet, which is
				// unsanitised AND unscoped. Decode before comparing, mirroring
				// the property-name handling in the Declaration walk below.
				if (FORBIDDEN_ATRULES.includes(decodeCssEscapes(node.name).toLowerCase())) {
					list.remove(item);
				}
			}
		});

		// Drop hostile declarations. All matching is done on the CSS-escape-decoded
		// property name and value so escape bypasses cannot survive, and the value
		// scan covers url() hidden inside custom properties / var() fallbacks that
		// never surface as a `Url` AST node.
		csstree.walk(ast, {
			visit: 'Declaration',
			enter(node, item, list) {
				const property = decodeCssEscapes(node.property);
				if (FORBIDDEN_DECL.test(property)) {
					list.remove(item);
					return;
				}
				const value = decodeCssEscapes(csstree.generate(node.value));
				if (
					valueIsHostile(property, value) ||
					valueHasDisallowedUrl(value) ||
					valueHasDisallowedImageString(value) ||
					customPropertyStringIsHostile(property, value)
				) {
					list.remove(item);
				}
			}
		});

		// Scope every TOP-LEVEL selector under the app root. Skip keyframe stops
		// (from/to/%) and selectors nested inside functional pseudo-classes
		// (:is/:where/:not/:has) — prefixing those would corrupt the rule.
		csstree.walk(ast, {
			visit: 'Selector',
			enter(node) {
				// saiku#1944: decode the at-rule name here too, so an escaped
				// `@\6beyframes` is still recognised as a keyframes container
				// and its stops are not mangled by scoping.
				if (this.atrule && KEYFRAMES_ATRULE.test(decodeCssEscapes(this.atrule.name))) return;
				if (this.function) return;
				node.children.prependData({ type: 'Combinator', name: ' ' } as csstree.CssNode);
				node.children.prependData({ type: 'Raw', value: rootSelector } as csstree.CssNode);
			}
		});

		return csstree.generate(ast);
	} catch {
		return '';
	}
}
