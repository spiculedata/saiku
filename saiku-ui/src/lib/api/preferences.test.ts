/** @vitest-environment jsdom */
/**
 * saiku#1868 — per-user preferences client.
 *
 * The behaviours that matter are the failure ones: a preferences lookup must never break the page
 * that asked for it, and a user who dismissed something must not see it again just because the
 * write failed.
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { fetchPreferences, setPreference, cachedPreference } from './preferences';

function stubFetch(impl: (url: string, init?: RequestInit) => Response | Promise<Response>) {
	const calls: Array<{ url: string; method: string; body?: string }> = [];
	vi.stubGlobal(
		'fetch',
		vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
			calls.push({
				url: String(url),
				method: init?.method ?? 'GET',
				body: init?.body as string | undefined
			});
			return impl(String(url), init);
		})
	);
	return calls;
}

beforeEach(() => localStorage.clear());
afterEach(() => vi.unstubAllGlobals());

describe('fetchPreferences', () => {
	it('returns the server document and caches it', async () => {
		stubFetch(() => new Response(JSON.stringify({ tourDone: true }), { status: 200 }));

		const prefs = await fetchPreferences('admin');

		expect(prefs.tourDone).toBe(true);
		expect(cachedPreference('admin', 'tourDone')).toBe(true);
	});

	// A preferences lookup is never important enough to break the page it was called from.
	it('falls back to the cache when the request fails', async () => {
		stubFetch(() => new Response(JSON.stringify({ tourDone: true }), { status: 200 }));
		await fetchPreferences('admin');

		stubFetch(() => {
			throw new Error('network down');
		});
		const prefs = await fetchPreferences('admin');

		expect(prefs.tourDone).toBe(true);
	});

	it('returns an empty object when there is neither server nor cache', async () => {
		stubFetch(() => new Response('', { status: 500 }));

		expect(await fetchPreferences('admin')).toEqual({});
	});

	it('keeps one user out of another user cache', async () => {
		stubFetch(() => new Response(JSON.stringify({ secret: 'alice' }), { status: 200 }));
		await fetchPreferences('alice');

		expect(cachedPreference('bob', 'secret')).toBeUndefined();
	});
});

describe('setPreference', () => {
	it('sends a single-key merge, not the whole document', async () => {
		const calls = stubFetch(
			() => new Response(JSON.stringify({ tourDone: true }), { status: 200 })
		);

		await setPreference('admin', 'tourDone', true);

		expect(calls[0].method).toBe('PUT');
		expect(JSON.parse(calls[0].body!)).toEqual({ tourDone: true });
	});

	// THE property: dismissing something must stick locally even if the server write fails,
	// or the user sees the tour again on the very next page load.
	it('updates the cache even when the write fails', async () => {
		stubFetch(() => {
			throw new Error('network down');
		});

		await setPreference('admin', 'tourDone', true);

		expect(cachedPreference('admin', 'tourDone')).toBe(true);
	});

	it('leaves other keys untouched', async () => {
		stubFetch(() => new Response(JSON.stringify({ theme: 'dark' }), { status: 200 }));
		await fetchPreferences('admin');

		stubFetch(() => {
			throw new Error('offline');
		});
		await setPreference('admin', 'tourDone', true);

		expect(cachedPreference('admin', 'theme')).toBe('dark');
		expect(cachedPreference('admin', 'tourDone')).toBe(true);
	});

	it('removes a key when the value is null', async () => {
		stubFetch(() => new Response(JSON.stringify({ tourDone: true }), { status: 200 }));
		await fetchPreferences('admin');

		stubFetch(() => {
			throw new Error('offline');
		});
		await setPreference('admin', 'tourDone', null);

		expect(cachedPreference('admin', 'tourDone')).toBeUndefined();
	});

	it('survives unparseable cached data rather than throwing', async () => {
		localStorage.setItem('saiku.prefs.admin', 'not json');
		stubFetch(() => new Response(JSON.stringify({ tourDone: true }), { status: 200 }));

		await expect(setPreference('admin', 'tourDone', true)).resolves.toBeTruthy();
	});
});
