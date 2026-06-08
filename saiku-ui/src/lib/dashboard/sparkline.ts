/*
 * Pure geometry for the table-tile sparkline column (issue #920).
 *
 * A sparkline is a tiny inline trend drawn from a single row's numeric
 * measure cells. This module owns ONLY the maths — turning a list of raw
 * values into normalised SVG coordinates (polyline points / a path
 * string, and per-bar rectangles) inside a fixed viewBox. No DOM, no
 * fetch: TableTile.svelte collects the row's numeric values, calls these
 * helpers, and renders the returned coordinates with inline <svg> using
 * theme tokens for the stroke. Keeping it here means it can be
 * unit-tested without a DOM (vitest node env), mirroring the sibling
 * $lib/dashboard/conditionalFormat.ts engine.
 *
 * Coordinate system: x runs left→right across the viewBox width, evenly
 * spaced over the points; y is inverted so the LARGEST value sits at the
 * top (small y) — the natural reading for a trend line. A `pad` inset
 * keeps the stroke from clipping at the box edges.
 *
 * Degenerate handling:
 *   - fewer than 2 finite points  → not renderable (caller shows a dash).
 *   - all points equal (flat)     → a centred horizontal line.
 */

/** A single normalised point inside the sparkline viewBox. */
export interface SparkPoint {
  x: number;
  y: number;
}

/** Geometry options. All optional; sensible compact defaults. */
export interface SparkOptions {
  /** viewBox width in user units. Defaults to 100. */
  width?: number;
  /** viewBox height in user units. Defaults to 24. */
  height?: number;
  /** Inset (in user units) kept clear on every edge so the stroke and
   *  end-point markers are not clipped. Defaults to 2. */
  pad?: number;
}

/** Fully-resolved geometry for one row's sparkline. {@code renderable} is
 *  false when there are fewer than two finite values — the caller renders
 *  a placeholder dash instead. */
export interface SparkGeometry {
  renderable: boolean;
  width: number;
  height: number;
  /** Normalised points, left→right. Empty when not renderable. */
  points: SparkPoint[];
  /** {@code points} as an SVG polyline `points` attribute string
   *  ("x1,y1 x2,y2 …"). Empty string when not renderable. */
  polyline: string;
  /** {@code points} as an SVG path `d` string ("M x y L x y …"). Empty
   *  string when not renderable. */
  path: string;
  /** The first / last drawn point — handy for an end marker. Null when
   *  not renderable. */
  first: SparkPoint | null;
  last: SparkPoint | null;
  /** The min / max of the finite input values (data space, pre-scaling).
   *  Null when not renderable. */
  min: number | null;
  max: number | null;
}

const DEFAULT_WIDTH = 100;
const DEFAULT_HEIGHT = 24;
const DEFAULT_PAD = 2;

/** Coerce an arbitrary value to a finite number, or null. Mirrors the
 *  coercion used by the conditional-format engine: raw numbers pass
 *  through; numeric strings have spaces / thousands-commas stripped;
 *  everything else (null, NaN, non-numeric strings) yields null. */
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

/** Extract the finite numeric subset of a value list, preserving order. */
export function numericValues(values: readonly unknown[]): number[] {
  const out: number[] = [];
  for (const v of values) {
    const n = toNumber(v);
    if (n !== null) out.push(n);
  }
  return out;
}

/** Round to 2 decimals to keep the emitted SVG strings compact and
 *  deterministic (avoids long floating-point tails in attributes). */
function r2(n: number): number {
  return Math.round(n * 100) / 100;
}

function resolveOpts(opts?: SparkOptions): Required<SparkOptions> {
  const width = opts?.width ?? DEFAULT_WIDTH;
  const height = opts?.height ?? DEFAULT_HEIGHT;
  const pad = opts?.pad ?? DEFAULT_PAD;
  return { width, height, pad };
}

/** Map finite values to normalised points inside the (padded) viewBox.
 *  Returns an empty array for fewer than two finite values. y is inverted
 *  (max at top). A flat series maps to the vertical centre. */
