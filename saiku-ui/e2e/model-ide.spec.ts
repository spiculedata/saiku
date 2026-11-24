import { expect, test, type Page } from './fixtures/coverage';

const ORIGINAL_XML = '<Schema name="Foodmart"><Cube name="Original"/></Schema>';
const EDITED_XML = '<Schema name="Foodmart"><Cube name="Edited"/></Schema>';
const YAML = 'semantic_model:\n- name: sample\n  relationships:\n  - from: ';

function model(name: string, dataset: string) {
	return {
		connection: name,
		name,
		datasets: [{ name: dataset, fields: [{ name: 'id' }], primaryKey: ['id'] }],
		metrics: [],
		relationships: []
	};
}

async function registerBackend(
	page: Page,
	onModel?: (name: string) => Promise<void>
): Promise<{ uploads: string[]; modelRequests: string[] }> {
	const uploads: string[] = [];
	const modelRequests: string[] = [];
	// Register the fallback first; Playwright checks newer routes first.
	await page.route('**/rest/**', (route) =>
		route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
	);
	await page.route('**/rest/saiku/session', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify({
				username: 'admin',
				roles: ['ROLE_ADMIN'],
				sessionid: 'model-ide-test',
				language: 'en',
				isadmin: true
			})
		})
	);
	await page.route('**/rest/saiku/api/ai/cubes', (route) =>
		route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
	);
	await page.route('**/rest/saiku/admin/schema', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify([{ name: 'Foodmart', path: '/datasources/Foodmart.xml' }])
		})
	);
	await page.route('**/rest/saiku/admin/datasources', (route) =>
		route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify(
				['A', 'B'].map((name) => ({ id: name, connectionname: name, connectiontype: 'OSSIE' }))
			)
		})
	);
	await page.route('**/rest/saiku/admin/schema/Foodmart', async (route) => {
		if (route.request().method() === 'POST') {
			uploads.push(route.request().postData() ?? '');
			await route.fulfill({ status: 200, body: '' });
		} else {
			await route.fulfill({ status: 200, contentType: 'application/xml', body: ORIGINAL_XML });
		}
	});
	await page.route('**/rest/saiku/admin/ossie/schema/*', (route) =>
		route.fulfill({ status: 200, contentType: 'text/plain', body: YAML })
	);
	await page.route('**/rest/saiku/*/discover/*/ossie-model', async (route) => {
		const name = decodeURIComponent(
			new URL(route.request().url()).pathname.split('/').at(-2) ?? ''
		);
		modelRequests.push(name);
		await onModel?.(name);
		await route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify(model(name, name === 'A' ? 'live_alpha' : 'live_beta'))
		});
	});
	return { uploads, modelRequests };
}

async function selectOssie(page: Page, name: string): Promise<void> {
	const response = page.waitForResponse(
		(r) => r.url().endsWith(`/discover/${name}/ossie-model`) && r.status() === 200
	);
	await page.locator('.ide__file-list').getByRole('button', { name, exact: true }).click();
	await response;
	await expect(page.locator('.monaco-editor')).toBeVisible();
}

async function expectSuggestions(page: Page, present: string, absent: string): Promise<void> {
	const input = page.locator('.monaco-editor textarea.inputarea');
	await input.focus();
	await input.press('Control+End');
	await input.press('Control+Space');
	const suggestions = page.locator('.suggest-widget .monaco-list-row');
	await expect(suggestions.filter({ hasText: present })).toBeVisible();
	await expect(suggestions.filter({ hasText: absent })).toHaveCount(0);
	await input.press('Escape');
}

test('Mondrian opens its saved XML and uploads the edited editor content', async ({ page }) => {
	const backend = await registerBackend(page);
	await page.goto('/admin/model-ide');
	await page.getByRole('button', { name: 'Foodmart' }).click();
	const editor = page.locator('.monaco-editor');
	await expect(editor).toContainText('Original');
	const input = editor.locator('textarea.inputarea');
	await input.focus();
	await input.press('Control+A');
	await page.keyboard.insertText(EDITED_XML);
	await expect(editor).toContainText('Edited');
	await page.getByRole('button', { name: 'Save', exact: true }).click();
	await expect(page.getByText(/Saved "Foodmart"/)).toBeVisible();
	expect(backend.uploads).toHaveLength(1);
	expect(backend.uploads[0]).toContain(EDITED_XML);
	expect(backend.uploads[0]).not.toContain(ORIGINAL_XML);
});

test('Ossie completion refreshes when returning to an already-open datasource', async ({
	page
}) => {
	const backend = await registerBackend(page);
	await page.goto('/admin/model-ide');
	await selectOssie(page, 'A');
	await expectSuggestions(page, 'live_alpha', 'live_beta');
	await selectOssie(page, 'B');
	await expectSuggestions(page, 'live_beta', 'live_alpha');
	await selectOssie(page, 'A');
	await expectSuggestions(page, 'live_alpha', 'live_beta');
	expect(backend.modelRequests).toEqual(['A', 'B', 'A']);
});

test('a late response from the prior datasource cannot replace current completion', async ({
	page
}) => {
	let releaseA!: () => void;
	const heldA = new Promise<void>((resolve) => (releaseA = resolve));
	const backend = await registerBackend(page, (name) => (name === 'A' ? heldA : Promise.resolve()));
	await page.goto('/admin/model-ide');
	await page.locator('.ide__file-list').getByRole('button', { name: 'A', exact: true }).click();
	await expect.poll(() => backend.modelRequests).toContain('A');
	await selectOssie(page, 'B');
	await expectSuggestions(page, 'live_beta', 'live_alpha');
	const lateResponse = page.waitForResponse((r) => r.url().endsWith('/discover/A/ossie-model'));
	releaseA();
	await lateResponse;
	await expectSuggestions(page, 'live_beta', 'live_alpha');
});
