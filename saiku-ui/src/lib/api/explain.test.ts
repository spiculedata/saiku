/*
 * Unit tests for the explainCell client. fetch is stubbed per test; no network.
 */

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { ExplainError, explainCell } from './explain';

const REQ = { queryName: 'q1', position: { row: 2, column: 0 } };

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'Content-Type': 'application/json' }
	});
}

describe('explainCell', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
		vi.restoreAllMocks();
	});

	test('posts the query name and the cell coordinates', async () => {
		const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ value: 10, drivers: [] }));
		globalThis.fetch = fetchMock;

		await explainCell(REQ);

		expect(fetchMock).toHaveBeenCalledWith(
			'/rest/saiku/api/ai/explain',
			expect.objectContaining({ method: 'POST', credentials: 'include' })
		);
		const body = JSON.parse(fetchMock.mock.calls[0][1].body as string);
		expect(body).toEqual({ queryName: 'q1', position: { row: 2, column: 0 } });
	});

	test('forwards the include* switches when set', async () => {
		const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ value: 10, drivers: [] }));
		globalThis.fetch = fetchMock;

		await explainCell({ ...REQ, includeDrivers: false, includeSql: false });

		const body = JSON.parse(fetchMock.mock.calls[0][1].body as string);
		expect(body.includeDrivers).toBe(false);
		expect(body.includeSql).toBe(false);
		expect(body.includeNarrative).toBeUndefined();
	});

	test('returns the panel payload as-is', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(
			jsonResponse({
				measure: 'Store Sales',
				rowPath: 'USA',
				value: 26507.17,
				formatted: '$26,507.17',
				cellMdx: 'SELECT {[Measures].[Store Sales]} ON 0 FROM [Sales]',
				drivers: [{ kind: 'SHARE_OF_COLUMN', caption: 'USA', detail: '', share: 0.5 }],
				narrative: 'Store Sales for USA is $26,507.17.',
				narrativeSource: 'COMPUTED',
				notes: ['SQL was not captured on this backend']
			})
		);

		const out = await explainCell(REQ);

		expect(out.formatted).toBe('$26,507.17');
		expect(out.narrativeSource).toBe('COMPUTED');
		expect(out.drivers).toHaveLength(1);
		expect(out.notes?.[0]).toContain('SQL');
	});

	test('a 404 raises an ExplainError carrying the server code and message', async () => {
		globalThis.fetch = vi
			.fn()
			.mockResolvedValue(
				jsonResponse({ status: 'ERROR', error: "no query named 'q1'", code: 'UNKNOWN_QUERY' }, 404)
			);

		await expect(explainCell(REQ)).rejects.toMatchObject({
			name: 'ExplainError',
			status: 404,
			code: 'UNKNOWN_QUERY',
			message: "no query named 'q1'"
		});
	});

	test('a 409 says the query has not been executed', async () => {
		globalThis.fetch = vi
			.fn()
			.mockResolvedValue(
				jsonResponse({ status: 'ERROR', error: 'run it first', code: 'NOT_EXECUTED' }, 409)
			);

		const err = await explainCell(REQ).catch((e: ExplainError) => e);
		expect(err).toBeInstanceOf(ExplainError);
		expect((err as ExplainError).code).toBe('NOT_EXECUTED');
	});

	test('a non-JSON error body still throws with the status', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 }));

		await expect(explainCell(REQ)).rejects.toMatchObject({ status: 502, code: 'HTTP_ERROR' });
	});

	test('a transport failure is an ExplainError with status 0', async () => {
		globalThis.fetch = vi.fn().mockRejectedValue(new Error('offline'));

		await expect(explainCell(REQ)).rejects.toMatchObject({
			name: 'ExplainError',
			status: 0,
			code: 'NETWORK',
			message: 'offline'
		});
	});
});
