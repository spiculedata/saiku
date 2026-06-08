/*
 * Pure state logic for the cascading single-select filter widget
 * (issue #922).
 *
 * A cascading-select filter walks a single hierarchy level-by-level:
 * the analyst picks a parent member, the next dropdown reveals only that
 * parent's children, and so on (e.g. Region -> Country -> State -> City).
 * The deepest *concrete* selection is the member the widget emits as the
 * active filter. An "All" choice at any level resets every level below
 * it.
 *
 * This module owns ALL of the cascade branching so the Svelte component
 * stays thin — it only does the async member fetch + binding and hands
 * raw selections in / reads derived state out. Nothing here touches the
 * DOM, fetch, or the dashboard store, which keeps it trivially testable.
 *
 * Naming note: lives alongside the older $lib/dashboard/cascadingFilters
 * (plural) module, which solves a *different* problem — ancestor-prefix
 * restriction across two independent panel widgets. This one (singular)
 * is the self-contained N-dropdown walk of a single widget. They do not
 * share code.
 */

/** "All" / no-selection sentinel for a cascade level. Modelled as null
 *  so a selections array is `(string | null)[]` indexed by depth. */
export type CascadeSelection = string | null;

/** Per-level selections, index 0 = root-most cascade level. A `null`
 *  entry means "All" (no member chosen at that level). The array may be
 *  shorter than the configured depth — missing trailing entries are
 *  treated as "All". */
export type CascadeSelections = CascadeSelection[];

/** Clamp a requested cascade depth to a sane range. A cascade needs at
 *  least one dropdown; we cap at {@code maxDepth} (default 6) so a
 *  mis-configured tile can't spawn an unbounded column of selects. The
 *  effective depth is additionally bounded by how many levels actually
 *  remain in the hierarchy below the start level — see
 *  {@link effectiveDepth}. */
export function clampDepth(depth: number | undefined, maxDepth = 6): number {
  if (depth == null || Number.isNaN(depth)) return 1;
  const floored = Math.floor(depth);
  if (floored < 1) return 1;
  if (floored > maxDepth) return maxDepth;
  return floored;
}

/** The number of cascade dropdowns this widget can show, given the
 *  configured depth and the number of hierarchy levels available at or
 *  below the start level. Never exceeds {@code availableLevels} (you
 *  can't walk past the leaf) and never drops below 1 when at least one
 *  level exists. Returns 0 when there are no available levels. */
export function effectiveDepth(configuredDepth: number | undefined, availableLevels: number): number {
  if (availableLevels <= 0) return 0;
  const want = clampDepth(configuredDepth);
  return Math.min(want, availableLevels);
}

/** How many dropdowns are currently *visible*, given the selections so
 *  far. The first dropdown is always visible. Each subsequent dropdown
 *  appears only once its parent has a concrete (non-"All") selection —
 *  there's nothing to fetch children for until the parent is chosen. The
 *  count is capped at {@code depth}.
 *
 *  Example (depth 4): with selections [USA, null] the visible count is 2
 *  — dropdown 0 (USA chosen) and dropdown 1 (its children, currently
 *  "All"); dropdowns 2+ stay hidden because dropdown 1 is still "All". */
export function visibleDropdownCount(selections: CascadeSelections, depth: number): number {
  if (depth <= 0) return 0;
  let visible = 1;
  for (let i = 0; i < depth - 1; i++) {
    const sel = selections[i] ?? null;
    if (sel == null) break; // parent is "All" — deeper dropdowns hidden
    visible = i + 2;
  }
  return Math.min(visible, depth);
}

/** Apply a selection at level {@code level}, returning a NEW selections
 *  array. Selecting "All" (value null) at level k truncates the array to
 *  length k — every deeper selection is discarded because its parent no
 *  longer pins a member. Selecting a concrete member at level k sets that
 *  slot and likewise drops any now-stale deeper selections (the children
 *  shown below must be re-derived from the new parent).
 *
 *  Pure: the input array is never mutated. Re-selecting the same value
 *  that's already at that level (with the same tail) returns a value that
 *  is deep-equal to the input — see {@link selectionsEqual} for the
 *  idempotence check callers can use to short-circuit. */
export function applySelection(
  selections: CascadeSelections,
  level: number,
  value: CascadeSelection,
): CascadeSelections {
  if (level < 0) return [...selections];
  // Keep everything strictly above the changed level untouched; the
  // changed level takes the new value; everything below is dropped.
  const head = selections.slice(0, level);
  // Pad any gap (sparse upstream "All"s) with explicit nulls so indexing
  // stays dense and predictable for the component's #each.
  while (head.length < level) head.push(null);
  if (value == null) {
    // "All" at this level: truncate here, no slot retained.
    return head;
  }
  return [...head, value];
}

/** The deepest concrete (non-"All") selection — the member the widget
 *  emits as its filter. Returns null when every level is "All" (the
 *  widget contributes no slicing). Trailing "All"s are ignored. */
export function emittedLeafMember(selections: CascadeSelections): string | null {
  for (let i = selections.length - 1; i >= 0; i--) {
    const sel = selections[i];
    if (sel != null) return sel;
  }
  return null;
}

/** Emit the leaf as the members[] array the rest of the dashboard filter
 *  machinery expects (single-element, or empty for "All at every
 *  level"). Mirrors how the other widget variants push an ActiveFilter
 *  member list. */
export function emittedMembers(selections: CascadeSelections): string[] {
  const leaf = emittedLeafMember(selections);
  return leaf == null ? [] : [leaf];
}

/** The concrete member selected at level {@code level} whose children
 *  should populate dropdown {@code level + 1}. Returns null when that
 *  level is "All" (or out of range) — i.e. there is no parent to fetch
 *  children for, so the next dropdown stays hidden. */
export function parentForLevel(selections: CascadeSelections, level: number): string | null {
  if (level < 0 || level >= selections.length) return null;
  return selections[level] ?? null;
}

/** Structural equality of two selections arrays, treating a trailing
 *  run of nulls as equivalent to absence. Lets callers detect a no-op
 *  re-selection and skip a store write / re-render. */
export function selectionsEqual(a: CascadeSelections, b: CascadeSelections): boolean {
  const an = trimTrailingNulls(a);
  const bn = trimTrailingNulls(b);
  if (an.length !== bn.length) return false;
  for (let i = 0; i < an.length; i++) {
    if (an[i] !== bn[i]) return false;
  }
  return true;
}

function trimTrailingNulls(s: CascadeSelections): CascadeSelections {
  let end = s.length;
  while (end > 0 && (s[end - 1] ?? null) == null) end--;
  return s.slice(0, end);
}
