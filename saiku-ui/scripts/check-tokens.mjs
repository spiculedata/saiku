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

import { execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const here = resolve(import.meta.dirname, '..');
process.chdir(here);

// Forbidden colour roots — the status tones we tokenised.
const FORBIDDEN_TONES = ['emerald', 'red', 'amber', 'rose', 'orange'];

const SEARCH_DIRS = ['src/routes', 'src/lib', 'src/embed'];
const EXEMPT = [
	'src/lib/design-system/'
];

function isExempt(path) {
	return EXEMPT.some((e) => path === e || path.startsWith(e));
}

const pattern = `\\b(text|bg|border|hover:bg|hover:text)-(${FORBIDDEN_TONES.join('|')})-[0-9]+`;
const includes = ['*.svelte', '*.ts'].map((p) => `--include=${p}`).join(' ');
const dirs = SEARCH_DIRS.filter((d) => existsSync(join(here, d))).join(' ');

let raw;
try {
	raw = execSync(`grep -rnE '${pattern}' ${dirs} ${includes}`, {
		encoding: 'utf8',
		stdio: ['ignore', 'pipe', 'ignore']
	});
} catch (e) {
	// grep exits 1 when no matches. That's the success case for this check.
	if (e.status === 1) {
		console.log('✓ No raw status-tone Tailwind utilities found.');
		process.exit(0);
	}
	throw e;
}

const lines = raw.trim().split('\n');
const violations = lines.filter((line) => {
	const path = line.split(':')[0];
	return !isExempt(path);
});

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
