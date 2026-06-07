export type ChartType =
  | "bar"
  | "stackedBar"
  | "line"
  | "stackedLine"
  | "area"
  | "stackedArea"
  | "pie"
  | "donut"
  | "heatmap"
  | "radar"
  | "scatter"
  | "bubble"
  | "treemap"
  | "sunburst"
  | "waterfall"
  | "map";

export const CHART_TYPES: { id: ChartType; label: string; group: string }[] = [
  { id: "bar", label: "Bar", group: "Bars" },
  { id: "stackedBar", label: "Stacked bar", group: "Bars" },
  { id: "waterfall", label: "Waterfall", group: "Bars" },
  { id: "line", label: "Line", group: "Lines" },
  { id: "stackedLine", label: "Stacked line", group: "Lines" },
  { id: "area", label: "Area", group: "Lines" },
  { id: "stackedArea", label: "Stacked area", group: "Lines" },
  { id: "pie", label: "Pie", group: "Proportional" },
  { id: "donut", label: "Donut", group: "Proportional" },
  { id: "treemap", label: "Treemap", group: "Proportional" },
  { id: "sunburst", label: "Sunburst", group: "Proportional" },
  { id: "heatmap", label: "Heatmap", group: "Matrix" },
  { id: "radar", label: "Radar", group: "Matrix" },
  { id: "scatter", label: "Scatter", group: "Points" },
  { id: "bubble", label: "Bubble", group: "Points" },
  // issue #1071: world-countries choropleth (Phase 1). Place names come from
  // the row hierarchy; the active (first) measure drives the colour.
  { id: "map", label: "Map (choropleth)", group: "Geo" },
];

/** issue #1071: sequential / diverging colour ramps for the map visualMap. */
export type ChartColorRamp = "blues" | "greens" | "reds" | "viridis" | "diverging";

/** Set of all supported chart-type ids, derived from CHART_TYPES so it can
 *  never drift from the palette. */
const CHART_TYPE_SET: ReadonlySet<string> = new Set(CHART_TYPES.map((c) => c.id));

/** Type guard: is `kind` one of the supported chart types? The single source
 *  of truth for "can we render this kind", shared by the workspace and the
 *  dashboard (re-exported from chartOptions.ts as isSupportedChartKind). */
export function isChartType(kind: string): kind is ChartType {
  return CHART_TYPE_SET.has(kind);
}

export type TrendLineMode = "none" | "linear" | "ma" | "wma";

/** issue #1082: number-formatting controls for chart VALUE text — applied to
 *  the value-axis labels, tooltip values and (when shown) series data labels.
 *  All fields optional; an absent/empty `numberFormat` renders raw values
 *  exactly as before (legacy charts are unchanged). The category axis is never
 *  reformatted. */
export interface NumberFormatOptions {
  /** Prepended verbatim, e.g. "£", "$". */
  prefix?: string;
  /** Appended verbatim, e.g. "%", " ms". */
  suffix?: string;
  /** Fixed decimal places. null/undefined = auto (natural precision). */
  decimals?: number | null;
  /** Group integer digits with locale thousands separators. */
  thousands?: boolean;
  /** Collapse large magnitudes to k / M / B / T. */
  abbreviate?: boolean;
}

export interface ChartOptions {
  title: string;
  xAxisLabel: string;
  yAxisLabel: string;
  showLegend: boolean;
  legendPosition: "top" | "bottom" | "left" | "right";
  trendLine: TrendLineMode;
  trendPeriod: number;
  /** When a multi-level hierarchy is on ROWS (e.g. Year + Quarter), the
   *  cellset includes both rollup rows ("2024", "2025") and leaf rows
   *  ("2024 Q1", ...). The rollups are sums of their children so they
   *  dominate every bar height and make the leaves unreadable on a chart.
   *  This flag drops rollup rows from the chart only — the grid still
   *  shows them. Defaults to `true`. */
  hideRollupRows: boolean;
  /** Auto-split series across two y-axes when their magnitudes differ
   *  by more than the SERIES_AXIS_THRESHOLD ratio (currently 1%). Use
   *  case: charting an Event Count series in thousands alongside an
   *  Avg Tone series in single digits — without dual-axis the Tone
   *  series gets crushed to the zero line. Per-series picks in
   *  {@link seriesAxis} always override the auto decision. */
  dualAxis: boolean;
  /** Per-series y-axis override, keyed by column-category name (the
   *  same labels rendered in the legend). Values: "left" or "right".
   *  Absent entries fall back to the auto decision (or "left" when
   *  {@link dualAxis} is off). */
  seriesAxis: Record<string, "left" | "right">;
  /** issue #1071 (map only): colour ramp for the choropleth visualMap. */
  colorRamp: ChartColorRamp;
  /** issue #1071 (map only): how to render a country present in the data
   *  with a missing/null measure — "blank" leaves it the map's grey
   *  unmapped colour, "zero" colours it at the ramp's low end. */
  mapMissing: "blank" | "zero";
  /** issue #1082: optional number-formatting for VALUE text (axis labels,
   *  tooltip values, data labels). Undefined/empty = raw values as today. */
  numberFormat?: NumberFormatOptions;
}

/** Auto-split threshold: a series whose maximum absolute value is less
 *  than this fraction of the largest series's max-abs is moved to the
 *  right y-axis. 1% is conservative enough that homogeneous-magnitude
 *  charts (Sales £ vs Refunds £) stay on a single axis. */
export const SERIES_AXIS_THRESHOLD = 0.01;

export const DEFAULT_CHART_OPTIONS: ChartOptions = {
  title: "",
  xAxisLabel: "",
  yAxisLabel: "",
  showLegend: true,
  legendPosition: "top",
  trendLine: "none",
  trendPeriod: 3,
  hideRollupRows: true,
  dualAxis: true,
  seriesAxis: {},
  colorRamp: "blues",
  mapMissing: "blank",
};
