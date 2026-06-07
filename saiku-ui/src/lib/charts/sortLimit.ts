/*
 * Client-side category sort + top-N limit for charts (#1083).
 *
 * Today sort/limit lives in the MDX query: to "show top 10 stores by Sales,
 * descending" a user has to rebuild the query. This module lets a chart re-order
 * and/or trim the CATEGORIES (the row axis) it shows WITHOUT re-querying — a pure
 * transform applied to the already-projected {@link ChartProjection} just before
 * the ECharts option is built. It only touches rows (categories); the series
 * (column-categories) and the per-cell values are left exactly as they came back.
 *
 * Scope (deliberately small — workspace-first):
 *   - sort by the first measure column, ascending / descending / off ("none"),
 *   - top-N: keep the first N rows AFTER sorting (so desc + N = the N highest,
 *     asc + N = the N lowest; N with sort off = the first N as queried).
 *
 * Stability: rows that compare equal keep their original relative order (a
 * stable sort), so toggling the control is predictable. Null / NaN measure
 * values sort to the END regardless of direction (a missing value isn't the
 * "biggest" or "smallest" — it just has no rank), again keeping original order
 * among themselves.
 *
 * NOT persisted: the control state is component-local/transient in the .svelte
 * surface (dashboard per-tile persistence is #1077, paused) — this module is
 * pure state-in / state-out and has no opinion about storage.
 *
 * Pure: no DOM, no ECharts, no fetches. Tests live alongside.
 */

import type { ChartProjection } from "$lib/charts/build";

/** Sort direction for the category axis. "none" = keep the queried order. */
export type ChartSortDirection = "none" | "asc" | "desc";

/** Component-local (transient) sort + limit state for one chart. */
export interface ChartSortLimit {
  /** Sort the categories by the chosen measure column, or leave as queried. */
  direction: ChartSortDirection;
  /** Which measure column to sort by (index into columnCategories). Clamped
   *  into range; out-of-range / missing falls back to the first column. */
  measureIndex?: number;
  /** Keep only the first N categories AFTER sorting. null / undefined / <=0 =
   *  no limit. Clamped to the available row count. */
  topN?: number | null;
}

/** The off / identity state — no sort, no limit. */
export const NO_SORT_LIMIT: ChartSortLimit = { direction: "none", measureIndex: 0, topN: null };

/** True when the state would change nothing (so callers can skip the work). */
export function isNoOp(s: ChartSortLimit, rowCount: number): boolean {
  const limits = s.topN != null && s.topN > 0 && s.topN < rowCount;
  return s.direction === "none" && !limits;
}

/* A row that compares "missing" (null / NaN) always sorts after a real value.
 * Two missing rows compare equal (0) so the stable sort keeps their order. */
function compareMeasure(a: number | null, b: number | null, direction: "asc" | "desc"): number {
  const aMissing = a == null || Number.isNaN(a);
  const bMissing = b == null || Number.isNaN(b);
  if (aMissing && bMissing) return 0;
  if (aMissing) return 1; // a after b
  if (bMissing) return -1; // b after a
  const diff = (a as number) - (b as number);
  return direction === "asc" ? diff : -diff;
}

/**
 * Re-order and/or trim the CATEGORY rows of a projection per the sort+limit
 * state. Series (columns) and cell values are unchanged. Returns a new
 * projection (the input is never mutated); when the state is a no-op the same
 * row order is returned (a fresh object, still safe to use).
 *
 * @param projection the already-projected {rowCategories, columnCategories, matrix}
 * @param state      component-local sort + top-N state
 */
/**
 * The display ORDER — a permutation of input row indices in the order the
 * categories will be shown (after sort, then top-N). Identity `[0..n-1]` when
 * the state is a no-op.
 *
 * Exposed separately from {@link applySortLimit} so click-to-drill (#1086) can
 * map a clicked chart category (its displayed index) back to the ORIGINAL
 * projection row it came from — otherwise a click after sorting/trimming would
 * drill the wrong cell.
 */
export function sortLimitOrder(
  projection: ChartProjection,
  state: ChartSortLimit = NO_SORT_LIMIT,
): number[] {
  const { rowCategories, columnCategories, matrix } = projection;
  const rowCount = rowCategories.length;

  // Index permutation so we move labels and matrix rows together in lockstep.
  let order = rowCategories.map((_, i) => i);

  if (state.direction === "asc" || state.direction === "desc") {
    // Clamp the measure column into range; default to the first measure. Array's
    // sort is stable (ES2019+), and we tie-break on original index defensively so
    // equal rows keep their queried order even on older engines.
    const colCount = columnCategories.length;
    const col =
      colCount > 0
        ? Math.min(Math.max(state.measureIndex ?? 0, 0), colCount - 1)
        : 0;
    order = order
      .map((idx) => ({ idx, value: matrix[idx]?.[col] ?? null }))
      .sort((a, b) => compareMeasure(a.value, b.value, state.direction as "asc" | "desc") || a.idx - b.idx)
      .map((e) => e.idx);
  }

  // top-N: keep the first N after sorting. Clamp to [1, rowCount]; null / <=0 =
  // no limit. (>= rowCount is also effectively no limit.)
  if (state.topN != null && state.topN > 0 && state.topN < rowCount) {
    order = order.slice(0, state.topN);
  }

  return order;
}

export function applySortLimit(
  projection: ChartProjection,
  state: ChartSortLimit = NO_SORT_LIMIT,
): ChartProjection {
  const { rowCategories, columnCategories, matrix } = projection;
  const order = sortLimitOrder(projection, state);
  return {
    rowCategories: order.map((i) => rowCategories[i]),
    columnCategories,
    matrix: order.map((i) => matrix[i]),
  };
}
