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
 *
 * Reactivity model: read methods (`all`, `isFavourite`) are pure —
 * they re-read localStorage on every call and use a `version` counter
 * as a tracking dep so $derived consumers re-evaluate whenever a
 * mutation runs. This keeps the read path free of $state writes
 * (which Svelte 5 forbids inside $derived).
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
  /** Re-render trigger. Mutations bump this so $derived consumers
   *  notice a write. Read methods only *read* this — never write. */
  private version = $state<number>(0);

  /** True if {@code path} is currently favourited for the active user. */
  isFavourite(path: string): boolean {
    void this.version; // tracking dep — re-evaluate when mutations bump
    const u = session.current?.username ?? null;
    return loadFromStorage(u).has(path);
  }

  /** All favourite paths for the current user. Order is insertion-
   *  order of the underlying Set, which is *not* alphabetical — the
   *  catalogue render sorts when displaying. Empty when no user. */
  all(): string[] {
    void this.version; // tracking dep
    const u = session.current?.username ?? null;
    return Array.from(loadFromStorage(u));
  }

  /** Flip the favourite state for {@code path}. No-op when no user. */
  toggle(path: string): void {
    if (!path) return;
    const u = session.current?.username ?? null;
    if (!u) return;
    const current = loadFromStorage(u);
    const next = new Set(current);
    if (next.has(path)) {
      next.delete(path);
    } else {
      next.add(path);
    }
    saveToStorage(u, next);
    this.version++;
  }

  /** Force-remove a path — used when the catalogue deletes a
   *  dashboard, so a stale favourite chip doesn't survive. */
  remove(path: string): void {
    const u = session.current?.username ?? null;
    if (!u) return;
    const current = loadFromStorage(u);
    if (!current.has(path)) return;
    const next = new Set(current);
    next.delete(path);
    saveToStorage(u, next);
    this.version++;
  }
}

export const favouriteDashboards = new FavouriteDashboardsStore();
