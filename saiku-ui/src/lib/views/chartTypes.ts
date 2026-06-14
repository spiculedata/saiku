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

/** issue #1079: a single reference / annotation line drawn across a cartesian
 *  chart (ECharts markLine). `axis:"y"` draws a horizontal line at the value
 *  (a threshold / target); `axis:"x"` draws a vertical line at the category
 *  index `value` (e.g. a "campaign launched" marker). `label` annotates it;
 *  `color` overrides the theme default. */
export interface ReferenceLine {
  axis: "x" | "y";
  value: number;
  label?: string;
  color?: string;
}

/** issue #1079: a shaded reference band (ECharts markArea) spanning [from, to]
 *  on the given axis — a target range / SLA window / highlighted period. */
export interface ReferenceBand {
  axis: "x" | "y";
  from: number;
  to: number;
  label?: string;
  color?: string;
}

/** issue #1084: one threshold rule in a conditional-format band. First rule
 *  whose comparison matches a data point wins, recolouring that point. */
export type ChartCondOp = "gt" | "gte" | "lt" | "lte" | "between";
export interface ChartCondRule {
  op: ChartCondOp;
  /** Comparison operand. A `[min, max]` tuple for `between` (inclusive),
   *  a single number otherwise. */
  value: number | [number, number];
  /** Colour applied to a matching data point (any CSS colour). */
  color: string;
  /** Optional legend/label for the rule (reserved for a future legend). */
  label?: string;
}

/** issue #1084: per-measure conditional formatting — threshold-driven colours
 *  on a chart series' data points (e.g. red below target, green above).
 *  Optional; legacy charts (undefined / []) render with the normal palette. */
export interface ChartConditionalFormat {
  /** Which measure/series (column index) the rules apply to. */
  measureIndex: number;
  /** Rules in priority order — first match wins per data point. */
  rules: ChartCondRule[];
  /** Colour for points matching no rule. Omit to keep the palette colour. */
  fallbackColor?: string;
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
  /** Auto-split series across two y-axes when a series's maximum is at
   *  or below {@link SERIES_AXIS_THRESHOLD} (10%) of the dominant
   *  series's maximum. That's roughly "more than an order of magnitude
   *  apart" — the visual cliff where the smaller series gets crushed
   *  to the zero line of a left axis dominated by the larger one
   *  (Balance £7k vs Fees £75; Event Count in thousands vs Avg Tone
   *  in single digits). Per-series picks in {@link seriesAxis} always
   *  override the auto decision. */
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
  /** Client-side category sort (#1083) — sorts the cartesian x-axis by
   *  the first measure column without re-querying. "none" preserves
   *  query order. Moved from ChartView's transient local state into
   *  the persisted options bag in saiku 2026-06 so it can be tweaked
   *  via the chart-options modal rather than a separate toolbar above
   *  the canvas. */
  sortDirection: "none" | "asc" | "desc";
  /** Client-side top-N category limit (#1083). `null` = show all
   *  categories; otherwise trim to the top N after sorting. Saved
   *  alongside {@link sortDirection} so the cleanup-after-export look
   *  persists with the tile. */
  topN: number | null;
  /** issue #1082: optional number-formatting for VALUE text (axis labels,
   *  tooltip values, data labels). Undefined/empty = raw values as today. */
  numberFormat?: NumberFormatOptions;
  /** issue #1081: named categorical palette id (see NAMED_PALETTES in
   *  chartTheme.ts — "default", "vibrant", "cool", "warm", "earth"). Optional;
   *  legacy charts predating #1081 omit it and fall back to the theme default
   *  palette (tk.chartColors), so their output is byte-for-byte unchanged.
   *  Precedence: per-series {@link seriesColors} > colour-blind-safe (when the
   *  global pref is on) > this named palette > theme default. */
  palette?: string;
  /** issue #1081: per-series colour override, keyed by series (column-category)
   *  name — the same labels rendered in the legend / used by {@link seriesAxis}.
   *  Values are hex colours. An entry wins over the named palette AND the
   *  colour-blind-safe palette for that one series. Optional; legacy charts omit
   *  it (treated as {}) so nothing overrides and their output is unchanged. */
  seriesColors?: Record<string, string>;
  /** issue #1079: reference / annotation lines drawn over CARTESIAN charts
   *  (bar/line/area/scatter/column) as ECharts markLines. Optional; legacy
   *  charts (undefined / []) get no markLine and render exactly as before. */
  referenceLines?: ReferenceLine[];
  /** issue #1079: shaded reference bands (ECharts markArea) over cartesian
   *  charts. Optional; undefined / [] adds no markArea. */
  referenceBands?: ReferenceBand[];
  /** issue #1084: threshold-driven conditional colours per measure (bar /
   *  stackedBar / scatter / bubble in Phase 1). Optional; undefined / [] keeps
   *  the normal palette so legacy charts are unchanged. */
  conditionalFormat?: ChartConditionalFormat[];
}

/** Auto-split threshold: a series whose maximum absolute value is at
 *  or below this fraction of the largest series's max-abs is moved to
 *  the right y-axis. 10% ≈ "more than an order of magnitude apart" —
 *  bumped from 1% (which only fired at 100x ratios — Balance vs Fees
 *  in the user-reported 2026-06-07 screenshot was exactly 1% and so
 *  stayed crushed on the left axis). Series within ~10% of dominant
 *  still share an axis (Sales £ ≈ Refunds £). */
export const SERIES_AXIS_THRESHOLD = 0.1;

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
  sortDirection: "none",
  topN: null,
  // #1081: inert defaults — "default" resolves to the theme palette and an
  // empty override map applies nothing, so legacy charts are unchanged.
  palette: "default",
  seriesColors: {},
  // issue #1079: inert defaults — no reference lines/bands on legacy charts.
  referenceLines: [],
  referenceBands: [],
  // issue #1084: inert default — no conditional colour rules on legacy charts.
  conditionalFormat: [],
};
