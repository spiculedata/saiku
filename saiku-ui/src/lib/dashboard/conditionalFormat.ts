/*
 * Pure rule-evaluation engine for per-column conditional formatting on
 * dashboard table tiles (issue #919). Display-only: the underlying data
 * is never mutated — the helper only computes a style/class/icon for a
 * single cell given the column's full value set and a rule.
 *
 * The Svelte components (TableTile.svelte, TileEditorModal.svelte) are
 * intentionally thin: they collect the numeric values of a column, hand
 * a (rule, value, columnValues) triple to {@link evaluateCell}, and apply
 * the returned {@link CellFormat} verbatim. All the maths lives here so
 * it can be unit-tested without a DOM.
 *
 * Threshold modes:
 *   - "relative": thresholds are percentiles (0–100) of the column's
 *     own non-null values. Used to band a column by its own distribution.
 *   - "absolute": thresholds are fixed numeric values compared directly.
 *
 * Format types:
 *   - "background": red/yellow/green cell background by band.
 *   - "font":       font colour by band, or by sign when no thresholds.
 *   - "icon":       ↑ ↓ → glyph by band.
 *   - "bar":        horizontal data-bar width 0–100% of the column range.
 */

import type { ConditionalFormatRule } from "$lib/api/dashboards";

/** The computed presentation for a single cell. All fields optional —
 *  the renderer applies only what is set. {@code backgroundColor} /
 *  {@code color} map to inline CSS; {@code icon} is a glyph to prepend;
 *  {@code barWidthPct} (0–100) drives a mini data-bar overlay. */
export interface CellFormat {
  backgroundColor?: string;
  color?: string;
  icon?: string;
  /** Bar width as a percentage of the cell, clamped to 0–100. Only set
   *  for "bar" rules with a finite value. */
  barWidthPct?: number;
  barColor?: string;
}

/** The three threshold bands, low → high. {@code none} means the value
 *  was not classifiable (null / NaN / no usable thresholds). */
export type Band = "low" | "mid" | "high" | "none";

/** Default band palette — picked to read as red (bad/low) → yellow
 *  (mid) → green (good/high). Overridable per rule via
 *  {@link ConditionalFormatRule.colors}. */
export const DEFAULT_BAND_COLORS: Record<Exclude<Band, "none">, string> = {
  low: "#e5534b", // red
  mid: "#d9a420", // amber
  high: "#3fa45b", // green
};

/**
 * saiku#1775 — data-bar fill.
 *
 * This used to be a bare `#4c8dff`, so a table on a cyan/amber App Builder theme
 * grew blue bars that read as a foreign element (the docs promise custom tiles
 * "follow the App theme"). Resolve against the App shell's accent token and keep
 * the old blue as the fallback, so dashboards outside an App — where the token
 * isn't defined — render exactly as before.
 */
const DEFAULT_BAR_COLOR = "var(--saiku-app-accent, #4c8dff)";

/** Coerce an arbitrary cell value into a finite number, or null. Accepts
 *  raw numbers and numeric strings (commas / spaces stripped); everything
 *  else — null, undefined, NaN, non-numeric strings — yields null. */
export function toNumber(value: unknown): number | null {
  if (value == null) return null;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string") {
    const cleaned = value.replace(/[\s,]/g, "").trim();
    if (cleaned === "") return null;
    const n = Number(cleaned);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** Extract the finite numeric subset of a column's values, preserving
 *  order. Used as the basis for percentile / min-max computations. */
export function numericValues(values: readonly unknown[]): number[] {
  const out: number[] = [];
  for (const v of values) {
    const n = toNumber(v);
    if (n !== null) out.push(n);
  }
  return out;
}

/** Linear-interpolated percentile of a numeric array. {@code p} is in
 *  0–100. Returns null for an empty array. Matches the common
 *  "linear interpolation between closest ranks" definition so that
 *  p=0 is the min, p=100 the max, p=50 the median. */
export function percentile(values: readonly number[], p: number): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 1) return sorted[0];
  const clampedP = Math.min(100, Math.max(0, p));
  const rank = (clampedP / 100) * (sorted.length - 1);
  const lo = Math.floor(rank);
  const hi = Math.ceil(rank);
  if (lo === hi) return sorted[lo];
  const frac = rank - lo;
  return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
}

