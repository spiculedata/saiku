/*
 * saiku#1865 — /query/execute now classifies failures as 400 or 500 instead of dressing every
 * one as 200. The BODY shape is deliberately unchanged, so the client must keep turning a
 * failure envelope into a QueryResult the grid can render inline, rather than throwing a raw
 * status string at the user.
 */
import { describe, expect, it, afterEach, vi } from 'vitest';
import { executeQuery } from './query';
import type { ThinQuery } from './query';

const QUERY = { name: 'q', type: 'MDX', mdx: 'SELECT FROM [Sales]' } as unknown as ThinQuery;

function respondWith(body: string, status: number, contentType = 'application/json') {
	vi.stubGlobal(
		'fetch',
		vi.fn(async () => new Response(body, { status, headers: { 'Content-Type': contentType } }))
	);
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('executeQuery error handling', () => {
	it('returns the envelope for a 400 so the grid can render the message inline', async () => {
		respondWith(
			JSON.stringify({ cellset: null, error: 'Unknown connection ( nope ). Available: a, b' }),
			400
		);

		const result = await executeQuery(QUERY);

		expect(result.error).toContain('Unknown connection');
	});

	it('returns the envelope for a 500 too — the user still needs the message', async () => {
		respondWith(JSON.stringify({ cellset: null, error: 'Connection refused' }), 500);

		const result = await executeQuery(QUERY);

		expect(result.error).toBe('Connection refused');
	});

	// A proxy's HTML error page or an auth redirect carries nothing worth putting in the grid.
	it('throws when the failure body is not a query envelope', async () => {
		respondWith('<html><body>502 Bad Gateway</body></html>', 502, 'text/html');

		await expect(executeQuery(QUERY)).rejects.toThrow(/execute 502/);
	});

	it('throws on an empty failure body', async () => {
		respondWith('', 503);

		await expect(executeQuery(QUERY)).rejects.toThrow(/execute 503/);
	});

	it('throws when the envelope parses but carries no error message', async () => {
		respondWith(JSON.stringify({ cellset: null, error: null }), 500);

		await expect(executeQuery(QUERY)).rejects.toThrow(/execute 500/);
	});
});
