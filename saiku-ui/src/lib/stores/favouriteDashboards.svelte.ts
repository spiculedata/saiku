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
 * As of #1162 the shared mechanics (per-user localStorage key,
 * version-counter reactivity, try/swallow IO) live in
 * {@link createUserKeyedListStore}; this file is a thin wrapper that
 * fixes the favourites semantics (uncapped, membership toggle) and
 * re-exposes the original public method names so consumers compile
 * unchanged.
 */

import { createUserKeyedListStore, type UserKeyedListStore } from "$lib/stores/userKeyedListStore.svelte";

const STORAGE_PREFIX = "saiku:favourites:";

class FavouriteDashboardsStore {
  // SET semantics: no cap, membership toggle, de-dupe on load (the original
  // Set-backed store collapsed duplicates from a corrupt/tampered blob).
  private readonly store: UserKeyedListStore = createUserKeyedListStore({
    prefix: STORAGE_PREFIX,
    dedupeOnLoad: true,
  });

  /** True if {@code path} is currently favourited for the active user. */
  isFavourite(path: string): boolean {
    return this.store.has(path);
  }

  /** All favourite paths for the current user. Order is insertion-
   *  order of the underlying list, which is *not* alphabetical — the
   *  catalogue render sorts when displaying. Empty when no user. */
  all(): string[] {
    return this.store.all();
  }

  /** Flip the favourite state for {@code path}. No-op when no user. */
  toggle(path: string): void {
    this.store.toggle(path);
  }

  /** Force-remove a path — used when the catalogue deletes a
   *  dashboard, so a stale favourite chip doesn't survive. */
  remove(path: string): void {
    this.store.remove(path);
  }
}

export const favouriteDashboards = new FavouriteDashboardsStore();