/** Classify {@code value} into a low/mid/high band given two cut points.
 *  Boundaries are inclusive on the upper edge of each lower band:
 *   value < lowCut          → "low"
 *   lowCut <= value < highCut → "mid"
 *   value >= highCut        → "high"
 *  Returns "none" when either cut is null or the value isn't finite. */
export function classifyByCuts(value: number | null, lowCut: number | null, highCut: number | null): Band {
  if (value === null || lowCut === null || highCut === null) return "none";
  // Guard against inverted cuts (analyst typed high < low): normalise.
  const lo = Math.min(lowCut, highCut);
  const hi = Math.max(lowCut, highCut);
  if (value < lo) return "low";
  if (value < hi) return "mid";
  return "high";
}

/** Resolve the (lowCut, highCut) pair a rule implies, given the column's
 *  numeric values. For "relative" rules the thresholds are percentiles
 *  evaluated against {@code columnNumbers}; for "absolute" they are used
 *  directly. Returns nulls when the rule lacks usable thresholds. */
export function resolveCuts(
  rule: ConditionalFormatRule,
  columnNumbers: readonly number[],
): { lowCut: number | null; highCut: number | null } {
  const lowT = rule.lowThreshold;
  const highT = rule.highThreshold;
  if (lowT == null || highT == null) return { lowCut: null, highCut: null };
  if (rule.thresholdMode === "relative") {
    return {
      lowCut: percentile(columnNumbers, lowT),
      highCut: percentile(columnNumbers, highT),
    };
  }
  // absolute
  return { lowCut: lowT, highCut: highT };
}

/** Band → colour, honouring per-rule overrides then the default palette. */
export function bandColor(band: Band, rule: ConditionalFormatRule): string | undefined {
  if (band === "none") return undefined;
  const override = rule.colors?.[band];
  return override ?? DEFAULT_BAND_COLORS[band];
}

/** Compute the data-bar width (0–100%) for {@code value} relative to the
 *  column's min/max. The bar fills proportionally between min (0%) and
 *  max (100%). When all values are equal the bar is full (100%) for any
 *  finite value. Returns null for non-finite values or an empty column. */
export function barWidthPct(value: number | null, columnNumbers: readonly number[]): number | null {
  if (value === null || columnNumbers.length === 0) return null;
  let min = columnNumbers[0];
  let max = columnNumbers[0];
  for (const n of columnNumbers) {
    if (n < min) min = n;
    if (n > max) max = n;
  }
  if (max === min) return 100;
  const pct = ((value - min) / (max - min)) * 100;
  return Math.min(100, Math.max(0, pct));
}

/** Icon glyphs by band for the "icon" format. Up for high, down for low,
 *  flat for mid. */
const BAND_ICON: Record<Exclude<Band, "none">, string> = {
  low: "↓", // ↓
  mid: "→", // →
  high: "↑", // ↑
};

/** Sign-based icon: positive ↑, negative ↓, zero →. */
function signIcon(value: number): string {
  if (value > 0) return BAND_ICON.high;
  if (value < 0) return BAND_ICON.low;
  return BAND_ICON.mid;
}

/** Sign-based colour: positive → high colour, negative → low colour,
 *  zero → undefined (no emphasis). Honours per-rule overrides. */
function signColor(value: number, rule: ConditionalFormatRule): string | undefined {
  if (value > 0) return bandColor("high", rule);
  if (value < 0) return bandColor("low", rule);
  return undefined;
}

