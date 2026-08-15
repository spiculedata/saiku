import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The module reads `browser` from $app/environment and the `platform` singleton.
// Mock both so we can drive the capability gate deterministically in a node env.
vi.mock('$app/environment', () => ({ browser: true }));

const platform = { capabilities: null as unknown, version: '9.9.9' };
vi.mock('$lib/stores/platform.svelte', () => ({ platform }));

// A minimal in-memory sessionStorage + a stub navigator/document/crypto so the
// module's browser-only paths run under vitest's node environment.
function stubBrowserGlobals(): { fetch: ReturnType<typeof vi.fn> } {
	const store = new Map<string, string>();
	vi.stubGlobal('sessionStorage', {
		getItem: (k: string) => store.get(k) ?? null,
		setItem: (k: string, v: string) => void store.set(k, v)
	});
	vi.stubGlobal('crypto', { randomUUID: () => 'test-uuid-1234' });
	vi.stubGlobal('document', { addEventListener: vi.fn(), visibilityState: 'visible' });
	vi.stubGlobal('navigator', {}); // no sendBeacon → forces the fetch path
	const fetch = vi.fn(() => Promise.resolve(new Response('{}')));
	vi.stubGlobal('fetch', fetch);
	return { fetch };
}

const ENABLED = { enabled: true, endpoint: 'https://collector.test/v1/event' };

async function loadTrack() {
	// Fresh module each time so the module-level queue/timer/listener state resets.
	vi.resetModules();
	return (await import('./demoAnalytics')).trackDemo;
}

describe('demoAnalytics.trackDemo', () => {
	let fetchMock: ReturnType<typeof vi.fn>;

	beforeEach(() => {
		vi.useFakeTimers();
		fetchMock = stubBrowserGlobals().fetch;
		platform.capabilities = null;
	});

	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	it('is a no-op when the demo-analytics capability is absent', async () => {
		const trackDemo = await loadTrack();
		trackDemo('query', 'run');
		vi.advanceTimersByTime(10_000);
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it('is a no-op when the capability is present but disabled', async () => {
		platform.capabilities = { demoAnalytics: { enabled: false, endpoint: ENABLED.endpoint } };
		const trackDemo = await loadTrack();
		trackDemo('app', 'open', 'view');
		vi.advanceTimersByTime(10_000);
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it('batches events and POSTs an anonymous, coarse payload when enabled', async () => {
		platform.capabilities = { demoAnalytics: ENABLED };
		const trackDemo = await loadTrack();

		trackDemo('app', 'open', 'view');
		trackDemo('query', 'run');
		expect(fetchMock).not.toHaveBeenCalled(); // still debouncing

		vi.advanceTimersByTime(4000);

		expect(fetchMock).toHaveBeenCalledTimes(1);
		const [url, init] = fetchMock.mock.calls[0];
		expect(url).toBe(ENABLED.endpoint);
		const payload = JSON.parse(init.body);
		expect(payload.session).toBe('test-uuid-1234');
		expect(payload.version).toBe('9.9.9');
		expect(payload.events).toEqual([
			{ type: 'app', name: 'open', detail: 'view' },
			{ type: 'query', name: 'run', detail: undefined }
		]);
		// never sends credentials — coarse, anonymous, cross-origin
		expect(init.credentials).toBe('omit');
	});

	it('reuses one anonymous session id across events (per-tab)', async () => {
		platform.capabilities = { demoAnalytics: ENABLED };
		const trackDemo = await loadTrack();

		trackDemo('ai', 'ask');
		vi.advanceTimersByTime(4000);
		trackDemo('cube-designer', 'open');
		vi.advanceTimersByTime(4000);

		expect(fetchMock).toHaveBeenCalledTimes(2);
		const s1 = JSON.parse(fetchMock.mock.calls[0][1].body).session;
		const s2 = JSON.parse(fetchMock.mock.calls[1][1].body).session;
		expect(s1).toBe(s2);
	});
});
