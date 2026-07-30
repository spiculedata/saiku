import { describe, expect, test } from "vitest";

import { asPageGrid, dashboardToPageGrid, pageGridToDashboard } from "./appPageView";
import type { AppPage } from "$lib/api/apps";
import type { DashboardTile } from "$lib/api/dashboards";

function page(grid: unknown, over: Partial<AppPage> = {}): AppPage {
  return { id: "page-1", title: "Overview", grid, ...over };
}

describe("asPageGrid", () => {
  test("passes an object grid through", () => {
    const grid = { cols: 12, tiles: [] };
    expect(asPageGrid(grid)).toBe(grid);
  });

  test("collapses a non-object grid to an empty grid", () => {
    expect(asPageGrid(null)).toEqual({});
    expect(asPageGrid(undefined)).toEqual({});
    expect(asPageGrid("nonsense")).toEqual({});
  });
});

describe("pageGridToDashboard — parity / back-compat", () => {
  // A classic dashboard is a 1-page app whose page.grid IS the dashboard
  // layout. The app path must hand that layout to the SAME grid renderer
  // without mutating or wrapping it, so it renders byte-identical to the
  // standalone dashboard viewer.
  test("passes the grid's layout to the renderer UNCHANGED (no clone, no mutation)", () => {
    const tiles: DashboardTile[] = [
      { id: "t1", type: "kpi", x: 0, y: 0, w: 3, h: 2 },
      { id: "t2", type: "chart", x: 3, y: 0, w: 6, h: 4, chartType: "bar" },
    ];
    const grid = { cols: 10, tiles };
    const before = structuredClone(grid);

    const dashboard = pageGridToDashboard(page(grid));

    // Same cols, and the SAME tiles array by reference — not a copy.
    expect(dashboard.layout.cols).toBe(10);
    expect(dashboard.layout.tiles).toBe(tiles);
    // The source grid was not mutated in the process.
    expect(grid).toEqual(before);
  });

  test("carries the page identity onto the dashboard (id + name)", () => {
    const dashboard = pageGridToDashboard(page({ cols: 12, tiles: [] }, { id: "p9", title: "Sales" }));
    expect(dashboard.id).toBe("p9");
    expect(dashboard.name).toBe("Sales");
  });

  test("carries the grid's filter config through", () => {
    const filterPanel = { collapsed: false, filters: [] };
    const filters = [{ dimension: "Time", hierarchy: "Time", level: "Year", members: [] }];
    const dashboard = pageGridToDashboard(page({ cols: 12, tiles: [], filters, filterPanel }));
    expect(dashboard.filters).toBe(filters);
    expect(dashboard.filterPanel).toBe(filterPanel);
  });

  test("defaults an empty / partial grid without throwing", () => {
    const dashboard = pageGridToDashboard(page(null));
    expect(dashboard.layout.cols).toBe(12);
    expect(dashboard.layout.tiles).toEqual([]);
    expect(dashboard.filters).toEqual([]);
    expect(dashboard.filterPanel).toBeUndefined();
  });
});

describe("dashboardToPageGrid — write-back round-trip", () => {
  test("is the inverse of pageGridToDashboard for a full grid", () => {
    const grid = {
      cols: 8,
      tiles: [{ id: "t1", type: "table", x: 0, y: 0, w: 4, h: 3 }] as DashboardTile[],
      filters: [{ dimension: "Store", hierarchy: "Store", level: "Country", members: [] }],
      filterPanel: { collapsed: true, filters: [] },
    };
    const roundTripped = dashboardToPageGrid(pageGridToDashboard(page(grid)));
    expect(roundTripped).toEqual(grid);
    // References preserved — the write-back doesn't deep-clone the tiles.
    expect(roundTripped.tiles).toBe(grid.tiles);
  });
});
