/*
 * Pure number-formatting helper for chart value text (#1082).
 *
 * Charts (workspace + dashboard tiles) feed every value-axis label, tooltip
 * value and data label through {@link formatNumber} so users can add a prefix
 * (£, $), a suffix (%, ms), pin a number of decimals, group thousands and/or
 * abbreviate large magnitudes (k / M / B / T).
 *
 * Inert by default: with no {@link NumberFormat} (or an all-empty one) the value
 * is rendered exactly as `String(value)` was before — so legacy charts are
 * byte-for-byte unchanged. Dependency-free; no DOM, no Intl beyond the built-in
 * Number.prototype.toLocaleString grouping.
 */

export interface NumberFormat {
  /** Prepended verbatim (e.g. "£", "$"). */
  prefix?: string;
  /** Appended verbatim (e.g. "%", " ms"). */
  suffix?: string;
  /** Fixed number of decimal places (toFixed). null/undefined = auto (as-is). */
  decimals?: number | null;
  /** Group integer digits with locale thousands separators. */
  thousands?: boolean;
  /** Collapse large magnitudes to k / M / B / T with a compact suffix. */
  abbreviate?: boolean;
}

/** Magnitude thresholds for abbreviation, largest first. */
const ABBREVIATIONS: { value: number; symbol: string }[] = [
  { value: 1e12, symbol: "T" },
  { value: 1e9, symbol: "B" },
  { value: 1e6, symbol: "M" },
  { value: 1e3, symbol: "k" },
];

/** True when the format has nothing to apply — render as plain String(value). */
function isInert(fmt?: NumberFormat): boolean {
  if (!fmt) return true;
  return (
    !fmt.prefix &&
    !fmt.suffix &&
    (fmt.decimals === null || fmt.decimals === undefined) &&
    !fmt.thousands &&
    !fmt.abbreviate
  );
}

/** Apply fixed decimals when set, else leave the natural precision. */
function applyDecimals(n: number, decimals: number | null | undefined): { text: string; fixed: boolean } {
  if (decimals === null || decimals === undefined || !Number.isFinite(decimals)) {
    return { text: String(n), fixed: false };
  }
  const d = Math.max(0, Math.min(20, Math.floor(decimals)));
  return { text: n.toFixed(d), fixed: true };
}

/** Group the integer part with the host locale's thousands separators,
 *  preserving any decimal places already decided by {@link applyDecimals}. */
function groupThousands(n: number, decimals: number | null | undefined): string {
  if (decimals !== null && decimals !== undefined && Number.isFinite(decimals)) {
    const d = Math.max(0, Math.min(20, Math.floor(decimals)));
    return n.toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d });
  }
  // No fixed decimals: group but keep the natural fractional digits (up to the
  // toLocaleString cap, which is generous enough for chart values).
  return n.toLocaleString(undefined, { maximumFractionDigits: 20 });
}

/**
 * Format a numeric chart value.
 *
 * Order of operations: abbreviate (if on) → decimals/thousands → prefix/suffix.
 * Null / NaN / non-finite → "—" (em-dash) so axis labels and tooltips never
 * show "null" or "NaN".
 */
export function formatNumber(value: number | null | undefined, fmt?: NumberFormat): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  if (isInert(fmt)) return String(value);

  const f = fmt as NumberFormat;
  let core: string;

  if (f.abbreviate) {
    const abs = Math.abs(value);
    const hit = ABBREVIATIONS.find((a) => abs >= a.value);
    if (hit) {
      const scaled = value / hit.value;
      // Abbreviated numbers default to 1 decimal when the caller hasn't pinned
      // one (so 1_500 → "1.5k", not "2k"); an explicit decimals value wins.
      const dec = f.decimals === null || f.decimals === undefined ? 1 : f.decimals;
      const { text } = applyDecimals(scaled, dec);
      core = text + hit.symbol;
    } else {
      core = formatPlain(value, f);
    }
  } else {
    core = formatPlain(value, f);
  }

  return `${f.prefix ?? ""}${core}${f.suffix ?? ""}`;
}

/** Decimals + thousands for a non-abbreviated value. */
function formatPlain(value: number, f: NumberFormat): string {
  if (f.thousands) return groupThousands(value, f.decimals);
  return applyDecimals(value, f.decimals).text;
}
