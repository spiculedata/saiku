/*
 * The single, canonical chart-option builder for Saiku (#1076).
 *
 * Before this module the workspace (ChartView.svelte) and the dashboard
 * (chartOptions.ts) each re-implemented the same 15 ECharts chart types with
 * subtly different configs — so dashboard tiles silently lacked dual-axis,
 * trend lines and rollup filtering, and every new feature meant two PRs that
 * kept drifting. This builder is the one place both surfaces go through.
 *
 * Both surfaces project their native result shape into a {@link ChartProjection}
 * ({rowCategories, columnCategories, matrix}) and call buildChartOption() with
 * the full {@link ChartOptions}, the active {@link ThemeTokens}, and a
 * {@link ChartGeometry}. The two surfaces differ only in *presentation density*,
 * captured by the single `compact` geometry flag:
 *
 *   - workspace (compact = false): roomy grid margins, configurable legend,
 *     geometry-derived axis-label truncation width, missing values drawn as 0.
 *   - dashboard tiles (compact = true): tile-tight margins, bottom scroll
 *     legend, fixed label width (+rotate when crowded), missing values as gaps.
 *
 * Surface-specific concerns stay OUT of here: cellset/AiQueryResponse parsing,
 * rollup-row filtering (needs cellset depth), the ECharts instance lifecycle,
 * click→drillthrough, host sizing, and reading live theme tokens from the DOM.
 *
 * Pure: no DOM, no fetches, no ECharts import. Tests live alongside.
 */

import type {
  ChartType,
  ChartOptions,
  ChartColorRamp,
  ReferenceLine,
  ReferenceBand,
  ComboSeriesType,
} from "$lib/views/chartTypes";
import { DEFAULT_CHART_OPTIONS, SERIES_AXIS_THRESHOLD, isChartType } from "$lib/views/chartTypes";
import { aliasGeoName } from "$lib/charts/geoMatch";
import { assignSeriesAxes } from "$lib/views/cellsetUtils";
import { axisLabelConfig, deriveAxisLabelWidth } from "$lib/views/chartAxisLabel";
import { cellRadiusPct, gridCells, MAX_LABELLED_SLICES } from "$lib/dashboard/smallMultiples";
import {
  type ThemeTokens,
  DEFAULT_THEME_TOKENS,
  COLORBLIND_WATERFALL,
  resolvePalette,
} from "$lib/views/chartTheme";
import { buildSparklineSvg } from "$lib/charts/sparkline";
import { formatNumber, type NumberFormat } from "$lib/charts/numberFormat";
// #1084: threshold-driven per-point colours.
import { colorForValue, conditionalFormatForMeasure } from "$lib/charts/chartConditionalFormat";

/** The shared, already-projected chart input both surfaces produce. Rollup
 *  rows are filtered (and parent context promoted into labels) by the caller
 *  BEFORE projection — the builder treats `rowCategories`/`matrix` as final. */
export interface ChartProjection {
  /** Category labels — one per row (the x-axis categories / pie slices). */
  rowCategories: string[];
  /** Series labels — one per measure column (the legend entries). */
  columnCategories: string[];
  /** Values: matrix[row][col]. `null` = a genuinely missing cell. */
  matrix: (number | null)[][];
  /** #1596: the row hierarchy's dimension/level name (e.g. "Year", "Product /
   *  Category") used as the DEFAULT category-axis title when the user hasn't set
   *  an explicit `xAxisLabel`. Derived from the query by the caller (the row
   *  header caption); omitted for legacy callers, in which case the category
   *  axis stays untitled unless overridden. The value axis needs no such field —
   *  its default title is the measure name(s) in `columnCategories`. */
  categoryAxisName?: string;
}

/** Canvas geometry + presentation density. `aspect` (w/h) keeps small-multiple
 *  radii on-screen-constant; `chartWidth` drives derived axis-label width in the
 *  roomy (non-compact) mode; `compact` selects the dashboard-tile cosmetics. */
export interface ChartGeometry {
  aspect?: number;
  chartWidth?: number;
  compact?: boolean;
}

/* ----------------------------- helpers ----------------------------- */

function legendConfig(o: ChartOptions, tk: ThemeTokens): Record<string, unknown> {
  if (!o.showLegend) return { show: false };
  const position: Record<string, unknown> = { textStyle: { color: tk.fg } };
  if (o.legendPosition === "top") position.top = 10;
  else if (o.legendPosition === "bottom") position.bottom = 10;
  else if (o.legendPosition === "left") {
    position.left = 10;
    position.top = "middle";
    position.orient = "vertical";
  } else if (o.legendPosition === "right") {
    position.right = 10;
    position.top = "middle";
    position.orient = "vertical";
  }
  return position;
}

/** Compact (dashboard-tile) legend: always a scroll legend (tiles are small,
 *  so many series must scroll), positioned by `legendPosition`. The default
 *  ("bottom") yields `{type:"scroll", bottom:0}` — exactly the pre-#1077 tile
 *  legend — so legacy tiles are unchanged; the chart-tile editor (#1077) can
 *  now move it. `showLegend:false` hides it. */
function compactLegend(o: ChartOptions, tk: ThemeTokens): Record<string, unknown> {
  if (!o.showLegend) return { show: false };
  const base: Record<string, unknown> = { type: "scroll", textStyle: { color: tk.fg } };
  if (o.legendPosition === "top") base.top = 0;
  else if (o.legendPosition === "left") {
    base.left = 0;
    base.top = "middle";
    base.orient = "vertical";
  } else if (o.legendPosition === "right") {
    base.right = 0;
    base.top = "middle";
    base.orient = "vertical";
  } else {
    base.bottom = 0; // "bottom" / default
  }
  return base;
}

// #1595: the title gets its own band pinned to the very top (a small `top`
// inset) so it reads as a header rather than floating into the plot. The
// cartesian grids below reserve `grid.top` room beneath this band via
// `TITLE_GRID_TOP` so the title never overlaps the top gridline / data.
function titleConfig(o: ChartOptions, tk: ThemeTokens): Record<string, unknown> | undefined {
  if (!o.title) return undefined;
  return { text: o.title, left: "center", top: 8, textStyle: { color: tk.fg } };
}

// #1595: grid.top (px) that reserves a clear band for the title above the plot
// in compact tiles. Roomy charts already reserve their own (title ? 50 : …).
const TITLE_GRID_TOP = 44;

function linearRegression(vals: (number | null)[]): number[] {
  const n = vals.length;
  let sumX = 0,
    sumY = 0,
    sumXY = 0,
    sumXX = 0,
    count = 0;
  for (let i = 0; i < n; i++) {
    const y = vals[i];
    if (y == null || !Number.isFinite(y)) continue;
    sumX += i;
    sumY += y;
    sumXY += i * y;
    sumXX += i * i;
    count += 1;
  }
  if (count < 2) return vals.map(() => 0);
  const meanX = sumX / count;
  const meanY = sumY / count;
  const denom = sumXX - sumX * meanX;
  const slope = denom === 0 ? 0 : (sumXY - sumX * meanY) / denom;
  const intercept = meanY - slope * meanX;
  return vals.map((_, i) => slope * i + intercept);
}

