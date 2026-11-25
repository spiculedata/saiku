import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * SQL workbench e2e, mocked backend (saiku#2599). Verifies:
 *
 * 1. C1/C5 — a result taller than the viewport scrolls (the page is the scroll container,
 *    since `.app` is overflow:hidden), the pager is reachable by scrolling, and a wide grid
 *    scrolls horizontally inside its own wrapper.
 * 2. C2 — the datasource selector shows what it connects to: database product, catalog and
 *    default schema from DatabaseMetaData, plus the credential-redacted JDBC URL.
 * 3. C3 — the collapsible table list names the default schema's tables; clicking one puts
 *    `SELECT * FROM "<table>"` into the editor (quoted, not bare).
 * 4. C4 — the topbar does not clip any nav item at 1024px or 1280px.
 */

const DATASOURCE = { name: 'bank', type: 'OLAP' };

const INFO = {
	name: 'bank',
	databaseProductName: 'H2',
	databaseProductVersion: '2.2.224',
	driverName: 'H2 JDBC Driver',
	driverVersion: '2.2.224',
	catalog: 'FOODMART',
	schema: 'PUBLIC',
	url: 'jdbc:h2:file:/data/bank;IFEXISTS=TRUE'
};

const TABLES = ['account', 'branch', 'customer'];

const ROW_COUNT = 300;

function bigResult(rowCount: number) {
	return {
		columns: ['id', 'label'],
		rows: Array.from({ length: rowCount }, (_, i) => [
			i,
			i === 0 ? `row-${i}-${'wide'.repeat(100)}` : `row-${i}`
		]),
		rowCount,
		truncated: false,
		durationMs: 1
	};
}

interface SqlWorkbenchBackendHandle {
	/** The body of the most recent POST /sql-workbench/query. */
	lastQueryBody: { datasource?: string; sql?: string; maxRows?: number | null } | null;
}

function registerSqlWorkbenchBackend(
	page: Page,
	options: { rowCount?: number } = {}
): SqlWorkbenchBackendHandle {
	const state: SqlWorkbenchBackendHandle = { lastQueryBody: null };

	const routes: Array<[string | RegExp, (route: Route) => Promise<void> | void]> = [
		// Session: a signed-in admin with ROLE_SQL_EXEC so the workbench page mounts.
		[
			'**/rest/saiku/session',
			async (route) => {
				if (route.request().method() === 'GET') {
					await route.fulfill({
						status: 200,
						contentType: 'application/json',
						body: JSON.stringify({
							username: 'admin',
							roles: ['ROLE_ADMIN', 'ROLE_USER', 'ROLE_SQL_EXEC'],
							sessionid: 'e2e-session-admin',
							language: 'en',
							isadmin: true
						})
					});
				} else {
					await route.fulfill({ status: 204 });
				}
			}
		],
		[
			'**/rest/saiku/sql-workbench/datasources',
			async (route) =>
				route.fulfill({
					status: 200,
					contentType: 'application/json',
					body: JSON.stringify([DATASOURCE])
				})
		],
		[
			'**/rest/saiku/sql-workbench/datasources/bank/info',
			async (route) =>
				route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(INFO) })
		],
		[
			'**/rest/saiku/sql-workbench/datasources/bank/tables',
			async (route) =>
				route.fulfill({
					status: 200,
					contentType: 'application/json',
					body: JSON.stringify(TABLES)
				})
		],
		[
			'**/rest/saiku/sql-workbench/query',
			async (route) => {
				try {
					state.lastQueryBody = JSON.parse(route.request().postData() ?? 'null');
				} catch {
					state.lastQueryBody = null;
				}
				await route.fulfill({
					status: 200,
					contentType: 'application/json',
					body: JSON.stringify(bigResult(options.rowCount ?? ROW_COUNT))
				});
			}
		],
		// Boot-time endpoints the app probes on any page. Empty answers keep the console clean.
		[
			'**/rest/saiku/version',
			async (route) => route.fulfill({ status: 200, contentType: 'text/plain', body: 'e2e-mock' })
		],
		[
			'**/rest/saiku/api/info/**',
			async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
		],
		[
			'**/rest/saiku/api/repository',
			async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
		],
		[
			'**/rest/saiku/*/discover',
			async (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
		]
	];

	for (const [pattern, handler] of routes) {
		void page.route(pattern, handler);
	}
	return state;
}

