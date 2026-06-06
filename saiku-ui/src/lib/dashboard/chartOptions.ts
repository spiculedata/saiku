/*
 * AiQueryResponse → ECharts option adapter for dashboard chart tiles.
 *
 * Since #1076 the actual ECharts option for all 15 chart types is built by the
 * single shared builder in `$lib/charts/build` — the same one the workspace
 * (ChartView.svelte) uses. This module is now just the dashboard *adapter*:
 *   1. projectFromAiQueryResponse() — turn an AiQueryResponse into the shared
 *      {rowCategories, columnCategories, matrix} projection.
 *   2. buildChartOption() — project, then call the shared builder in `compact`
 *      mode (tile-tight cosmetics) with the dashboard's current baseline
 *      ChartOptions, so tiles render exactly as before.
 *
 * Tile-specific chart options (dual-axis, trend lines, title, …) flow in once
 * the chart-tile editor (#1077) is wired; today the baseline reproduces the
 * pre-#1076 dashboard look (single axis, no trend, bottom scroll legend).
 *
 * Pure: no DOM, no fetches. Tests live alongside.
 */

import type { AiCell, AiQueryResponse } from "$lib/api/aiQuery";
import type { ChartOptions } from "$lib/views/chartTypes";
import { DEFAULT_CHART_OPTIONS, isChartType } from "$lib/views/chartTypes";
import { type ThemeTokens, DEFAULT_THEME_TOKENS } from "$lib/views/chartTheme";
import { buildChartOption as buildSharedChartOption, type ChartProjection } from "$lib/charts/build";

/** All chart kinds the workspace exposes. Kept as an alias to the canonical
 *  ChartType so existing imports (`import type { ChartKind }`) still resolve. */
export type { ChartType as ChartKind } from "$lib/views/chartTypes";

/** Re-exported supported-kind guard (single source of truth in chartTypes.ts). */
export const isSupportedChartKind = isChartType;

/** Dashboard baseline chart options — reproduces the pre-#1076 tile look:
 *  single y-axis, no trend line, no overall title, bottom scroll legend. The
 *  chart-tile editor (#1077) will override these per tile. */
const DASHBOARD_BASELINE: ChartOptions = {
  ...DEFAULT_CHART_OPTIONS,
  title: "",
  trendLine: "none",
  dualAxis: false,
  hideRollupRows: false,
};

function isAiCell(v: unknown): v is AiCell {
  return typeof v === "object" && v !== null && "formatted" in (v as object);
}

/** Normalise an AiQueryResponse into the shared {rowCategories,
 *  columnCategories, matrix} projection — the same shape ChartView's
 *  parseCellset path produces, so both surfaces feed the builder identically. */
export function projectFromAiQueryResponse(response: AiQueryResponse): ChartProjection {
  const rows = response.data ?? [];
  if (rows.length === 0) return { rowCategories: [], columnCategories: [], matrix: [] };

  // Pick row-header columns (plain string keys) vs measure columns (AiCell
  // keys) from the first row's shape — insertion order.
  const firstRow = rows[0];
  const headerKeys: string[] = [];
  const measureKeys: string[] = [];
  for (const k of Object.keys(firstRow)) {
    if (isAiCell(firstRow[k])) measureKeys.push(k);
    else headerKeys.push(k);
  }

  const rowCategories = rows.map((r, i) => {
    const parts = headerKeys.map((k) => String(r[k] ?? "")).filter((s) => s.length > 0);
    return parts.length > 0 ? parts.join(" / ") : `row ${i + 1}`;
  });

  const matrix = rows.map((r) =>
    measureKeys.map((k) => {
      const v = r[k];
      return isAiCell(v) ? v.value : null;
    }),
  );

  return { rowCategories, columnCategories: measureKeys, matrix };
}

/**
 * Build an ECharts option object for a dashboard chart tile from an
 * AiQueryResponse. Returns null when the response has no rows or the kind
 * isn't recognised.
 *
 * `aspect` (canvas w/h) keeps small-multiple radii on-screen-constant; `tk`
 * carries the active theme tokens (chartTheme.ts) so tiles repaint on a
 * light/dark/system flip. Both default to keep the pure callers (tests)
 * DOM-free and structurally unchanged.
 */
export function buildChartOption(
  response: AiQueryResponse,
  kind: string,
  aspect = 1,
  tk: ThemeTokens = DEFAULT_THEME_TOKENS,
): Record<string, unknown> | null {
  if (!isChartType(kind)) return null;
  const projection = projectFromAiQueryResponse(response);
  if (projection.matrix.length === 0) return null;
  return buildSharedChartOption(projection, kind, DASHBOARD_BASELINE, tk, { aspect, compact: true });
}
