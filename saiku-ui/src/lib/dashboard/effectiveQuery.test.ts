/*
 * Unit tests for the effective-query builder. Covers:
 *   - applicability resolution + alias matching
 *   - replace-by-hierarchy vs append
 *   - silent drop on missing schema target
 *   - axis-rewrite when the filter hierarchy is already on rows/columns
 *   - immutability: source tile + active filters never mutated
 */

import { describe, test, expect } from "vitest";
import {
  applicableSavedFilters,
  appliesToSchema,
  conflictsWithAxis,
  effectiveQueryFor,
  mergeFilters,
  resolveTarget,
  type AiQueryRequestLike,
  type SchemaLike,
} from "$lib/dashboard/effectiveQuery";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";
import type { DashboardTile } from "$lib/api/dashboards";

function sampleSchema(): SchemaLike {
  return {
    dimensions: {
      time: {
        name: "Time",
        hierarchies: {
          time: {
            name: "Time",
            levels: {
              year: { name: "Year" },
              quarter: { name: "Quarter" },
            },
          },
        },
      },
      product: {
        name: "Product",
        hierarchies: {
          products: {
            name: "Products",
            levels: {
              "product family": { name: "Product Family" },
            },
          },
        },
      },
    },
    dimensionAliases: {
      // Synonym → canonical (lowercased).
      timeframe: "time",
    },
  };
}

function active(dim: string, hier: string, level: string, members: string[] = []): ActiveFilter {
  return {
    id: `${dim}-${hier}-${level}`,
    source: { kind: "panel", filterId: "w1" },
    filter: { dimension: dim, hierarchy: hier, level, members },
  };
}

/* ----------------------------- resolution --------------------------- */

describe("resolveTarget", () => {
  test("returns canonical names for an exact match", () => {
    const r = resolveTarget(sampleSchema(), "Time", "Time", "Year");
    expect(r).toEqual({ dimension: "Time", hierarchy: "Time", level: "Year" });
  });

  test("is case-insensitive and trims whitespace", () => {
    const r = resolveTarget(sampleSchema(), "  TIME  ", "TIME", "year");
    expect(r?.dimension).toBe("Time");
    expect(r?.level).toBe("Year");
  });

  test("resolves via dimensionAliases", () => {
    const r = resolveTarget(sampleSchema(), "Timeframe", "Time", "Year");
    expect(r?.dimension).toBe("Time");
  });

  test("returns null for unknown dimension", () => {
    expect(resolveTarget(sampleSchema(), "Nope", "X", "Y")).toBeNull();
  });

  test("returns null for unknown level inside a real hierarchy", () => {
    expect(resolveTarget(sampleSchema(), "Time", "Time", "Decade")).toBeNull();
  });
});

/* ----------------------------- applicability ------------------------ */

describe("appliesToSchema", () => {
  test("true when the target resolves", () => {
    expect(appliesToSchema({ dimension: "Time", hierarchy: "Time", level: "Year" }, sampleSchema())).toBe(true);
  });
  test("false when the target doesn't resolve", () => {
    expect(appliesToSchema({ dimension: "Customer", hierarchy: "X", level: "Y" }, sampleSchema())).toBe(false);
  });
});

/* ----------------------------- axis conflict ------------------------ */

describe("conflictsWithAxis", () => {
  test("true when filter hierarchy matches a row axis hierarchy", () => {
    const base: AiQueryRequestLike = {
      cube: {},
      measures: [],
      rows: [{ dimension: "Time", hierarchy: "Time", level: "Quarter" }],
    };
    expect(conflictsWithAxis({ dimension: "Time", hierarchy: "Time", level: "Year" }, base)).toBe(true);
  });
  test("false when the hierarchy isn't on any axis", () => {
    const base: AiQueryRequestLike = {
      cube: {},
      measures: [],
      rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
    };
    expect(conflictsWithAxis({ dimension: "Time", hierarchy: "Time", level: "Year" }, base)).toBe(false);
  });
});

