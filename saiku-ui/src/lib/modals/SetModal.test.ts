/**
 * SetModal — the "Add named set" / edit dialog for saiku#826. Covers the
 * fields the issue calls for (name + MDX expression, optional caption),
 * the same name-validation shape CalculatedMemberModal uses, the MDX
 * preview, and that Save stays disabled until the form is valid.
 *
 * DOM assertions run against Svelte's server render, same approach as
 * SaveQueryModal.test.ts — this repo has no client-mount test harness.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from 'svelte/server';

vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

import SetModal from './SetModal.svelte';

function bodyFor(props: Record<string, unknown> = {}): string {
	return render(SetModal, {
		props: {
			open: true,
			onSave: vi.fn(),
			onCancel: vi.fn(),
			...props
		}
	}).body;
}

describe('SetModal (saiku#826)', () => {
	it('renders name, caption and expression fields', () => {
		const body = bodyFor();
		expect(body).toContain('modal.set.name');
		expect(body).toContain('modal.set.caption');
		expect(body).toContain('modal.set.expression');
	});

	// Button's variant classes always carry Tailwind `disabled:*` selectors
	// (e.g. "disabled:bg-muted") regardless of state, so the actual disabled
	// attribute must be matched as `disabled=""` / `disabled>`, not a bare
	// "disabled" substring.
	function saveButtonMarkup(body: string): string {
		const saveIdx = body.indexOf('modal.save<');
		const footerStart = body.indexOf('<footer');
		const chunk = body.slice(footerStart, saveIdx);
		return chunk.slice(chunk.lastIndexOf('<button'));
	}

	it('Save is disabled on a blank form', () => {
		const body = bodyFor();
		expect(saveButtonMarkup(body)).toMatch(/\sdisabled(=""|>)/);
	});

	it('Save is enabled once name + expression are both filled', () => {
		const body = bodyFor({ initial: { name: 'Premium', expression: 'TopCount(...)' } });
		expect(saveButtonMarkup(body)).not.toMatch(/\sdisabled(=""|>)/);
	});

	it('shows the name validation hint only for an invalid non-empty name', () => {
		const blank = bodyFor();
		expect(blank).not.toContain('modal.set.nameError');

		const invalid = bodyFor({ initial: { name: '1bad name!', expression: 'X' } });
		expect(invalid).toContain('modal.set.nameError');

		const valid = bodyFor({ initial: { name: 'Good_Name-1', expression: 'X' } });
		expect(valid).not.toContain('modal.set.nameError');
	});

	it('renders a WITH SET MDX preview from the current name/expression', () => {
		const body = bodyFor({ initial: { name: 'Premium', expression: 'TopCount([Customer], 10)' } });
		expect(body).toContain("WITH SET [Premium] AS 'TopCount([Customer], 10)'");
	});

	it('renders an ellipsis preview placeholder when name/expression are blank', () => {
		const body = bodyFor();
		expect(body).toContain('WITH SET […] AS');
	});
});
