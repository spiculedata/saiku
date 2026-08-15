/**
 * The "Email" action lives INSIDE the Export dropdown (alongside XLS/CSV/PDF),
 * not as its own toolbar button. WorkspaceToolbar has 26 store imports and the
 * Export menu only renders when its internal `exportMenuOpen` state is true, so
 * a full SSR mount is impractical/brittle here — instead pin the exact source
 * contract the placement change must preserve (same pattern as
 * SaveQueryModal.test.ts's source-assertions half):
 *  - the Email item sits within the Export dropdown block,
 *  - it stays gated by `mailHealth.configured` (disabled when unconfigured),
 *  - it fires the same Email-me-this composer action, and
 *  - the old standalone Email toolbar button is gone.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const SOURCE = readFileSync(
	fileURLToPath(new URL('./WorkspaceToolbar.svelte', import.meta.url)),
	'utf8'
);

/** The Export dropdown block: from `{#if exportMenuOpen}` to its `{/if}`. */
function exportDropdownBlock(src: string): string {
	const start = src.indexOf('{#if exportMenuOpen}');
	expect(start, 'Export dropdown block must exist').toBeGreaterThanOrEqual(0);
	const end = src.indexOf('{/if}', start);
	expect(end).toBeGreaterThan(start);
	return src.slice(start, end);
}

describe('Email action lives in the Export dropdown', () => {
	it('renders an Email item INSIDE the Export dropdown', () => {
		const block = exportDropdownBlock(SOURCE);
		// The Export menu carries the export formats AND the Email item.
		expect(block).toMatch(/exportCurrent\(['"']pdf['"']\)/);
		expect(block).toContain('toolbar.emailMeThis');
		// The Email item fires the shared handler that closes the menu + opens the composer.
		expect(block).toMatch(/onclick=\{emailFromExport\}/);
	});

	it('gates the Email item on mailHealth.configured (unchanged behavior)', () => {
		const block = exportDropdownBlock(SOURCE);
		// Disabled when mail isn't configured — same gate the old button used.
		expect(block).toMatch(/disabled=\{!mailHealth\.configured\}/);
		// Keeps the configured/disabled tooltip copy.
		expect(block).toContain('toolbar.emailMeThis.disabled');
	});

	it('routes emailFromExport through the same composer action (open the modal)', () => {
		// The handler closes the Export menu and opens the existing Email-me-this
		// modal — the POST /saiku/api/email/self composer flow is unchanged.
		expect(SOURCE).toMatch(
			/function emailFromExport\(\)\s*\{[\s\S]*?exportMenuOpen = false;[\s\S]*?emailModalOpen = true;[\s\S]*?\}/
		);
		// The modal itself is untouched.
		expect(SOURCE).toContain(
			'<EmailMeThisModal open={emailModalOpen} onClose={() => (emailModalOpen = false)} />'
		);
	});

	it('removes the standalone Email toolbar button', () => {
		// The old standalone button lived in its own `role="group" aria-label="Email"`.
		expect(SOURCE).not.toContain('aria-label="Email"');
		// And it was a `tb-btn` firing the modal directly; that inline handler is gone.
		expect(SOURCE).not.toMatch(
			/class="tb-btn"[\s\S]*?onclick=\{\(\) => \(emailModalOpen = true\)\}/
		);
	});
});
