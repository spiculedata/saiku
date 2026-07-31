/**
 * Minimal, dependency-free, isomorphic XML parser (saiku-cloud#710).
 *
 * The dashboard's vitest `server` project runs in a Node environment
 * where the global {@link DOMParser} is unavailable, and we deliberately
 * do NOT add an XML-parser npm dependency (keeps the dashboard bundle +
 * SSR surface lean — see the PR for the rationale). This hand-rolled
 * recursive-descent parser handles exactly the subset of XML the
 * gateway's `MondrianXmlEmitter` produces:
 *
 * - An optional `<?xml ... ?>` declaration.
 * - Comments (`<!-- ... -->`), skipped.
 * - Elements with double-quoted attribute values.
 * - Text content (with the five standard entity references).
 * - Self-closing (`<Foo/>`) and container (`<Foo>...</Foo>`) elements.
 *
 * It intentionally does NOT support: DOCTYPE, processing instructions
 * other than the XML declaration, CDATA, namespaces, single-quoted
 * attribute values, or custom entities. The emitter emits none of
 * those, and refusing them keeps the parser small + auditable.
 *
 * The parser never throws: malformed input yields `{ ok: false }` so
 * the caller can fall back to a raw-XML editing surface.
 */

/** A parsed XML element node. Text-only content is collected in `text`. */
export interface XmlNode {
	/** Tag name, e.g. `"Cube"`. */
	readonly name: string;
	/** Attribute map; values are entity-decoded. */
	readonly attributes: Readonly<Record<string, string>>;
	/** Child element nodes, in document order. */
	readonly children: readonly XmlNode[];
	/** Concatenated, entity-decoded text content (trimmed). */
	readonly text: string;
}

export type XmlParseResult =
	| { readonly ok: true; readonly root: XmlNode }
	| { readonly ok: false; readonly reason: string };

const ENTITY_MAP: Readonly<Record<string, string>> = {
	lt: '<',
	gt: '>',
	amp: '&',
	quot: '"',
	apos: "'"
};

/**
 * Decode the five standard XML entity references plus numeric ones.
 * Unknown entities are left verbatim (best-effort, never throws).
 */
function decodeEntities(input: string): string {
	if (input.indexOf('&') === -1) return input;
	return input.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (match, body: string) => {
		if (body[0] === '#') {
			const isHex = body[1] === 'x' || body[1] === 'X';
			const code = parseInt(body.slice(isHex ? 2 : 1), isHex ? 16 : 10);
			if (Number.isNaN(code)) return match;
			return String.fromCodePoint(code);
		}
		const replacement = ENTITY_MAP[body];
		return replacement ?? match;
	});
}

interface Cursor {
	readonly s: string;
	pos: number;
}

function isWhitespace(ch: string): boolean {
	return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r';
}

function skipWhitespace(c: Cursor): void {
	while (c.pos < c.s.length && isWhitespace(c.s[c.pos])) c.pos++;
}

/**
 * Skip any number of leading comments / whitespace / the XML
 * declaration. Returns false if it hit unterminated markup.
 */
function skipProlog(c: Cursor): boolean {
	for (;;) {
		skipWhitespace(c);
		if (c.s.startsWith('<?', c.pos)) {
			const end = c.s.indexOf('?>', c.pos);
			if (end === -1) return false;
			c.pos = end + 2;
			continue;
		}
		if (c.s.startsWith('<!--', c.pos)) {
			const end = c.s.indexOf('-->', c.pos);
			if (end === -1) return false;
			c.pos = end + 3;
			continue;
		}
		return true;
	}
}

const NAME_TERMINATORS = new Set([' ', '\t', '\n', '\r', '>', '/', '=']);

function readName(c: Cursor): string {
	const start = c.pos;
	while (c.pos < c.s.length && !NAME_TERMINATORS.has(c.s[c.pos])) c.pos++;
	return c.s.slice(start, c.pos);
}