/* ------------------------------- merge ------------------------------ */

describe("mergeFilters", () => {
  function baseQuery(): AiQueryRequestLike {
    return {
      cube: { connectionName: "foodmart", cubeName: "Sales" },
      measures: [{ name: "Unit Sales" }],
      rows: [{ dimension: "Product", hierarchy: "Products", level: "Product Family" }],
      filters: [
        // baked-in default
        { dimension: "Time", hierarchy: "Time", level: "Year", members: ["[Time].[Time].[1997]"] },
      ],
    };
  }

  test("replaces a same-hierarchy baked-in filter", () => {
    const result = mergeFilters(
      baseQuery(),
      [active("Time", "Time", "Quarter", ["[Time].[Time].[1998].[Q1]"])],
      sampleSchema(),
    );
    expect(result.filters).toHaveLength(1);
    expect(result.filters?.[0]).toEqual({
      dimension: "Time",
      hierarchy: "Time",
      level: "Quarter",
      members: ["[Time].[Time].[1998].[Q1]"],
    });
  });

  test("appends a new-hierarchy filter", () => {
    // Build a base query whose rows axis doesn't use Product, so the new
    // Product filter appends rather than triggering the axis-reuse skip.
    const noRows: AiQueryRequestLike = {
      cube: { connectionName: "foodmart", cubeName: "Sales" },
      measures: [{ name: "Unit Sales" }],
      rows: [],
      filters: [
        { dimension: "Time", hierarchy: "Time", level: "Year", members: ["[Time].[Time].[1997]"] },
      ],
    };
    const result = mergeFilters(
      noRows,
      [active("Product", "Products", "Product Family", ["[Product].[Products].[Drink]"])],
      sampleSchema(),
    );
    expect(result.filters).toHaveLength(2);
    const productFilter = result.filters?.find((f) => f.dimension === "Product");
    expect(productFilter?.members).toEqual(["[Product].[Products].[Drink]"]);
  });

  test("drops a filter that doesn't resolve in the schema (multi-cube auto-skip)", () => {
    const result = mergeFilters(
      baseQuery(),
      [active("Customer", "Customers", "Country", ["[Customer].[Customers].[USA]"])],
      sampleSchema(),
    );
    // Only the original baked-in Time filter survives.
    expect(result.filters).toHaveLength(1);
    expect(result.filters?.[0].dimension).toBe("Time");
  });

  test("rewrites the rows axis selection when filter hierarchy is on rows", () => {
    // Product/Products is on rows at Product Family; dashboard filter
    // narrows it to [Drink]. The rows entry's members must be updated in
    // place and the filter should NOT also appear in filters[] (Mondrian
    // rejects same-hierarchy on axis + slicer).
    const result = mergeFilters(
      baseQuery(),
      [active("Product", "Products", "Product Family", ["[Product].[Products].[Drink]"])],
      sampleSchema(),
    );
    // filters[] only carries the baked-in Time filter still.
    expect(result.filters).toHaveLength(1);
    expect(result.filters?.[0].dimension).toBe("Time");
    // rows axis selection now narrowed.
    expect(result.rows).toHaveLength(1);
    expect(result.rows?.[0]).toEqual({
      dimension: "Product",
      hierarchy: "Products",
      level: "Product Family",
      members: ["[Product].[Products].[Drink]"],
    });
  });

  test("collapses multi-level rows on the same hierarchy into a single narrowed entry", () => {
    // A query showing Country+State drilldown on rows narrowed by a
    // State filter should collapse to a single State entry. Models the
    // common "filter widget narrows the hierarchical drill" case.
    const base: AiQueryRequestLike = {
      cube: { connectionName: "foodmart", cubeName: "Sales" },
      measures: [{ name: "Unit Sales" }],
      rows: [
        { dimension: "Product", hierarchy: "Products", level: "Product Family" },
        { dimension: "Product", hierarchy: "Products", level: "Product Department" },
      ],
      filters: [],
    };
    const schemaWithDept: SchemaLike = {
      ...sampleSchema(),
      dimensions: {
        ...sampleSchema().dimensions,
        product: {
          name: "Product",
          hierarchies: {
            products: {
              name: "Products",
              levels: {
                "product family": { name: "Product Family" },
                "product department": { name: "Product Department" },
              },
            },
          },
        },
      },
    };
    const result = mergeFilters(
      base,
      [active("Product", "Products", "Product Family", ["[Product].[Products].[Drink]"])],
      schemaWithDept,
    );
    // One axis entry survives (Product Family slot replaced); the
    // Department duplicate-hierarchy entry is dropped.
    expect(result.rows).toHaveLength(1);
    expect(result.rows?.[0].level).toBe("Product Family");
    expect(result.rows?.[0].members).toEqual(["[Product].[Products].[Drink]"]);
    expect(result.filters).toEqual([]);
  });

  test("never mutates the source query", () => {
    const base = baseQuery();
    const snapshot = JSON.parse(JSON.stringify(base));
    mergeFilters(
      base,
      [active("Time", "Time", "Quarter", ["[Time].[Time].[1998].[Q1]"])],
      sampleSchema(),
    );
    expect(base).toEqual(snapshot);
  });

  test("resolves filter dim via aliases before merge", () => {
    // Active filter targets "Timeframe" (alias) — should hit Time after resolution.
    const result = mergeFilters(
      baseQuery(),
      [active("Timeframe", "Time", "Year", ["[Time].[Time].[1998]"])],
      sampleSchema(),
    );
    expect(result.filters).toHaveLength(1);
    // Same hierarchy as the baked-in default → replaced.
    expect(result.filters?.[0].members).toEqual(["[Time].[Time].[1998]"]);
  });
});

