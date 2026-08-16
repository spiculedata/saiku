/*
 * Per-session "show hidden measures" toggle (#834).
 *
 * Backs the admin-only checkbox surfaced by MeasuresModal. Persisted to
 * sessionStorage so a page reload inside the same tab session keeps the
 * admin's choice — but the toggle silently resets when the browser
 * session ends, matching the cross-session-amnesic posture we want for
 * an opt-in admin-debug surface.
 *
 * Per-user persistence (localStorage keyed by username) is intentionally
 * out of scope for v1; the spec on issue #834 calls per-session "fine
 * for v1". A future ticket can promote storage without changing this
 * module's exported shape.
 *
 * Security note: the UI hides the toggle from non-admin users as a
 * convenience, not as an enforcement boundary. The server-side
 * `?includeHidden=` query param on
 * GET /rest/saiku/{user}/discover/.../measures/ is gated by
 * OlapDiscoverResource and is the actual access control point
 * (saiku#778). If a non-admin client forges the query string, the
 * server filters hidden measures regardless.
 */

const STORAGE_KEY = 'saiku:measures:includeHidden';

function hasSessionStorage(): boolean {
	// SSR-safe: SvelteKit can prerender pages, so sessionStorage may not
	// exist when this module is first evaluated. We don't import
	// `$app/environment` here so the helper stays trivially unit-testable
	// outside the SvelteKit harness (vitest doesn't load SvelteKit).
	return typeof globalThis !== 'undefined' && typeof globalThis.sessionStorage !== 'undefined';
}

function readStorage(): boolean {
	if (!hasSessionStorage()) return false;
	try {
		return globalThis.sessionStorage.getItem(STORAGE_KEY) === '1';
	} catch {
		return false;
	}
}

function writeStorage(value: boolean): void {
	if (!hasSessionStorage()) return;
	try {
		if (value) {
			globalThis.sessionStorage.setItem(STORAGE_KEY, '1');
		} else {
			globalThis.sessionStorage.removeItem(STORAGE_KEY);
		}
	} catch {
		// sessionStorage may be unavailable (private mode, quota, blocked
		// by an enterprise policy). Failing silently is the right call: the
		// toggle is a per-session convenience, not a contract.
	}
}

class MeasuresHiddenToggleStore {
	/** Source of truth for the current toggle value. Initialised from
	 *  sessionStorage so reloads keep the admin's prior choice. */
	enabled = $state<boolean>(readStorage());

	/** Flip to a specific value (idempotent). Persists when changed. */
	set(value: boolean): void {
		if (this.enabled === value) return;
		this.enabled = value;
		writeStorage(value);
	}

	/** Convenience: flip the current value. */
	toggle(): void {
		this.set(!this.enabled);
	}

	/** Reset to false and drop the persisted entry. Used by the test
	 *  harness; not surfaced in the UI. */
	reset(): void {
		this.enabled = false;
		if (!hasSessionStorage()) return;
		try {
			globalThis.sessionStorage.removeItem(STORAGE_KEY);
		} catch {
			// see writeStorage()
		}
	}
}

export const measuresHiddenToggle = new MeasuresHiddenToggleStore();

// Exported for tests so they can assert exact storage layout without
// duplicating the magic string.
export const MEASURES_HIDDEN_TOGGLE_STORAGE_KEY = STORAGE_KEY;