function movingAverage(vals: (number | null)[], period: number): (number | null)[] {
  const out: (number | null)[] = [];
  for (let i = 0; i < vals.length; i++) {
    if (i < period - 1) {
      out.push(null);
      continue;
    }
    let sum = 0;
    let c = 0;
    for (let k = i - period + 1; k <= i; k++) {
      const v = vals[k];
      if (v != null && Number.isFinite(v)) {
        sum += v;
        c += 1;
      }
    }
    out.push(c ? sum / c : null);
  }
  return out;
}

function weightedMovingAverage(vals: (number | null)[], period: number): (number | null)[] {
  const out: (number | null)[] = [];
  for (let i = 0; i < vals.length; i++) {
    if (i < period - 1) {
      out.push(null);
      continue;
    }
    let num = 0,
      den = 0;
    for (let k = 0; k < period; k++) {
      const v = vals[i - period + 1 + k];
      if (v != null && Number.isFinite(v)) {
        num += (k + 1) * v;
        den += k + 1;
      }
    }
    out.push(den ? num / den : null);
  }
  return out;
}

function trendSeries(
  name: string,
  vals: (number | null)[],
  o: ChartOptions,
  tk: ThemeTokens,
): Record<string, unknown>[] {
  if (o.trendLine === "none") return [];
  const period = Math.max(2, o.trendPeriod || 3);
  let data: (number | null)[] = [];
  let seriesName = name;
  if (o.trendLine === "linear") {
    data = linearRegression(vals);
    seriesName = `${name} (trend)`;
  } else if (o.trendLine === "ma") {
    data = movingAverage(vals, period);
    seriesName = `${name} (MA${period})`;
  } else if (o.trendLine === "wma") {
    data = weightedMovingAverage(vals, period);
    seriesName = `${name} (WMA${period})`;
  }
  return [
    {
      type: "line",
      name: seriesName,
      data,
      smooth: true,
      showSymbol: false,
      lineStyle: { type: "dashed", width: 2, color: tk.fgMuted },
      itemStyle: { color: tk.fgMuted },
    },
  ];
}

/* issue #1079: reference / annotation lines + bands for CARTESIAN charts.
 *
 * Both are ECharts series-level overlays attached to a single carrier series
 * (the first measure series, or a dedicated zero-data series when there are no
 * measures, so they always paint). They DON'T add data points — markLine and
 * markArea live alongside `data` on the series. Returning `undefined` when there
 * is nothing to draw keeps the carrier series byte-for-byte identical to the
 * pre-#1079 output (so existing charts and their snapshots are unchanged).
 *
 * A y-axis line → markLine data `{ yAxis: value }` (horizontal); an x-axis line
 * → `{ xAxis: value }` (the category index for a category x-axis). Bands become
 * markArea data PAIRS (`[{start},{end}]`). Default colour is the muted
 * foreground token; per-entry `color` overrides it. In compact (tile) mode the
 * label text is hidden to avoid clutter — the line/band still renders. The
 * builder stays pure: this only shapes plain option JSON. */
function markLineConfig(
  lines: ReferenceLine[] | undefined,
  tk: ThemeTokens,
  compact: boolean,
): Record<string, unknown> | undefined {
  if (!lines || lines.length === 0) return undefined;
  return {
    symbol: ["none", "none"],
    label: { show: !compact },
    data: lines.map((l) => {
      const color = l.color || tk.fgMuted;
      const point: Record<string, unknown> = l.axis === "x" ? { xAxis: l.value } : { yAxis: l.value };
      point.lineStyle = { color, type: "dashed" };
      if (l.label) point.name = l.label;
      if (!compact && l.label) {
        point.label = { show: true, formatter: l.label, color };
      }
      return point;
    }),
  };
}

function markAreaConfig(
  bands: ReferenceBand[] | undefined,
  tk: ThemeTokens,
  compact: boolean,
): Record<string, unknown> | undefined {
  if (!bands || bands.length === 0) return undefined;
  return {
    label: { show: !compact },
    data: bands.map((b) => {
      const color = b.color || tk.fgMuted;
      const axisKey = b.axis === "x" ? "xAxis" : "yAxis";
      const start: Record<string, unknown> = {
        [axisKey]: b.from,
        itemStyle: { color, opacity: 0.12 },
      };
      if (!compact && b.label) {
        start.name = b.label;
        start.label = { show: true, formatter: b.label, color };
      }
      const end: Record<string, unknown> = { [axisKey]: b.to };
      return [start, end];
    }),
  };
}

/* issue #1079: attach the prepared markLine/markArea overlays to a cartesian
 * series list. They ride on the FIRST series so they paint once (markLine on
 * every series would draw duplicate lines). When the list is empty (no measure
 * columns) a dedicated zero-data carrier series is appended so the annotations
 * still render. Mutating in place is safe — `series` is freshly mapped here. A
 * no-op (returns the list unchanged) when there are no marks, preserving legacy
 * output exactly. */
function attachMarks(
  series: Record<string, unknown>[],
  markLine: Record<string, unknown> | undefined,
  markArea: Record<string, unknown> | undefined,
): Record<string, unknown>[] {
  if (markLine === undefined && markArea === undefined) return series;
  const carrier =
    series.length > 0
      ? series[0]
      : (() => {
          const c: Record<string, unknown> = { type: "line", name: "", data: [], silent: true };
          series.push(c);
          return c;
        })();
  if (markLine !== undefined) carrier.markLine = markLine;
  if (markArea !== undefined) carrier.markArea = markArea;
  return series;
}

/* issue #1071: sequential / diverging colour ramps for the map visualMap.
 * Light→dark low→high; ECharts interpolates between the stops. */
const COLOR_RAMPS: Record<ChartColorRamp, string[]> = {
  blues: ["#deebf7", "#9ecae1", "#4292c6", "#08519c"],
  greens: ["#e5f5e0", "#a1d99b", "#41ab5d", "#006d2c"],
  reds: ["#fee0d2", "#fc9272", "#ef3b2c", "#a50f15"],
  viridis: ["#440154", "#3b528b", "#21918c", "#5ec962", "#fde725"],
  diverging: ["#2166ac", "#67a9cf", "#f7f7f7", "#ef8a62", "#b2182b"],
};

function colorRampFor(ramp: ChartColorRamp | undefined): string[] {
  return COLOR_RAMPS[ramp ?? "blues"] ?? COLOR_RAMPS.blues;
}

/* #1081: resolve the active categorical colour CYCLE (the option-level `color`
 * array) honouring the documented precedence:
 *   colour-blind-safe (when the global pref made tk.highContrast)  >  named
 *   palette (o.palette)  >  theme default (tk.chartColors).
 * The per-series override layer is applied separately, on the individual
 * series, so it can win for a single series without disturbing the cycle.
 *
 * resolveThemeTokens() already swaps tk.chartColors to the Okabe-Ito palette
 * when cb-safe is on; so when tk.highContrast is set we keep tk.chartColors and
 * IGNORE any named palette — cb-safe accessibility must win over cosmetics. */
