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
 *
 * As of #1162 the shared mechanics (per-user localStorage key,
 * version-counter reactivity, try/swallow IO) live in
 * {@link createUserKeyedListStore}; this file is a thin wrapper that
 * fixes the recents semantics (cap = RECENTS_CAP, dedupe-to-front) and
 * re-exposes the original public method names so consumers compile
 * unchanged.
 */

import { createUserKeyedListStore, type UserKeyedListStore } from "$lib/stores/userKeyedListStore.svelte";

/** Hard cap on the number of entries we keep. Beyond this and the
 *  "recently viewed" section just becomes another catalogue list. */
export const RECENTS_CAP = 10;

const STORAGE_PREFIX = "saiku:recents:";

class RecentDashboardsStore {
  // ORDERED-LIST semantics: capped, dedupe-to-front (most-recent first).
  private readonly store: UserKeyedListStore = createUserKeyedListStore({
    prefix: STORAGE_PREFIX,
    cap: RECENTS_CAP,
    dedupe: true,
  });

  /** All recent paths for the current user, most-recent first, capped
   *  at {@link RECENTS_CAP}. Empty when there's no current user. */
  all(): string[] {
    return this.store.all();
  }

  /** Push a path onto the recents list for the current user. If the
   *  path is already present, it moves to the front (dedup). Trims to
   *  RECENTS_CAP entries. No-op when there's no current user. */
  push(path: string): void {
    this.store.addFront(path);
  }

  /** Remove a single path — used when the catalogue deletes a
   *  dashboard so its row doesn't linger in the recents section. */
  remove(path: string): void {
    this.store.remove(path);
  }

  /** Drop the entire list for the current user. Exposed mainly for
   *  the unit-test harness; the UI doesn't surface a "clear" affordance
   *  in the first cut. */
  clear(): void {
    this.store.clear();
  }
}

export const recentDashboards = new RecentDashboardsStore();