/* --------------------------- effectiveQueryFor ---------------------- */

describe("effectiveQueryFor", () => {
  function inlineTile(): DashboardTile {
    return {
      id: "tile-a",
      x: 0,
      y: 0,
      w: 6,
      h: 4,
      type: "chart",
      cube: { connectionName: "foodmart", catalog: "F", schema: "F", cubeName: "Sales" },
      query: {
        kind: "inline",
        body: {
          cube: { connectionName: "foodmart", catalog: "F", schema: "F", cubeName: "Sales" },
          measures: [{ name: "Unit Sales" }],
          rows: [],
          filters: [],
        },
      },
    };
  }

  test("returns null for non-renderable tile types", () => {
    const filterTile = { ...inlineTile(), type: "filter" as const };
    expect(effectiveQueryFor(filterTile, [], sampleSchema())).toBeNull();
  });

  test("computes the effective query for an inline custom tile (saiku#1441)", () => {
    // A queryable custom renderer (e.g. echarts-option) fetches through the same
    // path as chart/table — an inline custom tile must NOT short-circuit to null.
    const customTile = { ...inlineTile(), type: "custom" as const };
    const result = effectiveQueryFor(
      customTile,
      [active("Time", "Time", "Year", ["[Time].[Time].[1998]"])],
      sampleSchema(),
    );
    expect(result).not.toBeNull();
    expect(result?.filters?.length).toBe(1);
  });

  test("returns null for reference tiles (caller must resolve first)", () => {
    const refTile = {
      ...inlineTile(),
      query: { kind: "reference" as const, path: "/queries/foo.saiku" },
    };
    expect(effectiveQueryFor(refTile, [], sampleSchema())).toBeNull();
  });

  test("returns the base query when no schema is available yet", () => {
    const tile = inlineTile();
    const result = effectiveQueryFor(tile, [active("Time", "Time", "Year", ["[Time].[Time].[1998]"])], null);
    // Filters NOT merged when schema absent.
    expect(result?.filters).toEqual([]);
  });

  test("merges applicable filters when schema is present", () => {
    const tile = inlineTile();
    const result = effectiveQueryFor(
      tile,
      [active("Time", "Time", "Year", ["[Time].[Time].[1998]"])],
      sampleSchema(),
    );
    expect(result?.filters).toHaveLength(1);
    expect(result?.filters?.[0].members).toEqual(["[Time].[Time].[1998]"]);
  });
});

