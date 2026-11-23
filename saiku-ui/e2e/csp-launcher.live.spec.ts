import { expect, test } from '@playwright/test';

/**
 * saiku#2430: the CSP e2e in `csp-clean-console.spec.ts` runs against `vite preview` with a
 * mocked backend, where the policy arrives as a header. This is the shipped path: the policy is
 * the `<meta http-equiv>` baked into the static `index.html`, served by the Java launcher under
 * `/ui/`, and deep links reach it through `SpaFallbackFilter`. Gated on `RUN_LIVE_E2E=1`.
 *
 *   mvn -pl saiku-launcher -am -Dmaven.test.skip=true package
 *   SAIKU_DEMO=true java -jar saiku-launcher/target/saiku-*.jar serve --port 8080 --home <tmp>
 *   RUN_LIVE_E2E=1 npx playwright test csp-launcher.live.spec.ts
 *
 * Only violations with `disposition === 'enforce'` fail the test: a report-only policy (an
 * operator's `saiku.security.cspReportOnly`) is observed, not blocking, and must not turn this red.
 */

const BASE = process.env.LIVE_UI_BASE ?? '/ui';
const LIVE_USER = process.env.LIVE_ADMIN_USER ?? 'admin';
const LIVE_PASS = process.env.LIVE_ADMIN_PASS ?? 'admin';

// `/ui/` is the shell itself; the others are SvelteKit routes with no file on disk, so Jetty
// 404s them and SpaFallbackFilter forwards to index.html (a deep link / hard refresh).
const ROUTES = [
	`${BASE}/`,
	`${BASE}/workbench`,
	`${BASE}/dashboards/homes/admin/deep-link.saikudash`
];

type Violation = { directive: string; blocked: string; disposition: string };

for (const route of ROUTES) {
	test(`${route} loads from the launcher with no enforced CSP violations`, async ({ page }) => {
		const consoleCsp: string[] = [];
		page.on('console', (msg) => {
			// Chrome words every CSP block as "... violates the following Content Security Policy
			// directive"; a bare "Refused to apply style" is a MIME error, not a policy block.
			if (/content security policy/i.test(msg.text())) {
				consoleCsp.push(msg.text());
			}
		});
		await page.addInitScript(() => {
			const w = window as unknown as { __csp: Violation[] };
			w.__csp = [];
			document.addEventListener('securitypolicyviolation', (e) => {
				w.__csp.push({
					directive: e.violatedDirective,
					blocked: e.blockedURI,
					disposition: e.disposition
				});
			});
			window.localStorage.setItem('saiku.tour.done', '1');
		});

		const response = await page.goto(route);
		await page.waitForLoadState('networkidle');
		expect(response?.status(), 'a deep link must be served by the SPA fallback, not 404').toBe(200);

		// The shipped policy is the <meta> in index.html, not a header.
		const meta = await page
			.locator('meta[http-equiv="content-security-policy"]')
			.getAttribute('content');
		expect(meta, 'index.html must carry the policy as a <meta>').toContain("script-src 'self'");
		expect(meta).toContain("form-action 'self'");
		expect(meta).not.toMatch(/script-src[^;]*unsafe-(inline|eval)/);
		// The shell must not get a header script-src: it would intersect with the meta and
		// block the hash-pinned bootstrap.
		const header = response?.headers()['content-security-policy'] ?? '';
		expect(header).not.toContain('script-src');

		// The app actually booted (the bootstrap ran under the policy): the login form or the shell.
		await expect(page.getByTestId('login-submit').or(page.getByRole('main')).first()).toBeVisible({
			timeout: 15_000
		});
		if (await page.getByLabel(/username/i).count()) {
			await page.getByLabel(/username/i).fill(LIVE_USER);
			await page.getByLabel(/password/i).fill(LIVE_PASS);
			await page.getByRole('button', { name: 'Sign in', exact: true }).click();
			await page.waitForLoadState('networkidle');
		}

		const violations = await page.evaluate(
			() => (window as unknown as { __csp: Violation[] }).__csp
		);
		const enforced = violations.filter((v) => v.disposition === 'enforce');
		expect(enforced).toEqual([]);
		expect(consoleCsp.filter((m) => !/report.only/i.test(m))).toEqual([]);
	});
}
