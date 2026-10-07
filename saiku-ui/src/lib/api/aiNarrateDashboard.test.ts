/*
 * Unit tests for aiNarrateDashboard.ts. No network — global fetch is stubbed
 * per test. Mirrors aiDashboard.test.ts's setup.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
	narrateDashboard,
	type NarrateDashboardRequest,
	type NarrateDashboardResponse
} from './aiNarrateDashboard';
import { AiAskTransportError } from './aiAsk';

const baseReq: NarrateDashboardRequest = {
	dashboardTitle: 'Sales Overview',
	tiles: [
		{
			title: 'Sales by Region',
			query: { cube: { cubeName: 'Sales' }, measures: [{ name: 'Store Sales' }] }
		}
	]
};

describe('narrateDashboard', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
		vi.restoreAllMocks();
	});

	function mockJson(status: number, body: unknown) {
		globalThis.fetch = vi.fn().mockResolvedValue(
			new Response(JSON.stringify(body), {
				status,
				headers: { 'Content-Type': 'application/json' }
			})
		);
	}

	test('returns the parsed narrative on a 200 happy path', async () => {
		const body: NarrateDashboardResponse = {
			degraded: false,
			model: 'claude-x',
			narrative: 'Sales are up 15% year over year, led by the West region.'
		};
		mockJson(200, body);

		const out = await narrateDashboard(baseReq);

		expect(out.degraded).toBe(false);
		expect(out.model).toBe('claude-x');
		expect(out.narrative).toContain('Sales are up 15%');
	});

	test('returns the fixed no-data narrative without treating it as degraded', async () => {
		mockJson(200, { degraded: false, narrative: 'No data to summarise.' });

		const out = await narrateDashboard({ tiles: [] });

		expect(out.degraded).toBe(false);
		expect(out.narrative).toBe('No data to summarise.');
	});

	test('returns a degraded:true 200 envelope as-is (does not throw)', async () => {
		mockJson(200, { degraded: true, reason: 'provider did not return a narrative' });

		const out = await narrateDashboard(baseReq);

		expect(out.degraded).toBe(true);
		expect(out.reason).toContain('provider did not return a narrative');
		expect(out.narrative).toBeUndefined();
	});

	test('returns a 429 rate-limit degrade envelope as-is (does not throw)', async () => {
		mockJson(429, {
			degraded: true,
			reason: 'Too many AI narrative requests — limit is 5 per 60s.'
		});

		const out = await narrateDashboard(baseReq);

		expect(out.degraded).toBe(true);
		expect(out.reason).toContain('Too many AI narrative requests');
	});

	test('returns a 503 not-configured degrade envelope as-is (does not throw)', async () => {
		mockJson(503, {
			degraded: true,
			reason: 'AI ask is not configured. Set saiku.ai.ask.provider...'
		});

		const out = await narrateDashboard(baseReq);

		expect(out.degraded).toBe(true);
		expect(out.reason).toContain('not configured');
	});

	test('throws AiAskTransportError on a 500 that is not a narrative envelope', async () => {
		mockJson(500, { error: 'boom' });
		await expect(narrateDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
	});

	test('throws AiAskTransportError on an empty body', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('', { status: 500 }));
		await expect(narrateDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
	});

	test('throws AiAskTransportError on a non-JSON body', async () => {
		globalThis.fetch = vi
			.fn()
			.mockResolvedValue(new Response('<html>oops</html>', { status: 500 }));
		await expect(narrateDashboard(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
	});

	test('throws AiAskTransportError with status 0 on a network failure', async () => {
		globalThis.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
		try {
			await narrateDashboard(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(0);
		}
	});

	test('rethrows an AbortError UNWRAPPED (not an AiAskTransportError) so a cancel reads as a cancel', async () => {
		globalThis.fetch = vi
			.fn()
			.mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' }));
		try {
			await narrateDashboard(baseReq, new AbortController().signal);
			expect.fail('should have thrown');
		} catch (e) {
			expect((e as Error).name).toBe('AbortError');
			expect(e).not.toBeInstanceOf(AiAskTransportError);
		}
	});

	test('posts to the narrate-dashboard endpoint with credentials + JSON + the request body', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValue(
				new Response(JSON.stringify({ degraded: false, narrative: 'x' }), { status: 200 })
			);
		globalThis.fetch = fetchMock;

		await narrateDashboard(baseReq);

		const [url, init] = fetchMock.mock.calls[0];
		expect(url).toBe('/rest/saiku/api/ai/narrate-dashboard');
		expect(init.method).toBe('POST');
		expect(init.credentials).toBe('include');
		expect(init.headers['Content-Type']).toBe('application/json');
		const parsed = JSON.parse(init.body as string);
		expect(parsed.dashboardTitle).toBe('Sales Overview');
		expect(parsed.tiles).toHaveLength(1);
	});

	test('forwards an AbortSignal to fetch', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValue(
				new Response(JSON.stringify({ degraded: false, narrative: 'x' }), { status: 200 })
			);
		globalThis.fetch = fetchMock;
		const controller = new AbortController();

		await narrateDashboard(baseReq, controller.signal);

		const [, init] = fetchMock.mock.calls[0];
		expect(init.signal).toBe(controller.signal);
	});
});
