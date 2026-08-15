import { expect, test, type Page } from '@playwright/test';

/**
 * Live-backend counterpart to `ossie-workbench.spec.ts`. Exercises the same drag-drop flow
 * but against a real Saiku launcher on port 8080. Gated on `RUN_LIVE_E2E=1` (see
 * playwright.config.ts) so CI never runs it unintentionally.
 *
 * Requires the launcher to have:
 *
 * 1. Started (`java -jar saiku-launcher/target/saiku-*.jar serve --port 8080`).
 * 2. An OSSIE datasource named "SALES" (or whichever `LIVE_OSSIE_CONN` picks) already
 *    registered — either via the admin API or via a pre-seeded `.sds` file in
 *    `saiku-home/repository/data/unknown/datasources/`.
 * 3. The Ossie YAML the datasource points at, plus a live warehouse the YAML references.
 *
 * The spec is deliberately terse: the full assertion surface lives in the mocked spec.
 * This one exists to catch backend regressions the mocks can't (JSON shape changes,
 * routing changes, session-cookie behaviour).
 */

const LIVE_CONN = process.env.LIVE_OSSIE_CONN ?? 'SALES';
const LIVE_USER = process.env.LIVE_ADMIN_USER ?? 'admin';
const LIVE_PASS = process.env.LIVE_ADMIN_PASS ?? 'admin';
const LIVE_FIELD = process.env.LIVE_OSSIE_FIELD ?? 'region';
const LIVE_METRIC = process.env.LIVE_OSSIE_METRIC ?? 'revenue';
const LIVE_DATASET = process.env.LIVE_OSSIE_DATASET ?? 'customers';

test.describe('Ossie workbench (live backend)', () => {
	test.beforeEach(async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('saiku.tour.done', '1');
			window.localStorage.setItem(`saiku.tour.done.${'admin'}`, '1');
		});
		await page.goto('/');
		await page.waitForLoadState('networkidle');
		// Log in if the page is showing the login form.
		if (await page.locator('input[name="username"]').isVisible()) {
			await page.locator('input[name="username"]').fill(LIVE_USER);
			await page.locator('input[name="password"]').fill(LIVE_PASS);
			await page.getByRole('button', { name: /sign in|log in/i }).click();
			await page.waitForLoadState('networkidle');
		}
	});

	test('end-to-end: pick model, drop shelves, run, see rows', async ({ page }) => {
		await expect(page.locator('#cubes-select')).toBeVisible();
		// Verify the ossie: option exists — driven by the live server's connection listing.
		const optionValues = await page
			.locator('#cubes-select option')
			.evaluateAll((els) => (els as HTMLOptionElement[]).map((o) => o.value));
		expect(optionValues).toContain(`ossie:${LIVE_CONN}`);

		await page.locator('#cubes-select').selectOption(`ossie:${LIVE_CONN}`);
		await expect(page.locator('.ossie-tree__field').filter({ hasText: LIVE_FIELD })).toBeVisible();

		await dropOssieField(page, LIVE_DATASET, LIVE_FIELD, '[aria-label="Rows shelf"]');
		await dropOssieMetric(page, LIVE_METRIC, '[aria-label="Values shelf"]');

		await page.locator('.ossie-canvas').getByRole('button', { name: /^Run/ }).click();

		// Live tolerance — accept any body rows; the specific values depend on the warehouse.
		await expect(page.locator('.ossie-result thead')).toBeVisible();
		await expect(page.locator('.ossie-result tbody tr').first()).toBeVisible();
	});
});

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
