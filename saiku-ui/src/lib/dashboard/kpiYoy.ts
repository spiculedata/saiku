/*
 * Pure helpers for the KPI tile's year-over-year (same-period-previous-year)
 * comparison (#992).
 *
 * Mirrors Mondrian's ParallelPeriod() primitive without ever emitting MDX:
 * given the flat, chronologically-ordered member list at a single level
 * (e.g. every Quarter across every year, as returned by /ai/members/search)
 * and a pinned slicer member (e.g. [Time].[1997].[Q2]), resolve the same
 * relative position one parent (year) earlier — [Time].[1996].[Q2].
 *
 * The resolution is done entirely from member unique names: a member's
 * parent is its unique name with the last MDX segment dropped. We group the
 * ordered list by parent (preserving order), then for the slicer member
 * step back one parent group and pick the child at the same index.
 *
 * Kept DOM/fetch-free so it is unit-testable (vitest "node" env).
 */

/** A level member as returned by /ai/members/search. */
export interface LevelMember {
  uniqueName: string;
  caption: string;
}

/** Split an MDX unique name into its bracketed segments.
 *  "[Time].[1997].[Q2]" -> ["[Time]", "[1997]", "[Q2]"].
 *  Falls back to a dot-split when the name isn't bracketed, so non-standard
 *  member keys still degrade gracefully rather than throwing. */
export function splitMemberSegments(uniqueName: string): string[] {
  const matches = uniqueName.match(/\[[^\]]*\]/g);
  if (matches && matches.length > 0) return matches;
  return uniqueName.split(".");
}

/** The parent unique name: the member's unique name with its last segment
 *  removed. Returns "" when there's only one segment (a root member with no
 *  parent we can step back from). */
export function parentKey(uniqueName: string): string {
  const segs = splitMemberSegments(uniqueName);
  if (segs.length <= 1) return "";
  return segs.slice(0, -1).join(".");
}

/**
 * Resolve the year-ago (parallel-period) member for {@code target} within a
 * chronologically-ordered flat list of members at the same level.
 *
 * Algorithm (matches the issue's proposed walk):
 *   1. parent  = target's parent prefix          ([Time].[1997])
 *   2. find the parent group that *precedes* it    ([Time].[1996])
 *   3. pick the child at the same index within     ([Time].[1996].[Q2])
 *      that predecessor parent group.
 *
 * Returns the resolved member's unique name, or {@code null} when no parallel
 * period exists (e.g. the target sits under the earliest parent, the parent
 * can't be determined, or the predecessor parent has fewer children than the
 * target's index). The caller renders the muted "no prior period" UX in that
 * case — same fallback PR #991 introduced.
 */
export function yearAgoMemberKey(
  target: string,
  orderedMembers: ReadonlyArray<LevelMember>,
): string | null {
  if (!target || orderedMembers.length === 0) return null;
  const targetParent = parentKey(target);
  if (!targetParent) return null;

  // Group the ordered members by parent, preserving first-seen order. Each
  // group keeps its members in their original (chronological) order.
  const groupOrder: string[] = [];
  const groups = new Map<string, LevelMember[]>();
  for (const m of orderedMembers) {
    const pk = parentKey(m.uniqueName);
    let g = groups.get(pk);
    if (!g) {
      g = [];
      groups.set(pk, g);
      groupOrder.push(pk);
    }
    g.push(m);
  }

  const parentIdx = groupOrder.indexOf(targetParent);
  if (parentIdx <= 0) return null; // unknown parent, or earliest parent → no year-ago.
  const prevParent = groupOrder[parentIdx - 1];
  const currentGroup = groups.get(targetParent)!;
  const prevGroup = groups.get(prevParent)!;

  const childIdx = currentGroup.findIndex((m) => m.uniqueName === target);
  if (childIdx < 0) return null;
  if (childIdx >= prevGroup.length) return null; // predecessor parent is shorter.

  return prevGroup[childIdx].uniqueName;
}

/**
 * Build the ordered row-member selection for a YoY query: each pinned slicer
 * member preceded by its year-ago counterpart, de-duplicated and returned in
 * the cube's chronological declaration order so the headline (latest) and
 * baseline (earlier) line up with {@link lastAndPriorValues}.
 *
 * When a slicer member has no resolvable year-ago counterpart it is still
 * included on its own — the tile then shows the muted "no prior period" UX.
 * Returns an empty array when nothing resolved (caller leaves rows untouched).
 */
export function expandYearOverYearRows(
  slicerMembers: ReadonlyArray<string>,
  orderedMembers: ReadonlyArray<LevelMember>,
): string[] {
  if (slicerMembers.length === 0 || orderedMembers.length === 0) return [];
  const wanted = new Set<string>();
  for (const m of slicerMembers) {
    wanted.add(m);
    const ago = yearAgoMemberKey(m, orderedMembers);
    if (ago) wanted.add(ago);
  }
  // Preserve the cube's declared order.
  return orderedMembers.filter((m) => wanted.has(m.uniqueName)).map((m) => m.uniqueName);
}
