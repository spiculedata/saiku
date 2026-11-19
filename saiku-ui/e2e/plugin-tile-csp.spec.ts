import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from './fixtures/coverage';

/**
 * saiku#2440: a dashboard/app `plugin` tile must render under the SPA shell's ENFORCED
 * Content-Security-Policy (saiku#1917, `script-src 'self' 'sha256-...'`).
 *
 * The plugin frame used to be an `<iframe srcdoc>`. An about:srcdoc document inherits its
 * parent's policy, so the shell's strict script-src blocked both the host-injected nonce
 * script and the plugin's own inline script, and the tile stayed at "Waiting for data…".
 * The frame now loads the same-origin bootstrap page `plugin-frame.html`, which carries its
 * own (sandboxed) policy, and the host posts the plugin document into it.
 *
 * This mounts an app page with one plugin tile using the first-party `records-bars` seed
 * plugin (read straight from the launcher's seed so the test runs the real plugin), feeds it
 * three records through a mocked saved-query response, and asserts that the bars render and
 * that no frame logged an enforced CSP violation.
 */

const here = dirname(fileURLToPath(import.meta.url));
const RECORDS_BARS_HTML = readFileSync(
	join(here, '../../saiku-launcher/src/main/resources/seed/tile-plugins/records-bars/plugin.html'),
	'utf8'
);

const APP_PATH = 'homes/admin/plugin-csp.saikuapp';

/** Screenshots go to SAIKU_E2E_SHOTS when set, else the test output dir. */
function shot(name: string): string {
	return join(process.env.SAIKU_E2E_SHOTS ?? join(here, '../test-results'), name);
}

const SESSION_BODY = JSON.stringify({
	username: 'admin',
	roles: ['ROLE_ADMIN', 'ROLE_USER'],
	sessionid: 'e2e-session-plugin-csp',
	language: 'en',
	isadmin: true
});

const APP_DOC = JSON.stringify({
	id: '',
	name: 'Plugin CSP',
	version: 1,
	logo: null,
	theme: { mode: 'auto' },
	nav: { position: 'rail' },
	assistantSlot: { enabled: false },
	tags: [],
	pages: [
		{
			id: 'p1',
			title: 'Overview',
			grid: {
				cols: 12,
				tiles: [
					{
						id: 't-plugin',
						type: 'custom',
						title: 'Plugin tile',
						x: 0,
						y: 0,
						w: 6,
						h: 4,
						query: { kind: 'reference', path: 'homes/admin/sales.saiku' },
						custom: { renderer: 'plugin', options: { pluginId: 'records-bars' } }
					}
				]
			}
		}
	]
});

const RECORDS = [
	{ Store: 'Alpha', Sales: { value: 30, formatted: '30' } },
	{ Store: 'Bravo', Sales: { value: 20, formatted: '20' } },
	{ Store: 'Charlie', Sales: { value: 10, formatted: '10' } }
];

async function mockBackend(page: Page) {
	// Registered first so the specific routes below take precedence.
	await page.route('**/rest/**', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
	});
	await page.route('**/rest/saiku/session', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: SESSION_BODY });
	});
	await page.route('**/rest/saiku/api/repository**', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
	});
	await page.route('**/rest/saiku/api/apps/**', async (route) => {
		await route.fulfill({ status: 200, contentType: 'application/json', body: APP_DOC });
	});
	await page.route('**/rest/saiku/api/tile-plugins/records-bars/html', async (route) => {
		await route.fulfill({ status: 200, contentType: 'text/html', body: RECORDS_BARS_HTML });
	});
	await page.route('**/rest/saiku/api/ai/query/saved**', async (route) => {
		await route.fulfill({
			status: 200,
			contentType: 'application/json',
			body: JSON.stringify({
				queryId: 'q-plugin',
				status: 'SUCCESS',
				format: 'records',
				data: RECORDS
			})
		});
	});
}

