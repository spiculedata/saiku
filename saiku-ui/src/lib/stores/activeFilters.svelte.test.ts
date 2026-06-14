/*
 * Unit tests for the brush cross-filter mutators on the active-filters store
 * (saiku#1085). Panel filters derive from the dashboard store (empty here), so
 * these focus on the transient click/cross buffers and the merged `all` set.
 */

import { beforeEach, describe, expect, test } from "vitest";
import { activeFilters } from "./activeFilters.svelte";
import type { DashboardFilter } from "$lib/api/dashboards";

function filter(members: string[], level = "Product Family"): DashboardFilter {
  return { dimension: "Product", hierarchy: "Products", level, members };
}

beforeEach(() => {
  activeFilters.resetTransient(); // clear clicks + crosses between tests
});

describe("pushCross", () => {
  test("adds a cross-filter tagged with the source tile id", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    expect(activeFilters.crosses).toHaveLength(1);
    expect(activeFilters.crosses[0].source).toEqual({ kind: "cross", tileId: "tile-a" });
    expect(activeFilters.crosses[0].filter.members).toEqual(["[Product].[Products].[Drink]"]);
  });

  test("a fresh brush on the SAME source tile replaces its previous selection", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.pushCross(filter(["[Product].[Products].[Food]", "[Product].[Products].[Drink]"]), "tile-a");
    expect(activeFilters.crosses).toHaveLength(1);
    expect(activeFilters.crosses[0].filter.members).toHaveLength(2);
  });

  test("different source tiles each keep their own cross-filter", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.pushCross(filter(["[Product].[Products].[Food]"]), "tile-b");
    expect(activeFilters.crosses).toHaveLength(2);
  });

  test("empty members[] clears the source tile's cross-filter", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.pushCross(filter([]), "tile-a");
    expect(activeFilters.crosses).toHaveLength(0);
  });
});

describe("clearCrossesFrom", () => {
  test("drops only the named source tile's cross-filter", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.pushCross(filter(["[Product].[Products].[Food]"]), "tile-b");
    activeFilters.clearCrossesFrom("tile-a");
    expect(activeFilters.crosses).toHaveLength(1);
    expect(activeFilters.crosses[0].source).toEqual({ kind: "cross", tileId: "tile-b" });
  });

  test("is a no-op for a tile with no cross-filter", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.clearCrossesFrom("tile-z");
    expect(activeFilters.crosses).toHaveLength(1);
  });
});

describe("all + clearChip", () => {
  test("merged `all` includes the cross-filter (no dashboard panel here)", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    const crossInAll = activeFilters.all.find((f) => f.source.kind === "cross");
    expect(crossInAll).toBeTruthy();
    expect(crossInAll?.filter.members).toEqual(["[Product].[Products].[Drink]"]);
  });

  test("clearChip removes a cross-filter by id", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    const id = activeFilters.crosses[0].id;
    activeFilters.clearChip(id);
    expect(activeFilters.crosses).toHaveLength(0);
  });
});

describe("resetTransient", () => {
  test("wipes cross-filters (dashboard swap)", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.resetTransient();
    expect(activeFilters.crosses).toHaveLength(0);
  });
});
