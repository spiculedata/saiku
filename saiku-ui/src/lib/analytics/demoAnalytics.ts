/**
 * Demo-only engagement analytics (saiku#1636).
 *
 * Fires anonymous, coarse interaction events — which surfaces a demo visitor
 * explores — so we can see how the online demo is used. It is inert everywhere
 * except the hosted demo:
 *
 *   - Gated on `platform.capabilities.demoAnalytics.enabled`, which the server
 *     sets true ONLY when `demoMode` is on AND telemetry isn't opted out (the
 *     same `SAIKU_TELEMETRY` / `DO_NOT_TRACK` switch as the install heartbeat).
 *     A day-to-day self-hosted Saiku reports `enabled:false`, so `track()` is a
 *     no-op there — we never spy on real users.
 *
 * Privacy by construction: no user id, no query data, no cube/member values —
 * just a coarse `{type, name, detail?}` (e.g. `app/open/foodmart-ops`,
 * `cube-designer/open`, `ai/ask`). The "session" is a random per-browser id kept
 * in sessionStorage (cleared when the tab closes) and hashed server-side before
 * storage, so it can't be correlated to anything.
 *
 * Events batch and flush on a short debounce, on tab-hide (sendBeacon), and when
 * the queue fills. Failures are swallowed — analytics must never break the app.
 */
import { browser } from '$app/environment';
import { platform } from '$lib/stores/platform.svelte';

/** Coarse event category. Keep the set small + stable so the report stays useful. */
export type DemoEventType = 'app' | 'cube-designer' | 'ai' | 'query' | 'dashboard' | 'nav';

interface QueuedEvent {
	type: DemoEventType;
	name: string;
	detail?: string;
}

const SESSION_KEY = 'saiku.demo.session';
const FLUSH_DEBOUNCE_MS = 4000;
const MAX_QUEUE = 40; // matches the Worker's per-request cap

let queue: QueuedEvent[] = [];
let flushTimer: ReturnType<typeof setTimeout> | null = null;
let listenersBound = false;

/** Analytics config from the capabilities probe, or null when off/unknown. */
function config(): { enabled: boolean; endpoint: string } | null {
	const c = platform.capabilities?.demoAnalytics;
	return c && c.enabled && c.endpoint ? c : null;
}

/** Random, anonymous, per-tab session id (sessionStorage). Coarse identifier the
 *  server hashes before storing — never a user id. */
function sessionId(): string {
	if (!browser) return 'ssr';
	try {
		let id = sessionStorage.getItem(SESSION_KEY);
		if (!id) {
			id = crypto.randomUUID();
			sessionStorage.setItem(SESSION_KEY, id);
		}
		return id;
	} catch {
		return 'no-storage';
	}
}

function scheduleFlush(): void {
	if (flushTimer) return;
	flushTimer = setTimeout(() => {
		flushTimer = null;
		void flush();
	}, FLUSH_DEBOUNCE_MS);
}

/** POST the queued events. `beacon` uses sendBeacon (for tab-hide) so the
 *  request survives the page unloading. */
function flush(beacon = false): void {
	const cfg = config();
	if (!cfg || queue.length === 0) return;
	const events = queue;
	queue = [];
	const payload = JSON.stringify({
		session: sessionId(),
		version: platform.version ?? undefined,
		events
	});
	try {
		if (beacon && browser && typeof navigator.sendBeacon === 'function') {
			navigator.sendBeacon(cfg.endpoint, new Blob([payload], { type: 'application/json' }));
			return;
		}
		void fetch(cfg.endpoint, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: payload,
			keepalive: true,
			// The collector is a different origin (telemetry.saiku.bi); we only send,
			// never read the response, and carry no credentials.
			credentials: 'omit',
			mode: 'cors'
		}).catch(() => {
			/* analytics is best-effort */
		});
	} catch {
		/* never throw from analytics */
	}
}

function bindUnloadFlush(): void {
	if (listenersBound || !browser) return;
	listenersBound = true;
	// Flush the tail on tab-hide — the most reliable "leaving" signal on mobile +
	// desktop (visibilitychange > unload). sendBeacon so it survives teardown.
	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'hidden') flush(true);
	});
}

/**
 * Record a coarse engagement event. No-op unless the demo-analytics capability
 * is enabled. Safe to call from anywhere, any time — it never throws.
 */
export function trackDemo(type: DemoEventType, name: string, detail?: string): void {
	if (!browser || !config()) return;
	bindUnloadFlush();
	queue.push({ type, name, detail });
	if (queue.length >= MAX_QUEUE) {
		flush();
		return;
	}
	scheduleFlush();
}