function paletteColors(o: ChartOptions, tk: ThemeTokens): string[] {
  if (tk.highContrast) return tk.chartColors;
  return resolvePalette(o.palette, tk);
}

/* #1081: the per-series colour override for `name`, or undefined. Keyed by the
 * series (column-category) label — the legend entry / measure name. */
function seriesColorFor(o: ChartOptions, name: string): string | undefined {
  return o.seriesColors?.[name];
}

/* issue #1080: x-axis zoom + pan for CARTESIAN charts (bar/line/area family,
 * scatter/bubble, waterfall). Brush/selection events are out of scope (#1085) —
 * this is zoom/pan only.
 *
 * Roomy (workspace) gets BOTH an `inside` zoom (mouse-wheel + drag-to-pan) and a
 * thin bottom `slider` so the zoomed window is visible and resettable by hand.
 * Compact (dashboard tiles) gets ONLY the `inside` zoom — tiles are too small to
 * spend vertical space on a slider, and dashboard charts are usually glanceable
 * rather than explored. Both are x-axis-only (zooming the value axis fights the
 * "compare magnitudes" job a bar/line chart does).
 *
 * Behaviour is always-on for the surface (driven by the existing `compact` geom
 * flag) — no new persisted ChartOptions field, so dashboard save (#1077, paused)
 * stays untouched. The builder stays pure: this just shapes option JSON; the
 * echarts instance/lifecycle lives in the .svelte components.
 *
 * `categoryCount` gates the visible slider — with one or a handful of bars the
 * slider has nothing meaningful to zoom and just steals vertical space (and
 * draws an empty track under the chart, which reads as a UI bug). The inside
 * (mouse-wheel) zoom stays armed regardless; it's invisible until used.
 *
 * For scatter / bubble / heatmap / waterfall — pass {visibleSlider: false}.
 * On those types the slider competes visually with the visualMap legend
 * (heatmap) and adds no useful navigation (scatter / bubble don't "scroll"
 * over time, waterfalls are single-sequence). Inside-zoom still works
 * for mouse-wheel users. */
const ZOOM_SLIDER_MIN_CATEGORIES = 12;
function dataZoomConfig(
  compact: boolean,
  categoryCount: number,
  visibleSlider = true,
): Record<string, unknown>[] {
  const inside = { type: "inside", xAxisIndex: 0, filterMode: "none" as const };
  if (compact) return [inside];
  if (!visibleSlider) return [inside];
  if (categoryCount < ZOOM_SLIDER_MIN_CATEGORIES) return [inside];
  return [
    inside,
    {
      type: "slider",
      xAxisIndex: 0,
      filterMode: "none" as const,
      bottom: 8,
      height: 16,
    },
  ];
}

/* issue #1085: brush cross-filter. Chart types whose x-axis is a discrete
 * category axis the user can rubber-band to select a contiguous range of
 * members. Proportional (pie/donut/treemap/sunburst), matrix (heatmap/radar),
 * point (scatter/bubble) and geo (map) charts have no single category x-axis to
 * brush, so they're excluded — the tile editor only offers the toggle for these
 * kinds. */
export const BRUSHABLE_CHART_TYPES: ReadonlySet<string> = new Set([
  "bar",
  "stackedBar",
  "line",
  "stackedLine",
  "area",
  "stackedArea",
]);

/* issue #1084: chart kinds that support threshold-driven conditional colours in
 * Phase 1. Bar/stacked-bar map cleanly (one series per measure → per-bar colour
 * by value). Scatter/bubble (series = rows, transposed), pie/donut (segment
 * rules) and heatmap (visualMap) are deferred to later phases per the issue. */
export const CONDITIONAL_FORMAT_CHART_TYPES: ReadonlySet<string> = new Set(["bar", "stackedBar"]);

/** issue #1085: the ECharts `brush` option for a brushable cartesian chart, or
 * `null` for a non-brushable type. `lineX` rubber-bands an x-axis RANGE (→ a
 * contiguous run of category members); `single` keeps one active selection;
 * `removeOnClick` lets a plain click clear it. The component enters brush mode
 * programmatically via `takeGlobalCursor` (no toolbox button) — see ChartTile.
 * Pure: just shapes option JSON, like {@link dataZoomConfig}. */
export function brushOption(chartType: string): Record<string, unknown> | null {
  if (!BRUSHABLE_CHART_TYPES.has(chartType)) return null;
  return {
    xAxisIndex: 0,
    brushType: "lineX",
    brushMode: "single",
    transformable: false,
    throttleType: "debounce",
    throttleDelay: 250,
    removeOnClick: true,
    brushStyle: { borderWidth: 1, color: "rgba(120,140,210,0.18)", borderColor: "rgba(120,140,210,0.65)" },
  };
}

/* Escape HTML — the map tooltip uses an ECharts function formatter whose return
 * value is inserted as innerHTML (renderMode "html"), and the region name is
 * data-derived (an aliased cube member caption). Without escaping, a hostile
 * caption could inject markup into the tooltip (stored XSS). #1071 hardening. */
function escapeHtml(s: string): string {
  return s.replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] ?? c,
  );
}

/* Cartesian (bar/line/area) tooltip with an inline sparkline (#1087).
 *
 * trigger:"axis" gives ECharts a default formatter that lists each series'
 * value at the hovered category. We replace it with a FUNCTION formatter that
 * keeps that value text AND, beneath each row, draws a tiny SVG sparkline of
 * THAT series across ALL categories with the hovered point dotted — so a single
 * hovered bar/point is read in the context of its whole trend.
 *
 * SECURITY: the return is inserted as innerHTML and is NOT escaped by ECharts
 * (same hazard as the #1071 map tooltip). Every data-derived string here — the
 * axis (category) label and each series name — is run through escapeHtml. The
 * SVG itself is produced by buildSparklineSvg from PURE NUMERIC matrix columns
 * (never from a caller string), and its only string input, the colour, is
 * sanitised inside that builder.
 *
 * The sparkline data is captured by column name from the projection matrix, so
 * trend/MA overlay series (which aren't real columns) simply get no sparkline.
 * Gated to roomy mode by the caller — compact tiles have no room for it. */
type AxisTipParam = {
  axisValueLabel?: string;
  axisValue?: string | number;
  seriesName?: string;
  dataIndex?: number;
  value?: unknown;
  marker?: string;
  color?: string;
};

