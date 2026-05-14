/**
 * Pure helpers that translate date-filter UI state into Mondrian MDX set
 * expressions. Extracted from DateFilterModal.svelte so they can be unit
 * tested without mounting the component.
 *
 * MVP assumption: the target hierarchy is time-ordered and either
 *   (a) has a single ISO-date leaf level (e.g. `[Time].[2024-01-15]`), or
 *   (b) is a Foodmart-style Year/Quarter/Month/Day calendar where we can
 *       construct `[Time].[<year>].[Q<q>].[<monthName>].[<day>]`.
 * Most of the relative presets don't require member-path construction — they
 * rely on MDX functions like `PeriodsToDate`, `LastPeriods`, and
 * `<Level>.CurrentMember`. Absolute ranges use the `:` range operator so the
 * caller gets an inclusive set back from whatever pair of members we emit.
 *
 * TODO: `levelType` (from the OLAP metadata) would tell us unambiguously
 * whether a level is a Time-Day / Time-Month etc. Until `discover.ts` exposes
 * that, callers fall back to caption heuristics.
 */

export type RelativePreset =
  | "TODAY"
  | "YESTERDAY"
  | "LAST_N_DAYS"
  | "LAST_N_WEEKS"
  | "LAST_N_MONTHS"
  | "LAST_N_QUARTERS"
  | "LAST_N_YEARS"
  | "ROLLING_N"
  | "MONTH_TO_DATE"
  | "QUARTER_TO_DATE"
  | "YEAR_TO_DATE";

/** One of the Foodmart-style period level-grain hints we understand. The
 *  "day" grain is the default and what Absolute ranges always use. */
export type LevelGrain = "day" | "week" | "month" | "quarter" | "year";

export interface RelativeOptions {
  preset: RelativePreset;
  /** Count for LAST_N_* / ROLLING_N presets. Required for those, ignored otherwise. */
  n?: number;
  /** Grain hint from the chip — picked up for ROLLING_N when preset alone is ambiguous. */
  grain?: LevelGrain;
  /** Hierarchy unique name, e.g. `[Time].[Time]`. */
  hierarchy: string;
  /** Level unique name of the chip the user right-clicked, e.g. `[Time].[Time].[Day]`. */
  level: string;
}

export interface AbsoluteOptions {
  /** ISO date, inclusive. */
  from: string;
  /** ISO date, inclusive. */
  to: string;
  /** Hierarchy unique name, e.g. `[Time].[Time]`. */
  hierarchy: string;
  /** Level unique name — used when we need to construct member paths. */
  level: string;
  /** Whether to also emit a prior-period / prior-year comparison set. */
  compare?: "NONE" | "PRIOR_PERIOD" | "PRIOR_YEAR";
}

const MONTH_NAMES = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

/** Heuristic: if the level unique name's last segment looks ISO-date-like,
 *  build a single `[hier].[<iso>]` member. Otherwise build the Foodmart
 *  `[hier].[<year>].[Q<q>].[<month>].[<day>]` path. Conservative: defaults
 *  to the ISO leaf shape because it's a single member reference that at
 *  worst yields an MDX error the user can see in the preview. */
function looksIsoLeaf(levelUniqueName: string): boolean {
  const last = levelUniqueName.split(".").pop() ?? "";
  const cleaned = last.replace(/[[\]]/g, "").toLowerCase();
  // Rough signal: "date" or "day" tokens suggest a single-grain date level.
  // Year / Quarter / Month tokens suggest Foodmart-shape.
  if (cleaned.includes("year") || cleaned.includes("quarter") || cleaned.includes("month")) {
    return false;
  }
  return true;
}

/** Build the MDX member reference for a single calendar date, on the level
 *  described by `level`. Exported for tests. */
export function memberForDate(iso: string, hierarchy: string, level: string): string {
  const [y, m, d] = iso.split("-").map((s) => Number.parseInt(s, 10));
  if (!y || !m || !d) return `${hierarchy}.[${iso}]`;
  if (looksIsoLeaf(level)) {
    return `${hierarchy}.[${iso}]`;
  }
  // Foodmart-shape: Year / Quarter / Month / Day. Day segments are numeric
  // with no leading zero; months are full English names; quarters are Q1-Q4.
  const q = Math.ceil(m / 3);
  const monthName = MONTH_NAMES[m - 1];
  return `${hierarchy}.[${y}].[Q${q}].[${monthName}].[${d}]`;
}

/** Absolute range → `{ <fromMember> : <toMember> }`. If a comparison is
 *  requested we union the shifted set onto the primary range. */
