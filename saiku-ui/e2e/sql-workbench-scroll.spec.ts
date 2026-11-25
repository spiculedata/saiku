import { expect, test, type Page } from './fixtures/coverage';

// saiku#2599: results taller or wider than the viewport must be reachable by a user scrolling.
// The app shell is `height: 100vh; overflow: hidden`, so the workbench has to provide its own
// scroll container. The check uses the mouse wheel on purpose: a programmatic scrollIntoView
// also moves an overflow:hidden box, so it would pass even while users are stuck.

const COLUMNS = Array.from({ length: 20 }, (_, i) => `a_rather_long_column_name_${i}`);
const ROWS = Array.from({ length: 120 }, (_, r) => COLUMNS.map((_, c) => `row ${r} col ${c}`));

async function registerBackend(page: Page): Promise<void> {
	await page.route('**/rest/**', (route) =>
		route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
	);
	await page.route('**/rest/saiku/session', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify({
				username: 'admin',
				roles: ['ROLE_ADMIN', 'ROLE_SQL_EXEC'],
				sessionid: 'sql-scroll-test',
				language: 'en',
				isadmin: true
			})
		})
	);
	await page.route('**/rest/saiku/sql-workbench/datasources', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify([{ name: 'bank', type: 'MONDRIAN' }])
		})
	);
	await page.route('**/rest/saiku/sql-workbench/query', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify({
				columns: COLUMNS,
				rows: ROWS,
				rowCount: ROWS.length,
				truncated: false,
				durationMs: 3
			})
		})
	);
}

test.use({ viewport: { width: 1280, height: 720 } });

test('a tall, wide result can be scrolled to its pager and its last column', async ({ page }) => {
	await registerBackend(page);
	await page.goto('/sql-workbench');
	await page.locator('.monaco-editor').click();
	await page.keyboard.type('select 1');
	await page.getByRole('button', { name: 'Run' }).click();
	await expect(page.getByRole('columnheader', { name: COLUMNS[0] })).toBeVisible();

	const pager = page.getByRole('button', { name: /next/i });
	await expect(pager).not.toBeInViewport();
	await page.getByRole('columnheader', { name: COLUMNS[0] }).hover();
	for (let i = 0; i < 12 && !(await pager.evaluate(isInViewport)); i++) {
		await page.mouse.wheel(0, 600);
	}
	await expect(pager).toBeInViewport();

	const lastHeader = page.getByRole('columnheader', { name: COLUMNS[COLUMNS.length - 1] });
	await page.getByRole('cell', { name: 'row 0 col 0', exact: true }).hover();
	for (let i = 0; i < 12 && !(await lastHeader.evaluate(isInViewport)); i++) {
		await page.mouse.wheel(600, 0);
	}
	await expect(lastHeader).toBeInViewport();
});

function isInViewport(el: Element): boolean {
	const r = el.getBoundingClientRect();
	return (
		r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth
	);
}
