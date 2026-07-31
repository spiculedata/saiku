/**
 * Syntax highlighters for the schema-canvas Code tab (XML + YAML previews).
 *
 * Extracted from WorkbenchView so the string logic is unit-testable (the
 * dashboard has no Svelte render tests). Rendered via `{@html}`, so the
 * output must be well-formed: each highlighter is SINGLE-PASS.
 *
 * Why single-pass matters: the old implementation ran several sequential
 * `.replace()`es. Each inserted `<span class="...">`, and a *later* pass then
 * matched the `class="..."` (or the quotes) inside that span — corrupting the
 * markup into visible garbage like `name=class="xml-string">"product_name"`
 * (note the stray `>` that would never validate). A single alternation pass
 * never re-scans its own replacement text, so tokens can't corrupt each other.
 */

/** Escape the three HTML-structural characters so `{@html}` renders text, not markup. */
export function escapeBasic(s: string): string {
	return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * Highlight XML: tag names, attribute names, quoted values, comments.
 * Operates on the HTML-escaped source (so `<` is already `&lt;`).
 */
export function highlightXml(src: string): string {
	const s = escapeBasic(src);
	return s.replace(
		// comment | quoted-value | tag-open + name | attribute-name (before `=`)
		/(&lt;!--[\s\S]*?--&gt;)|("[^"]*")|(&lt;\/?)([A-Za-z][\w:-]*)|([\w:-]+)(?==)/g,
		(_m, comment, str, open, tag, attr) => {
			if (comment) return `<span class="xml-comment">${comment}</span>`;
			if (str) return `<span class="xml-string">${str}</span>`;
			if (open) return `${open}<span class="xml-tag">${tag}</span>`;
			if (attr) return `<span class="xml-attr">${attr}</span>`;
			return _m;
		}
	);
}

/**
 * Highlight YAML: leading keys → xml-tag, quoted values → xml-string.
 * Per-line + single-pass for the same anti-collision reason as {@link highlightXml}.
 */
export function highlightYaml(src: string): string {
	return src
		.split('\n')
		.map((line) =>
			escapeBasic(line).replace(
				// leading key (`indent key:`) | quoted string value
				/^(\s*-?\s*)([\w.]+)(:)|("[^"]*")/g,
				(_m, indent, key, colon, str) => {
					if (str) return `<span class="xml-string">${str}</span>`;
					if (key !== undefined) {
						return `${indent}<span class="xml-tag">${key}</span>${colon}`;
					}
					return _m;
				}
			)
		)
		.join('\n');
}
