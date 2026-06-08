/*
 * Generic per-user, localStorage-backed ordered-list store (#1162).
 *
 * Extracted from the near-identical favouriteDashboards + recentDashboards
 * stores, which shared ~80% of their code: a per-user localStorage key
 * layout (`<prefix><username>`), a `version` reactivity counter, try/swallow
 * IO, `session.current?.username` plumbing, and the loadFromStorage /
 * saveToStorage skeleton.
 *
 * The two consumers differ only in semantics, captured by the options:
 *   - favourites: SET semantics — `cap` unset, membership toggle, sorted by
 *     the catalogue render (insertion order in storage).
 *   - recents: ORDERED-LIST semantics — `cap = 10`, dedupe-to-front so the
 *     most-recently-pushed entry sits at index 0.
 *
 * Items are persisted as a JSON array of strings (the existing on-disk
 * layout, unchanged). `T` is constrained to `string` to keep the storage
 * format identical to the pre-refactor stores; the generic parameter is
 * kept so the abstraction can broaden later without churning callers.
 *
 * Reactivity model (unchanged from the originals): read methods are pure —
 * they re-read localStorage on every call and read the `version` counter as
 * a tracking dep so Svelte 5 $derived / $effect consumers re-evaluate
 * whenever a mutation runs. The read path never writes $state (which Svelte
 * 5 forbids inside $derived).
 */

import { session } from "$lib/stores/session.svelte";

export interface UserKeyedListStoreOptions {
  /** localStorage key prefix; the active username is appended to form the key. */
  prefix: string;
  /**
   * Optional hard cap on stored entries. When set, the list is trimmed to
   * the first `cap` items on both load and save (so a corrupt over-length
   * blob is also clamped on read). When unset, the list is unbounded.
   */
  cap?: number;
  /**
   * When true, `addFront` removes any existing equal entry before
   * prepending, giving dedupe-to-front (most-recent-first) ordering.
   * When false/unset, `addFront` is a no-op for an already-present entry.
   */
  dedupe?: boolean;
  /**
   * When true, duplicates are collapsed (keeping the first occurrence, so
   * order is preserved) when reading from storage. This matches the
   * favourites store's original SET semantics, which silently de-duplicated
   * a corrupt / externally-edited blob on load. The store itself never
   * writes duplicates, so this only affects already-tampered storage.
   */
  dedupeOnLoad?: boolean;
}

export class UserKeyedListStore<T extends string = string> {
  private readonly prefix: string;
  private readonly cap?: number;
  private readonly dedupe: boolean;
  private readonly dedupeOnLoad: boolean;

  /** Re-render trigger. Mutations bump this so $derived consumers notice a
   *  write. Read methods only *read* this — never write. */
  private version = $state<number>(0);

  constructor(options: UserKeyedListStoreOptions) {
    this.prefix = options.prefix;
    this.cap = options.cap;
    this.dedupe = options.dedupe ?? false;
    this.dedupeOnLoad = options.dedupeOnLoad ?? false;
  }

  private storageKey(username: string | null | undefined): string | null {
    if (!username) return null;
    return `${this.prefix}${username}`;
  }

  private applyCap(items: T[]): T[] {
    return this.cap === undefined ? items : items.slice(0, this.cap);
  }

  private loadFromStorage(username: string | null | undefined): T[] {
    if (typeof window === "undefined") return [];
    const key = this.storageKey(username);
    if (!key) return [];
    try {
      const raw = window.localStorage.getItem(key);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return [];
      const strings = parsed.filter((p): p is T => typeof p === "string");
      // dedupeOnLoad mirrors the favourites store's original Set-on-load, which
      // collapsed duplicates from a corrupt/hand-edited blob. Set preserves
      // first-occurrence order, matching the old insertion-ordered iteration.
      const deduped = this.dedupeOnLoad ? ([...new Set(strings)] as T[]) : strings;
      return this.applyCap(deduped);
    } catch {
      // Corrupt / unavailable localStorage is a UX inconvenience, not a
      // contract violation. Treat as empty and swallow.
      return [];
    }
  }

  private saveToStorage(username: string | null | undefined, items: T[]): void {
    if (typeof window === "undefined") return;
    const key = this.storageKey(username);
    if (!key) return;
    try {
      window.localStorage.setItem(key, JSON.stringify(items));
    } catch {
      // localStorage may be unavailable (private mode, quota). Failing
      // silently is the right call: these lists are a convenience, not a
      // contract.
    }
  }

  private get username(): string | null {
    return session.current?.username ?? null;
  }

  /** All entries for the current user, in storage order (capped when a cap
   *  is configured). Empty when there's no current user. */
  all(): T[] {
    void this.version; // tracking dep — re-evaluate when mutations bump
    return this.loadFromStorage(this.username);
  }

  /** True if {@code item} is present for the active user. */
  has(item: T): boolean {
    void this.version; // tracking dep
    return this.loadFromStorage(this.username).includes(item);
  }

  /**
   * Prepend {@code item} to the list (most-recent-first). With `dedupe`,
   * any existing equal entry is removed first so the item moves to the
   * front; without it, an already-present entry is left untouched. The
   * result is clamped to `cap`. No-op for an empty item or no current user.
   */
  addFront(item: T): void {
    if (!item) return;
    const u = this.username;
    if (!u) return;
    const current = this.loadFromStorage(u);
    if (!this.dedupe && current.includes(item)) return;
    const filtered = this.dedupe ? current.filter((p) => p !== item) : current;
    const next = this.applyCap([item, ...filtered]);
    this.saveToStorage(u, next);
    this.version++;
  }

  /**
   * Membership toggle (SET semantics): add {@code item} if absent, remove
   * it if present. New entries are appended (insertion order preserved).
   * No-op for an empty item or no current user.
   */
  toggle(item: T): void {
    if (!item) return;
    const u = this.username;
    if (!u) return;
    const current = this.loadFromStorage(u);
    const next = current.includes(item)
      ? current.filter((p) => p !== item)
      : this.applyCap([...current, item]);
    this.saveToStorage(u, next);
    this.version++;
  }

  /** Remove a single {@code item}. No-op when absent or no current user. */
  remove(item: T): void {
    const u = this.username;
    if (!u) return;
    const current = this.loadFromStorage(u);
    const next = current.filter((p) => p !== item);
    if (next.length === current.length) return;
    this.saveToStorage(u, next);
    this.version++;
  }

  /** Drop the entire list for the current user. No-op when no current user. */
  clear(): void {
    const u = this.username;
    if (!u) return;
    this.saveToStorage(u, []);
    this.version++;
  }
}

/** Factory mirroring the requested `createUserKeyedListStore<T>(...)` API. */
export function createUserKeyedListStore<T extends string = string>(
  options: UserKeyedListStoreOptions,
): UserKeyedListStore<T> {
  return new UserKeyedListStore<T>(options);
}
