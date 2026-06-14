/*
 * issue #1084 — threshold-driven conditional formatting for chart series.
 *
 * Pure evaluator: given a data point's numeric value and a measure's
 * {@link ChartConditionalFormat} band, decide the colour override (if any).
 * Semantics (per the issue):
 *   - rules are tried in order; the FIRST whose comparison matches wins;
 *   - a finite value matching no rule gets `fallbackColor` (or no override);
 *   - a null / non-finite value never matches a rule (gaps keep the palette).
 *
 * No DOM, no ECharts — just the colour decision. build.ts maps it onto each
 * data point's `itemStyle.color`; tests live alongside.
 */

import type { ChartCondRule, ChartConditionalFormat } from "$lib/views/chartTypes";

/** Does a single rule match a finite value? */
export function matchRule(value: number, rule: ChartCondRule): boolean {
  if (!Number.isFinite(value)) return false;
  const v = rule.value;
  switch (rule.op) {
    case "gt":
      return typeof v === "number" && value > v;
    case "gte":
      return typeof v === "number" && value >= v;
    case "lt":
      return typeof v === "number" && value < v;
    case "lte":
      return typeof v === "number" && value <= v;
    case "between": {
      if (!Array.isArray(v) || v.length !== 2) return false;
      const [min, max] = v;
      const lo = Math.min(min, max);
      const hi = Math.max(min, max);
      return value >= lo && value <= hi; // inclusive
    }
    default:
      return false;
  }
}

/** Resolve the colour for a data point under a measure's conditional-format
 *  band. Returns the first matching rule's colour, else the band's
 *  `fallbackColor`, else `undefined` (no override → keep the palette colour).
 *  `null` / non-finite values always return `undefined`. */
export function colorForValue(
  value: number | null | undefined,
  cf: ChartConditionalFormat | undefined,
): string | undefined {
  if (!cf || !Array.isArray(cf.rules) || cf.rules.length === 0) {
    // No rules → fallback only applies when there ARE rules; with none, the
    // band is inert (keeps palette) even if a stray fallbackColor is set.
    return undefined;
  }
  if (value === null || value === undefined || !Number.isFinite(value)) return undefined;
  for (const rule of cf.rules) {
    if (matchRule(value, rule)) return rule.color;
  }
  return cf.fallbackColor || undefined;
}

/** Find the conditional-format band targeting a given measure/series index. */
export function conditionalFormatForMeasure(
  formats: ChartConditionalFormat[] | undefined,
  measureIndex: number,
): ChartConditionalFormat | undefined {
  if (!formats) return undefined;
  return formats.find((f) => f.measureIndex === measureIndex);
}
