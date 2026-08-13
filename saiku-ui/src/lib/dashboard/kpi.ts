/*
 * Pure helpers for the KPI tile.
 *
 * Format selection, threshold-to-colour mapping, and prior-period delta
 * calculation live here so the renderer (KpiTile.svelte) stays a thin
 * dispatcher and these branches are unit-testable without a DOM.
 */

import type {
  KpiComparison,
  KpiDirection,
  KpiFormat,
  KpiThresholds,
  TimeLevelRef,
} from "$lib/api/dashboards";

/* ------------------------------ delta label ---------------------------- *
 * What the callout after the percentage says.
 *
 * This used to be author free-text with a generic "vs prior" fallback, and
 * nothing tied it to the query: FoodMart Ops shipped four KPIs that all
 * aggregated at MONTH grain while announcing "vs last Thu". The number was
 * right and the sentence describing it was wrong, which is worse than an
 * obviously broken tile.
 *
 * So the label is DERIVED from the comparison + the time level the tile
 * actually queries. `deltaSuffix` still overrides it for the cases a cube's
 * own vocabulary needs ("vs last trading day"), but the default can no longer
 * disagree with the data.
 * ----------------------------------------------------------------------- */

/** An i18n key plus its English fallback — callers resolve through i18n.t. */
export interface DeltaLabel {
  key: string;
  fallback: string;
}

/** Coarse time grain of a level name. Checked most-specific first so
 *  "Week of Year" reads as a week and "Day Of Season" as a day. */
function grainOf(level: string | undefined): string | null {
  const l = (level ?? "").toLowerCase();
  for (const g of ["day", "week", "month", "quarter", "year"]) {
    if (l.includes(g)) return g;
  }
  return null;
}

/** The delta callout for a comparison at a given time level. */
export function deltaLabelFor(
  comparison: KpiComparison | undefined,
  timeLevel: Pick<TimeLevelRef, "level"> | undefined,
): DeltaLabel {
  if (comparison === "target") return { key: "dashboard.kpi.vsTarget", fallback: "vs target" };
  if (comparison === "year-over-year") {
    return { key: "dashboard.kpi.vsLastYear", fallback: "vs last year" };
  }
  switch (grainOf(timeLevel?.level)) {
    case "day":
      return { key: "dashboard.kpi.vsYesterday", fallback: "vs yesterday" };
    case "week":
      return { key: "dashboard.kpi.vsLastWeek", fallback: "vs last week" };
    case "month":
      return { key: "dashboard.kpi.vsLastMonth", fallback: "vs last month" };
    case "quarter":
      return { key: "dashboard.kpi.vsLastQuarter", fallback: "vs last quarter" };
    case "year":
      return { key: "dashboard.kpi.vsLastYear", fallback: "vs last year" };
    default:
      // No time level configured — nothing to name, so stay generic.
      return { key: "dashboard.kpi.vsPrior", fallback: "vs prior" };
  }
}

/** Apply the KPI format choice to a raw numeric value. Returns a
 *  display string. Locale-aware via Intl.NumberFormat — falls back to
 *  the browser default; for SSR / unit tests, jsdom honours "en-US".
 *
 *  Custom pattern handling is intentionally minimal: a percent-sign
 *  suffix or currency-prefix shorthand ("$", "€", "£") is detected
 *  and routed to Intl.NumberFormat; anything else is treated as a
 *  digit-count hint (number of fractional digits). Full d3-format
 *  support would be a bigger lift than this PR; the existing format
 *  cards cover the four cases the issue calls out.
 *
 *  `locale` is an override for tests and for callers that pin a locale;
 *  undefined keeps the runtime default. */
export function formatKpi(
  value: number | null | undefined,
  format: KpiFormat | undefined,
  customFormat?: string,
  locale?: string,
): string {
  if (value == null || Number.isNaN(value)) return "—";
  switch (format) {
    case "currency":
      return new Intl.NumberFormat(locale, {
        ...CURRENCY_BASE,
        currency: "USD",
        maximumFractionDigits: 0,
      }).format(value);
    case "percent":
      return new Intl.NumberFormat(locale, {
        style: "percent",
        maximumFractionDigits: 1,
      }).format(value);
    case "custom":
      return formatCustom(value, customFormat ?? "", locale);
    case "number":
    default:
      return new Intl.NumberFormat(locale, {
        maximumFractionDigits: 2,
      }).format(value);
  }
}

/** Shared currency options.
 *
 *  `currencyDisplay: "narrowSymbol"` is load-bearing, not cosmetic. The author
 *  picks the symbol by typing it ("$c1"), so USD outside the en-US locale must
 *  still render "$" — the default "symbol" display disambiguates it to "US$"
 *  (and "A$", "CA$"…), which turned the FoodMart Ops headline KPI into
 *  "US$57k". narrowSymbol drops the region qualifier while leaving digit
 *  grouping and symbol placement locale-correct. */
const CURRENCY_BASE = {
  style: "currency",
  currencyDisplay: "narrowSymbol",
} as const satisfies Intl.NumberFormatOptions;

