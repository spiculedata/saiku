/*
 * saiku#1820 — `readOnly` must gate STRUCTURE, never SELECTION.
 *
 * The panel is rendered with readOnly=true in an App's view mode (it is simply
 * !editable). That was applied to the member <select> as well, so a published
 * App showed its Filters bar with every picker `disabled` — no reader could use
 * a filter, which is the entire reason one is put on a page.
 *
 * It survived because a programmatic selection ignores `disabled`: every test
 * and every script that drove the panel set .value and dispatched `change`,
 * which works perfectly on a disabled element. Only a real click is refused.
 * So this asserts on the SOURCE, which is the thing that was wrong.
 */

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';

const src = readFileSync('src/lib/views/dashboard/DashboardFilterPanel.svelte', 'utf8');

/** The markup block for a control, taken from its opening tag to the first `>`. */
function controlsWith(attr: string): string[] {
	return src
		.split('<')
		.filter((chunk) => chunk.includes(attr))
		.map((chunk) => chunk.slice(0, chunk.indexOf('>') + 1));
}

describe('filter panel — readOnly gating', () => {
	it('does not disable any control bound to the member selection', () => {
		// The member <select> commits through commitPanelMembers; a date-range
		// input commits through the same path. Both are a READER's controls.
		const gated = controlsWith('disabled={readOnly}');
		for (const c of gated) {
			expect(c, `a selection control must not be gated: ${c}`).not.toMatch(
				/aria-label="Filter members"|onchange=\{\(e\) => commitPanelMembers/
			);
			expect(c, `a date-range input must not be gated: ${c}`).not.toContain('type="date"');
		}
	});

	it('still gates the structural controls', () => {
		// Top-N direction / N / measure retune the FILTER, not the selection, and
		// stay gated. If this ever reads 0 the pendulum has swung too far.
		expect(controlsWith('disabled={readOnly}').length).toBeGreaterThan(0);
	});

	it("does not pass readOnly down to the cascading picker's selects", () => {
		// A cascading select IS the selection control for its filter.
		expect(src).toContain('readOnly={false}');
	});
});