function cartesianTooltipFormatter(
  cols: string[],
  matrix: (number | null)[][],
  tk: ThemeTokens,
  nf?: NumberFormat,
  withSparkline = true,
): (params: AxisTipParam | AxisTipParam[]) => string {
  // Column name -> its values down the rows (the series' full trend).
  const colSeries = new Map<string, (number | null)[]>();
  cols.forEach((name, c) => {
    colSeries.set(
      name,
      matrix.map((row) => row[c] ?? null),
    );
  });

  return (params: AxisTipParam | AxisTipParam[]): string => {
    const list = Array.isArray(params) ? params : [params];
    if (list.length === 0) return "";
    const header = escapeHtml(String(list[0]?.axisValueLabel ?? list[0]?.axisValue ?? ""));
    const dataIndex = list[0]?.dataIndex ?? -1;

    const rows = list
      .map((p) => {
        const name = String(p.seriesName ?? "");
        const v = p.value;
        // #1082: route the numeric value through formatNumber (prefix/suffix/
        // decimals/thousands/abbreviate) when a format is set; with none it
        // falls back to the prior String(v) / "—" behaviour. Still escaped
        // below because the return is inserted as innerHTML.
        const valueText =
          nf && typeof v === "number"
            ? formatNumber(v, nf)
            : v == null || (typeof v === "number" && Number.isNaN(v))
              ? "—"
              : String(v);
        const safeName = escapeHtml(name);
        // ECharts' own coloured marker dot is safe HTML it generated itself.
        const marker = p.marker ?? "";
        const spark = withSparkline && colSeries.has(name)
          ? buildSparklineSvg(colSeries.get(name)!, {
              width: 120,
              height: 26,
              color: p.color,
              highlightIndex: dataIndex,
            })
          : "";
        const row =
          `<div style="display:flex;align-items:center;gap:6px;margin-top:4px">` +
          `${marker}<span>${safeName}</span>` +
          `<strong style="margin-left:auto">${escapeHtml(valueText)}</strong>` +
          `</div>`;
        return spark ? row + `<div style="margin-top:2px">${spark}</div>` : row;
      })
      .join("");

    return `<div style="font-weight:600;color:${escapeHtml(tk.fg)}">${header}</div>${rows}`;
  };
}

/* ----------------------------- builder ----------------------------- */

/**
 * Build an ECharts option object for one chart, from an already-projected
 * {@link ChartProjection}. Returns null when the kind isn't supported or the
 * projection has no rows.
 *
 * @param projection rows/cols/matrix (rollup-filtered by the caller)
 * @param type       one of the 15 supported chart types
 * @param options    full ChartOptions (title/legend/axes/trend/dual-axis)
 * @param tk         active theme tokens (text/axis/palette colours)
 * @param geom       canvas geometry + the `compact` density flag
 */