/* ----------------------- applicableSavedFilters --------------------- */

describe("applicableSavedFilters (reference-tile projection)", () => {
  test("returns canonical-named filters for schema-resolvable inputs", () => {
    const result = applicableSavedFilters(sampleSchema(), [
      active("timeframe", "TIME", "year", ["[Time].[Time].[1998]"]),
    ]);
    expect(result).toHaveLength(1);
    expect(result[0].dimension).toBe("Time");
    expect(result[0].hierarchy).toBe("Time");
    expect(result[0].level).toBe("Year");
    expect(result[0].members).toEqual(["[Time].[Time].[1998]"]);
  });

  test("drops filters that don't resolve against the schema", () => {
    const result = applicableSavedFilters(sampleSchema(), [
      active("Time", "Time", "Year", ["[Time].[Time].[1998]"]),
      active("Made Up", "X", "Y", ["[Whatever]"]),
    ]);
    expect(result).toHaveLength(1);
    expect(result[0].dimension).toBe("Time");
  });

  test("returns empty list when schema is null", () => {
    const result = applicableSavedFilters(null, [active("Time", "Time", "Year", ["[Time].[Time].[1998]"])]);
    expect(result).toEqual([]);
  });

  test("copies members defensively (no shared reference with the input)", () => {
    const af = active("Time", "Time", "Year", ["[a]", "[b]"]);
    const [out] = applicableSavedFilters(sampleSchema(), [af]);
    out.members?.push("[c]");
    expect(af.filter.members).toEqual(["[a]", "[b]"]);
  });
});

/* ----------------- #1085: brush cross-filter source exclusion ----------- */

describe("effectiveQueryFor — #1085 cross-filter source exclusion", () => {
  function tile(id: string): DashboardTile {
    return {
      id,
      x: 0,
      y: 0,
      w: 6,
      h: 4,
      type: "chart",
      cube: { connectionName: "foodmart", catalog: "F", schema: "F", cubeName: "Sales" },
      query: {
        kind: "inline",
        body: {
          cube: { connectionName: "foodmart", catalog: "F", schema: "F", cubeName: "Sales" },
          measures: [{ name: "Unit Sales" }],
          rows: [],
          filters: [],
        },
      },
    };
  }

  function cross(tileId: string, members: string[]): ActiveFilter {
    return {
      id: `cross-${tileId}`,
      source: { kind: "cross", tileId },
      filter: { dimension: "Product", hierarchy: "Products", level: "Product Family", members },
    };
  }

  const members = ["[Product].[Products].[Drink]", "[Product].[Products].[Food]"];

  test("the SOURCE tile excludes its own cross-filter (keeps full context)", () => {
    const result = effectiveQueryFor(tile("tile-a"), [cross("tile-a", members)], sampleSchema());
    // Excluded → base query unchanged: no Product Family filter applied.
    expect(result?.filters).toEqual([]);
  });

  test("a DIFFERENT tile receives the cross-filter (narrows)", () => {
    const result = effectiveQueryFor(tile("tile-b"), [cross("tile-a", members)], sampleSchema());
    expect(result?.filters).toHaveLength(1);
    expect(result?.filters?.[0]).toMatchObject({
      dimension: "Product",
      hierarchy: "Products",
      level: "Product Family",
      members,
    });
  });

  test("click/panel filters from a tile are NOT excluded for that tile (unchanged #1166 behaviour)", () => {
    const clickFromA: ActiveFilter = {
      id: "click-a",
      source: { kind: "click", tileId: "tile-a" },
      filter: { dimension: "Product", hierarchy: "Products", level: "Product Family", members: [members[0]] },
    };
    const result = effectiveQueryFor(tile("tile-a"), [clickFromA], sampleSchema());
    expect(result?.filters).toHaveLength(1); // click still applies to its own source
  });
});
