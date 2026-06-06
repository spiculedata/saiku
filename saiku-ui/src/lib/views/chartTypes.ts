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
  | "waterfall";

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
];

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
};
