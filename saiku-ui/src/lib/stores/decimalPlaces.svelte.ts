/*
 * Workspace decimal-places preference (saiku#1988).
 *
 * `null` means "auto" — the cellset renders the server's formatted string
 * exactly as before. A number (0–4 by default) pins the number of decimal
 * places shown on numeric data cells, display-only: the MDX, the schema's
 * `FORMAT_STRING`, drillthrough and export are untouched.
 *
 * Persisted to sessionStorage so a page reload inside the same tab session
 * keeps "no cents" — the same per-session posture as the hidden-measures
 * toggle (saiku#834). Per-user / per-saved-query persistence is deliberately
 * out of scope for the MVP; promoting the storage medium later needs no
 * change to this module's exported shape.
 *
 * SSR-safe: SvelteKit prerenders pages, so sessionStorage may be absent when
 * this module is first evaluated. We deliberately avoid importing
 * `$app/environment` to keep the store trivially unit-testable under vitest.
 */

import { DEFAULT_MAX_OPTIONS, MAX_DECIMALS } from '$lib/cellset/decimalFormat';

const STORAGE_KEY = 'saiku:cellset:decimals';

/** Decimal-place options offered by the picker: "auto" (null) plus 0…max. */
export function decimalOptions(max: number = DEFAULT_MAX_OPTIONS): (number | null)[] {
	return [null, ...Array.from({ length: max + 1 }, (_, i) => i)];
}

function hasSessionStorage(): boolean {
	return typeof globalThis !== 'undefined' && typeof globalThis.sessionStorage !== 'undefined';
}

/** Read a persisted value; anything unparseable or out of range falls back to
 *  "auto" so a hand-edited/corrupted entry can never wedge the grid. */
export function parseStoredDecimals(raw: string | null): number | null {
	if (raw == null || raw === '') return null;
	const n = Number(raw);
	if (!Number.isFinite(n)) return null;
	const d = Math.floor(n);
	if (d < 0 || d > MAX_DECIMALS) return null;
	return d;
}

function readStorage(): number | null {
	if (!hasSessionStorage()) return null;
	try {
		return parseStoredDecimals(globalThis.sessionStorage.getItem(STORAGE_KEY));
	} catch {
		return null;
	}
}

function writeStorage(value: number | null): void {
	if (!hasSessionStorage()) return;
	try {
		if (value === null) {
			globalThis.sessionStorage.removeItem(STORAGE_KEY);
		} else {
			globalThis.sessionStorage.setItem(STORAGE_KEY, String(value));
		}
	} catch {
		// sessionStorage may be unavailable (private mode, quota, enterprise
		// policy). The grid still works — it just forgets on reload.
	}
}

class DecimalPlacesStore {
	/** null = auto (server formatting), otherwise 0…MAX_DECIMALS. */
	decimals = $state<number | null>(readStorage());

	/** Set an explicit value (idempotent). null restores "auto" and clears
	 *  the persisted entry. Out-of-range values are clamped/rejected rather
	 *  than throwing — this is a display preference, not user input. */
	set(value: number | null): void {
		const next = value === null ? null : parseStoredDecimals(String(value));
		if (this.decimals === next) return;
		this.decimals = next;
		writeStorage(next);
	}

	/** Convenience for the "auto" entry in the picker. */
	useAuto(): void {
		this.set(null);
	}

	/** Drop back to "auto" and forget the persisted entry. */
	reset(): void {
		this.decimals = null;
		writeStorage(null);
	}
}

export const decimalPlaces = new DecimalPlacesStore();

/** Exported for tests so they can assert the storage layout without
 *  duplicating the magic string. */
export const DECIMAL_PLACES_STORAGE_KEY = STORAGE_KEY;
