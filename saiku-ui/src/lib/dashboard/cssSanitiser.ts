import * as csstree from 'css-tree';

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
 *    `@charset`, `@namespace`, `@page`, remote `url()` (only `data:` and
 *    relative/same-origin are allowed — including url() hidden inside custom
 *    properties and `var()` fallbacks), `position: fixed`, CSS
 *    `expression(...)`, `behavior`, `-moz-binding`. Detection runs on the
 *    CSS-escape-decoded property name and value so escape-sequence bypasses
 *    (e.g. `\65 xpression(`, `\62 ehavior`) cannot slip through.
 *
 * It FAILS CLOSED: if the CSS cannot be fully parsed (or any transform step
 * throws) the whole stylesheet is dropped and `""` is returned. It never
 * throws.
 */

/** Declaration properties that are removed outright regardless of value. */
const FORBIDDEN_DECL = /^(behavior|-moz-binding)$/i;

/** At-rules removed outright (name compared case-insensitively). */
const FORBIDDEN_ATRULES = ['import', 'font-face', 'charset', 'namespace', 'page'];

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
 * True when a `url(...)` reference is allowed. Only `data:` URIs and
 * relative/same-origin references pass; absolute schemes (`https://`,
 * `http://`, `javascript:`, etc.) and protocol-relative (`//host`) references
 * are rejected. An empty target (already-blanked url()) is allowed.
 */
function urlIsAllowed(raw: string): boolean {
	const u = raw.trim().replace(/^['"]|['"]$/g, '');
	if (u === '') return true;
	if (u.startsWith('data:')) return true;
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
				if (FORBIDDEN_ATRULES.includes(node.name.toLowerCase())) {
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
				if (valueIsHostile(property, value) || valueHasDisallowedUrl(value)) {
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
				if (this.atrule && KEYFRAMES_ATRULE.test(this.atrule.name)) return;
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
