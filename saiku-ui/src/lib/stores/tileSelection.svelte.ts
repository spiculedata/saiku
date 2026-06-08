/*
 * Multi-select state for dashboard tiles (issue #915).
 *
 * Holds a Set<string> of selected tile ids plus the small interaction
 * grammar that drives ctrl/cmd-click (toggle), shift-click (extend) and
 * plain-click (replace). The actual mutation logic lives in PURE helpers
 * (toggle / add / clear / isSelected / applyClick) exported standalone so
 * they can be unit-tested without a DOM — the singleton store below is a
 * thin reactive wrapper that holds the live $state Set and delegates.
 *
 * Same singleton pattern as $lib/stores/selection.svelte.ts.
 */

/** A pointer interaction's modifier intent, distilled from the mouse
 *  event by the call-site so the pure reducer stays DOM-free. */
export type SelectClickMode = "replace" | "toggle" | "extend";

/* ----------------------------- pure helpers ----------------------------- */

/** True iff {@code id} is in the selection. */
export function isSelected(set: ReadonlySet<string>, id: string): boolean {
  return set.has(id);
}

/** Return a new set with {@code id} added (no-op clone if already in). */
export function add(set: ReadonlySet<string>, id: string): Set<string> {
  const next = new Set(set);
  next.add(id);
  return next;
}

/** Return a new set with {@code id} toggled in/out. */
export function toggle(set: ReadonlySet<string>, id: string): Set<string> {
  const next = new Set(set);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** Return an empty set. */
export function clear(): Set<string> {
  return new Set<string>();
}

/**
 * Reduce a click on a tile to the next selection set.
 *  - "replace": plain click — selection becomes just {id}.
 *  - "toggle":  ctrl/cmd-click — flip {id} in/out, keep the rest.
 *  - "extend":  shift-click — add {id}, keep the rest.
 *
 * Pure: never mutates {@code set}, always returns a fresh Set.
 */
export function applyClick(
  set: ReadonlySet<string>,
  id: string,
  mode: SelectClickMode,
): Set<string> {
  switch (mode) {
    case "toggle":
      return toggle(set, id);
    case "extend":
      return add(set, id);
    case "replace":
    default:
      return new Set<string>([id]);
  }
}

/** Drop any ids no longer present in {@code liveIds} (e.g. after tiles are
 *  deleted). Returns the same reference when nothing changed so callers can
 *  short-circuit reactivity. */
export function prune(set: ReadonlySet<string>, liveIds: ReadonlySet<string>): Set<string> {
  let dropped = false;
  const next = new Set<string>();
  for (const id of set) {
    if (liveIds.has(id)) next.add(id);
    else dropped = true;
  }
  return dropped ? next : (set as Set<string>);
}

/* ----------------------------- reactive store --------------------------- */

class TileSelectionStore {
  /** Live selection. Replaced wholesale on every mutation (the helpers
   *  return fresh Sets) so Svelte 5 reactivity fires reliably. */
  ids = $state<Set<string>>(new Set<string>());

  /** Number of selected tiles. */
  get count(): number {
    return this.ids.size;
  }

  isSelected(id: string): boolean {
    return isSelected(this.ids, id);
  }

  add(id: string): void {
    this.ids = add(this.ids, id);
  }

  toggle(id: string): void {
    this.ids = toggle(this.ids, id);
  }

  clear(): void {
    if (this.ids.size === 0) return;
    this.ids = clear();
  }

  /** Apply a modifier-aware click (see {@link applyClick}). */
  click(id: string, mode: SelectClickMode): void {
    this.ids = applyClick(this.ids, id, mode);
  }

  /** Drop selected ids that no longer exist in the layout. */
  prune(liveIds: Iterable<string>): void {
    const next = prune(this.ids, new Set(liveIds));
    if (next !== this.ids) this.ids = next;
  }

  /** Snapshot the current ids as a plain array (stable order not
   *  guaranteed — callers that loop for bulk ops don't care). */
  toArray(): string[] {
    return Array.from(this.ids);
  }
}

export const tileSelection = new TileSelectionStore();