export function buildChartOption(
  projection: ChartProjection,
  type: ChartType | string,
  options: ChartOptions = DEFAULT_CHART_OPTIONS,
  tk: ThemeTokens = DEFAULT_THEME_TOKENS,
  geom: ChartGeometry = {},
): Record<string, unknown> | null {
  if (!isChartType(type)) return null;
  const t: ChartType = type;
  const rows = projection.rowCategories;
  const cols = projection.columnCategories;
  const matrix = projection.matrix;
  if (matrix.length === 0) return null;

  const o = options;
  const compact = geom.compact ?? false;
  const aspect = Number.isFinite(geom.aspect) && (geom.aspect ?? 0) > 0 ? (geom.aspect as number) : 1;

  // issue #1079: reference lines / bands — cartesian charts only (attached
  // below to the first measure series, or a dedicated carrier when there are
  // no measures). undefined when none configured, so the carrier series is
  // unchanged for legacy charts.
  const markLine = markLineConfig(o.referenceLines, tk, compact);
  const markArea = markAreaConfig(o.referenceBands, tk, compact);

  // Theme-derived fragments — identical across both surfaces.
  // #1081: `color` is the resolved categorical cycle (cb-safe > named palette >
  // theme default). With the inert default (palette "default", cb-safe off) this
  // equals tk.chartColors, so legacy output and snapshots stay unchanged.
  const palette = paletteColors(o, tk);
  const common = {
    backgroundColor: "transparent",
    color: palette,
    textStyle: { color: tk.fg },
  };

  // #1091: high-contrast / colour-blind-safe mode. tk.highContrast is set by
  // resolveThemeTokens() when the global colour-blind-safe pref is on (it also
  // swaps tk.chartColors to the Okabe-Ito palette). Here we bump stroke widths
  // and marker sizes for low-vision users, and give every series a same-coloured
  // border so adjacent bars/slices stay separable for monochrome readers. These
  // fragments are no-ops (empty objects / unchanged sizes) when the mode is off.
  const hc = tk.highContrast;
  // All high-contrast fragments are empty objects when the mode is off, so they
  // spread to nothing and the default output (and its snapshots) stays
  // byte-for-byte unchanged. seriesBorder is the raw itemStyle border value;
  // itemStyleHC wraps it for series that don't otherwise set itemStyle.
  const seriesBorder = hc ? { borderColor: tk.bg, borderWidth: 1.5 } : {};
  const itemStyleHC = hc ? { itemStyle: seriesBorder } : {};
  const lineStyleHC = hc ? { lineStyle: { width: 4 } } : {};
  const markerHC = hc ? { symbolSize: 10 } : {};
  const axisLabel = { color: tk.fgMuted };
  const axisLine = { lineStyle: { color: tk.border } };
  const axisTick = { lineStyle: { color: tk.border } };
  const splitLine = { lineStyle: { color: tk.border, opacity: 0.5 } };
  const nameTextStyle = { color: tk.fg };
  const tooltipStyle = { backgroundColor: tk.bg, borderColor: tk.border, textStyle: { color: tk.fg } };

  // Category-axis label: compact uses a fixed truncation width (tiles are
  // small); roomy mode derives the per-label width from the canvas width and
  // category count. `rotate` is only used by the compact bar branch when
  // categories crowd. The full label always shows in the axis-pointer tooltip.
  // Per-axis label width: divide chart width by the count of categories
  // ON THAT AXIS, not max(rows, cols) — the earlier max() left scatter /
  // bubble's single-category x-axis cramped to 40px just because the
  // y-side had 50+ series (user feedback 2026-06-07: a single "Cust…"
  // label clipped to 5 chars on a 1400px-wide chart). Callers pass the
  // axis-specific count explicitly via {@link catLabel}.
  // #1598: when the category axis crowds, tick labels rotate to read diagonally.
  // The truncation width is RESPONSIVE — compact tiles thin aggressively (a short
  // cap, so long city names ellipsize early instead of sprawling off a small
  // tile), roomy charts show more. axisLabelConfig gives every rotated label
  // `overflow:"truncate"` + `ellipsis`, so an over-long label ends in "…" rather
  // than clipping. The matching grid.bottom reservation is `rotatedTickBottom()`
  // below — the two are derived from the same width + angle so they never
  // disagree.
  const ROTATED_LABEL_ANGLE = 30;
  const ROTATED_LABEL_WIDTH = compact ? 72 : 160;
  const catLabel = (rotate = 0, axisCategoryCount = cols.length || 1) => {
    if (rotate !== 0) {
      return {
        color: tk.fgMuted,
        rotate,
        ...axisLabelConfig(ROTATED_LABEL_WIDTH),
      };
    }
    const w = compact
      ? 100
      : deriveAxisLabelWidth(geom.chartWidth ?? 0, axisCategoryCount);
    return { color: tk.fgMuted, rotate, ...axisLabelConfig(w) };
  };

  // #1598: extra grid.bottom (px) a rotated tick label needs so its DIAGONAL DROP
  // clears the plot instead of clipping at the tile edge. A label capped at
  // ROTATED_LABEL_WIDTH and rotated ROTATED_LABEL_ANGLE° drops width·sin θ and its
  // glyph box adds line·cos θ; we reserve only the part BEYOND the single
  // horizontal label row that the base grid.bottom already covers. Returns 0 when
  // the axis isn't rotated (legacy layout unchanged). This is TICK-label room and
  // is added ALONGSIDE #1596's axis-NAME room (catNameBottomPad) — the two are
  // independent and compose, so a rotated axis that also has a dimension title
  // reserves room for both.
  const ROTATED_TICK_LINE_PX = 16;
  const rotatedTickBottom = (rotated: boolean): number => {
    if (!rotated) return 0;
    const rad = (ROTATED_LABEL_ANGLE * Math.PI) / 180;
    const extent = ROTATED_LABEL_WIDTH * Math.sin(rad) + ROTATED_TICK_LINE_PX * Math.cos(rad);
    return Math.max(0, Math.ceil(extent) - ROTATED_TICK_LINE_PX);
  };

  const legend = compact ? compactLegend(o, tk) : legendConfig(o, tk);
  const title = titleConfig(o, tk);
  // Pure axis-name overrides (used as-is by the special axis geometries below —
  // heatmap's TWO category axes and scatter/bubble's measure x-axis — where an
  // auto-derived measure/dimension title would be misleading).
  const xName = o.xAxisLabel || undefined;
  const yName = o.yAxisLabel || undefined;

  // #1596: axis TITLES for the standard cartesian charts (bar/line/area family +
  // waterfall) — where the y-axis is a genuine value axis and the x-axis is the
  // row-dimension category axis. Charts were rendering with bare numbers and no
  // measure/dimension name. Precedence: an explicit user label always wins;
  // otherwise default from the query — value axis = the measure name(s)
  // (columnCategories), category axis = the row dimension name the caller
  // resolved (projection.categoryAxisName). Auto-derivation is roomy-mode only
  // (dashboard tiles stay tile-tight; an explicit per-tile label still wins in
  // compact). Placement is nameLocation "middle" so the title reads centred and
  // clear of the #1595 title band at grid.top — these only touch grid.left (the
  // vertical value title) and grid.bottom (the category title).
  const measureAxisName = !compact && cols.length > 0 ? cols.join(", ") : undefined;
  const valueName = yName ?? measureAxisName;
  const categoryName = xName ?? (!compact ? projection.categoryAxisName || undefined : undefined);
  // Vertical value-axis title config (rotated to read up the axis); a bare
  // `{ name: undefined }` when there's nothing to draw, so legacy output for the
  // no-title case is byte-for-byte unchanged.
  const valueNameCfg: Record<string, unknown> = valueName
    ? { name: valueName, nameLocation: "middle", nameGap: compact ? 40 : 56, nameRotate: 90 }
    : { name: undefined };
  const VALUE_NAME_LEFT_PAD = valueName ? (compact ? 22 : 34) : 0;
  // Horizontal category-axis title config. When the category labels rotate
  // (crowded axis), the title needs a bigger gap + bottom margin to sit clear of
  // the angled tick labels; otherwise a tight gap under a single label row.
  const catNameCfg = (rotated: boolean): Record<string, unknown> =>
    categoryName
      ? { name: categoryName, nameLocation: "middle", nameGap: rotated ? (compact ? 52 : 68) : (compact ? 26 : 32) }
      : { name: undefined };
  const catNameBottomPad = (rotated: boolean): number =>
    categoryName ? (rotated ? (compact ? 34 : 46) : (compact ? 20 : 28)) : 0;

  // #1082: optional number formatting for VALUE text (axis labels, tooltip
  // values, data labels). `nf` is undefined when no format is set, so every
  // value-axis/tooltip/label fragment below stays byte-for-byte identical and
  // legacy snapshots are unchanged. The category axis is never reformatted.
  const nf: NumberFormat | undefined =
    o.numberFormat && Object.values(o.numberFormat).some((v) => v !== undefined && v !== null && v !== false && v !== "")
      ? o.numberFormat
      : undefined;
  // Value-axis label config: only attach a formatter when nf is active, so the
  // axisLabel object is unchanged for unformatted charts.
  const valueAxisLabel = nf
    ? { color: tk.fgMuted, formatter: (val: number) => formatNumber(val, nf) }
    : axisLabel;
  // Series data-label formatter (#1082): attached to cartesian series so that
  // IF data labels are shown they use the format. We don't force labels on —
  // we only set label.formatter (label.show stays at its ECharts default), and
  // only when nf is active so the series object is otherwise unchanged. The
  // value-axis branch always formats; this covers any displayed point labels.
  const seriesValueLabel: Record<string, unknown> = nf
    ? { label: { formatter: (p: { value?: unknown }) => formatNumber(p?.value as number, nf) } }
    : {};

  const baseAxis = { type: "category" as const, axisLabel, axisLine, axisTick, splitLine, nameTextStyle };
  // #1599: the value axis was drawing too few gridlines (e.g. just 0 and the max,
  // 20,000) so intermediate magnitudes were unreadable. Give ECharts an explicit
  // splitNumber target so it picks several nice-number intervals; compact tiles
  // ask for fewer so a small tile's value axis doesn't crowd. `splitNumber` is a
  // hint — ECharts still rounds to nice numbers, so labels stay clean.
  const VALUE_AXIS_SPLIT_NUMBER = compact ? 4 : 6;
  const valueAxis = {
    type: "value" as const,
    splitNumber: VALUE_AXIS_SPLIT_NUMBER,
    axisLabel: valueAxisLabel,
    axisLine,
    axisTick,
    splitLine,
    nameTextStyle,
  };

  // Pie / donut / treemap / sunburst encode a SINGLE measure. With M measures
  // we fan out into M small-multiple charts — one per measure — laid out in a
  // grid inside the same option (M series, one per cell). M=1 is a single chart.
  if (t === "pie" || t === "donut" || t === "treemap" || t === "sunburst") {
    // Beyond this many measures we collapse to a single chart whose slices
    // are summed across rows — the screenshot bug 2026-06-07 had 24+
    // tiny pies with vast whitespace because gridCells fanned the layout
    // across every column. A single pie with the columns as slices is the
    // useful default for "Store Sales by Product Category".
    const MAX_SMALL_MULTIPLES = 6;
    const collapseToSingle = cols.length > MAX_SMALL_MULTIPLES;
    const cells = gridCells(collapseToSingle ? 1 : cols.length);
    // When collapsed, the single cell's title is just the chart title (set
    // by titleConfig); skip the per-cell title so we don't draw two stacked
    // labels.
    const cellTitles = collapseToSingle
      ? []
      : cells.map((cell, m) => ({
          text: cols[m],
          left: cell.centerXPct + "%",
          top: cell.topPct + "%",
          textAlign: "center" as const,
          textStyle: { color: tk.fg, fontSize: 12 },
        }));
    const titles = title ? [title, ...cellTitles] : cellTitles;
    // Name slices directly when few enough to read; else lean on the tooltip.
    // Inside the slices for a small-multiple grid (leader lines would cross
    // between neighbours); outside for a single chart where there's room.
    const showSliceLabels =
      (collapseToSingle ? cols.length : rows.length) <= MAX_LABELLED_SLICES;
    const sliceLabelInside = cells.length > 1;
    // Pre-compute the collapsed dataset (one slice per column, value =
    // sum of that column across all rows).
    const collapsedData = collapseToSingle
      ? cols.map((name, c) => ({
          name,
          value: matrix.reduce((sum, row) => sum + (row[c] ?? 0), 0),
        }))
      : null;

    if (t === "pie" || t === "donut") {
      return {
        ...common,
        title: titles,
        tooltip: { trigger: "item", ...tooltipStyle },
        series: cells.map((cell, m) => {
          const outer = cellRadiusPct(cell, aspect);
          return {
            type: "pie",
            name: collapsedData ? "Total" : cols[m],
            radius: t === "donut" ? [outer * 0.55 + "%", outer + "%"] : [0, outer + "%"],
            center: [cell.centerXPct + "%", cell.centerYPct + "%"],
            label: {
              show: showSliceLabels,
              position: sliceLabelInside ? "inside" : "outside",
              formatter: "{b}",
              color: sliceLabelInside ? "#fff" : tk.fg,
            },
            labelLine: { show: showSliceLabels && !sliceLabelInside },
            ...itemStyleHC,
            // #1081: pie slices are the row categories, so the per-series colour
            // override applies at the DATA-ITEM level (by slice name). An item
            // with no override keeps the palette-cycle colour. Merges with the
            // high-contrast same-bg border above when both are active. The
            // collapsedData short-circuit (rollup-only result) keeps precedence.
            data:
              collapsedData ??
              rows.map((name, i) => {
                const override = seriesColorFor(o, name);
                const base = { name, value: matrix[i][m] ?? 0 };
                return override
                  ? { ...base, itemStyle: { ...seriesBorder, color: override } }
                  : base;
              }),
          };
        }),
      };
    }

    if (t === "treemap") {
      return {
        ...common,
        title: titles,
        tooltip: tooltipStyle,
        series: cells.map((cell, m) => ({
          type: "treemap",
          name: collapsedData ? "Total" : cols[m],
          left: cell.leftPct + "%",
          top: cell.topPct + cell.heightPct * 0.18 + "%",
          width: cell.widthPct + "%",
          height: cell.heightPct * 0.82 + "%",
          label: { color: "#fff" },
          breadcrumb: { show: false },
          data: (
            collapsedData ?? rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 }))
          ).filter((d) => d.value > 0),
        })),
      };
    }

    // sunburst
    return {
      ...common,
      title: titles,
      tooltip: tooltipStyle,
      series: cells.map((cell, m) => ({
        type: "sunburst",
        name: collapsedData ? "Total" : cols[m],
        center: [cell.centerXPct + "%", cell.centerYPct + "%"],
        radius: [0, cellRadiusPct(cell, aspect) + "%"],
        label: { show: showSliceLabels, color: "#fff" },
        data: (
          collapsedData ?? rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 }))
        ).filter((d) => d.value > 0),
      })),
    };
  }

  if (t === "heatmap") {
    const data: [number, number, number][] = [];
    for (let i = 0; i < matrix.length; i++) {
      for (let j = 0; j < (matrix[i]?.length ?? 0); j++) {
        data.push([j, i, matrix[i][j] ?? 0]);
      }
    }
    const values = data.map((d) => d[2]);
    const min = values.length ? Math.min(...values) : 0;
    const max = values.length ? Math.max(...values) : 1;
    return {
      ...common,
      title,
      tooltip: tooltipStyle,
      // Reserve a little extra left padding for vertical visualMap and
      // leave the bottom narrow now that the heatmap no longer paints
      // a horizontal legend there. Rotated x-axis labels need ~60px
      // bottom room.
      grid: compact
        ? { left: 96, top: title ? TITLE_GRID_TOP : 24, right: 16, bottom: 60 }
        : { left: 120, top: title ? 56 : 40, right: 96, bottom: 60 },
      xAxis: {
        ...baseAxis,
        // Heatmap categories had the same readability problem as scatter
        // / bar (user feedback 2026-06-07): "Alcohol…" / "Breakfa…" /
        // "Eggs / …" with no way to read full names. Rotate when crowded.
        axisLabel: catLabel(cols.length > 8 ? 30 : 0),
        data: cols,
        name: xName,
      },
      yAxis: { ...baseAxis, data: rows, inverse: true, name: yName },
      // visualMap moved to the right edge (vertical, non-calculable).
      // The horizontal `calculable: true` legend at the bottom looked
      // like a second draggable slider next to dataZoom — the user
      // called it "actually 2 drag bars" 2026-06-07. Vertical + on the
      // right is the standard heatmap layout and lifts the ambiguity.
      visualMap: {
        min,
        max,
        calculable: false,
        orient: "vertical",
        right: 16,
        top: "center",
        itemHeight: 140,
        textStyle: { color: tk.fgMuted },
      },
      // Heatmap doesn't get the bottom slider either — the colour-band
      // already shows the full distribution at a glance and the slider
      // adds visual noise. Inside-zoom is also off (heatmaps aren't
      // "panned over time" like a long bar chart is).
      series: [
        { type: "heatmap", data, label: { show: false }, emphasis: { itemStyle: { shadowBlur: 10 } } },
      ],
    };
  }

  if (t === "radar") {
    const max = Math.max(0, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
    return {
      ...common,
      title,
      tooltip: tooltipStyle,
      legend,
      radar: {
        indicator: cols.map((c) => ({ name: c, max: max || 1 })),
        axisName: { color: tk.fgMuted },
        axisLine: { lineStyle: { color: tk.border } },
        splitLine: { lineStyle: { color: tk.border, opacity: 0.5 } },
        splitArea: { areaStyle: { color: [tk.bg, tk.bgMuted], opacity: 0.3 } },
      },
      series: [
        {
          type: "radar",
          // #1091: thicker spokes + symbols in high-contrast mode.
          ...lineStyleHC,
          ...markerHC,
          data: rows.map((r, i) => ({ name: r, value: matrix[i].map((v) => v ?? 0) })),
        },
      ],
    };
  }

  if (t === "map") {
    // issue #1071 Phase 1: world-countries choropleth. Row categories are
    // place names (from the row hierarchy); the FIRST measure column drives
    // the colour (multi-measure maps small-multiple later — Phase 2+). The
    // map must be registered with echarts.registerMap("world", …) BEFORE
    // setOption — the components do that (this builder stays pure). Names go
    // through aliasGeoName so OLAP captions (USA, UK) hit the GeoJSON
    // feature names (United States of America, United Kingdom); unmatched
    // names simply don't paint.
    const zero = o.mapMissing === "zero";
    const data = rows.map((name, i) => {
      const v = matrix[i][0];
      return { name: aliasGeoName(name), value: v ?? (zero ? 0 : null) };
    });
    const present = data.map((d) => d.value).filter((v): v is number => v != null && Number.isFinite(v));
    const min = present.length ? Math.min(...present) : 0;
    const max = present.length ? Math.max(...present) : 1;
    return {
      ...common,
      title,
      tooltip: {
        trigger: "item",
        ...tooltipStyle,
        // value can be null (blank country) — show "—" rather than "null".
        formatter: (p: { name?: string; value?: number | null }) =>
          `${escapeHtml(p?.name ?? "")}: ${p?.value == null || Number.isNaN(p.value) ? "—" : p.value}`,
      },
      visualMap: {
        min,
        max: max > min ? max : min + 1,
        calculable: true,
        orient: "vertical",
        left: compact ? 4 : 10,
        bottom: compact ? 4 : 20,
        textStyle: { color: tk.fgMuted },
        inRange: { color: colorRampFor(o.colorRamp) },
      },
      series: [
        {
          type: "map",
          map: "world",
          roam: true,
          nameProperty: "name",
          emphasis: { label: { show: false }, itemStyle: { areaColor: tk.accent ?? "#7aa6ff" } },
          itemStyle: { areaColor: tk.bgMuted, borderColor: tk.border },
          label: { show: false },
          data,
        },
      ],
    };
  }

  if (t === "scatter" || t === "bubble") {
    const bubbleMax = Math.max(1, ...matrix.flatMap((r) => r.map((v) => Math.abs(v ?? 0))));
    return {
      ...common,
      title,
      tooltip: tooltipStyle,
      legend,
      // #1080: roomy gets a slider + inside zoom; compact gets inside-only. The
      // roomy slider sits at the bottom, so reserve a little extra grid bottom.
      grid: compact
        ? { top: title ? TITLE_GRID_TOP : 24, left: 48, right: 16, bottom: 56 }
        : { left: 60, top: title ? 50 : 40, right: 40, bottom: 56 },
      // scatter / bubble: never draw the visible slider (the points
      // already show the full distribution and the slider added clutter
      // per user feedback 2026-06-07). Inside-zoom stays for mouse-wheel.
      dataZoom: dataZoomConfig(compact, cols.length, false),
      xAxis: {
        ...baseAxis,
        // Rotate when categories crowd — same rule the bar branch uses
        // and the only way to keep "Alcohol…" / "Breakfa…" readable when
        // there are 30+ entries.
        axisLabel: catLabel(cols.length > 8 ? 30 : 0),
        data: cols,
        name: xName,
      },
      yAxis: { ...valueAxis, name: yName },
      series: attachMarks(
        rows.map((name, i) => ({
          type: "scatter",
          name,
          // #1081: per-series colour override (by series name) wins for this series.
          ...(seriesColorFor(o, name) ? { color: seriesColorFor(o, name) } : {}),
          // #1091: larger floor marker + bordered points for low-vision users.
          ...itemStyleHC,
          symbolSize:
            t === "bubble"
              ? (val: unknown) => {
                  const v = Array.isArray(val) ? (val[1] as number) : (val as number);
                  return Math.max(hc ? 10 : 6, Math.sqrt(Math.abs(v) / bubbleMax) * 40);
                }
              : hc
                ? 14
                : 10,
          data: matrix[i].map((v, j) => [j, v ?? 0]),
        })),
        markLine,
        markArea,
      ),
    };
  }

  if (t === "waterfall") {
    const vals = matrix.map((r) => r[0] ?? 0);
    const spacers: number[] = [];
    const positives: (number | null)[] = [];
    const negatives: (number | null)[] = [];
    let running = 0;
    for (const v of vals) {
      if (v >= 0) {
        spacers.push(running);
        positives.push(v);
        negatives.push(null);
      } else {
        spacers.push(running + v);
        positives.push(null);
        negatives.push(-v);
      }
      running += v;
    }
    return {
      ...common,
      title,
      tooltip: { trigger: "axis", ...tooltipStyle },
      legend,
      // #1596: grow grid.left/bottom for the value/category titles when drawn
      // (pads are 0 otherwise); grid.top stays reserved for the #1595 title band.
      grid: compact
        ? {
            top: title ? TITLE_GRID_TOP : 24,
            left: 48 + VALUE_NAME_LEFT_PAD,
            right: 16,
            // #1598: + rotatedTickBottom so rotated tick labels don't clip.
            bottom: 56 + catNameBottomPad(rows.length > 8) + rotatedTickBottom(rows.length > 8),
          }
        : {
            left: 60 + VALUE_NAME_LEFT_PAD,
            top: title ? 50 : 30,
            right: 40,
            // #1598: + rotatedTickBottom so rotated tick labels don't clip.
            bottom: 56 + catNameBottomPad(rows.length > 8) + rotatedTickBottom(rows.length > 8),
          },
      // waterfall is a single ordered sequence — no useful "zoom window"
      // semantics. Inside-zoom stays for power users, slider hidden.
      dataZoom: dataZoomConfig(compact, rows.length, false),
      xAxis: {
        ...baseAxis,
        // waterfall's x-axis is `rows`; pass rows.length so the
        // per-label width is computed against the right dim. #1598: angle const.
        axisLabel: catLabel(rows.length > 8 ? ROTATED_LABEL_ANGLE : 0, rows.length),
        data: rows,
        // #1596: category-dimension title (or the user override).
        ...catNameCfg(rows.length > 8),
      },
      // #1596: value-axis measure title (or the user override).
      yAxis: { ...valueAxis, ...valueNameCfg },
      series: attachMarks(
        [
        {
          type: "bar",
          name: "",
          stack: "waterfall",
          itemStyle: { borderColor: "transparent", color: "transparent" },
          emphasis: { itemStyle: { borderColor: "transparent", color: "transparent" } },
          data: spacers,
        },
        {
          type: "bar",
          name: cols[0] ?? "Positive",
          stack: "waterfall",
          data: positives,
          // #1091: the default pos/neg blue/red isn't colour-blind safe; swap to
          // the Okabe-Ito-derived pair (bordered) when high-contrast is on.
          itemStyle: { color: hc ? COLORBLIND_WATERFALL.positive : "#4c9ee6", ...seriesBorder },
        },
        {
          type: "bar",
          name: `-${cols[0] ?? "Negative"}`,
          stack: "waterfall",
          data: negatives,
          itemStyle: { color: hc ? COLORBLIND_WATERFALL.negative : "#e66c6c", ...seriesBorder },
        },
      ],
        markLine,
        markArea,
      ),
    };
  }

  // bar / stackedBar / line / stackedLine / area / stackedArea
  const isStacked = t.startsWith("stacked");
  // includes("bar") is case-sensitive — "stackedBar" has a capital B, so the
  // naive check returned false and stacked-bar fell through to the line branch.
  const lower = t.toLowerCase();
  const kindBase: "bar" | "line" = lower.includes("bar") ? "bar" : "line";
  const areaStyle = t === "area" || t === "stackedArea" ? {} : undefined;

  // Dual y-axis: auto-split low-magnitude series to the right so a dominant
  // series doesn't crush them to the zero line (Event Count in thousands vs
  // Avg Tone in single digits). Per-series picks in o.seriesAxis always win;
  // o.dualAxis=false reverts to single-axis. assignSeriesAxes returns all-left
  // when dualAxis is off, so hasRight is false and we emit a single y-axis.
  const seriesSides = assignSeriesAxes(cols, matrix, {
    dualAxis: o.dualAxis,
    seriesAxis: o.seriesAxis,
    threshold: SERIES_AXIS_THRESHOLD,
  });
  const hasRight = seriesSides.some((s) => s === "right");
  // #1596: the left value axis carries the measure-name title (valueNameCfg);
  // the right (dual) axis is left untitled — its own series names are in the
  // legend and a second rotated title would crowd the plot.
  const yAxis = hasRight
    ? [
        { ...valueAxis, ...valueNameCfg, position: "left" as const },
        // Drop the right axis's splitLine so left-axis gridlines aren't doubled.
        { ...valueAxis, name: undefined, position: "right" as const, splitLine: { show: false } },
      ]
    : { ...valueAxis, ...valueNameCfg };

  // issue #1089: the chart-level default series type (combo overrides fall back
  // to it). "area" = a line with an areaStyle fill.
  const chartDefaultType: ComboSeriesType = areaStyle ? "area" : kindBase;
  const base = cols.map((name, c) => {
    // #1089: per-series chart-type override (combo charts). Each series can be
    // bar / line / area / scatter on the same cartesian grid; absent → the
    // chart-level default. "area" renders as a line + areaStyle.
    const stype: ComboSeriesType = o.seriesType?.[name] ?? chartDefaultType;
    const echType = stype === "area" ? "line" : stype; // bar | line | scatter
    const isLineLike = stype === "line" || stype === "area";
    // #1084: conditional-format band for this measure — only for an EFFECTIVE
    // bar series (line/area keep a continuous stroke; scatter is point-based but
    // out of Phase-1 cond-format scope). A match recolours the point via
    // point-level itemStyle, which ECharts merges over the series itemStyle (so
    // the #1091 HC border is kept) and over the #1081 per-series colour.
    const cf = echType === "bar" ? conditionalFormatForMeasure(o.conditionalFormat, c) : undefined;
    return {
      type: echType,
      name,
      // #1081: per-series colour override (by series name) wins over the cycle
      // for this one series. ECharts honours a series-level `color` for both bar
      // and line; absent overrides leave it off so the palette cycle drives it.
      ...(seriesColorFor(o, name) ? { color: seriesColorFor(o, name) } : {}),
      // Single-axis stacked → one "total" group (matches both surfaces' prior
      // output); dual-axis stacked → separate groups per side so each axis stacks
      // independently. (Mixed-type stacking is out of #1089 scope; the field is
      // ignored by ECharts for scatter and best-effort otherwise.)
      stack: isStacked
        ? hasRight
          ? seriesSides[c] === "right"
            ? "total-right"
            : "total-left"
          : "total"
        : undefined,
      // #1089: areaStyle only on an effective area series.
      areaStyle: stype === "area" ? {} : undefined,
      smooth: isLineLike,
      // #1091: high-contrast — thicker line strokes + bigger markers for line-
      // like series, a same-bg border for bars/points. No-ops when off.
      ...(isLineLike ? { ...lineStyleHC, ...markerHC } : itemStyleHC),
      // #1082: format any displayed data labels (no-op object when nf is off).
      ...seriesValueLabel,
      yAxisIndex: hasRight ? (seriesSides[c] === "right" ? 1 : 0) : 0,
      // Compact (tiles) draws missing cells as gaps; roomy (workspace) as 0.
      data: matrix.map((row) => {
        const raw = row[c] ?? (compact ? null : 0);
        const ccolor = cf && typeof raw === "number" ? colorForValue(raw, cf) : undefined;
        return ccolor ? { value: raw, itemStyle: { color: ccolor } } : raw;
      }),
    };
  });
  const trend =
    kindBase === "line" && cols.length > 0
      ? trendSeries(
          cols[0],
          matrix.map((row) => row[0] ?? null),
          o,
          tk,
        )
      : [];

  // Sparkline-in-tooltip (#1087): roomy mode only (compact tiles are too small)
  // and only when there's more than one category — a single column has no trend
  // to draw (matches the issue's "disabled when there's only one column").
  const sparklineTip = !compact && rows.length > 1;
  // #1082: when a number format is set we always need a function formatter so
  // tooltip values are formatted, even in compact mode (which has no sparkline).
  // Without a format we keep the prior behaviour: sparkline formatter in roomy
  // mode, ECharts' default text formatter otherwise.
  const cartesianTooltip =
    sparklineTip || nf
      ? {
          trigger: "axis" as const,
          ...tooltipStyle,
          formatter: cartesianTooltipFormatter(cols, matrix, tk, nf, sparklineTip),
        }
      : { trigger: "axis" as const, ...tooltipStyle };

  // #1596: the category axis rotates its labels past 8 members; the axis title
  // then needs a bigger gap / bottom margin to clear the angled labels.
  const xRotated = rows.length > 8;
  return {
    ...common,
    title,
    tooltip: cartesianTooltip,
    legend,
    // Reserve room for the zoom slider only when it's actually drawn —
    // otherwise the plot area gets squashed for an affordance that
    // isn't present (saiku follow-up to #1080 + screenshot 2026-06-07).
    // #1596: grow grid.left for the vertical value title and grid.bottom for the
    // category title — only when each title is actually drawn (pads are 0
    // otherwise, so the legacy no-title layout is unchanged). grid.top is left to
    // the #1595 title band.
    grid: compact
      ? {
          top: title ? TITLE_GRID_TOP : 24,
          left: 48 + VALUE_NAME_LEFT_PAD,
          right: 16,
          // #1598: + rotatedTickBottom so rotated tick labels don't clip.
          bottom: 36 + catNameBottomPad(xRotated) + rotatedTickBottom(xRotated),
        }
      : {
          left: 60 + VALUE_NAME_LEFT_PAD,
          top: title ? 50 : 40,
          right: hasRight ? 60 : 40,
          // #1598: + rotatedTickBottom so rotated tick labels clear the slider/edge.
          bottom:
            (rows.length >= ZOOM_SLIDER_MIN_CATEGORIES ? 76 : 40) +
            catNameBottomPad(xRotated) +
            rotatedTickBottom(xRotated),
        },
    // bar / line / area: x-axis is `rows`.
    dataZoom: dataZoomConfig(compact, rows.length), // #1080: x-axis zoom + pan
    xAxis: {
      ...baseAxis,
      // bar / line / area: x-axis is `rows`. Pass rows.length so the
      // per-label width is computed against the right dim — the old
      // max(rows, cols) cramped scatter / bar single-category x-axes
      // to 40px when the y side had many series ("Cust…" / "CA /…"
      // 2026-06-07 screenshot). #1598: rotate uses ROTATED_LABEL_ANGLE.
      axisLabel: catLabel(xRotated ? ROTATED_LABEL_ANGLE : 0, rows.length),
      data: rows,
      // #1596: category-dimension title (or the user override); nothing drawn
      // when neither is present.
      ...catNameCfg(xRotated),
    },
    yAxis,
    // issue #1079: reference lines/bands ride on the first measure series (or a
    // dedicated carrier when there are no measures). Trend overlays stay
    // markLine-free. No-op when nothing is configured (legacy output unchanged).
    series: attachMarks([...base, ...trend], markLine, markArea),
  };
}
