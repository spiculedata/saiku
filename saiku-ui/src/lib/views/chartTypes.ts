export type ChartType =
  | "bar"
  | "stackedBar"
  | "line"
  | "stackedLine"
  | "area"
  | "stackedArea"
  | "pie"
  | "heatmap"
  | "radar"
  | "scatter";

export const CHART_TYPES: { id: ChartType; label: string }[] = [
  { id: "bar", label: "Bar" },
  { id: "stackedBar", label: "Stacked bar" },
  { id: "line", label: "Line" },
  { id: "stackedLine", label: "Stacked line" },
  { id: "area", label: "Area" },
  { id: "stackedArea", label: "Stacked area" },
  { id: "pie", label: "Pie" },
  { id: "heatmap", label: "Heatmap" },
  { id: "radar", label: "Radar" },
  { id: "scatter", label: "Scatter" },
];
