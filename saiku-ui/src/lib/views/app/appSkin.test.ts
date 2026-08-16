/*
 * @vitest-environment jsdom
 *
 * Unit tests for the built-in App Builder skin.
 *
 * The one rule worth pinning is the chart-card header hide. It exists so a
 * chart tile doesn't render the generic tile header on top of the title the
 * ECharts option already draws — but the tile header is also where the
 * configure / menu / remove buttons live, so hiding it unconditionally makes
 * chart tiles unreachable from the authoring UI. The rule must therefore be
 * gated on the app root NOT carrying `data-saiku-app-edit` (which AppShell
 * stamps while editing).
 */

import { describe, test, expect } from 'vitest';
import { appSkinCss } from '$lib/views/app/appSkin';

const SCOPE = '[data-saiku-app="preview"]';

/** The single declaration block that hides a chart card's generic header. */
function chartHeaderRule(css: string): string {
	const line = css.split('\n').find((l) => l.includes('.tile-header { display: none; }'));
	if (!line) throw new Error('chart-card header rule not found in skin CSS');
	return line;
}

describe('appSkinCss', () => {
	test('scopes every rule under the app root so it cannot leak', () => {
		const css = appSkinCss(SCOPE);
		const selectors = css
			.split('\n')
			.filter((l) => l.includes('{') && !l.trimStart().startsWith('/*'))
			.map((l) => l.slice(0, l.indexOf('{')).trim());

		expect(selectors.length).toBeGreaterThan(0);
		for (const sel of selectors) {
			for (const part of sel.split(',')) {
				expect(part.trim().startsWith(SCOPE)).toBe(true);
			}
		}
	});

	test("hides the chart card's generic header only outside edit mode", () => {
		const rule = chartHeaderRule(appSkinCss(SCOPE));
		expect(rule).toContain(`${SCOPE}:not([data-saiku-app-edit])`);
	});

	/*
	 * saiku#1788 — the rule used to select `.tile:not(:has(.kpi-tile)):not(:has(tbody))`,
	 * i.e. "not a KPI and not a table", as a stand-in for "is a chart". It got both
	 * ends wrong:
	 *
	 *  - Chart tiles render a screen-reader data table (`table.sr-only > tbody`) for
	 *    accessibility, so they matched :has(tbody) and KEPT the header the rule was
	 *    written to hide.
	 *  - Text and custom-renderer tiles (ranked list, graph) have neither marker, so
	 *    they matched and silently LOST the title the author typed — those renderers
	 *    draw no title of their own, so nothing replaced it.
	 *
	 * The signal is now `data-chart-titled`, stamped by Tile.svelte only when a chart
	 * actually carries its own chartOptions.title — the sole case where the generic
	 * header genuinely duplicates something.
	 */
	describe('view-mode header hiding targets titled charts only (saiku#1788)', () => {
		const selector = (() => {
			const rule = chartHeaderRule(appSkinCss(SCOPE));
			return rule.slice(0, rule.indexOf('{')).trim();
		})();

		/** Build an app root in view mode holding one tile, and report whether the rule hides its header. */
		function headerHidden(tileHtml: string): boolean {
			const root = document.createElement('div');
			root.setAttribute('data-saiku-app', 'preview');
			root.innerHTML = tileHtml;
			document.body.append(root);
			try {
				return [...document.querySelectorAll(selector)].some((el) => root.contains(el));
			} finally {
				root.remove();
			}
		}

		const HEADER = '<header class="tile-header"><span>Author title</span></header>';

		test('hides the header of a chart that draws its own title', () => {
			expect(
				headerHidden(
					`<div class="tile" data-tile-type="chart" data-chart-titled>${HEADER}<div class="chart-tile"></div></div>`
				)
			).toBe(true);
		});

		test('keeps the header of a chart with no title of its own', () => {
			expect(
				headerHidden(
					`<div class="tile" data-tile-type="chart">${HEADER}<div class="chart-tile"></div></div>`
				)
			).toBe(false);
		});

		test('keeps the header of a chart carrying an sr-only a11y table', () => {
			// The old :has(tbody) sniff was satisfied by this table, inverting the rule.
			expect(
				headerHidden(
					`<div class="tile" data-tile-type="chart">${HEADER}` +
						`<div class="chart-tile"><table class="sr-only"><tbody><tr><td>1</td></tr></tbody></table></div></div>`
				)
			).toBe(false);
		});

		for (const [label, html] of [
			[
				'text',
				`<div class="tile" data-tile-type="text">${HEADER}<div class="text-tile"></div></div>`
			],
			[
				'custom (ranked list / graph)',
				`<div class="tile" data-tile-type="custom">${HEADER}<div></div></div>`
			],
			['kpi', `<div class="tile" data-tile-type="kpi">${HEADER}<div class="kpi-tile"></div></div>`],
			[
				'table',
				`<div class="tile" data-tile-type="table">${HEADER}<table><tbody><tr><td>1</td></tr></tbody></table></div>`
			]
		] as const) {
			test(`keeps the author's title on a ${label} tile`, () => {
				expect(headerHidden(html)).toBe(false);
			});
		}
	});

	test('chart-card header rule does not match an app root marked as editing', () => {
		const rule = chartHeaderRule(appSkinCss(SCOPE));
		const selector = rule.slice(0, rule.indexOf('{')).trim();

		// saiku#1788: the tile must carry data-chart-titled, or the rule matches
		// nothing and this test passes for the wrong reason.
		const tile =
			'<div class="tile" data-tile-type="chart" data-chart-titled>' +
			'<div class="tile-header"></div></div>';

		const editing = document.createElement('div');
		editing.setAttribute('data-saiku-app', 'preview');
		editing.setAttribute('data-saiku-app-edit', '');
		editing.innerHTML = tile;

		const viewing = document.createElement('div');
		viewing.setAttribute('data-saiku-app', 'preview');
		viewing.innerHTML = tile;

		// Attached so :not()/:has() resolve against a real tree. Matching runs from
		// the document, because the selector's leading compound is the app root
		// itself — an element-scoped querySelector only ever looks at descendants.
		document.body.append(editing, viewing);
		try {
			const matched = [...document.querySelectorAll(selector)];
			expect(matched).toHaveLength(1);
			expect(viewing.contains(matched[0])).toBe(true);
			expect(editing.contains(matched[0])).toBe(false);
		} finally {
			editing.remove();
			viewing.remove();
		}
	});
});

