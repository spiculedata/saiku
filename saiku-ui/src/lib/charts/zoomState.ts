/*
 * Pure dataZoom / selection-state reconcile helper (#1092).
 *
 * #1080 added a cartesian dataZoom (zoom + pan window). But every re-render path
 * in the chart components — the ResizeObserver re-render, a `theme.effective`
 * flip, a debounced data refresh — calls `chart.setOption(...)` with a freshly
 * built option whose dataZoom defaults back to the full [0,100] range. The window
 * the user dragged to (and any active brush selection) snaps back. That's the
 * "twitchy" feeling the issue describes.
 *
 * Fix shape: BEFORE re-rendering, the component captures the live state via
 * `chart.getOption()`; AFTER setOption it re-applies the captured zoom/brush —
 * but ONLY when the chart is still "the same chart" (same type, same number of
 * categories). A genuinely new dataset (chart-type switch, new query with a
 * different category count) must start fresh at full range, not mis-zoomed onto
 * stale coordinates.
 *
 * This module is the pure brain of that decision: no echarts import, no DOM. It
 * takes plain snapshots (what `getOption()` returns is plain JSON-ish data) and
 * returns either the slices to re-apply or null. The side effects — calling
 * getOption / setOption on the live instance — stay in the .svelte components,
 * mirroring the build.ts-is-pure convention.
 */

/** A minimal description of "what is currently drawn", taken before a re-render
 *  and compared against the same shape after. Type + category count are the
 *  identity we gate re-application on. */
export interface ChartIdentity {
  /** The chart kind (bar/line/area/scatter/…); a switch must reset zoom. */
  type: string;
  /** Number of categories on the zoomable (x) axis; a different count means a
   *  different dataset, so the old [start,end] window no longer maps cleanly. */
  categoryCount: number;
}

/** The slice of `getOption()` we preserve across a re-render. Both keys are
 *  optional: a chart may have a dataZoom but no brush, or neither. Shapes are
 *  left as `unknown[]` because they are echarts-defined option arrays we only
 *  pass straight back into setOption — we never introspect their internals. */
export interface PreservedState {
  dataZoom?: unknown[];
  brush?: unknown;
  // Index signature so the object satisfies echarts' ECBasicOption (which is an
  // open record) when fed straight into setOption — we still only ever set the
  // two keys above.
  [key: string]: unknown;
}

/* ECharts' getOption() always normalises a component to an array (even a single
 * dataZoom becomes `dataZoom: [ {...} ]`). We narrow defensively because the
 * object is `unknown` at the call boundary. */
type OptionLike = {
  dataZoom?: unknown;
  brush?: unknown;
  series?: unknown;
} | null | undefined;

function asArray(v: unknown): unknown[] | undefined {
  if (Array.isArray(v)) return v;
  if (v == null) return undefined;
  // A bare object (rare) is wrapped so callers always get an array to re-apply.
  return [v];
}

/**
 * Pull the preserve-worthy slices out of a live `getOption()` snapshot.
 *
 * Returns `null` when there is nothing worth preserving — no dataZoom AND no
 * brush — so the caller can skip the re-apply entirely (a fresh chart, or a
 * non-cartesian kind that never had a zoom). A captured dataZoom is only useful
 * if it is actually narrowed; we keep it regardless and let `shouldReapply`
 * decide, because even a full-range window is cheap to re-set and keeping the
 * check in one place is clearer.
 */
export function captureZoomState(option: OptionLike): PreservedState | null {
  if (!option) return null;
  const dataZoom = asArray(option.dataZoom);
  const brush = option.brush;
  if ((!dataZoom || dataZoom.length === 0) && brush == null) return null;
  const out: PreservedState = {};
  if (dataZoom && dataZoom.length > 0) out.dataZoom = dataZoom;
  if (brush != null) out.brush = brush;
  return out;
}

/**
 * Count the categories on the first series' source so two snapshots can be
 * compared for "same dataset". We read the xAxis `data` length when present
 * (cartesian charts carry the category labels there); falling back to the
 * first series' `data` length covers pie/radar-ish shapes. Returns 0 when
 * nothing is countable — two zero counts still compare equal, which is the
 * safe (don't-reapply-onto-nothing) default.
 */
function categoryCountOf(option: OptionLike): number {
  if (!option) return 0;
  const xAxis = asArray((option as { xAxis?: unknown }).xAxis);
  if (xAxis && xAxis.length > 0) {
    const first = xAxis[0] as { data?: unknown };
    if (Array.isArray(first?.data)) return first.data.length;
  }
  const series = asArray(option.series);
  if (series && series.length > 0) {
    const first = series[0] as { data?: unknown };
    if (Array.isArray(first?.data)) return first.data.length;
  }
  return 0;
}

/**
 * Build the {type, categoryCount} identity from a live option snapshot. The
 * caller usually already knows the *type* from its own component state (the
 * `ChartType` prop), so it can pass that in directly; this overload reads it
 * from the option only as a convenience for tests / symmetry.
 */
export function identityOf(option: OptionLike, type: string): ChartIdentity {
  return { type, categoryCount: categoryCountOf(option) };
}

/**
 * The reconcile decision. Re-apply the preserved zoom/brush ONLY when the chart
 * is still the same chart: same type AND same category count. A type switch
 * (bar → line) or a new dataset with a different number of categories resets to
 * full range, because the saved [start,end] percentages would land on the wrong
 * data — the very "mis-zoom" the issue warns against.
 *
 * Note we compare on `categoryCount`, not on the literal category labels: a
 * theme flip / resize keeps the same labels, and a same-shape data refresh
 * (same level, refreshed numbers) legitimately keeps the user's window. A
 * different *count* is the cheap, robust signal that the dataset changed.
 */
export function shouldReapply(prev: ChartIdentity, next: ChartIdentity): boolean {
  return prev.type === next.type && prev.categoryCount === next.categoryCount;
}

/**
 * Convenience for the components: given the live option BEFORE re-render, the
 * NEWLY built option, and the (unchanged-by-definition) chart type, return the
 * slices to feed back into `setOption(slices, { notMerge: false })` — or null
 * to apply nothing (fresh chart, or the dataset changed).
 *
 * Pure: callers do the getOption()/setOption() I/O; this only decides.
 */
export function reconcileZoomState(
  prevOption: OptionLike,
  nextOption: OptionLike,
  type: string,
): PreservedState | null {
  const preserved = captureZoomState(prevOption);
  if (!preserved) return null;
  const prev = identityOf(prevOption, type);
  const next = identityOf(nextOption, type);
  return shouldReapply(prev, next) ? preserved : null;
}
