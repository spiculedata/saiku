/*
 * Unit tests for the mailHealth store (#email-toolbar). Stubs global fetch so
 * the store's constructor-time probe resolves deterministically, then
 * re-imports the module fresh per test to get a new singleton.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

type MailHealthModule = typeof import('./mailHealth.svelte');

let originalFetch: typeof globalThis.fetch;

function mockHealthResponse(configured: boolean) {
	// A fresh Response per call — the constructor's fire-and-forget probe and
	// an explicit refresh() in the same test would otherwise both try to read
	// the body of a single shared Response instance, and the second read
	// throws ("body stream already read"), which fetchMailHealth's catch
	// silently turns into configured:false.
	globalThis.fetch = vi.fn().mockImplementation(
		() =>
			new Response(JSON.stringify({ configured }), {
				status: 200,
				headers: { 'Content-Type': 'application/json' }
			})
	);
}

async function loadFreshModule(): Promise<MailHealthModule> {
	vi.resetModules();
	return import('./mailHealth.svelte');
}

beforeEach(() => {
	originalFetch = globalThis.fetch;
});

afterEach(() => {
	globalThis.fetch = originalFetch;
	vi.restoreAllMocks();
});

describe('mailHealth', () => {
	test('stays unconfigured while the probe is still in flight', async () => {
		// A fetch that never resolves during this test lets us observe the
		// pre-probe state deterministically (no race with microtask flushing).
		let resolveFetch: (() => void) | undefined;
		globalThis.fetch = vi.fn().mockImplementation(
			() =>
				new Promise<Response>((resolve) => {
					resolveFetch = () =>
						resolve(
							new Response(JSON.stringify({ configured: true }), {
								status: 200,
								headers: { 'Content-Type': 'application/json' }
							})
						);
				})
		);

		const mod = await loadFreshModule();

		expect(mod.mailHealth.configured).toBe(false);
		expect(mod.mailHealth.loading).toBe(true);

		// Let the constructor's in-flight probe settle before the test ends —
		// vi.resetModules() in the next test's setup discards this instance
		// regardless, so there's nothing further to assert here.
		resolveFetch?.();
	});

	test('reflects configured=true once the probe resolves', async () => {
		mockHealthResponse(true);
		const mod = await loadFreshModule();
		await mod.mailHealth.refresh();
		expect(mod.mailHealth.configured).toBe(true);
		expect(mod.mailHealth.loading).toBe(false);
	});

	test('reflects configured=false when the backend reports not configured', async () => {
		mockHealthResponse(false);
		const mod = await loadFreshModule();
		await mod.mailHealth.refresh();
		expect(mod.mailHealth.configured).toBe(false);
	});

	test('treats a transport failure as not configured', async () => {
		globalThis.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'));
		const mod = await loadFreshModule();
		await mod.mailHealth.refresh();
		expect(mod.mailHealth.configured).toBe(false);
		expect(mod.mailHealth.loading).toBe(false);
	});
});
