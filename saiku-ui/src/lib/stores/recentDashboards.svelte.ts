/*
 * Per-user "recently viewed dashboards" store, backed by localStorage.
 *
 * Spec (#936): cap at 10 entries, ordered most-recent first, dedup so
 * re-opening the same dashboard moves it to the front rather than
 * duplicating. Storage is keyed by username so a shared browser
 * doesn't leak one user's recents into another's catalogue.
 *
 * Server-side persistence (cross-device sync) is explicitly out of
 * scope for the first pass — see issue #936 design notes. If a future
 * ticket adds it, the in-memory state shape doesn't change, only the
 * load / save IO does.
 */

import { session } from "$lib/stores/session.svelte";

/** Hard cap on the number of entries we keep. Beyond this and the
 *  "recently viewed" section just becomes another catalogue list. */
export const RECENTS_CAP = 10;

const STORAGE_PREFIX = "saiku:recents:";

function storageKey(username: string | null | undefined): string | null {
  if (!username) return null;
  return `${STORAGE_PREFIX}${username}`;
}

function loadFromStorage(username: string | null | undefined): string[] {
  if (typeof window === "undefined") return [];
  const key = storageKey(username);
  if (!key) return [];
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((p): p is string => typeof p === "string").slice(0, RECENTS_CAP);
  } catch {
    return [];
  }
}

function saveToStorage(username: string | null | undefined, paths: string[]): void {
  if (typeof window === "undefined") return;
  const key = storageKey(username);
  if (!key) return;
  try {
    window.localStorage.setItem(key, JSON.stringify(paths));
  } catch {
    // localStorage may be unavailable (private mode, quota). Failing
    // silently is the right call: the recents list is a convenience,
    // not a contract.
  }
}

class RecentDashboardsStore {
  /** Reactive view of the current user's recents. Re-derives from
   *  storage whenever a mutation runs OR the username changes. */
  private entries = $state<string[]>([]);
  private lastUser = $state<string | null>(null);

  /** Lazy-prime on first read so SSR doesn't touch localStorage. */
  private prime(username: string | null | undefined): void {
    const u = username ?? null;
    if (u === this.lastUser) return;
    this.lastUser = u;
    this.entries = loadFromStorage(u);
  }

  /** All recent paths for the current user, most-recent first, capped
   *  at {@link RECENTS_CAP}. Empty when there's no current user. */
  all(): string[] {
    const u = session.current?.username ?? null;
    this.prime(u);
    return this.entries;
  }

  /** Push a path onto the recents list for the current user. If the
   *  path is already present, it moves to the front (dedup). Trims to
   *  RECENTS_CAP entries. No-op when there's no current user. */
  push(path: string): void {
    if (!path) return;
    const u = session.current?.username ?? null;
    if (!u) return;
    this.prime(u);
    const filtered = this.entries.filter((p) => p !== path);
    const next = [path, ...filtered].slice(0, RECENTS_CAP);
    this.entries = next;
    saveToStorage(u, next);
  }

  /** Remove a single path — used when the catalogue deletes a
   *  dashboard so its row doesn't linger in the recents section. */
  remove(path: string): void {
    const u = session.current?.username ?? null;
    if (!u) return;
    this.prime(u);
    const next = this.entries.filter((p) => p !== path);
    if (next.length === this.entries.length) return;
    this.entries = next;
    saveToStorage(u, next);
  }

  /** Drop the entire list for the current user. Exposed mainly for
   *  the unit-test harness; the UI doesn't surface a "clear" affordance
   *  in the first cut. */
  clear(): void {
    const u = session.current?.username ?? null;
    if (!u) return;
    this.entries = [];
    saveToStorage(u, []);
  }
}

export const recentDashboards = new RecentDashboardsStore();
