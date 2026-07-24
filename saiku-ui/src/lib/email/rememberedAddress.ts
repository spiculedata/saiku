/*
 * Remembered "email me this" recipient address.
 *
 * The modal seeds its `address` field from this on open and writes back on
 * every successful Send, so a user who always sends to the same address
 * doesn't have to retype it. Persisted to localStorage (not sessionStorage)
 * — unlike the per-session admin toggle this is a plain convenience with no
 * privacy/security angle, so it's fine for it to survive across browser
 * sessions.
 *
 * SSR-safe guard cloned from measuresHiddenToggle.svelte.ts: SvelteKit can
 * prerender pages, so localStorage may not exist when this module is first
 * evaluated. No `$app/environment` import here either, so the helper stays
 * trivially unit-testable outside the SvelteKit harness (vitest doesn't
 * load SvelteKit).
 */

const STORAGE_KEY = "saiku_email_self_address";

function hasLocalStorage(): boolean {
  return typeof globalThis !== "undefined" && typeof globalThis.localStorage !== "undefined";
}

function get(): string {
  if (!hasLocalStorage()) return "";
  try {
    return globalThis.localStorage.getItem(STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

function set(v: string): void {
  if (!hasLocalStorage()) return;
  try {
    globalThis.localStorage.setItem(STORAGE_KEY, v);
  } catch {
    // localStorage may be unavailable (private mode, quota, blocked by an
    // enterprise policy). Failing silently is the right call: remembering
    // the address is a convenience, not a contract.
  }
}

export const rememberedAddress = { get, set };

// Exported for tests so they can assert exact storage layout without
// duplicating the magic string.
export const REMEMBERED_ADDRESS_STORAGE_KEY = STORAGE_KEY;