test.describe('SQL workbench (mocked backend)', () => {
	let backend: SqlWorkbenchBackendHandle;

	test.beforeEach(async ({ page }) => {
		backend = registerSqlWorkbenchBackend(page);
		// Suppress the first-run tour dialog (same scheme as the ossie specs).
		await page.addInitScript(() => {
			window.localStorage.setItem('saiku.tour.done', '1');
			window.localStorage.setItem('saiku.tour.done.admin', '1');
		});
	});

	test('C2: the datasource selector shows what it connects to (product · catalog.schema, redacted URL)', async ({
		page
	}) => {
		await page.goto('/sql-workbench');
		const info = page.getByTestId('ds-info');
		await expect(info).toBeVisible();
		await expect(info).toContainText('bank → H2 · FOODMART.PUBLIC');
		await expect(info).toContainText('jdbc:h2:file:/data/bank;IFEXISTS=TRUE');
		await expect(info).not.toContainText('password');
	});

	test('C3: the table list expands and inserts a quoted SELECT into the editor', async ({
		page
	}) => {
		await page.goto('/sql-workbench');

		const tableList = page.getByTestId('table-list');
		await expect(tableList.locator('summary')).toContainText('(3)');
		await tableList.locator('summary').click();
		await page.locator('[data-table="account"]').click();

		// The inserted statement reaches the server when Run is clicked — that is the
		// observable content of the Monaco buffer in a headless browser.
		await page.getByRole('button', { name: 'Run', exact: true }).click();
		await expect.poll(() => backend.lastQueryBody?.sql).toBe('SELECT * FROM "account"');
	});

	test('C1: results taller than the viewport scroll, and the pager is reachable', async ({
		page
	}) => {
		await page.goto('/sql-workbench');

		await page.getByRole('button', { name: 'Run', exact: true }).click();
		const grid = page.getByTestId('results-grid');
		await expect(grid).toBeVisible();

		// C5: the page scrolls to the pager even though `.app` hides overflow.
		const scroller = page.locator('.sql-workbench');
		await expect
			.poll(() => scroller.evaluate((el) => el.scrollHeight - el.clientHeight))
			.toBeGreaterThan(0);

		// The result has its own bounded scroll area so its header stays at the top
		// while the last row can be reached. A wide value also needs horizontal scroll.
		const gridMetrics = await grid.evaluate((el) => ({
			scrollHeight: el.scrollHeight,
			clientHeight: el.clientHeight,
			scrollWidth: el.scrollWidth,
			clientWidth: el.clientWidth
		}));
		expect(gridMetrics.scrollHeight).toBeGreaterThan(gridMetrics.clientHeight);
		expect(gridMetrics.scrollWidth).toBeGreaterThan(gridMetrics.clientWidth);
		await grid.scrollIntoViewIfNeeded();
		const headerTop = await grid
			.locator('th')
			.first()
			.evaluate((el) => el.getBoundingClientRect().top);
		await grid.evaluate((el) => el.scrollTo(0, el.scrollHeight));
		await expect(grid.locator('tbody tr').last()).toBeInViewport();
		expect(
			await grid
				.locator('th')
				.first()
				.evaluate((el) => el.getBoundingClientRect().top)
		).toBeCloseTo(headerTop, 0);
		await grid.evaluate((el) => (el.scrollLeft = el.scrollWidth));
		expect(await grid.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);

		// Scrolling reaches the pager.
		await scroller.evaluate((el) => el.scrollTo(0, el.scrollHeight));
		await expect(page.getByRole('button', { name: 'Next' })).toBeInViewport();
	});

	test('C4: no topbar item is clipped at 1024px or 1280px', async ({ page }) => {
		for (const width of [1024, 1280]) {
			await page.setViewportSize({ width, height: 800 });
			await page.goto('/');
			const actions = page.locator('.topbar__actions a, .topbar__actions button');
			await expect(actions.first()).toBeVisible();
			const clipped = await actions.evaluateAll((els) =>
				els.some((el) => {
					const r = el.getBoundingClientRect();
					return r.left < 0 || r.right > window.innerWidth + 1;
				})
			);
			expect(clipped, `a topbar item is clipped at ${width}px`).toBe(false);
		}
	});
});
