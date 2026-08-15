/**
 * Regression cover for saiku#1570: the Save dialog rendered the tall
 * folder browser first and pushed the Name input below the fold, so
 * users thought there was no way to name a query. The fix moves the
 * Name input to the top of the dialog body (before the folder browser),
 * auto-focuses it on open, and lets Enter save when valid.
 *
 * DOM ordering is asserted against Svelte's server render (the repo has
 * no client-mount test harness — `svelte`'s client `mount` resolves to
 * the server build under vitest). The save wiring is asserted against
 * the component source: every save path (footer button + Enter) routes
 * through one helper that preserves the onSave(folder, name) contract.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from 'svelte/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Stub the heavy folder browser with a marker-only component so the
// render doesn't pull in the repository store / fetch machinery.
vi.mock('$lib/components/RepositoryBrowser.svelte', async () => ({
	default: (await import('./__stubs__/RepositoryBrowserStub.svelte')).default
}));

// i18n: echo the key so assertions are locale-independent.
vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

import SaveQueryModal from './SaveQueryModal.svelte';

const SOURCE = readFileSync(
	fileURLToPath(new URL('./SaveQueryModal.svelte', import.meta.url)),
	'utf8'
);

function bodyFor(props: Record<string, unknown>): string {
	return render(SaveQueryModal, {
		props: {
			defaultName: '',
			defaultFolder: 'reports',
			folders: [],
			open: true,
			onSave: vi.fn(),
			onCancel: vi.fn(),
			...props
		}
	}).body;
}

describe('SaveQueryModal (saiku#1570)', () => {
	it('renders the Name input before the folder browser in DOM order', () => {
		const body = bodyFor({ open: true });

		const nameIdx = body.indexOf('field__input');
		const browserIdx = body.indexOf('data-testid="repo-browser"');

		// Both must be present, and the Name input must come first.
		expect(nameIdx).toBeGreaterThanOrEqual(0);
		expect(browserIdx).toBeGreaterThanOrEqual(0);
		expect(nameIdx).toBeLessThan(browserIdx);
	});

	it('renders the Name field label and intro', () => {
		const body = bodyFor({ open: true });
		expect(body).toContain('modal.save.name');
		expect(body).toContain('modal.save.intro');
		expect(body).toContain('field__input');
	});

	it('routes every save path through a single onSave(folder, name) helper', () => {
		// The helper preserves the folder + trimmed-name contract.
		expect(SOURCE).toMatch(
			/function save\(\)\s*:\s*void\s*\{\s*onSave\(normalizeFolder\(folder\),\s*name\.trim\(\)\);\s*\}/
		);
		// Footer Save button uses the helper.
		expect(SOURCE).toMatch(/onclick=\{save\}/);
		// Enter-to-save is wired and gated on `valid`.
		expect(SOURCE).toMatch(/e\.key === ['"']Enter['"'] && valid/);
		expect(SOURCE).toMatch(/use:autofocus/);
	});
});
