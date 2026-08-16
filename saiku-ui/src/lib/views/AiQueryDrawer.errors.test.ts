/**
 * Regression cover (saiku#1811): the Ask-AI drawer rendered a failed/forbidden
 * ask as "0 rows returned." A 403 from /ai/ask (e.g. "Session expired — log in
 * to continue") reached the SUCCESS render path where
 *   rowCount = ai?.totalRows ?? ai?.data?.length ?? ai?.matrix?.length ?? 0
 * defaulted to 0 — surfacing an auth failure as an empty data result.
 *
 * The fix has two halves, asserted here:
 *
 *  1. Client (aiAsk.ts): a non-2xx that isn't a `degraded:true` envelope THROWS
 *     AiAskTransportError carrying the HTTP status, instead of being returned as
 *     a zero-row envelope. (Behavioural — driven through a mocked fetch.)
 *
 *  2. Drawer (AiQueryDrawer.svelte): the throw is caught and mapped to a
 *     user-facing error via askErrorText(), which surfaces an auth/session
 *     message for 401/403. The "{rows} rows returned." resultSummary is only
 *     reachable AFTER a successful askAi() (i.e. below the try/catch), so a 403
 *     can never render it. (Asserted against the component source — the repo has
 *     no client-mount harness for Svelte event handlers; see SaveQueryModal.test.)
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { askAi, AiAskTransportError, type AskRequest } from '$lib/api/aiAsk';

const SOURCE = readFileSync(
	fileURLToPath(new URL('./AiQueryDrawer.svelte', import.meta.url)),
	'utf8'
);

const baseReq: AskRequest = {
	question: 'show sales by country',
	cube: {
		connectionName: 'foodmart',
		catalog: 'FoodMart',
		schema: 'FoodMart',
		cubeName: 'Sales'
	}
};

describe('AiQueryDrawer — auth/permission errors are not "0 rows returned" (saiku#1811)', () => {
	let originalFetch: typeof globalThis.fetch;
	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
		vi.restoreAllMocks();
	});

	test('a 403 ask throws (so the drawer catch runs) — it never returns a zero-row envelope', async () => {
		// Fresh Response per call — a Response body can only be read once.
		globalThis.fetch = vi.fn().mockImplementation(
			async () =>
				new Response(JSON.stringify({ reason: 'Session expired — log in to continue' }), {
					status: 403,
					headers: { 'Content-Type': 'application/json' }
				})
		);
		await expect(askAi(baseReq)).rejects.toBeInstanceOf(AiAskTransportError);
		try {
			await askAi(baseReq);
			expect.fail('should have thrown');
		} catch (e) {
			expect(e).toBeInstanceOf(AiAskTransportError);
			expect((e as AiAskTransportError).status).toBe(403);
		}
	});

	test('a genuine 2xx zero-row result is NOT an error — it still returns a SUCCESS envelope', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(
			new Response(
				JSON.stringify({
					degraded: false,
					response: { queryId: 'q', status: 'SUCCESS', totalRows: 0, data: [] }
				}),
				{ status: 200, headers: { 'Content-Type': 'application/json' } }
			)
		);
		const out = await askAi(baseReq);
		expect(out.degraded).toBe(false);
		expect(out.response?.status).toBe('SUCCESS');
		expect(out.response?.totalRows).toBe(0);
	});

	test('a 503 not-configured envelope still comes back as a degraded AskResponse (amber banner path)', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(
			new Response(JSON.stringify({ degraded: true, reason: 'AI ask is not configured.' }), {
				status: 503,
				headers: { 'Content-Type': 'application/json' }
			})
		);
		const out = await askAi(baseReq);
		expect(out.degraded).toBe(true);
		expect(out.reason).toContain('not configured');
	});

	test('drawer maps 401/403 to an auth/session error message, not the rows summary', () => {
		// askErrorText() special-cases 401/403 to the auth string.
		expect(SOURCE).toMatch(/e\.status === 401 \|\| e\.status === 403/);
		expect(SOURCE).toContain("i18n.t('workspace.aiQuery.authError')");
	});

	test('drawer catch renders the error via askErrorText (the throw is not swallowed)', () => {
		// Both ask paths (single + chained) route their catch through askErrorText.
		const occurrences = SOURCE.match(/text:\s*askErrorText\(e\)/g) ?? [];
		expect(occurrences.length).toBeGreaterThanOrEqual(2);
	});

	test('the "{rows} rows returned." summary is only reachable after a successful askAi (below the catch)', () => {
		// The resultSummary render sits after the try/catch that guards askAi(), so a
		// thrown 403 short-circuits (return) before rowCount is ever computed.
		const catchIdx = SOURCE.indexOf('text: askErrorText(e)');
		const summaryIdx = SOURCE.indexOf('workspace.aiQuery.resultSummary');
		expect(catchIdx).toBeGreaterThanOrEqual(0);
		expect(summaryIdx).toBeGreaterThan(catchIdx);
	});
});
