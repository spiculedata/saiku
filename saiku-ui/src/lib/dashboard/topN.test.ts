import { describe, expect, it } from "vitest";
import { buildTopNQuery, clampTopN, rankedCaptionsToUniqueNames } from "./topN";

describe("buildTopNQuery", () => {
  it("ranks descending for 'top' with order+limit", () => {
    const q = buildTopNQuery("cube/id", "Customer", "Customers", "Name", "Store Sales", 5, "top");
    expect(q).toEqual({
      cube: "cube/id",
      measures: [{ name: "Store Sales" }],
      rows: [{ dimension: "Customer", hierarchy: "Customers", level: "Name" }],
      order: [{ by: "Store Sales", direction: "desc" }],
      limit: 5,
    });
  });

  it("ranks ascending for 'bottom'", () => {
    const q = buildTopNQuery("c", "D", "H", "L", "M", 3, "bottom") as { order: { direction: string }[] };
    expect(q.order[0].direction).toBe("asc");
  });

  it("floors + min-clamps the limit", () => {
    expect((buildTopNQuery("c", "D", "H", "L", "M", 2.9, "top") as { limit: number }).limit).toBe(2);
    expect((buildTopNQuery("c", "D", "H", "L", "M", 0, "top") as { limit: number }).limit).toBe(1);
  });
});

describe("rankedCaptionsToUniqueNames", () => {
  const catalogue = [
    { caption: "Food", uniqueName: "[Product].[Products].[Food]" },
    { caption: "Drink", uniqueName: "[Product].[Products].[Drink]" },
    { caption: "Non-Consumable", uniqueName: "[Product].[Products].[Non-Consumable]" },
  ];

  it("maps captions to unique names preserving the ranked order", () => {
    expect(rankedCaptionsToUniqueNames(["Drink", "Food"], catalogue)).toEqual([
      "[Product].[Products].[Drink]",
      "[Product].[Products].[Food]",
    ]);
  });

  it("drops captions with no catalogue match", () => {
    expect(rankedCaptionsToUniqueNames(["Food", "Ghost"], catalogue)).toEqual(["[Product].[Products].[Food]"]);
  });

  it("is empty for an empty catalogue", () => {
    expect(rankedCaptionsToUniqueNames(["Food"], [])).toEqual([]);
  });
});

describe("clampTopN", () => {
  it("defaults, floors, and clamps to 1..1000", () => {
    expect(clampTopN(undefined)).toBe(10);
    expect(clampTopN(0)).toBe(1);
    expect(clampTopN(12.7)).toBe(12);
    expect(clampTopN(5000)).toBe(1000);
  });
});
