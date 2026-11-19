import { expect, test, type Page } from './fixtures/coverage';
import { registerOssieBackend } from './fixtures/ossieBackend';

/**
 * saiku#1393 E2E: "different users see different schemas + results".
 *
 * The server-side enforcement (saiku.roles HIDE filtering of the discover DTO and
 * row-predicate injection into the translated SQL) is proven by the Java suites —
 * OssieRoleSecurityIT and OssieFuzzIT run it against a live warehouse. The mocked-backend
 * fixture here stands in for that enforcing server: the *analyst* session gets the
 * discover payload the enforcing server would produce for a role-restricted caller, and
 * the execute payload its row predicates would allow. What this spec proves is the
 * UI-side half of the invariant: the workbench renders the filtered schema and the
 * restricted result grid faithfully, and never surfaces a row or schema entry the
 * (enforcing) server withheld.
 */

/** The full model an unrestricted caller sees — customers + orders, `amount` visible. */
const FULL_MODEL = {
	connection: 'SALES',
	name: 'SALES',
	datasets: [
		{
			name: 'customers',
			source: 'public.customers',
			primaryKey: ['id'],
			fields: [
				{ name: 'id', time: false, pii: false },
				{ name: 'region', time: false, pii: false }
			]
		},
		{
			name: 'orders',
			source: 'public.orders',
			primaryKey: ['order_id'],
			fields: [{ name: 'amount', time: false, pii: false }]
		}
	],
	metrics: [
		{
			name: 'revenue',
			expression: 'SUM(orders.amount)',
			aggregationKind: 'SUM'
		}
	],
	relationships: [
		{
			name: 'orders_to_customers',
			from: 'orders',
			to: 'customers',
			fromColumns: ['customer_id'],
			toColumns: ['id']
		}
	]
};

/**
 * The same model as the enforcing server would project it for a caller whose roles deny
 * the `orders.amount` field: the field is gone from discover entirely — the client never
 * sees, and so never renders, a field it can't query.
 */
const ANALYST_MODEL = {
	...FULL_MODEL,
	datasets: FULL_MODEL.datasets.map((ds) =>
		ds.name === 'orders' ? { ...ds, fields: ds.fields.filter((f) => f.name !== 'amount') } : ds
	)
};

/** Unrestricted result: two region rows. */
const FULL_RESULT = {
	runtime: 42,
	width: 2,
	height: 2,
	cellset: [
		[
			{ value: 'customers.region', type: 'COLUMN_HEADER' },
			{ value: 'revenue', type: 'COLUMN_HEADER' }
		],
		[
			{ value: 'North', type: 'ROW_HEADER' },
			{ value: '350.0', type: 'DATA_CELL', properties: { raw: '350.0' } }
		],
		[
			{ value: 'South', type: 'ROW_HEADER' },
			{ value: '210.0', type: 'DATA_CELL', properties: { raw: '210.0' } }
		]
	]
};

/** Row-restricted result: the same shelf state, but only the caller's access window. */
const ANALYST_RESULT = {
	runtime: 42,
	width: 2,
	height: 1,
	cellset: [
		[
			{ value: 'customers.region', type: 'COLUMN_HEADER' },
			{ value: 'revenue', type: 'COLUMN_HEADER' }
		],
		[
			{ value: 'North', type: 'ROW_HEADER' },
			{ value: '350.0', type: 'DATA_CELL', properties: { raw: '350.0' } }
		]
	]
};

