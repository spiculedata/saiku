/*
 * Per-user "favourite dashboards" store, backed by localStorage.
 *
 * Spec (#936) calls for server-side persistence at
 * {@code ${user}/preferences/favourites.json} so favourites follow
 * the user across devices. The first cut ships localStorage to land
 * the UX without blocking on a new REST endpoint; a follow-up issue
 * can swap the IO layer once the preferences-file endpoint exists.
 * The store's API doesn't change either way.
 *
 * Unordered set semantics — favourites display sorted by the
 * dashboard's own name in the catalogue render, not by toggle
 * order. The UI render handles that sort.
 */

import { session } from "$lib/stores/session.svelte";

const STORAGE_PREFIX = "saiku:favourites:";

function storageKey(username: string | null | undefined): string | null {
  if (!username) return null;
  return `${STORAGE_PREFIX}${username}`;
}

function loadFromStorage(username: string | null | undefined): Set<string> {
  if (typeof window === "undefined") return new Set();
  const key = storageKey(username);
  if (!key) return new Set();
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return new Set();
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((p): p is string => typeof p === "string"));
  } catch {
    return new Set();
  }
}

function saveToStorage(username: string | null | undefined, paths: Set<string>): void {
  if (typeof window === "undefined") return;
  const key = storageKey(username);
  if (!key) return;
  try {
    window.localStorage.setItem(key, JSON.stringify(Array.from(paths)));
  } catch {
    // Same rationale as recentDashboards: localStorage failures are
    // a UX inconvenience, not a contract violation. Swallow silently.
  }
}

class FavouriteDashboardsStore {
  private entries = $state<Set<string>>(new Set());
  private lastUser = $state<string | null>(null);

  private prime(username: string | null | undefined): void {
    const u = username ?? null;
    if (u === this.lastUser) return;
    this.lastUser = u;
    this.entries = loadFromStorage(u);
  }

  /** True if {@code path} is currently favourited for the active user. */
  isFavourite(path: string): boolean {
    const u = session.current?.username ?? null;
    this.prime(u);
    return this.entries.has(path);
  }

  /** All favourite paths for the current user. Order is insertion-
   *  order of the underlying Set, which is *not* alphabetical — the
   *  catalogue render sorts when displaying. Empty when no user. */
  all(): string[] {
    const u = session.current?.username ?? null;
    this.prime(u);
    return Array.from(this.entries);
  }

  /** Flip the favourite state for {@code path}. No-op when no user. */
  toggle(path: string): void {
    if (!path) return;
    const u = session.current?.username ?? null;
    if (!u) return;
    this.prime(u);
    const next = new Set(this.entries);
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
    }
    this.entries = next;
    saveToStorage(u, next);
  }

  /** Force-remove a path — used when the catalogue deletes a
   *  dashboard, so a stale favourite chip doesn't survive. */
  remove(path: string): void {
    const u = session.current?.username ?? null;
    if (!u) return;
    this.prime(u);
    if (!this.entries.has(path)) return;
    const next = new Set(this.entries);
    next.delete(path);
    this.entries = next;
    saveToStorage(u, next);
  }
}

export const favouriteDashboards = new FavouriteDashboardsStore();