function formatCustom(value: number, pattern: string, locale?: string): string {
  const p = pattern.trim();
  if (!p) return new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
  if (p.endsWith("%")) {
    const digits = parseFractionDigits(p.slice(0, -1));
    return new Intl.NumberFormat(locale, {
      style: "percent",
      maximumFractionDigits: digits,
      minimumFractionDigits: digits,
    }).format(value);
  }
  // Compact currency, e.g. "$c1" -> "$48.2K". The "c" selects compact notation;
  // the trailing digit count sets the fraction digits. Both bounds are pinned:
  // compact notation otherwise drops a trailing zero, so "$c1" on 57000 reads
  // "$57K" where the author asked for one decimal ("$57.0K").
  const compactMatch = p.match(/^([$€£¥])c(\d*)$/);
  if (compactMatch) {
    const symbol = compactMatch[1];
    const digits = parseFractionDigits(compactMatch[2]);
    return new Intl.NumberFormat(locale, {
      ...CURRENCY_BASE,
      currency: currencyCodeFor(symbol),
      notation: "compact",
      maximumFractionDigits: digits,
      minimumFractionDigits: digits,
    }).format(value);
  }
  const currencyMatch = p.match(/^([$€£¥])(\d*)$/);
  if (currencyMatch) {
    const symbol = currencyMatch[1];
    const digits = parseFractionDigits(currencyMatch[2]);
    return new Intl.NumberFormat(locale, {
      ...CURRENCY_BASE,
      currency: currencyCodeFor(symbol),
      maximumFractionDigits: digits,
      minimumFractionDigits: digits,
    }).format(value);
  }
  // Bare digit count, e.g. "3" -> 3 fractional digits.
  const digitsOnly = parseFractionDigits(p);
  return new Intl.NumberFormat(locale, {
    maximumFractionDigits: digitsOnly,
    minimumFractionDigits: digitsOnly,
  }).format(value);
}

function parseFractionDigits(s: string): number {
  const n = parseInt(s, 10);
  return Number.isFinite(n) && n >= 0 && n <= 10 ? n : 0;
}

function currencyCodeFor(symbol: string): string {
  switch (symbol) {
    case "$": return "USD";
    case "€": return "EUR";
    case "£": return "GBP";
    case "¥": return "JPY";
    default: return "USD";
  }
}

/** Bands the value into a CSS custom property name (one of --kpi-red /
 *  --kpi-yellow / --kpi-green / undefined). Direction flips the band
 *  comparisons — for "lower-is-better", *smaller* values map to green.
 *
 *  Semantics: green and yellow are explicit cutoffs; red is an
 *  open-ended fallback ("worse than yellow"). This makes the colour
 *  band a visual classifier rather than a strict membership check —
 *  an out-of-bounds value still flags red instead of silently going
 *  uncoloured.
 *
 *  Example (direction = higher-is-better, thresholds = {red:0, yellow:50, green:100}):
 *    value >= 100 → green
 *    50 <= value < 100 → yellow
 *    value < 50 (and red configured) → red — including values below 0
 *
 *  Returns undefined when no thresholds are configured at all, or when
 *  value is null. If only green/yellow are configured and the value
 *  misses both, also returns undefined (no red opt-in). */
export function kpiThresholdToken(
  value: number | null | undefined,
  thresholds: KpiThresholds | undefined,
  direction: KpiDirection = "higher-is-better",
): "--kpi-red" | "--kpi-yellow" | "--kpi-green" | undefined {
  if (value == null || Number.isNaN(value)) return undefined;
  if (!thresholds) return undefined;
  const { red, yellow, green } = thresholds;
  if (red == null && yellow == null && green == null) return undefined;
  const cmp = direction === "higher-is-better"
    ? (v: number, t: number) => v >= t
    : (v: number, t: number) => v <= t;
  // Evaluate from "best" to "worst". green and yellow are strict
  // boundaries; red is the open-ended fallback so a wildly bad value
  // doesn't silently fall off the colour scale.
  if (green != null && cmp(value, green)) return "--kpi-green";
  if (yellow != null && cmp(value, yellow)) return "--kpi-yellow";
  if (red != null) return "--kpi-red";
  return undefined;
}

export interface KpiDelta {
  /** Fractional change: (current - prior) / prior. NaN/Infinity become null. */
  ratio: number | null;
  /** Direction-aware classification — used to pick the arrow + colour. */
  tone: "positive" | "negative" | "flat";
}

/** Compute the delta between two KPI values, with the direction baked
 *  into the tone (so "lower-is-better" + delta < 0 → "positive"). Used
 *  for both prior-period and target comparisons. */
export function kpiDelta(
  current: number | null | undefined,
  baseline: number | null | undefined,
  direction: KpiDirection = "higher-is-better",
): KpiDelta {
  if (current == null || baseline == null || baseline === 0) {
    return { ratio: null, tone: "flat" };
  }
  const ratio = (current - baseline) / baseline;
  if (!Number.isFinite(ratio)) return { ratio: null, tone: "flat" };
  if (ratio === 0) return { ratio, tone: "flat" };
  const better = direction === "higher-is-better" ? ratio > 0 : ratio < 0;
  return { ratio, tone: better ? "positive" : "negative" };
}

/** Return the last two numeric cells from a time-sorted result series.
 *  Used by the KPI tile when comparison is "prior-period": the latest
 *  cell becomes the headline number, the second-to-last becomes the
 *  baseline. Cells with null value are skipped so a blank "no data"
 *  row doesn't poison the comparison. */
export function lastAndPriorValues(
  series: ReadonlyArray<{ value: number | null }>,
): { current: number | null; prior: number | null } {
  const nonNull: number[] = [];
  for (const c of series) {
    if (c.value != null && Number.isFinite(c.value)) nonNull.push(c.value);
  }
  if (nonNull.length === 0) return { current: null, prior: null };
  if (nonNull.length === 1) return { current: nonNull[nonNull.length - 1], prior: null };
  return {
    current: nonNull[nonNull.length - 1],
    prior: nonNull[nonNull.length - 2],
  };
}
