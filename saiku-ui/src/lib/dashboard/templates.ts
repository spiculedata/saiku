/*
 * Starter templates / sample dashboards (#938).
 *
 * Pure, client-side seed data: each template is a small set of laid-out
 * tiles plus a name + description. They give a new dashboard a useful
 * starting shape instead of a blank canvas — the user picks one when
 * creating a dashboard, the tiles are dropped into a fresh (unsaved)
 * Dashboard, and the user edits / binds them to cube content from there.
 *
 * Tiles are bound to FoodMart-ish content (Unit Sales, Store Sales,
 * the Store / Time / Product dimensions) where reasonable, but they're
 * deliberately generic starting points. A template tile carries no
 * cube binding: it lays out the type + title + chart-type / KPI shape,
 * and the analyst points each tile at a cube via the tile editor after
 * instantiation. This keeps templates safe (no dangling references to a
 * cube the user can't see) and 100% frontend-only.
 *
 * No DOM, no fetch — instantiateTemplate is unit-tested in
 * templates.test.ts. The UI (NewDashboardModal) renders the catalogue
 * from TEMPLATES and calls instantiateTemplate() on pick.
 */

import { newDashboard, type Dashboard, type DashboardTile } from "$lib/api/dashboards";

/** A single tile within a template. Same shape as {@link DashboardTile}
 *  minus the `id` (minted fresh on instantiation) — position, size, type,
 *  title and the per-type display config (chartType / kpi) are carried so
 *  the instantiated dashboard renders with a sensible layout out of the
 *  box. Cube bindings are intentionally omitted; the analyst wires those
 *  up per tile after picking the template. */
export type TemplateTile = Omit<DashboardTile, "id">;

/** A starter template: a name + description shown in the picker, and the
 *  tiles laid out on a 12-column grid. Kept as pure data so it serialises
 *  trivially and unit-tests without a DOM. */
export interface DashboardTemplate {
  /** Stable id used as the picker's selection key. */
  id: string;
  /** Display name shown on the template card. */
  name: string;
  /** One-line description of what the template lays out. */
  description: string;
  /** Tiles laid out on the 12-column grid (no overlaps; see test). */
  tiles: TemplateTile[];
}

/* ------------------------------------------------------------------ */
/* Template definitions.                                              */
/*                                                                    */
/* Layout convention: 12-column grid. KPI cards are 3×2 and sit in a  */
/* row across the top; charts/tables are 6×4 half-width; text tiles   */
/* are 6×2 / 12×2 banners. Coordinates are hand-checked to not        */
/* overlap (templateTilesValid() in the test enforces this).          */
/* ------------------------------------------------------------------ */

/** KPI dashboard — four headline numbers across the top with a
 *  supporting trend chart and a breakdown table. */
const KPI_DASHBOARD: DashboardTemplate = {
  id: "kpi-dashboard",
  name: "KPI dashboard",
  description: "Four headline KPI cards with a trend chart and a breakdown table.",
  tiles: [
    { x: 0, y: 0, w: 3, h: 2, type: "kpi", title: "Unit Sales", kpi: { format: "number" } },
    { x: 3, y: 0, w: 3, h: 2, type: "kpi", title: "Store Sales", kpi: { format: "currency" } },
    { x: 6, y: 0, w: 3, h: 2, type: "kpi", title: "Store Cost", kpi: { format: "currency" } },
    {
      x: 9,
      y: 0,
      w: 3,
      h: 2,
      type: "kpi",
      title: "Profit Margin",
      kpi: { format: "percent", direction: "higher-is-better" },
    },
    { x: 0, y: 2, w: 6, h: 4, type: "chart", title: "Sales trend", chartType: "line" },
    { x: 6, y: 2, w: 6, h: 4, type: "table", title: "Breakdown" },
  ],
};

/** Sales report — a title banner, a sales-by-category bar chart, a
 *  sales-over-time line chart, and a detail table. */
const SALES_REPORT: DashboardTemplate = {
  id: "sales-report",
  name: "Sales report",
  description: "Title banner, sales-by-category and over-time charts, plus a detail table.",
  tiles: [
    {
      x: 0,
      y: 0,
      w: 12,
      h: 2,
      type: "text",
      title: "Header",
      text: "# Sales report\n\nSales performance overview. Edit each tile to bind it to your cube.",
    },
    { x: 0, y: 2, w: 6, h: 4, type: "chart", title: "Sales by category", chartType: "bar" },
    { x: 6, y: 2, w: 6, h: 4, type: "chart", title: "Sales over time", chartType: "line" },
    { x: 0, y: 6, w: 12, h: 4, type: "table", title: "Sales detail" },
  ],
};

/** Operations summary — three operational KPI cards, an inventory bar
 *  chart, and a regional breakdown table. */
const OPERATIONS_SUMMARY: DashboardTemplate = {
  id: "operations-summary",
  name: "Operations summary",
  description: "Operational KPI cards with an inventory chart and a regional breakdown table.",
  tiles: [
    { x: 0, y: 0, w: 4, h: 2, type: "kpi", title: "Units shipped", kpi: { format: "number" } },
    { x: 4, y: 0, w: 4, h: 2, type: "kpi", title: "Warehouse cost", kpi: { format: "currency" } },
    {
      x: 8,
      y: 0,
      w: 4,
      h: 2,
      type: "kpi",
      title: "Fill rate",
      kpi: { format: "percent", direction: "higher-is-better" },
    },
    { x: 0, y: 2, w: 6, h: 4, type: "chart", title: "Inventory by store", chartType: "bar" },
    { x: 6, y: 2, w: 6, h: 4, type: "table", title: "By region" },
  ],
};

