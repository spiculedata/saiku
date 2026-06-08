/*
 * Top-N / Bottom-N filter resolution (#921). Pure (no DOM/fetch) so it
 * unit-tests cleanly; the panel component supplies the cube id, the member
 * catalogue (already fetched for the level), and runs the query.
 *
 * Strategy: run a ranking query (the AI Query API turns order+limit into
 * TopCount/BottomCount), which returns the ranked member CAPTIONS in
 * metadata.rows; map those captions back to their MDX unique names via the
 * level's member catalogue (uniqueName+caption pairs) so the result flows
 * through the existing filter-merge unchanged.
 */

/** The AI-query request body that ranks a level's members by `measure` and
 *  keeps the top (desc) or bottom (asc) `n`. `measure` is the measure name the
 *  AI API accepts (display or canonical). */
export function buildTopNQuery(
  cubeId: string,
  dimension: string,
  hierarchy: string,
  level: string,
  measure: string,
  n: number,
  direction: "top" | "bottom",
): Record<string, unknown> {
  return {
    cube: cubeId,
    measures: [{ name: measure }],
    rows: [{ dimension, hierarchy, level }],
    order: [{ by: measure, direction: direction === "bottom" ? "asc" : "desc" }],
    limit: Math.max(1, Math.floor(n)),
  };
}

/** Map ranked member captions to their MDX unique names via the level's
 *  catalogue, preserving the ranked order and dropping any caption with no
 *  catalogue match (so a stale/renamed member can't poison the filter). */
export function rankedCaptionsToUniqueNames(
  rankedCaptions: string[],
  catalogue: ReadonlyArray<{ uniqueName: string; caption: string }>,
): string[] {
  const byCaption = new Map<string, string>();
  for (const m of catalogue) {
    if (!byCaption.has(m.caption)) {
      byCaption.set(m.caption, m.uniqueName);
    }
  }
  const out: string[] = [];
  for (const caption of rankedCaptions) {
    const unique = byCaption.get(caption);
    if (unique) {
      out.push(unique);
    }
  }
  return out;
}

/** Clamp a user-entered N to a sane range (1..1000). */
export function clampTopN(n: number | undefined | null): number {
  if (n === undefined || n === null || !Number.isFinite(n)) return 10;
  return Math.min(1000, Math.max(1, Math.floor(n)));
}