export function buildAbsoluteMdx(opts: AbsoluteOptions): string {
  const from = memberForDate(opts.from, opts.hierarchy, opts.level);
  const to = memberForDate(opts.to, opts.hierarchy, opts.level);
  const primary = `{${from} : ${to}}`;
  const compare = opts.compare ?? "NONE";
  if (compare === "NONE") return primary;
  // Period counts — how many days the primary range spans — used to shift
  // via `ParallelPeriod`/`Lag` on the day level.
  const days = daysBetweenInclusive(opts.from, opts.to);
  if (compare === "PRIOR_PERIOD") {
    const shiftedFrom = `${from}.Lag(${days})`;
    const shiftedTo = `${to}.Lag(${days})`;
    return `UNION(${primary}, {${shiftedFrom} : ${shiftedTo}})`;
  }
  // PRIOR_YEAR — 365 is good enough for MVP (leap-year off-by-one is fine
  // because Mondrian snaps to the nearest existing member).
  const shiftedFrom = `${from}.Lag(365)`;
  const shiftedTo = `${to}.Lag(365)`;
  return `UNION(${primary}, {${shiftedFrom} : ${shiftedTo}})`;
}

function daysBetweenInclusive(fromIso: string, toIso: string): number {
  const a = Date.parse(fromIso);
  const b = Date.parse(toIso);
  if (Number.isNaN(a) || Number.isNaN(b)) return 0;
  return Math.max(0, Math.round((b - a) / 86_400_000)) + 1;
}

/** The `<Level>.CurrentMember` reference for MDX idioms that need "now". */
function currentMemberFor(hierarchy: string): string {
  return `${hierarchy}.CurrentMember`;
}

/** Relative preset → MDX set. Never throws; if a preset requires `n` and it's
 *  missing/invalid, we substitute 1 so the preview still compiles. Callers
 *  should gate Apply on `isRelativeValid` below. */
export function buildRelativeMdx(opts: RelativeOptions): string {
  const n = Math.max(1, Math.floor(opts.n ?? 1));
  const cm = currentMemberFor(opts.hierarchy);
  switch (opts.preset) {
    case "TODAY":
      return `{${cm}}`;
    case "YESTERDAY":
      return `{${cm}.Lag(1)}`;
    case "LAST_N_DAYS":
    case "LAST_N_WEEKS":
    case "LAST_N_MONTHS":
    case "LAST_N_QUARTERS":
    case "LAST_N_YEARS":
    case "ROLLING_N":
      // `LastPeriods(N, <level>.CurrentMember)` — level is implicit from the
      // current-member reference, which is exactly the chip the user right-
      // clicked. Clean, grain-agnostic, no path construction needed.
      return `LastPeriods(${n}, ${opts.level}.CurrentMember)`;
    case "MONTH_TO_DATE":
      return `PeriodsToDate([Time].[Month], ${cm})`;
    case "QUARTER_TO_DATE":
      return `PeriodsToDate([Time].[Quarter], ${cm})`;
    case "YEAR_TO_DATE":
      return `PeriodsToDate([Time].[Year], ${cm})`;
  }
}

export function isRelativeValid(opts: RelativeOptions): boolean {
  const needsN =
    opts.preset === "LAST_N_DAYS" ||
    opts.preset === "LAST_N_WEEKS" ||
    opts.preset === "LAST_N_MONTHS" ||
    opts.preset === "LAST_N_QUARTERS" ||
    opts.preset === "LAST_N_YEARS" ||
    opts.preset === "ROLLING_N";
  if (needsN) {
    return typeof opts.n === "number" && Number.isFinite(opts.n) && opts.n > 0;
  }
  return true;
}

export function isAbsoluteValid(opts: Pick<AbsoluteOptions, "from" | "to">): boolean {
  if (!opts.from || !opts.to) return false;
  const a = Date.parse(opts.from);
  const b = Date.parse(opts.to);
  if (Number.isNaN(a) || Number.isNaN(b)) return false;
  return a <= b;
}

/** Heuristic gate for the menu item: "does this hierarchy look time-like?"
 *  based on caption tokens. Deliberately permissive — false positives just
 *  mean the user sees a preview that probably won't execute, which is
 *  recoverable; false negatives hide the feature outright. */
export function looksLikeTimeHierarchy(caption: string | undefined): boolean {
  if (!caption) return true; // err on the side of showing
  const c = caption.toLowerCase();
  return (
    c.includes("date") ||
    c.includes("time") ||
    c.includes("day") ||
    c.includes("week") ||
    c.includes("month") ||
    c.includes("quarter") ||
    c.includes("year")
  );
}
