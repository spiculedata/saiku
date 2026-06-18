import { describe, it, expect } from "vitest";
import { buildDimensionTree, isAllLevel } from "../src/lib/dimensionsModel";
import type { CubeSchema } from "../src/lib/types";

const schema: CubeSchema = {
  cubeName: "Sales",
  measures: {},
  dimensions: {
    Product: { hierarchies: { Products: { levels: {
      "(All)": { name: "(All)", uniqueName: "[Product].[Products].[(All)]" },
      "Product Family": { name: "Product Family", uniqueName: "[Product].[Products].[Product Family]" },
      "Product Department": { name: "Product Department", uniqueName: "[Product].[Products].[Product Department]" }
    } } } },
    Time: { hierarchies: {
      Time: { levels: { "(All)": { name: "(All)", uniqueName: "x" }, Year: { name: "Year", uniqueName: "y" } } },
      Weekly: { levels: { Week: { name: "Week", uniqueName: "w" } } }
    } }
  }
};

describe("isAllLevel", () => {
  it("matches (All) variants case-insensitively", () => {
    expect(isAllLevel("(All)")).toBe(true);
    expect(isAllLevel("(all)")).toBe(true);
    expect(isAllLevel("(All Products)")).toBe(true);
    expect(isAllLevel("Product Family")).toBe(false);
  });
});

describe("buildDimensionTree", () => {
  it("groups by dimension, excludes (All), hides single hierarchy label", () => {
    const t = buildDimensionTree(schema);
    const product = t.find((g) => g.dimension === "Product")!;
    expect(product.showHierarchy).toBe(false);
    expect(product.levels.map((l) => l.level)).toEqual(["Product Family", "Product Department"]);
    expect(product.levels[0]).toMatchObject({ dimension: "Product", hierarchy: "Products", level: "Product Family", caption: "Product Family" });
  });
  it("shows hierarchy label when a dimension has multiple hierarchies", () => {
    const time = buildDimensionTree(schema).find((g) => g.dimension === "Time")!;
    expect(time.showHierarchy).toBe(true);
    expect(time.levels.map((l) => `${l.hierarchy}:${l.level}`)).toEqual(["Time:Year", "Weekly:Week"]);
  });
  it("filters levels by case-insensitive search and drops empty groups", () => {
    const t = buildDimensionTree(schema, "year");
    expect(t.map((g) => g.dimension)).toEqual(["Time"]);
    expect(t[0].levels.map((l) => l.level)).toEqual(["Year"]);
  });
});