/** Parse attributes up to the closing `>` or `/>`. Throws on malformed. */
function readAttributes(c: Cursor): Record<string, string> {
	const attributes: Record<string, string> = {};
	for (;;) {
		skipWhitespace(c);
		const ch = c.s[c.pos];
		if (ch === undefined) throw new Error('unterminated start tag');
		if (ch === '>' || ch === '/') return attributes;
		const attrName = readName(c);
		if (attrName.length === 0) throw new Error('expected attribute name');
		skipWhitespace(c);
		if (c.s[c.pos] !== '=') throw new Error(`attribute '${attrName}' missing '='`);
		c.pos++;
		skipWhitespace(c);
		if (c.s[c.pos] !== '"') throw new Error(`attribute '${attrName}' value must be double-quoted`);
		c.pos++;
		const valStart = c.pos;
		const valEnd = c.s.indexOf('"', c.pos);
		if (valEnd === -1) throw new Error(`attribute '${attrName}' value unterminated`);
		attributes[attrName] = decodeEntities(c.s.slice(valStart, valEnd));
		c.pos = valEnd + 1;
	}
}

/**
 * Parse a single element starting at the current `<`. Recursively
 * parses children. Throws on malformed markup (caught at the top).
 */
function parseElement(c: Cursor): XmlNode {
	if (c.s[c.pos] !== '<') throw new Error('expected element start');
	c.pos++;
	const name = readName(c);
	if (name.length === 0) throw new Error('empty tag name');
	const attributes = readAttributes(c);
	skipWhitespace(c);
	if (c.s.startsWith('/>', c.pos)) {
		c.pos += 2;
		return { name, attributes, children: [], text: '' };
	}
	if (c.s[c.pos] !== '>') throw new Error(`tag '${name}' not closed`);
	c.pos++;

	const children: XmlNode[] = [];
	let text = '';
	for (;;) {
		const lt = c.s.indexOf('<', c.pos);
		if (lt === -1) throw new Error(`tag '${name}' has no closing tag`);
		text += decodeEntities(c.s.slice(c.pos, lt));
		c.pos = lt;
		if (c.s.startsWith('<!--', c.pos)) {
			const end = c.s.indexOf('-->', c.pos);
			if (end === -1) throw new Error('unterminated comment');
			c.pos = end + 3;
			continue;
		}
		if (c.s.startsWith('</', c.pos)) {
			c.pos += 2;
			const closeName = readName(c);
			skipWhitespace(c);
			if (c.s[c.pos] !== '>') throw new Error(`closing tag '${closeName}' not closed`);
			c.pos++;
			if (closeName !== name) {
				throw new Error(`mismatched tag: expected </${name}>, got </${closeName}>`);
			}
			break;
		}
		children.push(parseElement(c));
	}
	return { name, attributes, children, text: text.trim() };
}

/**
 * Parse an XML string into a tree. Never throws — returns a structured
 * failure on any malformed input.
 */
export function parseXml(xml: string): XmlParseResult {
	if (typeof xml !== 'string' || xml.trim().length === 0) {
		return { ok: false, reason: 'empty or non-string XML' };
	}
	const c: Cursor = { s: xml, pos: 0 };
	try {
		if (!skipProlog(c))
			return { ok: false, reason: 'unterminated prolog (declaration or comment)' };
		if (c.s[c.pos] !== '<') return { ok: false, reason: 'no root element found' };
		const root = parseElement(c);
		// Trailing content after the root (other than comments/whitespace)
		// means the document is not a single well-formed tree.
		if (!skipProlog(c) || c.pos < c.s.length) {
			return { ok: false, reason: 'unexpected trailing content after root element' };
		}
		return { ok: true, root };
	} catch (err) {
		return { ok: false, reason: err instanceof Error ? err.message : 'malformed XML' };
	}
}

/** First direct child with the given tag name, or undefined. */
export function childNamed(node: XmlNode, name: string): XmlNode | undefined {
	return node.children.find((child) => child.name === name);
}

/** All direct children with the given tag name, in document order. */
export function childrenNamed(node: XmlNode, name: string): XmlNode[] {
	return node.children.filter((child) => child.name === name);
}
