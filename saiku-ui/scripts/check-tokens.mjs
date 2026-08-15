#!/usr/bin/env node
/**
 * Design-system token check.
 *
 * Fails the build when raw Tailwind status-tone utilities
 * (`text-emerald-300`, `bg-red-950/30`, `border-amber-600/40`, etc.)
 * appear outside the design-system layer. Use the semantic tone tokens
 * (`text-success`, `text-destructive`, `text-warning`, `text-info`,
 * `text-danger`) so light AND dark mode both work without overrides.
 *
 * saiku-ui has no eslint config (yet) so unlike saiku-cloud-dashboard
 * the AST-level no-restricted-syntax companion rule isn't paired with
 * this script — but the grep here catches the load-bearing case (raw
 * tone utilities in class attributes). When eslint lands, mirror the
 * saiku-cloud rule for the literal-string case too.
 *
 * Allowed exceptions:
 *   - src/lib/design-system/   — definition site of TONE_CLASSES
 */

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, resolve, relative, sep } from 'node:path';

const here = resolve(import.meta.dirname, '..');
process.chdir(here);

// Forbidden colour roots — the status tones we tokenised.
const FORBIDDEN_TONES = ['emerald', 'red', 'amber', 'rose', 'orange'];

const SEARCH_DIRS = ['src/routes', 'src/lib', 'src/embed'];
const EXEMPT = ['src/lib/design-system/'];

function isExempt(path) {
	return EXEMPT.some((e) => path === e || path.startsWith(e));
}

const pattern = new RegExp(
	`\\b(text|bg|border|hover:bg|hover:text)-(${FORBIDDEN_TONES.join('|')})-[0-9]+`
);
const EXTENSIONS = ['.svelte', '.ts'];

// Node-native recursive scan — no shell-out to `grep`, so the check runs the
// same on Windows / macOS / Linux (the old execSync(`grep …`) crashed on
// Windows where grep isn't a command; saiku#1362 follow-up). Emits the same
// `relPath:lineNo:lineText` shape grep -rnE produced, so the logic below is
// unchanged.
function scan(dir, out) {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
		const full = join(dir, entry.name);
		if (entry.isDirectory()) {
			scan(full, out);
		} else if (EXTENSIONS.some((e) => entry.name.endsWith(e))) {
			const rel = relative(here, full).split(sep).join('/'); // posix-style for isExempt
			const fileLines = readFileSync(full, 'utf8').split(/\r?\n/);
			fileLines.forEach((text, i) => {
				if (pattern.test(text)) out.push(`${rel}:${i + 1}:${text}`);
			});
		}
	}
}

const matches = [];
for (const d of SEARCH_DIRS) {
	const abs = join(here, d);
	if (existsSync(abs)) scan(abs, matches);
}

const violations = matches.filter((line) => !isExempt(line.split(':')[0]));

if (violations.length === 0) {
	console.log('✓ No raw status-tone Tailwind utilities found outside allowed paths.');
	process.exit(0);
}

console.error('\n✗ Raw Tailwind status-tone utilities found.');
console.error('  Use design-system tone tokens instead:');
console.error('    text-emerald-* → text-success');
console.error('    text-red-*     → text-destructive');
console.error('    text-amber-*   → text-warning');
console.error('  Background/border equivalents: bg-success/10, border-warning/40, etc.');
console.error('  See: dashboard/src/lib/design-system/README.md\n');
console.error(violations.join('\n'));
console.error(`\n${violations.length} violation(s) found.`);
process.exit(1);
