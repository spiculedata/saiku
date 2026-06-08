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

import type { ChartType, ChartOptions, ChartColorRamp } from "$lib/views/chartTypes";
import { DEFAULT_CHART_OPTIONS, SERIES_AXIS_THRESHOLD, isChartType } from "$lib/views/chartTypes";
import { aliasGeoName } from "$lib/charts/geoMatch";
import { assignSeriesAxes } from "$lib/views/cellsetUtils";
import { axisLabelConfig, deriveAxisLabelWidth } from "$lib/views/chartAxisLabel";
import { cellRadiusPct, gridCells, MAX_LABELLED_SLICES } from "$lib/dashboard/smallMultiples";
import { type ThemeTokens, DEFAULT_THEME_TOKENS, COLORBLIND_WATERFALL } from "$lib/views/chartTheme";
import { buildSparklineSvg } from "$lib/charts/sparkline";

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

function titleConfig(o: ChartOptions, tk: ThemeTokens): Record<string, unknown> | undefined {
  if (!o.title) return undefined;
  return { text: o.title, left: "center", textStyle: { color: tk.fg } };
}

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
        const valueText = v == null || (typeof v === "number" && Number.isNaN(v)) ? "—" : String(v);
        const safeName = escapeHtml(name);
        // ECharts' own coloured marker dot is safe HTML it generated itself.
        const marker = p.marker ?? "";
        const spark = colSeries.has(name)
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

  // Theme-derived fragments — identical across both surfaces.
  const common = {
    backgroundColor: "transparent",
    color: tk.chartColors,
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
  const ROTATED_LABEL_WIDTH = 160;
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

  const legend = compact ? compactLegend(o, tk) : legendConfig(o, tk);
  const title = titleConfig(o, tk);
  const xName = o.xAxisLabel || undefined;
  const yName = o.yAxisLabel || undefined;

  const baseAxis = { type: "category" as const, axisLabel, axisLine, axisTick, splitLine, nameTextStyle };
  const valueAxis = { type: "value" as const, axisLabel, axisLine, axisTick, splitLine, nameTextStyle };

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
            data:
              collapsedData ??
              rows.map((name, i) => ({ name, value: matrix[i][m] ?? 0 })),
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
        ? { left: 96, top: 24, right: 16, bottom: 60 }
        : { left: 120, top: 40, right: 96, bottom: 60 },
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
        ? { top: 24, left: 48, right: 16, bottom: 56 }
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
      series: rows.map((name, i) => ({
        type: "scatter",
        name,
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
      grid: compact
        ? { top: 24, left: 48, right: 16, bottom: 56 }
        : { left: 60, top: title ? 50 : 30, right: 40, bottom: 56 },
      // waterfall is a single ordered sequence — no useful "zoom window"
      // semantics. Inside-zoom stays for power users, slider hidden.
      dataZoom: dataZoomConfig(compact, rows.length, false),
      xAxis: {
        ...baseAxis,
        // waterfall's x-axis is `rows`; pass rows.length so the
        // per-label width is computed against the right dim.
        axisLabel: catLabel(rows.length > 8 ? 30 : 0, rows.length),
        data: rows,
        name: xName,
      },
      yAxis: { ...valueAxis, name: yName },
      series: [
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
  const yAxis = hasRight
    ? [
        { ...valueAxis, name: yName, position: "left" as const },
        // Drop the right axis's splitLine so left-axis gridlines aren't doubled.
        { ...valueAxis, name: undefined, position: "right" as const, splitLine: { show: false } },
      ]
    : { ...valueAxis, name: yName };

  const base = cols.map((name, c) => ({
    type: kindBase,
    name,
    // Single-axis stacked → one "total" group (matches both surfaces' prior
    // output); dual-axis stacked → separate groups per side so each axis stacks
    // independently.
    stack: isStacked
      ? hasRight
        ? seriesSides[c] === "right"
          ? "total-right"
          : "total-left"
        : "total"
      : undefined,
    areaStyle,
    smooth: kindBase === "line",
    // #1091: high-contrast — thicker line strokes + bigger markers for lines,
    // a same-bg border for bars so adjacent bars separate. No-ops when off.
    ...(kindBase === "line" ? { ...lineStyleHC, ...markerHC } : itemStyleHC),
    yAxisIndex: hasRight ? (seriesSides[c] === "right" ? 1 : 0) : 0,
    // Compact (tiles) draws missing cells as gaps; roomy (workspace) as 0.
    data: matrix.map((row) => row[c] ?? (compact ? null : 0)),
  }));
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
  const cartesianTooltip = sparklineTip
    ? { trigger: "axis" as const, ...tooltipStyle, formatter: cartesianTooltipFormatter(cols, matrix, tk) }
    : { trigger: "axis" as const, ...tooltipStyle };

  return {
    ...common,
    title,
    tooltip: cartesianTooltip,
    legend,
    // Reserve room for the zoom slider only when it's actually drawn —
    // otherwise the plot area gets squashed for an affordance that
    // isn't present (saiku follow-up to #1080 + screenshot 2026-06-07).
    grid: compact
      ? { top: 24, left: 48, right: 16, bottom: 36 }
      : {
          left: 60,
          top: title ? 50 : 40,
          right: hasRight ? 60 : 40,
          bottom: rows.length >= ZOOM_SLIDER_MIN_CATEGORIES ? 76 : 40,
        },
    // bar / line / area: x-axis is `rows`.
    dataZoom: dataZoomConfig(compact, rows.length), // #1080: x-axis zoom + pan
    xAxis: {
      ...baseAxis,
      // bar / line / area: x-axis is `rows`. Pass rows.length so the
      // per-label width is computed against the right dim — the old
      // max(rows, cols) cramped scatter / bar single-category x-axes
      // to 40px when the y side had many series ("Cust…" / "CA /…"
      // 2026-06-07 screenshot).
      axisLabel: catLabel(rows.length > 8 ? 30 : 0, rows.length),
      data: rows,
      name: xName,
    },
    yAxis,
    series: [...base, ...trend],
  };
}
