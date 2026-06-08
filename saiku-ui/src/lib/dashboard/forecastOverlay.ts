/*
 * Issue #908 — overlay a forecast horizon onto a built ECharts option.
 *
 * The /ai/forecast endpoint returns the observed response UNCHANGED plus a
 * sibling block keyed by measure caption: { caption: ForecastPoint[] }. This
 * module extends the chart by:
 *   - appending `horizon` future category labels to the x-axis,
 *   - adding, per measure series, a DASHED continuation line (the point
 *     forecast) that connects from the last observed point, and
 *   - a shaded confidence band (lower..upper) via the standard two-series
 *     stack trick (transparent base at `lower`, visible range of `upper-lower`).
 *
 * Pure + ECharts-agnostic (plain option objects) so the geometry is unit-tested
 * without a browser; ChartTile calls {@link applyForecastOverlay} after the
 * shared builder produces the base option.
 */

import type { ForecastPoint } from "$lib/api/aiQuery";

/** Future x-axis labels for a horizon: ["+1", "+2", … "+h"]. */
export function forecastLabels(horizon: number): string[] {
  const out: string[] = [];
  for (let h = 1; h <= horizon; h++) out.push("+" + h);
  return out;
}

/** A minimal ECharts series shape (only the fields we set). */
export interface OverlaySeries {
  name: string;
  type: "line";
  data: (number | null)[];
  lineStyle?: Record<string, unknown>;
  itemStyle?: Record<string, unknown>;
  areaStyle?: Record<string, unknown>;
  symbol?: string;
  symbolSize?: number;
  stack?: string;
  silent?: boolean;
  z?: number;
  tooltip?: Record<string, unknown>;
  emphasis?: Record<string, unknown>;
}

/**
 * Build the overlay series for ONE measure: a dashed forecast line plus the
 * confidence-band base + range. All arrays are left-padded with `observedCount`
 * nulls so they sit under the appended future categories; the slot at
 * `observedCount - 1` (the last observed point) is seeded so the dashed line +
 * band visually connect to the real data instead of floating.
 *
 * @param name measure series name (matches the observed series' name)
 * @param color the observed series' colour (forecast reuses it, dashed)
 * @param points the horizon forecast points
 * @param observedCount number of observed categories (forecast starts after)
 * @param lastObserved the last observed value (connection anchor), or null
 */
export function buildForecastSeries(
  name: string,
  color: string,
  points: ForecastPoint[],
  observedCount: number,
  lastObserved: number | null,
): OverlaySeries[] {
  const total = observedCount + points.length;
  const line: (number | null)[] = new Array(total).fill(null);
  const base: (number | null)[] = new Array(total).fill(null);
  const range: (number | null)[] = new Array(total).fill(null);

  const anchor = observedCount - 1;
  if (anchor >= 0 && lastObserved != null && Number.isFinite(lastObserved)) {
    line[anchor] = lastObserved;
    base[anchor] = lastObserved;
    range[anchor] = 0;
  }
  for (let i = 0; i < points.length; i++) {
    const p = points[i];
    const idx = observedCount + i;
    line[idx] = p.value;
    base[idx] = p.lower;
    range[idx] = Math.max(0, p.upper - p.lower);
  }

  const stack = `forecast-band-${name}`;
  return [
    // Transparent base sitting at `lower`.
    {
      name: `${name} (forecast band base)`,
      type: "line",
      data: base,
      stack,
      symbol: "none",
      silent: true,
      z: 1,
      lineStyle: { opacity: 0 },
      areaStyle: { opacity: 0 },
      tooltip: { show: false },
      emphasis: { disabled: true },
    },
    // Visible range (upper - lower) stacked on top of the base.
    {
      name: `${name} (confidence)`,
      type: "line",
      data: range,
      stack,
      symbol: "none",
      silent: true,
      z: 1,
      lineStyle: { opacity: 0 },
      areaStyle: { color, opacity: 0.13 },
      tooltip: { show: false },
      emphasis: { disabled: true },
    },
    // The dashed point-forecast line.
    {
      name: `${name} (forecast)`,
      type: "line",
      data: line,
      symbol: "circle",
      symbolSize: 5,
      z: 3,
      lineStyle: { type: "dashed", color, width: 2 },
      itemStyle: { color },
    },
  ];
}

/** Last finite value in a numeric/nullable series array, or null. */
export function lastFinite(data: unknown): number | null {
  if (!Array.isArray(data)) return null;
  for (let i = data.length - 1; i >= 0; i--) {
    const v = data[i];
    if (typeof v === "number" && Number.isFinite(v)) return v;
    // ECharts data points can be {value} objects.
    if (v && typeof v === "object" && typeof (v as { value?: unknown }).value === "number") {
      const n = (v as { value: number }).value;
      if (Number.isFinite(n)) return n;
    }
  }
  return null;
}

/**
 * Mutate a built ECharts option in place, appending the forecast horizon. Safe
 * no-op when the option has no category x-axis or no matching series. Returns
 * the number of measure series a forecast was applied to (0 = nothing changed).
 */
export function applyForecastOverlay(
  option: Record<string, unknown>,
  series: Record<string, ForecastPoint[]>,
  defaultColor: string,
): number {
  const keys = Object.keys(series ?? {});
  if (keys.length === 0) return 0;
  const horizon = Math.max(...keys.map((k) => series[k]?.length ?? 0));
  if (!Number.isFinite(horizon) || horizon <= 0) return 0;

  // x-axis may be an object or an array of axes; find the category axis.
  const rawAxis = (option as { xAxis?: unknown }).xAxis;
  const axis = Array.isArray(rawAxis) ? rawAxis[0] : rawAxis;
  if (!axis || typeof axis !== "object") return 0;
  const axisData = (axis as { data?: unknown[] }).data;
  if (!Array.isArray(axisData)) return 0;
  const observedCount = axisData.length;

  const chartSeries = (option as { series?: unknown }).series;
  if (!Array.isArray(chartSeries)) return 0;

  const extra: OverlaySeries[] = [];
  let applied = 0;
  for (const s of chartSeries) {
    if (!s || typeof s !== "object") continue;
    const name = (s as { name?: unknown }).name;
    if (typeof name !== "string" || !(name in series)) continue;
    const pts = series[name];
    if (!pts || pts.length === 0) continue;
    const color =
        ((s as { itemStyle?: { color?: unknown } }).itemStyle?.color as string) ??
        ((s as { color?: unknown }).color as string) ??
        defaultColor;
    const lastObserved = lastFinite((s as { data?: unknown }).data);
    extra.push(...buildForecastSeries(name, color, pts, observedCount, lastObserved));
    applied++;
  }
  if (applied === 0) return 0;

  // Append future category labels + the overlay series.
  axisData.push(...forecastLabels(horizon));
  chartSeries.push(...extra);
  return applied;
}
