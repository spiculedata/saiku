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

export type TrendLineMode = "none" | "linear" | "ma" | "wma";

export interface ChartOptions {
  title: string;
  xAxisLabel: string;
  yAxisLabel: string;
  showLegend: boolean;
  legendPosition: "top" | "bottom" | "left" | "right";
  trendLine: TrendLineMode;
  trendPeriod: number;
}

export const DEFAULT_CHART_OPTIONS: ChartOptions = {
  title: "",
  xAxisLabel: "",
  yAxisLabel: "",
  showLegend: true,
  legendPosition: "top",
  trendLine: "none",
  trendPeriod: 3,
};
