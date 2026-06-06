/*
 * Pure inline-SVG sparkline builder (#1087).
 *
 * The cartesian chart tooltip (bar/line/area, trigger "axis") enriches the
 * existing value text with a tiny line of the hovered series across ALL
 * categories — so a single hovered bar is read in the context of its whole
 * trend, not in isolation. The ECharts tooltip FUNCTION formatter returns a
 * string that ECharts inserts as innerHTML (renderMode "html") and does NOT
 * escape; this module produces that SVG fragment.
 *
 * SECURITY (repo lesson, mirrors the #1071 map-tooltip hardening): the
 * formatter return is innerHTML and un-escaped. So this builder NEVER accepts
 * caller markup — it builds the path purely from numeric data, and the one
 * place a caller-supplied string can appear (the optional aria label / title)
 * is HTML-escaped here. Series/category names interpolated by the formatter
 * itself must be escaped by the caller too; this module just guarantees that
 * what IT emits is safe.
 *
 * Pure: no DOM, no echarts, no fetch. Tests live alongside.
 */

/** Escape the five HTML-significant characters. Kept local so this module has
 *  zero imports and stays trivially testable / reusable. */
export function escapeHtml(s: string): string {
  return s.replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] ?? c,
  );
}

export interface SparklineOptions {
  /** Pixel width of the <svg>. Default 120. */
  width?: number;
  /** Pixel height of the <svg>. Default 28. */
  height?: number;
  /** Stroke colour of the line. Sanitised to a safe colour token. Default "#888". */
  color?: string;
  /** Index of the hovered point to mark with a dot (-1 / out of range = none). */
  highlightIndex?: number;
  /** Optional accessible title; HTML-escaped before insertion. */
  title?: string;
}

/* A colour coming from the theme/series palette is interpolated into an SVG
 * attribute, so constrain it to shapes that cannot break out of the attribute
 * or smuggle script: #hex, rgb()/rgba(), or a bare CSS identifier (named
 * colour). Anything else falls back to a neutral grey. */
const SAFE_COLOR = /^(#[0-9a-fA-F]{3,8}|rgba?\([0-9.,%\s]+\)|[a-zA-Z]+)$/;

function safeColor(c: string | undefined): string {
  if (c && SAFE_COLOR.test(c.trim())) return c.trim();
  return "#888";
}

/** Round to at most 2 dp and strip a trailing ".0" — keeps the path compact
 *  and the output deterministic for snapshot/equality tests. */
function r2(n: number): number {
  return Math.round(n * 100) / 100;
}

/**
 * Build a self-contained inline <svg> sparkline from a numeric series.
 *
 * Returns "" when there is nothing meaningful to draw (no series, or fewer
 * than two finite points — a single point is not a line). `null`/`NaN`/±Inf
 * entries are treated as gaps: the polyline simply skips them, so a series
 * with holes still renders its real points without inventing zeros.
 *
 * The geometry is computed entirely from the numbers (min/max normalised into
 * the box, y inverted because SVG's origin is top-left), so no data-derived
 * STRING is ever placed into markup except the optional, escaped `title`.
 */
export function buildSparklineSvg(
  series: ReadonlyArray<number | null | undefined>,
  opts: SparklineOptions = {},
): string {
  const width = opts.width && opts.width > 0 ? opts.width : 120;
  const height = opts.height && opts.height > 0 ? opts.height : 28;
  const pad = 2; // keep the stroke + dot inside the viewBox
  const color = safeColor(opts.color);

  const vals = series ?? [];
  // Index → finite value, preserving position so x spacing stays even.
  const finite: { i: number; v: number }[] = [];
  for (let i = 0; i < vals.length; i++) {
    const v = vals[i];
    if (v != null && Number.isFinite(v)) finite.push({ i, v: v as number });
  }
  if (finite.length < 2) return "";

  let min = Infinity;
  let max = -Infinity;
  for (const { v } of finite) {
    if (v < min) min = v;
    if (v > max) max = v;
  }
  const span = max - min;
  const n = vals.length;
  const innerW = width - pad * 2;
  const innerH = height - pad * 2;

  const xAt = (i: number) => pad + (n <= 1 ? innerW / 2 : (i / (n - 1)) * innerW);
  // Flat series (span 0) sits on the vertical centre.
  const yAt = (v: number) => pad + (span === 0 ? innerH / 2 : (1 - (v - min) / span) * innerH);

  const points = finite.map(({ i, v }) => `${r2(xAt(i))},${r2(yAt(v))}`).join(" ");

  // Optional highlight dot on the hovered point — only if it's a finite point.
  let dot = "";
  const hi = opts.highlightIndex;
  if (hi != null && hi >= 0 && hi < n) {
    const hit = finite.find((p) => p.i === hi);
    if (hit) {
      dot = `<circle cx="${r2(xAt(hit.i))}" cy="${r2(yAt(hit.v))}" r="2.5" fill="${color}"/>`;
    }
  }

  const titleEl = opts.title ? `<title>${escapeHtml(opts.title)}</title>` : "";

  return (
    `<svg width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" ` +
    `xmlns="http://www.w3.org/2000/svg" style="display:block">` +
    titleEl +
    `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="1.5" ` +
    `stroke-linejoin="round" stroke-linecap="round"/>` +
    dot +
    `</svg>`
  );
}