/** Time-series analysis — a wide trend line, a period-over-period bar,
 *  and a supporting detail table. */
const TIME_SERIES_ANALYSIS: DashboardTemplate = {
  id: "time-series-analysis",
  name: "Time-series analysis",
  description: "A wide trend line, a period-over-period bar chart, and a detail table.",
  tiles: [
    { x: 0, y: 0, w: 12, h: 4, type: "chart", title: "Trend over time", chartType: "line" },
    { x: 0, y: 4, w: 6, h: 4, type: "chart", title: "Period over period", chartType: "bar" },
    { x: 6, y: 4, w: 6, h: 4, type: "table", title: "Detail by period" },
  ],
};

/** Geographic overview — a choropleth map of a measure by country
 *  (uses the #1071 `map` chart type), two headline KPIs, and a regional
 *  breakdown table. Bind the map's rows to a geography level (e.g. Store
 *  Country) — common aliases like USA/UK are matched automatically. */
const GEOGRAPHIC_OVERVIEW: DashboardTemplate = {
  id: "geographic-overview",
  name: "Geographic overview",
  description: "A choropleth map of a measure by country, KPI cards, and a regional table.",
  tiles: [
    { x: 0, y: 0, w: 8, h: 4, type: "chart", title: "Sales by country", chartType: "map" },
    { x: 8, y: 0, w: 4, h: 2, type: "kpi", title: "Total sales", kpi: { format: "currency" } },
    { x: 8, y: 2, w: 4, h: 2, type: "kpi", title: "Countries", kpi: { format: "number" } },
    { x: 0, y: 4, w: 12, h: 4, type: "table", title: "Sales by region" },
  ],
};

/** Product performance — headline product KPIs, a top-products bar chart,
 *  a category-share donut, and a product detail table. */
const PRODUCT_PERFORMANCE: DashboardTemplate = {
  id: "product-performance",
  name: "Product performance",
  description: "Product KPIs, a top-products bar, a category-share donut, and a detail table.",
  tiles: [
    { x: 0, y: 0, w: 4, h: 2, type: "kpi", title: "Units sold", kpi: { format: "number" } },
    { x: 4, y: 0, w: 4, h: 2, type: "kpi", title: "Revenue", kpi: { format: "currency" } },
    { x: 8, y: 0, w: 4, h: 2, type: "kpi", title: "Avg price", kpi: { format: "currency" } },
    { x: 0, y: 2, w: 6, h: 4, type: "chart", title: "Top products", chartType: "bar" },
    { x: 6, y: 2, w: 6, h: 4, type: "chart", title: "Category share", chartType: "donut" },
    { x: 0, y: 6, w: 12, h: 4, type: "table", title: "Product detail" },
  ],
};

/** Executive overview — four headline KPIs, a revenue trend line, a
 *  revenue-by-segment donut, and a top-accounts table. */
const EXECUTIVE_OVERVIEW: DashboardTemplate = {
  id: "executive-overview",
  name: "Executive overview",
  description: "Headline KPIs, a revenue trend, a revenue-by-segment donut, and a top-accounts table.",
  tiles: [
    { x: 0, y: 0, w: 3, h: 2, type: "kpi", title: "Revenue", kpi: { format: "currency" } },
    { x: 3, y: 0, w: 3, h: 2, type: "kpi", title: "Orders", kpi: { format: "number" } },
    { x: 6, y: 0, w: 3, h: 2, type: "kpi", title: "Customers", kpi: { format: "number" } },
    {
      x: 9,
      y: 0,
      w: 3,
      h: 2,
      type: "kpi",
      title: "Margin",
      kpi: { format: "percent", direction: "higher-is-better" },
    },
    { x: 0, y: 2, w: 8, h: 4, type: "chart", title: "Revenue trend", chartType: "line" },
    { x: 8, y: 2, w: 4, h: 4, type: "chart", title: "Revenue by segment", chartType: "donut" },
    { x: 0, y: 6, w: 12, h: 3, type: "table", title: "Top accounts" },
  ],
};

/** All bundled starter templates, in picker display order. */
export const TEMPLATES: ReadonlyArray<DashboardTemplate> = [
  KPI_DASHBOARD,
  SALES_REPORT,
  OPERATIONS_SUMMARY,
  TIME_SERIES_ANALYSIS,
  GEOGRAPHIC_OVERVIEW,
  PRODUCT_PERFORMANCE,
  EXECUTIVE_OVERVIEW,
];

/** Look up a template by id. Returns `undefined` when no template
 *  matches (e.g. a stale selection key). */
export function getTemplate(id: string): DashboardTemplate | undefined {
  return TEMPLATES.find((t) => t.id === id);
}

/** Build a fresh, unsaved {@link Dashboard} seeded with the template's
 *  tiles. Each tile gets a fresh id (via the supplied {@code mintId}, so
 *  the caller controls id generation and the function stays pure) and the
 *  template's layout/title/config is copied verbatim. The dashboard is
 *  otherwise a standard blank dashboard (12-col grid, no filters) — the
 *  caller is responsible for marking it dirty / prompting for a save path.
 *
 *  Pure: the template is never mutated; the returned tiles are fresh
 *  objects. */
export function instantiateTemplate(
  template: DashboardTemplate,
  mintId: () => string,
  name?: string,
): Dashboard {
  const base = newDashboard(name ?? template.name);
  const tiles: DashboardTile[] = template.tiles.map((t) => ({ ...t, id: mintId() }));
  return {
    ...base,
    layout: { ...base.layout, tiles },
  };
}
