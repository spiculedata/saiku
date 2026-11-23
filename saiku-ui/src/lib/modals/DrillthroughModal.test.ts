/**
 * saiku#823 — regression cover for the row-bound (maxRows vs firstRowset)
 * toggle added to DrillthroughModal.
 *
 * DOM is asserted against Svelte's server render, matching the approach in
 * SaveQueryModal.test.ts — this repo has no client-mount test harness, so
 * the async column-discovery wiring (an `$effect` triggered on `open`) isn't
 * exercised here; that matching logic is unit-tested directly in
 * drillthroughColumnMatch.test.ts instead.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from 'svelte/server';

vi.mock('$lib/stores/i18n.svelte', () => ({
	i18n: { t: (key: string) => key }
}));

import DrillthroughModal from './DrillthroughModal.svelte';

function bodyFor(props: Record<string, unknown> = {}): string {
	return render(DrillthroughModal, {
		props: {
			dimensions: [{ name: 'Time', caption: 'Time', uniqueName: '[Time]', hierarchies: [] }],
			measures: [
				{ name: 'Store Sales', caption: 'Store Sales', uniqueName: '[Measures].[Store Sales]' }
			],
			maxRows: 1000,
			open: true,
			onRun: vi.fn(),
			onExportCsv: vi.fn(),
			onCancel: vi.fn(),
			...props
		}
	}).body;
}

describe('DrillthroughModal row-bound toggle (saiku#823)', () => {
	it('defaults to the maxRows radio checked and firstRowset unchecked', () => {
		const body = bodyFor();

		const maxRowsInput = body.match(/<input type="radio"([^>]*)value="maxRows"/)?.[0] ?? '';
		const firstRowsetInput = body.match(/<input type="radio"([^>]*)value="firstRowset"/)?.[0] ?? '';

		expect(maxRowsInput).toContain('checked');
		expect(firstRowsetInput).not.toContain('checked');
	});

	it('renders both row-bound option labels', () => {
		const body = bodyFor();

		expect(body).toContain('modal.drillthrough.rowBound.maxRows');
		expect(body).toContain('modal.drillthrough.rowBound.firstRowset');
	});

	it('does not show the discovery-loading hint when no discoverColumns prop is supplied', () => {
		const body = bodyFor();
		expect(body).not.toContain('modal.drillthrough.columnsLoading');
	});

	it('renders every passed-in dimension/measure when discovery is not wired up', () => {
		const body = bodyFor();
		expect(body).toContain('Time');
		expect(body).toContain('Store Sales');
	});
});
