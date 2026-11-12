import { expect, test, type Page } from './fixtures/coverage';

/**
 * saiku#1917: the SPA shell ships an enforced Content-Security-Policy (SvelteKit hash mode, a
 * <meta http-equiv> in index.html). This spec loads the built app in a real browser against a
 * mocked backend and fails on any CSP violation, which is how a too-strict directive (monaco
 * blob: workers, ECharts, inline styles, the SvelteKit bootstrap script) shows up.
 */

const SESSION_BODY = JSON.stringify({
	username: 'admin',
	roles: ['ROLE_ADMIN', 'ROLE_USER'],
	sessionid: 'e2e-session-csp',
	language: 'en',
	isadmin: true
});

async function mockBackend(page: Page) {
	await page.route('**/rest/**', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
	});
	await page.route('**/rest/saiku/session', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: SESSION_BODY });
	});
	await page.route('**/rest/saiku/api/repository**', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
	});
}

test.describe('enforced CSP (mocked backend)', () => {
	for (const path of ['/', '/workbench', '/sql-workbench', '/apps']) {
		test(`${path} loads with no CSP violations`, async ({ page }) => {
			const cspMessages: string[] = [];
			page.on('console', (msg) => {
				if (
					/content security policy|refused to (load|execute|apply|create|connect)/i.test(msg.text())
				) {
					cspMessages.push(msg.text());
				}
			});
			await page.addInitScript(() => {
				(window as unknown as { __csp: string[] }).__csp = [];
				document.addEventListener('securitypolicyviolation', (e) => {
					(window as unknown as { __csp: string[] }).__csp.push(
						`${e.violatedDirective} blocked ${e.blockedURI}`
					);
				});
			});
			await mockBackend(page);

			const response = await page.goto(path);
			await page.waitForLoadState('networkidle');

			// The shipped index.html carries the policy as a <meta>; `vite preview` server-renders
			// routes that are not prerendered and sends it as a header instead. Either way it is enforced.
			const meta = await page
				.locator('meta[http-equiv="content-security-policy"]')
				.getAttribute('content', { timeout: 1000 })
				.catch(() => null);
			const policy = meta ?? response?.headers()['content-security-policy'] ?? '';
			expect(policy, 'the SPA must be served with an enforced CSP').toContain("script-src 'self'");
			expect(policy).not.toMatch(/script-src[^;]*unsafe-(inline|eval)/);

			const violations = await page.evaluate(
				() => (window as unknown as { __csp: string[] }).__csp
			);
			expect(violations).toEqual([]);
			expect(cspMessages).toEqual([]);
		});
	}
});
