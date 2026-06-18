import { describe, it, expect } from "vitest";
import { buildRequest } from "../src/lib/queryModel";

describe("queryModel", () => {
  it("maps wizard picks to AiQueryRequest", () => {
    const req = buildRequest({
      cubeId: "c/cat/s/Sales",
      measures: ["Unit Sales", "Store Sales"],
      dimensions: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
      filters: [{ dimension: "Time", hierarchy: "Time", level: "Year", members: ["[Time].[1997]"] }]
    });
    expect(req.cube).toBe("c/cat/s/Sales");
    expect(req.measures).toEqual([{ name: "Unit Sales" }, { name: "Store Sales" }]);
    expect(req.rows).toEqual([{ dimension: "Product", hierarchy: "Products", level: "Product Family" }]);
    expect(req.filters).toEqual([{ dimension: "Time", hierarchy: "Time", level: "Year", op: "in", members: ["[Time].[1997]"] }]);
    expect(req.nonEmpty).toBe(true);
  });
  it("omits filters when none", () => {
    const req = buildRequest({ cubeId: "x", measures: ["M"], dimensions: [], filters: [] });
    expect(req.filters).toBeUndefined();
  });
});
