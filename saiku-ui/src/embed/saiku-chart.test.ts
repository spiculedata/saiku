/*
 * Smoke coverage for the <saiku-chart/> custom element (issue #1103 —
 * the <saiku-embed> split). Mirrors saiku-embed.test.ts's wire-surface
 * checks scoped to the chart-only element:
 *   - Importing the bundle entry registers the tag once globally.
 *   - With server + path attributes, it fires the documented fetch URL.
 *   - With server + path + token, it sends the token header.
 *   - saiku:load / saiku:error fire the same as <saiku-embed>'s chart path.
 *
 * Chart rendering itself (series mapping, theme CSS-var reads) is already
 * covered by embedChartOption.test.ts / embedChartTheme.test.ts — here we
 * only verify the element's own wire + lifecycle surface.
 */
// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

interface FetchCall {
	url: string;
	init?: RequestInit;
}

describe('<saiku-chart/>', () => {
	let calls: FetchCall[];
	let nextResp: { status: number; body: unknown };

	beforeEach(async () => {
		calls = [];
		nextResp = { status: 200, body: { format: 'records', data: [] } };
		vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
			calls.push({ url, init });
			return Promise.resolve(
				new Response(JSON.stringify(nextResp.body), {
					status: nextResp.status,
					headers: { 'Content-Type': 'application/json' }
				})
			);
		});
		// saiku#1668: dynamic bundle import can blow past vitest's default
		// hook timeout under load — see saiku-embed.test.ts for the full note.
		await import('./saiku-chart');
	}, 30_000);

	afterEach(() => {
		vi.unstubAllGlobals();
		document.body.innerHTML = '';
	});

	it('registers the custom element on import', () => {
		expect(customElements.get('saiku-chart')).toBeDefined();
	});

	it('fires the documented embed URL when server + path are set', async () => {
		const el = document.createElement('saiku-chart');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/sales.saiku');
		el.setAttribute('token', 'tok-abc');
		document.body.appendChild(el);
		await flush();

		expect(calls.length).toBeGreaterThanOrEqual(1);
		const last = calls[calls.length - 1];
		expect(last.url).toBe(
			'https://demo.saiku.bi/rest/saiku/api/embed/query/homes/admin/sales.saiku'
		);
		const headers = (last.init?.headers ?? {}) as Record<string, string>;
		expect(headers['X-Saiku-Embed-Token']).toBe('tok-abc');
	});

	it('omits the token header for anonymous (public) reads', async () => {
		const el = document.createElement('saiku-chart');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'shared/public.saiku');
		document.body.appendChild(el);
		await flush();

		const last = calls[calls.length - 1];
		const headers = (last.init?.headers ?? {}) as Record<string, string>;
		expect(headers['X-Saiku-Embed-Token']).toBeUndefined();
	});

	it('does not fetch until both server and path are set', async () => {
		const el = document.createElement('saiku-chart');
		el.setAttribute('server', 'https://demo.saiku.bi');
		document.body.appendChild(el);
		await flush();
		expect(calls).toHaveLength(0);

		el.setAttribute('path', 'homes/admin/x.saiku');
		await flush();
		expect(calls).toHaveLength(1);
	});

	it('POSTs a {filters} body when the filter attribute is set', async () => {
		const el = document.createElement('saiku-chart');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/sales.saiku');
		el.setAttribute(
			'filter',
			JSON.stringify([{ dimension: 'Time', level: 'Year', members: ['[Time].[2024]'] }])
		);
		document.body.appendChild(el);
		await flush();

		const last = calls[calls.length - 1];
		expect(last.init?.method).toBe('POST');
		expect(JSON.parse(String(last.init?.body))).toEqual({
			filters: [{ dimension: 'Time', level: 'Year', members: ['[Time].[2024]'] }]
		});
	});

	it('emits a saiku:load event with kind "chart" and the row count', async () => {
		nextResp = {
			status: 200,
			body: { format: 'records', data: [{ M: { value: 1, formatted: '1' } }] }
		};
		const el = document.createElement('saiku-chart');
		const events: CustomEvent[] = [];
		el.addEventListener('saiku:load', (e) => events.push(e as CustomEvent));
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/sales.saiku');
		document.body.appendChild(el);
		await waitFor(() => events.length > 0);

		expect(events[0].detail).toMatchObject({ kind: 'chart', rows: 1 });
	});

	it('emits a saiku:error event when a query load fails', async () => {
		nextResp = { status: 401, body: { status: 'EMBED_INVALID', error: 'nope' } };
		const el = document.createElement('saiku-chart');
		const events: CustomEvent[] = [];
		el.addEventListener('saiku:error', (e) => events.push(e as CustomEvent));
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/sales.saiku');
		document.body.appendChild(el);
		await waitFor(() => events.length > 0);

		expect((events[0].detail as { message: string }).message).toContain('unavailable');
	});

	it('surfaces a friendly message instead of the raw 401 body', async () => {
		nextResp = {
			status: 401,
			body: { status: 'EMBED_INVALID', error: 'Embed token is invalid or expired.' }
		};
		const el = document.createElement('saiku-chart');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/x.saiku');
		document.body.appendChild(el);

		const root = (el as unknown as { shadowRoot: ShadowRoot }).shadowRoot;
		await waitFor(() => roleText(root, 'alert') !== '');

		const text = roleText(root, 'alert');
		expect(text).toContain('unavailable');
		expect(text).not.toContain('EMBED_INVALID');
	});
});

async function flush(): Promise<void> {
	for (let i = 0; i < 6; i++) await Promise.resolve();
}

function roleText(root: ShadowRoot, role: string): string {
	const node = root.querySelector(`[role="${role}"]`);
	return (node?.textContent ?? '').trim();
}

async function waitFor(pred: () => boolean): Promise<void> {
	for (let i = 0; i < 50; i++) {
		if (pred()) return;
		await flush();
	}
}