/* ====================================================================
 * saiku#1798 — the KPI grid must place EVERY text child in the left column.
 *
 * The skin used a fixed `grid-template-areas: "value spark" "delta spark"`, so
 * only .value and .delta had a home. A KPI on a cube with no time dimension
 * renders neither a delta nor a sparkline — it renders .caption, which had no
 * area and auto-placed into the 96px spark gutter, where "Grocery Sqft" wrapped
 * over two lines beside a number sitting in a half-empty box.
 * ==================================================================== */
describe('appSkinCss — KPI grid (saiku#1798)', () => {
	const css = appSkinCss(SCOPE);

	test('pins every KPI child to the text column rather than naming two areas', () => {
		expect(css).toContain(`${SCOPE} .kpi-tile > * { grid-column: 1; }`);
		// The old fixed area map is what stranded the caption; it must be gone.
		expect(css).not.toContain('grid-template-areas: "value spark" "delta spark"');
	});

	test('the sparkline spans the second column for as many rows as there are', () => {
		const rule = css.split('\n').find((l) => l.includes('.kpi-tile div[aria-hidden="true"]'));
		expect(rule).toBeTruthy();
		expect(rule).toContain('grid-column: 2');
		expect(rule).toContain('grid-row: 1 / -1');
	});

	test('a KPI with no sparkline reclaims the gutter', () => {
		expect(css).toContain(
			`${SCOPE} .kpi-tile:not(:has(div[aria-hidden="true"])) { grid-template-columns: 1fr; }`
		);
	});

	test('the caption and the partial-period line are styled as the second line', () => {
		const rule = css
			.split('\n')
			.find((l) => l.includes('.kpi-tile .period,') && l.includes('.kpi-tile .caption'));
		expect(rule).toBeTruthy();
	});
});