export function sparklinePoints(values: readonly number[], opts?: SparkOptions): SparkPoint[] {
  if (values.length < 2) return [];
  const { width, height, pad } = resolveOpts(opts);

  let min = values[0];
  let max = values[0];
  for (const v of values) {
    if (v < min) min = v;
    if (v > max) max = v;
  }

  const innerW = Math.max(0, width - pad * 2);
  const innerH = Math.max(0, height - pad * 2);
  const span = max - min;
  const stepX = innerW / (values.length - 1);

  return values.map((v, i) => {
    const x = pad + stepX * i;
    // span === 0 → flat line through the vertical centre.
    const t = span === 0 ? 0.5 : (v - min) / span;
    // Invert: t=1 (max) → top (y=pad); t=0 (min) → bottom (y=height-pad).
    const y = pad + (1 - t) * innerH;
    return { x: r2(x), y: r2(y) };
  });
}

/** Serialise points to an SVG polyline `points` attribute string. */
export function pointsToPolyline(points: readonly SparkPoint[]): string {
  return points.map((p) => `${p.x},${p.y}`).join(" ");
}

/** Serialise points to an SVG path `d` string (move to first, line to rest). */
export function pointsToPath(points: readonly SparkPoint[]): string {
  if (points.length === 0) return "";
  const [head, ...rest] = points;
  let d = `M ${head.x} ${head.y}`;
  for (const p of rest) d += ` L ${p.x} ${p.y}`;
  return d;
}

/** One bar in a bar-style sparkline, normalised to the viewBox. */
export interface SparkBar {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Map finite values to evenly-spaced bars inside the (padded) viewBox.
 *  Bars grow upward from the baseline; the tallest value fills the inner
 *  height. A flat (all-equal) non-zero series yields full-height bars; an
 *  all-zero series yields zero-height bars. Returns an empty array for an
 *  empty input (a single value is still drawable as one bar). */
export function sparklineBars(values: readonly number[], opts?: SparkOptions): SparkBar[] {
  if (values.length === 0) return [];
  const { width, height, pad } = resolveOpts(opts);

  let max = values[0];
  let min = values[0];
  for (const v of values) {
    if (v > max) max = v;
    if (v < min) min = v;
  }
  // Baseline is zero when the data straddles / sits at zero from below,
  // else the smallest value — keeps the visual honest for all-positive
  // and all-negative series alike. For the compact sparkline we scale
  // height by max magnitude from the baseline.
  const base = Math.min(0, min);
  const top = Math.max(0, max);
  const range = top - base;

  const innerW = Math.max(0, width - pad * 2);
  const innerH = Math.max(0, height - pad * 2);
  const slot = innerW / values.length;
  const barW = Math.max(0, slot * 0.7);
  const gap = (slot - barW) / 2;

  return values.map((v, i) => {
    const x = pad + slot * i + gap;
    const h = range === 0 ? innerH : (Math.abs(v - base) / range) * innerH;
    const y = pad + (innerH - h);
    return { x: r2(x), y: r2(y), width: r2(barW), height: r2(h) };
  });
}

/** Top-level: turn a row's raw cell values into renderable sparkline
 *  geometry. Non-numeric cells are dropped; fewer than two finite values
 *  yields {@code renderable: false} so the caller can show a dash. */
export function sparklineGeometry(values: readonly unknown[], opts?: SparkOptions): SparkGeometry {
  const { width, height } = resolveOpts(opts);
  const nums = numericValues(values);
  if (nums.length < 2) {
    return {
      renderable: false,
      width,
      height,
      points: [],
      polyline: "",
      path: "",
      first: null,
      last: null,
      min: null,
      max: null,
    };
  }
  const points = sparklinePoints(nums, opts);
  let min = nums[0];
  let max = nums[0];
  for (const n of nums) {
    if (n < min) min = n;
    if (n > max) max = n;
  }
  return {
    renderable: true,
    width,
    height,
    points,
    polyline: pointsToPolyline(points),
    path: pointsToPath(points),
    first: points[0] ?? null,
    last: points[points.length - 1] ?? null,
    min,
    max,
  };
}
