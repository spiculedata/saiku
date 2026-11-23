/**
 * SetsPanel — the workspace sidebar entry for named sets (saiku#826).
 * Covers the list view (sets shown, empty-state hint), the drag-source
 * wiring dragstart carries (`application/x-saiku-namedset`), and that the
 * delete affordance calls through to the store rather than mutating state
 * directly (the panel is a thin view over QueryStore.namedSets).
 *
 * DOM assertions run against Svelte's server render, same approach as
 * SaveQueryModal.test.ts — this repo has no client-mount test harness. The
 * store is mocked so the panel's rendering doesn't depend on QueryStore's
 * own machinery (undo history, autorun, etc).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from 'svelte/server';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

// vi.mock factories are hoisted above module-scope const declarations, so the
// mock object referenced inside must itself be created through vi.hoisted().
const { mockQuery, upsertNamedSet, removeNamedSet } = vi.hoisted(() => {
	const upsertNamedSet = vi.fn();
	const removeNamedSet = vi.fn();
	const mockQuery: {
		current: { queryModel: { namedSets: Array<Record<string, unknown>> } } | null;
		upsertNamedSet: typeof upsertNamedSet;
		removeNamedSet: typeof removeNamedSet;
	} = {
		current: null,
		upsertNamedSet,
		removeNamedSet
	};
	return { mockQuery, upsertNamedSet, removeNamedSet };
});

vi.mock('$lib/stores/query.svelte', () => ({ query: mockQuery }));
vi.mock('$lib/stores/toasts.svelte', () => ({
	toasts: { success: vi.fn(), warning: vi.fn(), danger: vi.fn() }
}));

import SetsPanel from './SetsPanel.svelte';

const SOURCE = readFileSync(fileURLToPath(new URL('./SetsPanel.svelte', import.meta.url)), 'utf8');

beforeEach(() => {
	upsertNamedSet.mockClear();
	removeNamedSet.mockClear();
	mockQuery.current = null;
});

describe('SetsPanel (saiku#826)', () => {
	it('renders nothing when there is no active query', () => {
		mockQuery.current = null;
		const body = render(SetsPanel).body;
		expect(body.trim()).not.toContain('panels.sets');
	});

	it('shows the empty-state hint when the query has no named sets yet', () => {
		mockQuery.current = { queryModel: { namedSets: [] } };
		const body = render(SetsPanel).body;
		expect(body).toContain('panels.sets');
		expect(body).toContain('panels.noSets');
	});

	it('lists each defined set by caption, falling back to name', () => {
		mockQuery.current = {
			queryModel: {
				namedSets: [
					{ name: 'Premium', expression: 'TopCount(...)', caption: 'Premium Customers' },
					{ name: 'Bulk', expression: 'Filter(...)' }
				]
			}
		};
		const body = render(SetsPanel).body;
		expect(body).toContain('Premium Customers');
		expect(body).toContain('Bulk');
		expect(body).not.toContain('panels.noSets');
	});

	it('each set row is draggable with the sidebar drag-source convention', () => {
		mockQuery.current = {
			queryModel: { namedSets: [{ name: 'Premium', expression: 'TopCount(...)' }] }
		};
		const body = render(SetsPanel).body;
		expect(body).toContain('draggable="true"');
	});

	it('wires drag-start to the application/x-saiku-namedset payload, bracketing the name', () => {
		// SSR can't fire DOM drag events, so the payload shape is asserted at the
		// source level — same technique SaveQueryModal.test.ts uses for its save path.
		expect(SOURCE).toMatch(/application\/x-saiku-namedset['"],\s*JSON\.stringify\(payload\)/);
		expect(SOURCE).toMatch(
			/uniqueNameFor\(set: ThinNamedSet\): string \{\s*return `\[\$\{set\.name\}\]`;/
		);
	});

	it('the + button opens the add-set modal, not an edit of an existing set', () => {
		mockQuery.current = { queryModel: { namedSets: [] } };
		const body = render(SetsPanel).body;
		expect(body).toContain('panels.newSet');
	});

	it('delete routes through query.removeNamedSet rather than mutating namedSets locally', () => {
		expect(SOURCE).toMatch(
			/function onDelete\(name: string\): void \{\s*query\.removeNamedSet\(name\);/
		);
	});

	it('save routes through query.upsertNamedSet', () => {
		expect(SOURCE).toMatch(
			/function onSave\(set: ThinNamedSet\): void \{\s*query\.upsertNamedSet\(set\);/
		);
	});
});
