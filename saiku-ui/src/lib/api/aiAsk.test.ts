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
