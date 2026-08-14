/*
 * Unit tests for the brush cross-filter mutators on the active-filters store
 * (saiku#1085). Panel filters derive from the dashboard store (empty here), so
 * these focus on the transient click/cross buffers and the merged `all` set.
 */

import { beforeEach, describe, expect, test } from "vitest";
import { activeFilters, targetKey } from "./activeFilters.svelte";
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
    expect(activeFilters.crosses[0].source).toEqual({
      kind: "cross",
      tileId: "tile-a",
    });
    expect(activeFilters.crosses[0].filter.members).toEqual([
      "[Product].[Products].[Drink]",
    ]);
  });

  test("a fresh brush on the SAME source tile replaces its previous selection", () => {
    activeFilters.pushCross(filter(["[Product].[Products].[Drink]"]), "tile-a");
    activeFilters.pushCross(
      filter(["[Product].[Products].[Food]", "[Product].[Products].[Drink]"]),
      "tile-a",
    );
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
    expect(activeFilters.crosses[0].source).toEqual({
      kind: "cross",
      tileId: "tile-b",
    });
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
    expect(crossInAll?.filter.members).toEqual([
      "[Product].[Products].[Drink]",
    ]);
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

/*
 * App-level filters (saiku#1754). The App Builder's header context pill is app
 * chrome, not page state: its selection must survive the per-page hydrate that
 * wipes clicks/crosses, or a page shows national numbers under a header that
 * claims a region.
 */
describe("app-level filters (saiku#1754)", () => {
  beforeEach(() => {
    activeFilters.clearApp("app-context-pill");
  });

  test("pushApp registers an app-scoped filter", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    expect(activeFilters.appLevel).toHaveLength(1);
    expect(activeFilters.appLevel[0].source).toEqual({
      kind: "app",
      sourceId: "app-context-pill",
    });
  });

  test("pushApp replaces the previous selection for the same target", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[Midwest]"]),
      "app-context-pill",
    );
    expect(activeFilters.appLevel).toHaveLength(1);
    expect(activeFilters.appLevel[0].filter.members).toEqual([
      "[Geography].[Geography].[Midwest]",
    ]);
  });

  test("survives resetTransient — this is the page-switch bug", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    activeFilters.resetTransient();
    expect(activeFilters.appLevel).toHaveLength(1);
    expect(activeFilters.all.some((f) => f.source.kind === "app")).toBe(true);
  });

  test("clearApp drops the selection (the pill's 'All' entry)", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    activeFilters.clearApp("app-context-pill");
    expect(activeFilters.appLevel).toHaveLength(0);
  });

  test("a tile click on the same target outranks the app filter", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    activeFilters.pushClick(
      filter(["[Geography].[Geography].[South]"]),
      "tile-a",
    );
    const winner = activeFilters.all.find(
      (f) => targetKey(f.filter) === "Product/Products/Product Family",
    );
    expect(winner?.filter.members).toEqual(["[Geography].[Geography].[South]"]);
  });

  test("clearChip removes an app filter by id", () => {
    activeFilters.pushApp(
      filter(["[Geography].[Geography].[West]"]),
      "app-context-pill",
    );
    activeFilters.clearChip(activeFilters.appLevel[0].id);
    expect(activeFilters.appLevel).toHaveLength(0);
  });
});
