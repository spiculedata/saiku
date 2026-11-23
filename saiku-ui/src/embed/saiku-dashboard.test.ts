/*
 * Smoke coverage for the <saiku-dashboard/> custom element (issue #1103 —
 * the <saiku-embed> split):
 *   - Importing the bundle entry registers the tag once globally.
 *   - With server + path attributes, it fires the documented dashboard
 *     fetch URL, with the token in the dedicated header.
 *   - An empty-tile layout renders without error (grid dispatch itself is
 *     covered by EmbedGrid's own tests).
 *   - A fetch failure surfaces the same friendly message <saiku-embed
 *     kind="dashboard"> does.
 */
// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

interface FetchCall {
	url: string;
	init?: RequestInit;
}

describe('<saiku-dashboard/>', () => {
	let calls: FetchCall[];
	let nextResp: { status: number; body: unknown };

	beforeEach(async () => {
		calls = [];
		nextResp = {
			status: 200,
			body: { id: 'd1', name: 'Exec', version: 1, layout: { cols: 12, tiles: [] } }
		};
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
		await import('./saiku-dashboard');
	}, 30_000);

	afterEach(() => {
		vi.unstubAllGlobals();
		document.body.innerHTML = '';
	});

	it('registers the custom element on import', () => {
		expect(customElements.get('saiku-dashboard')).toBeDefined();
	});

	it('fires the documented dashboard URL when server + path are set', async () => {
		const el = document.createElement('saiku-dashboard');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/exec.saikudash');
		el.setAttribute('token', 'tok-dash');
		document.body.appendChild(el);
		await flush();

		expect(calls.length).toBeGreaterThanOrEqual(1);
		const last = calls[calls.length - 1];
		expect(last.url).toBe(
			'https://demo.saiku.bi/rest/saiku/api/embed/dashboard/homes/admin/exec.saikudash'
		);
		expect(last.init?.credentials).toBe('omit');
		const headers = (last.init?.headers ?? {}) as Record<string, string>;
		expect(headers['X-Saiku-Embed-Token']).toBe('tok-dash');
	});

	it('omits the token header for anonymous (public) reads', async () => {
		const el = document.createElement('saiku-dashboard');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'shared/public.saikudash');
		document.body.appendChild(el);
		await flush();

		const last = calls[calls.length - 1];
		const headers = (last.init?.headers ?? {}) as Record<string, string>;
		expect(headers['X-Saiku-Embed-Token']).toBeUndefined();
	});

	it('does not fetch until both server and path are set', async () => {
		const el = document.createElement('saiku-dashboard');
		el.setAttribute('server', 'https://demo.saiku.bi');
		document.body.appendChild(el);
		await flush();
		expect(calls).toHaveLength(0);

		el.setAttribute('path', 'homes/admin/exec.saikudash');
		await flush();
		expect(calls).toHaveLength(1);
	});

	it('surfaces a friendly message instead of the raw 401 body', async () => {
		nextResp = {
			status: 401,
			body: { status: 'EMBED_INVALID', error: 'Embed token is invalid or expired.' }
		};
		const el = document.createElement('saiku-dashboard');
		el.setAttribute('server', 'https://demo.saiku.bi');
		el.setAttribute('path', 'homes/admin/exec.saikudash');
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
