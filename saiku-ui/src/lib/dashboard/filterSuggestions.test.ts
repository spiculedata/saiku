/*
 * Unit tests for the filter-suggestion scanner.
 */

import { describe, test, expect } from "vitest";
import {
  pruneAlreadyExposed,
  suggestFiltersForTiles,
} from "$lib/dashboard/filterSuggestions";
import type { DashboardTile, CubeRef } from "$lib/api/dashboards";

const SALES: CubeRef = {
  connectionName: "foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "Sales",
};

const HR: CubeRef = {
  connectionName: "foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "HR",
};

function chartTile(id: string, cube: CubeRef, body: Record<string, unknown>): DashboardTile {
  return {
    id,
    x: 0,
    y: 0,
    w: 6,
    h: 4,
    type: "chart",
    cube,
    query: { kind: "inline", body },
  };
}

describe("suggestFiltersForTiles", () => {
  test("collects unique dim/hier/level triples across rows/columns/filters", () => {
    const tile = chartTile("t1", SALES, {
      rows: [
        { dimension: "Store", hierarchy: "Stores", level: "Store Country" },
        { dimension: "Store", hierarchy: "Stores", level: "Store State" },
      ],
      columns: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
      filters: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
    });

    const out = suggestFiltersForTiles([tile]);
    expect(out).toHaveLength(4);
    const labels = out.map((s) => `${s.dimension}/${s.hierarchy}/${s.level}`).sort();
    expect(labels).toEqual([
      "Product/Products/Product Family",
      "Store/Stores/Store Country",
      "Store/Stores/Store State",
      "Time/Time/Year",
    ]);
  });

  test("dedupes across tiles and records contributing tile ids", () => {
    const t1 = chartTile("t1", SALES, {
      rows: [{ dimension: "Store", hierarchy: "Stores", level: "Store State" }],
    });
    const t2 = chartTile("t2", SALES, {
      rows: [{ dimension: "Store", hierarchy: "Stores", level: "Store State" }],
    });
    const out = suggestFiltersForTiles([t1, t2]);
    expect(out).toHaveLength(1);
    expect(out[0].contributingTileIds).toEqual(["t1", "t2"]);
  });

  test("scopes suggestions by cube identity (same dim/hier/level on two cubes = two suggestions)", () => {
    const salesTile = chartTile("s", SALES, {
      rows: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
    });
    const hrTile = chartTile("h", HR, {
      rows: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
    });
    const out = suggestFiltersForTiles([salesTile, hrTile]);
    expect(out).toHaveLength(2);
    expect(out.map((s) => s.cube.cubeName).sort()).toEqual(["HR", "Sales"]);
  });

  test("ignores reference tiles, filter tiles, text tiles", () => {
    const ref: DashboardTile = {
      id: "r",
      x: 0,
      y: 0,
      w: 6,
      h: 4,
      type: "chart",
      cube: SALES,
      query: { kind: "reference", path: "/q/foo.saiku" },
    };
    const text: DashboardTile = {
      id: "t",
      x: 0,
      y: 0,
      w: 6,
      h: 2,
      type: "text",
      text: "hi",
    };
    expect(suggestFiltersForTiles([ref, text])).toEqual([]);
  });

  test("skips entries with missing dim/hier/level", () => {
    const tile = chartTile("t", SALES, {
      rows: [
        { dimension: "Store", level: "Store State" }, // hierarchy missing
        { hierarchy: "Stores", level: "Store State" }, // dim missing
        { dimension: "Time", hierarchy: "Time" }, // level missing
      ],
    });
    expect(suggestFiltersForTiles([tile])).toEqual([]);
  });
});

describe("pruneAlreadyExposed", () => {
  test("drops suggestions whose target matches an existing filter-widget tile", () => {
    const chart = chartTile("c", SALES, {
      rows: [{ dimension: "Store", hierarchy: "Stores", level: "Store State" }],
    });
    const widget: DashboardTile = {
      id: "w",
      x: 0,
      y: 4,
      w: 6,
      h: 2,
      type: "filter",
      cube: SALES,
      target: {
        dimension: "Store",
        hierarchy: "Stores",
        level: "Store State",
        members: [],
      },
      widget: "single-select",
    };
    const suggestions = suggestFiltersForTiles([chart, widget]);
    const pruned = pruneAlreadyExposed(suggestions, [chart, widget]);
    expect(pruned).toEqual([]);
  });

  test("keeps suggestions for targets a widget on a different cube already exposes", () => {
    const chart = chartTile("c", SALES, {
      rows: [{ dimension: "Time", hierarchy: "Time", level: "Year" }],
    });
    const hrWidget: DashboardTile = {
      id: "w",
      x: 0,
      y: 4,
      w: 6,
      h: 2,
      type: "filter",
      cube: HR,
      target: { dimension: "Time", hierarchy: "Time", level: "Year", members: [] },
      widget: "single-select",
    };
    const suggestions = suggestFiltersForTiles([chart, hrWidget]);
    const pruned = pruneAlreadyExposed(suggestions, [chart, hrWidget]);
    expect(pruned).toHaveLength(1);
    expect(pruned[0].cube.cubeName).toBe("Sales");
  });
});
