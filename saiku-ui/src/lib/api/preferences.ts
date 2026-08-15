/**
 * Per-user preferences — account-level, not browser-level.
 *
 * saiku#1868: UI decisions like "I have seen the onboarding tour" lived only in `localStorage`, so
 * they replayed on every new browser, machine or private window. The server now stores a small
 * key/value bag per user (`/homes/<user>/.preferences.json`), keyed on the authenticated caller —
 * there is no user parameter to pass, and none to get wrong.
 *
 * `localStorage` is kept as a **cache**, not the source of truth: the tour must not flash on every
 * page load while a network round-trip resolves, and a preference already known locally should not
 * need the server at all.
 */

const BASE = '/rest/saiku/api/preferences';
const CACHE_PREFIX = 'saiku.prefs.';

export type Preferences = Record<string, unknown>;

/** Local cache key for one user's preferences document. */
function cacheKey(username: string): string {
	return `${CACHE_PREFIX}${username}`;
}

function readCache(username: string): Preferences | null {
	if (typeof localStorage === 'undefined') return null;
	try {
		const raw = localStorage.getItem(cacheKey(username));
		if (!raw) return null;
		const parsed = JSON.parse(raw) as unknown;
		return parsed && typeof parsed === 'object' ? (parsed as Preferences) : null;
	} catch {
		return null;
	}
}

function writeCache(username: string, prefs: Preferences): void {
	if (typeof localStorage === 'undefined') return;
	try {
		localStorage.setItem(cacheKey(username), JSON.stringify(prefs));
	} catch {
		// Storage full or blocked (private mode) — the server copy is authoritative anyway.
	}
}

/**
 * Fetch the caller's preferences from the server, refreshing the local cache.
 *
 * Returns the cached copy on any failure rather than throwing: a preferences lookup must never be
 * able to break the page that asked for it.
 */
export async function fetchPreferences(username: string): Promise<Preferences> {
	try {
		const res = await fetch(BASE, { credentials: 'include' });
		if (!res.ok) return readCache(username) ?? {};
		const prefs = (await res.json()) as Preferences;
		writeCache(username, prefs);
		return prefs;
	} catch {
		return readCache(username) ?? {};
	}
}

/** The locally cached value for one key, without touching the network. */
export function cachedPreference<T = unknown>(username: string, key: string): T | undefined {
	return readCache(username)?.[key] as T | undefined;
}

/**
 * Merge one key into the caller's preferences.
 *
 * The cache is updated FIRST and unconditionally, so the effect is immediate and survives a failed
 * request — a user who dismissed something must not see it again just because the write 500'd.
 * Pass `null` to delete a key.
 */
export async function setPreference(
	username: string,
	key: string,
	value: unknown
): Promise<Preferences> {
	const next: Preferences = { ...(readCache(username) ?? {}) };
	if (value === null) delete next[key];
	else next[key] = value;
	writeCache(username, next);

	try {
		const res = await fetch(BASE, {
			method: 'PUT',
			credentials: 'include',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ [key]: value })
		});
		if (res.ok) {
			const saved = (await res.json()) as Preferences;
			writeCache(username, saved);
			return saved;
		}
	} catch {
		// fall through — the local cache already reflects the change
	}
	return next;
}
