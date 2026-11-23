/*
 * Regression cover for saiku#1766: a shared App Builder link naming a page
 * (`?p=<pageId>`) always opened page 1 and had the query string rewritten to
 * page 1's id.
 *
 * Two independent things had to give for that to happen, and both are pinned
 * here:
 *
 *  1. The load fired TWICE on first mount (an `onMount` load plus a
 *     path-watching effect), and the second response reset the active page to
 *     page 0 *after* the deep link had been restored. `createAppLoader` makes
 *     one-load-per-path a tested invariant instead of a lifecycle detail.
 *  2. The active page was restored by a view's mount hook, so any later load of
 *     the same app undid it. The store now derives the page from the load's own
 *     input — covered in appDoc.svelte.test.ts.
 */

import { describe, expect, it, vi } from 'vitest';
import { createAppLoader, type AppLoadRequest } from './appLoader';
import {
	decodeAppFilterState,
	encodeAppFilterState,
	PAGE_PARAM
} from '$lib/dashboard/urlFilterState';

function collector(): { requests: AppLoadRequest[]; load: ReturnType<typeof createAppLoader> } {
	const requests: AppLoadRequest[] = [];
	return { requests, load: createAppLoader((r) => requests.push(r)) };
}

describe('createAppLoader (saiku#1766)', () => {
	it('loads once per distinct path, not once per call', () => {
		const { requests, load } = collector();

		// The route component re-renders for reasons unrelated to the app; every
		// one of these would be a fresh load under an unguarded effect.
		load('homes/admin/demo.saikuapp', null);
		load('homes/admin/demo.saikuapp', null);
		load('homes/admin/demo.saikuapp', null);

		expect(requests).toEqual([{ path: 'homes/admin/demo.saikuapp', pageId: null }]);
	});

	it('reloads when the path changes, carrying that navigation’s page id', () => {
		const { requests, load } = collector();

		load('homes/admin/first.saikuapp', 'p-second');
		load('homes/admin/second.saikuapp', 'p-fourth');
		load('homes/admin/second.saikuapp', 'p-fourth'); // re-render, not a navigation

		expect(requests).toEqual([
			{ path: 'homes/admin/first.saikuapp', pageId: 'p-second' },
			{ path: 'homes/admin/second.saikuapp', pageId: 'p-fourth' }
		]);
	});

	it('reloads the same path when navigated back to with a new page id', () => {
		const { requests, load } = collector();

		load('homes/admin/demo.saikuapp', 'p-a');
		load('homes/admin/other.saikuapp', null);
		load('homes/admin/demo.saikuapp', 'p-c');

		expect(requests.map((r) => r.pageId)).toEqual(['p-a', null, 'p-c']);
	});

	it('ignores an empty path (the index route has no app to load)', () => {
		const { requests, load } = collector();

		load('', 'p-a');
		load('', 'p-a');

		expect(requests).toEqual([]);
	});

	it('does not cache the request handler’s result — a retry is a new path', () => {
		const request = vi.fn();
		const load = createAppLoader(request);

		load('homes/admin/demo.saikuapp', 'p-a');
		load('homes/admin/demo.saikuapp', 'p-a');
		expect(request).toHaveBeenCalledTimes(1);

		// A failed load leaves the store empty; the user hits reload, the route
		// hands us the same path, and nothing would re-request it. The loader is
		// deliberately per-mount, so a fresh mount gets a fresh loader — this
		// asserts the seam, not a retry policy.
		const retry = createAppLoader(request);
		retry('homes/admin/demo.saikuapp', 'p-a');
		expect(request).toHaveBeenCalledTimes(2);
	});
});

describe('?p= round-trips through the URL codec (saiku#1766)', () => {
	it('the page id the loader reads back out is the one the URL names', () => {
		const encoded = encodeAppFilterState('page-1-344334', {}, null);
		expect(encoded).toBe(`?${PAGE_PARAM}=page-1-344334`);
		expect(decodeAppFilterState(new URLSearchParams(encoded)).activePageId).toBe('page-1-344334');
	});

	it('a link with no page param yields null so the store opens page 0', () => {
		expect(decodeAppFilterState(new URLSearchParams('f=a/b/c=[X]')).activePageId).toBeNull();
	});
});
