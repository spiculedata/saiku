import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// http.ts reads `browser` from $app/environment once, inside
// installAuthInterceptor; mock it true for every test in this file except
// the dedicated !browser case below, which mocks it false and imports fresh.
vi.mock('$app/environment', () => ({ browser: true }));

async function loadHttp() {
	vi.resetModules();
	return import('./http');
}

function stubCookie(cookie: string): void {
	vi.stubGlobal('document', { cookie });
}

describe('http.ts installAuthInterceptor', () => {
	let originalFetch: typeof fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});

	afterEach(() => {
		globalThis.fetch = originalFetch;
		vi.unstubAllGlobals();
	});

	function mockFetch(status: number): ReturnType<typeof vi.fn> {
		const fn = vi.fn(() => Promise.resolve(new Response(null, { status })));
		globalThis.fetch = fn as unknown as typeof fetch;
		return fn;
	}

	it('is a no-op when not running in the browser', async () => {
		vi.resetModules();
		vi.doMock('$app/environment', () => ({ browser: false }));
		const http = await import('./http');
		const fn = mockFetch(200);
		http.installAuthInterceptor();
		expect(globalThis.fetch).toBe(fn);
		// vi.doUnmock would drop the module back to the real $app/environment
		// (not the hoisted vi.mock above) for every later dynamic import in
		// this file; re-mock to true instead so the rest of the suite still
		// gets a browser build.
		vi.doMock('$app/environment', () => ({ browser: true }));
	});

	it('only patches fetch once across repeated calls', async () => {
		const http = await loadHttp();
		mockFetch(200);
		http.installAuthInterceptor();
		const patchedOnce = globalThis.fetch;
		http.installAuthInterceptor();
		expect(globalThis.fetch).toBe(patchedOnce);
	});

	describe('CSRF header injection', () => {
		it('adds X-XSRF-TOKEN from the cookie on a state-changing /rest/ request', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123; other=xyz');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch('/rest/saiku/query', { method: 'POST' });

			const [, init] = fetchMock.mock.calls[0];
			const headers = new Headers((init as RequestInit).headers);
			expect(headers.get('X-XSRF-TOKEN')).toBe('abc123');
		});

		it('does not add the header for safe methods (GET)', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch('/rest/saiku/query', { method: 'GET' });

			const [, init] = fetchMock.mock.calls[0];
			expect(init).toEqual({ method: 'GET' });
		});

		it('does not add the header for non-/rest/ URLs', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch('/static/thing', { method: 'POST' });

			const [, init] = fetchMock.mock.calls[0];
			const headers = new Headers((init as RequestInit)?.headers);
			expect(headers.get('X-XSRF-TOKEN')).toBeNull();
		});

		it('does not overwrite a caller-supplied X-XSRF-TOKEN header', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch('/rest/saiku/query', {
				method: 'POST',
				headers: { 'X-XSRF-TOKEN': 'caller-value' }
			});

			const [, init] = fetchMock.mock.calls[0];
			const headers = new Headers((init as RequestInit).headers);
			expect(headers.get('X-XSRF-TOKEN')).toBe('caller-value');
		});

		it('does not add a header when no XSRF-TOKEN cookie is present', async () => {
			const http = await loadHttp();
			stubCookie('other=xyz');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch('/rest/saiku/query', { method: 'POST' });

			const [, init] = fetchMock.mock.calls[0];
			expect(init).toEqual({ method: 'POST' });
		});

		it('detects the method from a Request instance when no init is given', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			const req = new Request('https://x.test/rest/saiku/query', { method: 'POST' });
			await globalThis.fetch(req);

			const [, init] = fetchMock.mock.calls[0];
			const headers = new Headers((init as RequestInit).headers);
			expect(headers.get('X-XSRF-TOKEN')).toBe('abc123');
		});

		it('extracts the URL from a URL instance input', async () => {
			const http = await loadHttp();
			stubCookie('XSRF-TOKEN=abc123');
			const fetchMock = mockFetch(200);
			http.installAuthInterceptor();

			await globalThis.fetch(new URL('https://x.test/rest/saiku/query'), { method: 'POST' });

			const [, init] = fetchMock.mock.calls[0];
			const headers = new Headers((init as RequestInit).headers);
			expect(headers.get('X-XSRF-TOKEN')).toBe('abc123');
		});
	});

	describe('401/403 notification', () => {
		it('notifies onAuthFailure listeners on 401 to /rest/saiku/*', async () => {
			const http = await loadHttp();
			stubCookie('');
			mockFetch(401);
			http.installAuthInterceptor();

			const calls: Array<[number, string]> = [];
			http.onAuthFailure((status, path) => calls.push([status, path]));
			await globalThis.fetch('/rest/saiku/query');

			expect(calls).toEqual([[401, '/rest/saiku/query']]);
		});

		it('notifies on 403 to /rest/saiku/*', async () => {
			const http = await loadHttp();
			stubCookie('');
			mockFetch(403);
			http.installAuthInterceptor();

			const calls: Array<[number, string]> = [];
			http.onAuthFailure((status, path) => calls.push([status, path]));
			await globalThis.fetch('/rest/saiku/query');

			expect(calls).toEqual([[403, '/rest/saiku/query']]);
		});

		it('does not notify on a successful response', async () => {
			const http = await loadHttp();
			stubCookie('');
			mockFetch(200);
			http.installAuthInterceptor();

			const listener = vi.fn();
			http.onAuthFailure(listener);
			await globalThis.fetch('/rest/saiku/query');

			expect(listener).not.toHaveBeenCalled();
		});

		it('does not notify for a 401 outside /rest/saiku/*', async () => {
			const http = await loadHttp();
			stubCookie('');
			mockFetch(401);
			http.installAuthInterceptor();

			const listener = vi.fn();
			http.onAuthFailure(listener);
			await globalThis.fetch('/rest/other/thing');

			expect(listener).not.toHaveBeenCalled();
		});

		it.each(['/rest/saiku/session', '/rest/saiku/admin/version', '/rest/saiku/demo/gate'])(
			'does not notify for the %s carve-out',
			async (path) => {
				const http = await loadHttp();
				stubCookie('');
				mockFetch(401);
				http.installAuthInterceptor();

				const listener = vi.fn();
				http.onAuthFailure(listener);
				await globalThis.fetch(path);

				expect(listener).not.toHaveBeenCalled();
			}
		);

		it('stops notifying after onAuthFailure unsubscribe', async () => {
			const http = await loadHttp();
			stubCookie('');
			mockFetch(401);
			http.installAuthInterceptor();

			const listener = vi.fn();
			const unsubscribe = http.onAuthFailure(listener);
			unsubscribe();
			await globalThis.fetch('/rest/saiku/query');

			expect(listener).not.toHaveBeenCalled();
		});
	});
});