test.describe('plugin tile under the enforced CSP (mocked backend)', () => {
	test('records-bars renders its bars with no enforced CSP violation (saiku#2440)', async ({
		page
	}) => {
		// page.on('console') sees every frame's console, including the sandboxed plugin
		// frame, which is where the srcdoc regression logged "Refused to execute inline script".
		// Report-only notices are not enforced and are not what this asserts.
		const cspMessages: string[] = [];
		page.on('console', (msg) => {
			const text = msg.text();
			if (/\[Report Only\]/i.test(text)) return;
			if (
				/content security policy|refused to (load|execute|apply|create|connect|frame)/i.test(text)
			) {
				cspMessages.push(text);
			}
		});
		const pageErrors: string[] = [];
		page.on('pageerror', (err) => pageErrors.push(err.message));
		await page.addInitScript(() => {
			(window as unknown as { __csp: string[] }).__csp = [];
			document.addEventListener('securitypolicyviolation', (e) => {
				if (e.disposition !== 'enforce') return;
				(window as unknown as { __csp: string[] }).__csp.push(
					`${e.violatedDirective} blocked ${e.blockedURI}`
				);
			});
		});
		await mockBackend(page);

		const response = await page.goto(`/apps/${APP_PATH}`);

		// The shell itself is still served under the strict policy: no 'unsafe-inline' scripts.
		// The shipped index.html carries it as a <meta>; `vite preview` server-renders this
		// (non-prerendered) route and sends it as a header instead. Either way it is enforced.
		const meta = await page
			.locator('meta[http-equiv="content-security-policy"]')
			.getAttribute('content', { timeout: 1000 })
			.catch(() => null);
		const shellPolicy = meta ?? response?.headers()['content-security-policy'] ?? '';
		expect(shellPolicy, 'the SPA shell must carry the enforced #1917 policy').toContain(
			"script-src 'self'"
		);
		expect(shellPolicy).not.toMatch(/script-src[^;]*unsafe-(inline|eval)/);

		const frameEl = page.locator('iframe[title="Plugin tile"]');
		await expect(frameEl).toHaveCount(1, { timeout: 20_000 });
		// The frame stays an opaque-origin sandbox: scripts only, never allow-same-origin.
		await expect(frameEl).toHaveAttribute('sandbox', 'allow-scripts');

		// The plugin received its records and drew one bar per record.
		const frame = page.frameLocator('iframe[title="Plugin tile"]');
		await expect(frame.locator('.row')).toHaveCount(RECORDS.length, { timeout: 20_000 });
		await expect(frame.locator('.row .label')).toHaveText(['Alpha', 'Bravo', 'Charlie']);
		await expect(frame.getByText('Waiting for data…')).toHaveCount(0);

		// The plugin runs at an opaque origin and cannot reach into the shell.
		const pluginFrame = page.frames().find((f) => f.url().endsWith('/plugin-frame.html'));
		expect(pluginFrame, 'the tile loads the same-origin bootstrap, not an srcdoc').toBeTruthy();
		const isolation = await pluginFrame!.evaluate(() => {
			let parentDom = 'readable';
			try {
				void window.parent.document.title;
			} catch {
				parentDom = 'blocked';
			}
			return { origin: window.origin, parentDom };
		});
		expect(isolation).toEqual({ origin: 'null', parentDom: 'blocked' });

		const violations = await page.evaluate(() => (window as unknown as { __csp: string[] }).__csp);
		expect(violations, 'no enforced CSP violation in the shell').toEqual([]);
		expect(cspMessages, 'no enforced CSP violation in any frame').toEqual([]);
		expect(pageErrors).toEqual([]);
	});

	test('the plugin frame document is served with its own sandboxed policy (saiku#2440)', async ({
		page
	}) => {
		await mockBackend(page);
		const frameResponse = page.waitForResponse((r) => /\/plugin-frame\.html$/.test(r.url()));
		await page.goto(`/apps/${APP_PATH}`);
		const res = await frameResponse;
		expect(res.status()).toBe(200);
		const csp = res.headers()['content-security-policy'] ?? '';
		expect(csp).toMatch(/(^|;)\s*sandbox allow-scripts\s*(;|$)/);
		expect(csp).not.toContain('allow-same-origin');
		expect(csp).toContain("default-src 'none'");
		expect(csp).toContain("script-src 'unsafe-inline'");
		expect(csp).toContain("connect-src 'none'");
		expect(csp).toContain("frame-ancestors 'self'");
	});

	test('the bootstrap ignores a plugin document when it is not framed by the Saiku page (saiku#2440)', async ({
		page
	}) => {
		const res = await page.goto('/plugin-frame.html');
		expect(res?.headers()['content-security-policy'] ?? '').toContain('sandbox allow-scripts');
		// Top-level: no parent to accept a document from, so a load message (here from itself)
		// is ignored and the page stays empty.
		await page.evaluate(() => {
			window.postMessage({ type: 'saiku-plugin-load', html: '<p id="injected">x</p>' }, '*');
		});
		await page.waitForTimeout(200);
		await expect(page.locator('#injected')).toHaveCount(0);
	});

	test('a second sandboxed frame at the bootstrap URL never receives the plugin (saiku#2440)', async ({
		page
	}) => {
		await mockBackend(page);
		await page.goto(`/apps/${APP_PATH}`);
		const tileFrame = page.frameLocator('iframe[title="Plugin tile"]');
		await expect(tileFrame.locator('.row')).toHaveCount(RECORDS.length, { timeout: 20_000 });

		// Count what the host sends the tile's frame from here on: a host that answered the
		// intruder's boot (no event.source guard) would re-send the plugin document to it.
		const tileWindow = (await page
			.locator('iframe[title="Plugin tile"]')
			.elementHandle())!.contentFrame();
		await (await tileWindow)!.evaluate(() => {
			(window as unknown as { __loads: number }).__loads = 0;
			window.addEventListener('message', (e) => {
				if ((e.data as { type?: string } | null)?.type === 'saiku-plugin-load') {
					(window as unknown as { __loads: number }).__loads++;
				}
			});
		});

		// An intruder frame boots exactly like the tile's and asks the page for a document. The
		// host answers only its own tile's frame (event.source guard), so it stays empty.
		await page.evaluate(() => {
			const intruder = document.createElement('iframe');
			intruder.id = 'intruder';
			intruder.setAttribute('sandbox', 'allow-scripts');
			intruder.src = document.querySelector<HTMLIFrameElement>('iframe[title="Plugin tile"]')!.src;
			document.body.appendChild(intruder);
		});
		const intruder = page.frameLocator('#intruder');
		const intruderFrame = (await page.locator('#intruder').elementHandle())!.contentFrame();
		await expect
			.poll(async () => (await intruderFrame)?.evaluate(() => document.title).catch(() => ''))
			.toBe('Saiku plugin');
		await page.waitForTimeout(500);
		await expect(intruder.locator('#root')).toHaveCount(0);
		await expect(intruder.locator('.row')).toHaveCount(0);
		expect(
			await (await tileWindow)!.evaluate(() => (window as unknown as { __loads: number }).__loads),
			"the intruder's boot must not make the host deliver anything"
		).toBe(0);
		await expect(tileFrame.locator('.row')).toHaveCount(RECORDS.length);
		await page.screenshot({ path: shot('after-e2e-intruder-frame.png') });
	});

	test('the host never hands the plugin to a frame that is not sandboxed (saiku#2440)', async ({
		page
	}) => {
		await mockBackend(page);
		// Strip the response's sandbox directive; the iframe attribute is removed below. With both
		// layers gone the bootstrap runs at the Saiku origin and its boot message says so.
		await page.route('**/plugin-frame.html', async (route) => {
			const res = await route.fetch();
			const headers = { ...res.headers() };
			headers['content-security-policy'] = (headers['content-security-policy'] ?? '').replace(
				/sandbox allow-scripts;\s*/,
				''
			);
			await route.fulfill({ response: res, headers });
		});
		await page.goto(`/apps/${APP_PATH}`);
		const tileFrame = page.frameLocator('iframe[title="Plugin tile"]');
		// Positive control: the attribute alone still sandboxes it, so the plugin is delivered.
		await expect(tileFrame.locator('.row')).toHaveCount(RECORDS.length, { timeout: 20_000 });

		await page.evaluate(() => {
			const frame = document.querySelector<HTMLIFrameElement>('iframe[title="Plugin tile"]')!;
			frame.removeAttribute('sandbox');
			frame.src = String(frame.src); // re-navigate: sandbox flags apply per navigation
		});
		const tileEl = page.locator('iframe[title="Plugin tile"]');
		// The re-navigated bootstrap runs at the Saiku origin (not "null") and has booted.
		await expect
			.poll(async () =>
				(await (await tileEl.elementHandle())?.contentFrame())
					?.evaluate(() => `${window.origin}|${document.title}`)
					.catch(() => '')
			)
			.toMatch(/^http.*\|Saiku plugin$/);
		await page.waitForTimeout(500);
		await expect(tileFrame.locator('#root')).toHaveCount(0);
		await expect(tileFrame.locator('.row')).toHaveCount(0);
		await page.screenshot({ path: shot('after-e2e-unsandboxed-frame-empty.png') });
	});

	test("the shell's default-src 'self' keeps foreign documents out of the tile frame (saiku#2440)", async ({
		page
	}) => {
		// The boot handshake is unauthenticated by design: any document in the tile frame at an
		// opaque origin may ask for the plugin and its nonce. What keeps a foreign document out of
		// that frame is the shell's enforced default-src 'self' (frame-src fallback). If this test
		// fails, the shell policy was widened: revisit routeFrameMessage before shipping that.
		await page.addInitScript(() => {
			(window as unknown as { __csp: string[] }).__csp = [];
			document.addEventListener('securitypolicyviolation', (e) => {
				if (e.disposition !== 'enforce') return;
				(window as unknown as { __csp: string[] }).__csp.push(e.effectiveDirective);
			});
		});
		await mockBackend(page);
		await page.goto(`/apps/${APP_PATH}`);
		const tileFrame = page.frameLocator('iframe[title="Plugin tile"]');
		await expect(tileFrame.locator('.row')).toHaveCount(RECORDS.length, { timeout: 20_000 });

		const here = new URL(page.url());
		const foreignOrigin = `${here.protocol}//${here.hostname === 'localhost' ? '127.0.0.1' : 'localhost'}:${here.port}`;
		for (const target of [
			`${foreignOrigin}/plugin-frame.html`,
			'data:text/html,<p id=foreign>x</p>'
		]) {
			await page.evaluate((url) => {
				document.querySelector<HTMLIFrameElement>('iframe[title="Plugin tile"]')!.src = url;
			}, target);
			await page.waitForTimeout(500);
			const urls = page.frames().map((f) => f.url());
			expect(urls.some((u) => u.startsWith(foreignOrigin) || u.startsWith('data:'))).toBe(false);
		}
		const violations = await page.evaluate(() => (window as unknown as { __csp: string[] }).__csp);
		expect(violations.filter((d) => d === 'frame-src').length).toBeGreaterThanOrEqual(2);
		await page.screenshot({ path: shot('after-e2e-foreign-navigation-refused.png') });
	});
});
