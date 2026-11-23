/*
 * Client-side structural lint for Mondrian 4 schema XML (saiku#1428 Model IDE).
 *
 * This is deliberately NOT a full Mondrian `RolapSchema` compile — that needs a live JDBC
 * connection and runs server-side inside the OLAP engine, which a browser can't do. What
 * this catches instead, entirely offline, is the class of mistake that otherwise only
 * surfaces as an opaque 500 after Save → attach → refresh: unbalanced tags, a missing
 * `<Schema>` root, and Mondrian elements saved without the `name` attribute Mondrian
 * requires to reference them. It runs on every keystroke (debounced by the caller) and
 * feeds Monaco's marker/problems-pane UI.
 */

export type LintSeverity = 'error' | 'warning';

export interface LintIssue {
	severity: LintSeverity;
	message: string;
	startLine: number;
	startColumn: number;
	endLine: number;
	endColumn: number;
}

/** Mondrian elements that are meaningless without a `name` attribute. `Table` uses `name`
 *  for the physical table too, so it's included even though it isn't a schema-object name.
 *  `Hierarchy` is deliberately excluded: Mondrian defaults an unnamed hierarchy's name to
 *  its dimension's name, which is the common single-hierarchy-per-dimension case — flagging
 *  it would make the linter noisy on the majority of real schemas. */
const REQUIRES_NAME = new Set([
	'Schema',
	'Cube',
	'VirtualCube',
	'Dimension',
	'DimensionUsage',
	'Level',
	'Measure',
	'Table',
	'NamedSet'
]);

const VOID_LIKE_RE =
	/<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>|<\?[\s\S]*?\?>|<!DOCTYPE[^>]*>|<\/([A-Za-z_][\w.-]*)\s*>|<([A-Za-z_][\w.-]*)((?:\s+[^<>]*?)?)(\/)?>/g;

function buildLineStarts(text: string): number[] {
	const starts = [0];
	for (let i = 0; i < text.length; i++) {
		if (text[i] === '\n') starts.push(i + 1);
	}
	return starts;
}

function offsetToPosition(lineStarts: number[], offset: number): { line: number; column: number } {
	let lo = 0;
	let hi = lineStarts.length - 1;
	while (lo < hi) {
		const mid = (lo + hi + 1) >> 1;
		if (lineStarts[mid] <= offset) lo = mid;
		else hi = mid - 1;
	}
	return { line: lo + 1, column: offset - lineStarts[lo] + 1 };
}

function hasNameAttr(attrs: string): boolean {
	return /\bname\s*=\s*(".*?"|'.*?')/.test(attrs);
}

export function lintMondrianXml(text: string): LintIssue[] {
	const issues: LintIssue[] = [];
	const lineStarts = buildLineStarts(text);
	const at = (start: number, end: number) => {
		const s = offsetToPosition(lineStarts, start);
		const e = offsetToPosition(lineStarts, end);
		return { startLine: s.line, startColumn: s.column, endLine: e.line, endColumn: e.column };
	};

	type OpenTag = { name: string; start: number; end: number };
	const stack: OpenTag[] = [];
	let sawRoot = false;
	let rootIsSchema = false;

	VOID_LIKE_RE.lastIndex = 0;
	let m: RegExpExecArray | null;
	while ((m = VOID_LIKE_RE.exec(text))) {
		const closeName = m[1];
		const openName = m[2];
		if (closeName !== undefined) {
			// Closing tag: </Foo>
			const top = stack[stack.length - 1];
			if (!top) {
				issues.push({
					severity: 'error',
					message: `Unexpected closing tag </${closeName}> — no matching open tag.`,
					...at(m.index, m.index + m[0].length)
				});
			} else if (top.name !== closeName) {
				issues.push({
					severity: 'error',
					message: `Expected </${top.name}> but found </${closeName}>.`,
					...at(m.index, m.index + m[0].length)
				});
				// Best-effort recovery: pop anyway so one typo doesn't cascade into
				// reporting every subsequent tag in the file as mismatched.
				stack.pop();
			} else {
				stack.pop();
			}
			continue;
		}
		if (openName !== undefined) {
			const attrs = m[3] ?? '';
			const selfClosing = m[4] === '/';
			if (!sawRoot) {
				sawRoot = true;
				rootIsSchema = openName === 'Schema';
			}
			if (REQUIRES_NAME.has(openName) && !hasNameAttr(attrs)) {
				issues.push({
					severity: openName === 'Schema' ? 'warning' : 'error',
					message: `<${openName}> is missing a required "name" attribute.`,
					...at(m.index, m.index + m[0].length)
				});
			}
			if (!selfClosing) {
				stack.push({ name: openName, start: m.index, end: m.index + m[0].length });
			}
		}
	}

	if (!sawRoot) {
		issues.push({
			severity: 'error',
			message: 'Empty document — expected a Mondrian <Schema> root element.',
			startLine: 1,
			startColumn: 1,
			endLine: 1,
			endColumn: 1
		});
	} else if (!rootIsSchema) {
		issues.push({
			severity: 'warning',
			message: 'Root element is not <Schema> — Mondrian will not load this file as-is.',
			startLine: 1,
			startColumn: 1,
			endLine: 1,
			endColumn: 1
		});
	}

	for (const open of stack) {
		issues.push({
			severity: 'error',
			message: `<${open.name}> is never closed.`,
			...at(open.start, open.end)
		});
	}

	return issues;
}