test.describe('Ossie role visibility (mocked backend, saiku#1393)', () => {
	test('an unrestricted caller sees the full schema and both result rows', async ({ page }) => {
		registerOssieBackend(page, { result: FULL_RESULT });
		await suppressTour(page);
		await page.goto('/');
		await page.waitForLoadState('networkidle');

		await page.locator('#cubes-select').selectOption('ossie:SALES');
		await expect(page.locator('.ossie-tree')).toBeVisible();
		// Full schema: both datasets, both fields.
		await expect(
			page.locator('.ossie-tree__group-name').filter({ hasText: 'orders' })
		).toBeVisible();
		await expect(page.locator('.ossie-tree__field').filter({ hasText: 'amount' })).toBeVisible();

		await dropOssieField(page, 'customers', 'region', '[aria-label="Rows shelf"]');
		await dropOssieMetric(page, 'revenue', '[aria-label="Values shelf"]');
		await page.locator('.toolbar').getByRole('button', { name: /^Run/ }).click();

		const bodyRows = page.locator('.ossie-result tbody tr');
		await expect(bodyRows).toHaveCount(2);
		await expect(page.locator('.ossie-result').getByText('South')).toBeVisible();
	});

	test('a role-restricted caller sees a filtered schema and a strict row subset', async ({
		page
	}) => {
		registerOssieBackend(page, {
			username: 'analyst',
			roles: ['ROLE_APAC_ANALYST'],
			model: ANALYST_MODEL,
			result: ANALYST_RESULT
		});
		await suppressTour(page);
		await page.goto('/');
		await page.waitForLoadState('networkidle');

		await page.locator('#cubes-select').selectOption('ossie:SALES');
		await expect(page.locator('.ossie-tree')).toBeVisible();
		// The denied field is absent from the schema tree — not greyed out, not present.
		await expect(page.locator('.ossie-tree__field').filter({ hasText: 'amount' })).toHaveCount(0);
		// Everything the caller may query still renders.
		await expect(
			page.locator('.ossie-tree__group-name').filter({ hasText: 'orders' })
		).toBeVisible();
		await expect(page.locator('.ossie-tree__field').filter({ hasText: 'region' })).toBeVisible();
		await expect(page.locator('.ossie-tree__metric').filter({ hasText: 'revenue' })).toBeVisible();

		await dropOssieField(page, 'customers', 'region', '[aria-label="Rows shelf"]');
		await dropOssieMetric(page, 'revenue', '[aria-label="Values shelf"]');
		await page.locator('.toolbar').getByRole('button', { name: /^Run/ }).click();

		// The result grid shows exactly the caller's access window — one row, not two.
		const bodyRows = page.locator('.ossie-result tbody tr');
		await expect(bodyRows).toHaveCount(1);
		await expect(page.locator('.ossie-result').getByText('North')).toBeVisible();
		await expect(page.locator('.ossie-result').getByText('South')).toHaveCount(0);
	});
});

/** Suppress the first-run tour dialog — same scheme the workbench spec uses. */
async function suppressTour(page: Page) {
	await page.addInitScript(() => {
		window.localStorage.setItem('saiku.tour.done', '1');
		window.localStorage.setItem('saiku.tour.done.admin', '1');
	});
}

async function dropOssieField(page: Page, dataset: string, field: string, targetSelector: string) {
	await page.evaluate(
		([mime, payload, target]) => {
			const dt = new DataTransfer();
			dt.setData(mime, payload);
			const el = document.querySelector(target);
			if (!el) throw new Error(`missing target: ${target}`);
			const opts = { bubbles: true, cancelable: true, dataTransfer: dt } as DragEventInit;
			el.dispatchEvent(new DragEvent('dragover', opts));
			el.dispatchEvent(new DragEvent('drop', opts));
		},
		['application/x-saiku-ossie-field', JSON.stringify({ dataset, field }), targetSelector]
	);
}

async function dropOssieMetric(page: Page, metric: string, targetSelector: string) {
	await page.evaluate(
		([mime, payload, target]) => {
			const dt = new DataTransfer();
			dt.setData(mime, payload);
			const el = document.querySelector(target);
			if (!el) throw new Error(`missing target: ${target}`);
			const opts = { bubbles: true, cancelable: true, dataTransfer: dt } as DragEventInit;
			el.dispatchEvent(new DragEvent('dragover', opts));
			el.dispatchEvent(new DragEvent('drop', opts));
		},
		['application/x-saiku-ossie-metric', JSON.stringify({ metric }), targetSelector]
	);
}