describe('http.ts session-resume plumbing', () => {
	it('calls onSessionResumed listeners when notifySessionResumed fires', async () => {
		const http = await loadHttp();
		const listener = vi.fn();
		http.onSessionResumed(listener);
		http.notifySessionResumed();
		expect(listener).toHaveBeenCalledTimes(1);
	});

	it('stops calling a listener after it unsubscribes', async () => {
		const http = await loadHttp();
		const listener = vi.fn();
		const unsubscribe = http.onSessionResumed(listener);
		unsubscribe();
		http.notifySessionResumed();
		expect(listener).not.toHaveBeenCalled();
	});

	it('a throwing resume listener does not stop other listeners from running', async () => {
		const http = await loadHttp();
		const good = vi.fn();
		http.onSessionResumed(() => {
			throw new Error('boom');
		});
		http.onSessionResumed(good);
		expect(() => http.notifySessionResumed()).not.toThrow();
		expect(good).toHaveBeenCalledTimes(1);
	});

	it('hasPendingOps reflects registration and clears after resume', async () => {
		const http = await loadHttp();
		expect(http.hasPendingOps()).toBe(false);
		http.registerPendingOp(() => {});
		expect(http.hasPendingOps()).toBe(true);
		http.notifySessionResumed();
		expect(http.hasPendingOps()).toBe(false);
	});

	it('replays a registered pending op exactly once on resume', async () => {
		const http = await loadHttp();
		const op = vi.fn();
		http.registerPendingOp(op);
		http.notifySessionResumed();
		http.notifySessionResumed();
		expect(op).toHaveBeenCalledTimes(1);
	});

	it('does not replay a pending op that was unregistered before resume', async () => {
		const http = await loadHttp();
		const op = vi.fn();
		const unregister = http.registerPendingOp(op);
		unregister();
		http.notifySessionResumed();
		expect(op).not.toHaveBeenCalled();
	});

	it('swallows a synchronously-throwing pending op without blocking others', async () => {
		const http = await loadHttp();
		const good = vi.fn();
		http.registerPendingOp(() => {
			throw new Error('boom');
		});
		http.registerPendingOp(good);
		expect(() => http.notifySessionResumed()).not.toThrow();
		expect(good).toHaveBeenCalledTimes(1);
	});

	it('swallows a rejecting async pending op', async () => {
		const http = await loadHttp();
		const op = vi.fn(() => Promise.reject(new Error('boom')));
		http.registerPendingOp(op);
		expect(() => http.notifySessionResumed()).not.toThrow();
		expect(op).toHaveBeenCalledTimes(1);
	});
});