/** Core: evaluate a single cell against one rule, given the full set of
 *  the column's (raw) values. Returns an empty object when the rule
 *  doesn't apply (non-numeric cell, missing thresholds where required).
 *  Pure — no mutation of inputs. */
export function evaluateCell(
  rule: ConditionalFormatRule,
  cellValue: unknown,
  columnValues: readonly unknown[],
): CellFormat {
  const value = toNumber(cellValue);
  const columnNumbers = numericValues(columnValues);

  switch (rule.type) {
    case "bar": {
      const width = barWidthPct(value, columnNumbers);
      if (width === null) return {};
      return { barWidthPct: width, barColor: rule.barColor ?? DEFAULT_BAR_COLOR };
    }
    case "background": {
      const { lowCut, highCut } = resolveCuts(rule, columnNumbers);
      const band = classifyByCuts(value, lowCut, highCut);
      const bg = bandColor(band, rule);
      return bg ? { backgroundColor: bg } : {};
    }
    case "font": {
      if (value === null) return {};
      // No thresholds → colour by sign. With thresholds → colour by band.
      if (rule.lowThreshold == null || rule.highThreshold == null) {
        const c = signColor(value, rule);
        return c ? { color: c } : {};
      }
      const { lowCut, highCut } = resolveCuts(rule, columnNumbers);
      const band = classifyByCuts(value, lowCut, highCut);
      const c = bandColor(band, rule);
      return c ? { color: c } : {};
    }
    case "icon": {
      if (value === null) return {};
      if (rule.lowThreshold == null || rule.highThreshold == null) {
        return { icon: signIcon(value) };
      }
      const { lowCut, highCut } = resolveCuts(rule, columnNumbers);
      const band = classifyByCuts(value, lowCut, highCut);
      if (band === "none") return {};
      return { icon: BAND_ICON[band] };
    }
    default:
      return {};
  }
}

/** Find the rule (if any) that targets {@code columnCaption}. Rules are
 *  keyed by the column caption shown in the table header. Returns the
 *  first match — the editor enforces one rule per column, but a defensive
 *  first-match keeps a malformed config from throwing. */
export function ruleForColumn(
  rules: readonly ConditionalFormatRule[] | undefined,
  columnCaption: string,
): ConditionalFormatRule | undefined {
  if (!rules || rules.length === 0) return undefined;
  return rules.find((r) => r.column === columnCaption);
}

/** Convenience: resolve + evaluate in one call for a given column. When
 *  no rule targets the column (or rules is empty/undefined) returns an
 *  empty CellFormat — the no-rules pass-through. */
export function formatCell(
  rules: readonly ConditionalFormatRule[] | undefined,
  columnCaption: string,
  cellValue: unknown,
  columnValues: readonly unknown[],
): CellFormat {
  const rule = ruleForColumn(rules, columnCaption);
  if (!rule) return {};
  return evaluateCell(rule, cellValue, columnValues);
}

/** Build an inline-style string from a {@link CellFormat}. Returns
 *  undefined when nothing would be applied so callers can pass it
 *  straight to a Svelte {@code style={...}} attribute. The data-bar is
 *  rendered as a left-anchored linear-gradient so it needs no extra DOM. */
export function cellFormatToStyle(fmt: CellFormat): string | undefined {
  const parts: string[] = [];
  if (fmt.color) parts.push(`color: ${fmt.color}`);
  if (fmt.backgroundColor) parts.push(`background-color: ${fmt.backgroundColor}`);
  if (fmt.barWidthPct != null) {
    const color = fmt.barColor ?? DEFAULT_BAR_COLOR;
    const w = Math.min(100, Math.max(0, fmt.barWidthPct));
    // Tinted fill from the left edge to w%, transparent beyond.
    parts.push(
      `background-image: linear-gradient(to right, ${color} 0%, ${color} ${w}%, transparent ${w}%, transparent 100%)`,
    );
  }
  return parts.length > 0 ? parts.join("; ") : undefined;
}
