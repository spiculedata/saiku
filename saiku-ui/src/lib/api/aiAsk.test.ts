/*
 * Unit tests for aiAsk.ts. No network — global fetch is stubbed per test.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { askAi, AiAskTransportError, type AskRequest, type AskResponse } from './aiAsk';

const baseReq: AskRequest = {
	question: 'show sales by country',
	cube: {
		connectionName: 'foodmart',
		catalog: 'FoodMart',
		schema: 'FoodMart',
		cubeName: 'Sales'
	}
};

describe('aiAsk', () => {
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

	test('returns parsed AskResponse on 200 happy path', async () => {
		const body: AskResponse = {
			degraded: false,
			model: 'claude-x',
			request: { cube: { cubeName: 'Sales' }, measures: [{ name: 'Store Sales' }] },
			response: {
				queryId: 'q1',
				status: 'SUCCESS',
				metadata: { rows: [], columns: [], measures: [], generatedMdx: 'SELECT ... FROM [Sales]' }
			},
			generatedMdx: 'SELECT ... FROM [Sales]'
		};
		mockJson(200, body);

		const out = await askAi(baseReq);

		expect(out.degraded).toBe(false);
		expect(out.model).toBe('claude-x');
		expect(out.generatedMdx).toBe('SELECT ... FROM [Sales]');
		expect(out.response?.status).toBe('SUCCESS');
	});

	test('returns a genuine 2xx zero-row result envelope (a real empty result is NOT an error)', async () => {
		// A legitimately empty cellset is different from a 403 — this must still come
		// back as a SUCCESS envelope so the drawer can honestly say "0 rows returned."
		const body: AskResponse = {
			degraded: false,
			model: 'claude-x',
			request: { cube: { cubeName: 'Sales' }, measures: [{ name: 'Store Sales' }] },
			response: {
				queryId: 'q0',
				status: 'SUCCESS',
				totalRows: 0,
				data: [],
				metadata: { rows: [], columns: [], measures: [], generatedMdx: 'SELECT ...' }
			},
			generatedMdx: 'SELECT ...'
		};
		mockJson(200, body);

		const out = await askAi(baseReq);

		expect(out.degraded).toBe(false);
		expect(out.response?.status).toBe('SUCCESS');
		expect(out.response?.totalRows).toBe(0);
	});

	test('returns degraded AskResponse on 503 not-configured', async () => {
		const body: AskResponse = {
			degraded: true,
			reason: 'AI ask is not configured. Set saiku.ai.ask.provider...'
		};
		mockJson(503, body);

		const out = await askAi(baseReq);

		expect(out.degraded).toBe(true);
		expect(out.reason).toContain('not configured');
		expect(out.response).toBeUndefined();
	});

	test('returns VALIDATION_ERROR envelope when model emitted bad request', async () => {
		const body: AskResponse = {
			degraded: false,
			model: 'claude-x',
			request: { cube: { cubeName: 'Sales' }, measures: [{ name: 'Bogus' }] },
			response: {
				queryId: 'q2',
				status: 'VALIDATION_ERROR',
				error: 'measure not found',
				field: 'measures[0].name',
				available: ['Store Sales', 'Unit Sales']
			}
		};
		mockJson(200, body);

		const out = await askAi(baseReq);

		expect(out.degraded).toBe(false);
		expect(out.response?.status).toBe('VALIDATION_ERROR');
		expect(out.response?.available).toEqual(['Store Sales', 'Unit Sales']);
	});

	// Regression (saiku#1811): a 403 from the ask endpoint used to be returned as a
	// zero-row AskResponse envelope, and the drawer rendered it as "0 rows returned."
	// A 403 with an error body that ISN'T a degraded envelope MUST throw, carrying
	// the status, so the drawer can surface an auth/session error instead.
	test('throws AiAskTransportError with status 403 on forbidden (not a zero-row success)', async () => {
		mockJson(403, { reason: 'Session expired — log in to continue' });
		try {
			await askAi(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(403);
			expect((e as AiAskTransportError).message).toContain('Session expired');
		}
	});

	test('throws AiAskTransportError with status 401 on unauthorized', async () => {
		mockJson(401, { message: 'Not authorized' });
		try {
			await askAi(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(401);
		}
	});

	test('throws AiAskTransportError with status 500 even when body parses as JSON', async () => {
		// A 5xx JSON body that isn't a degraded envelope is a server error, not data.
		mockJson(500, { some: 'error object', totalRows: 0 });
		try {
			await askAi(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(500);
		}
	});

	test('throws AiAskTransportError on empty body', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('', { status: 500 }));
		await expect(askAi(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
	});

	test('throws AiAskTransportError on non-JSON body', async () => {
		globalThis.fetch = vi
			.fn()
			.mockResolvedValue(new Response('<html>oops</html>', { status: 500 }));
		await expect(askAi(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
	});

	test('throws AiAskTransportError on network failure', async () => {
		globalThis.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
		try {
			await askAi(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(0);
		}
	});

	test('posts body verbatim with credentials + JSON content-type', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValue(new Response(JSON.stringify({ degraded: false }), { status: 200 }));
		globalThis.fetch = fetchMock;

		await askAi({
			...baseReq,
			history: [
				{ role: 'user', content: 'sales by country' },
				{ role: 'assistant', content: '{...}' }
			]
		});

		const [url, init] = fetchMock.mock.calls[0];
		expect(url).toBe('/rest/saiku/api/ai/ask');
		expect(init.method).toBe('POST');
		expect(init.credentials).toBe('include');
		expect(init.headers['Content-Type']).toBe('application/json');
		expect(JSON.parse(init.body as string).question).toBe('show sales by country');
		expect(JSON.parse(init.body as string).history).toHaveLength(2);
	});
});
