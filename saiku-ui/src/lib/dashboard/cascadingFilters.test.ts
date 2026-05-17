/*
 * Unit tests for the cascading-filter helpers.
 */

import { describe, test, expect } from "vitest";
import {
  ancestorSelections,
  memberDescendsFromAny,
} from "$lib/dashboard/cascadingFilters";
import type { SchemaLike } from "$lib/dashboard/effectiveQuery";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";
import type { CubeRef, DashboardFilter } from "$lib/api/dashboards";

const SALES: CubeRef = {
  connectionName: "foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "Sales",
};

function customerSchema(): SchemaLike {
  return {
    dimensions: {
      customer: {
        name: "Customer",
        hierarchies: {
          customers: {
            name: "Customers",
            // LinkedHashMap order = root-most → leaf
            levels: {
              "(all)": { name: "(All)" },
              country: { name: "Country" },
              "state province": { name: "State Province" },
              city: { name: "City" },
            },
          },
        },
      },
    },
  };
}

function active(level: string, members: string[]): ActiveFilter {
  return {
    id: `widget-${level}`,
    source: { kind: "widget", tileId: `w-${level}` },
    filter: { dimension: "Customer", hierarchy: "Customers", level, members },
  };
}

function childTarget(level: string): DashboardFilter {
  return { dimension: "Customer", hierarchy: "Customers", level, members: [] };
}

describe("ancestorSelections", () => {
  test("returns the parent widget's selected members when child is one level deeper", () => {
    const result = ancestorSelections(
      [active("Country", ["[Customer].[Customers].[USA]"])],
      customerSchema(),
      SALES,
      childTarget("State Province"),
    );
    expect(result).toEqual(["[Customer].[Customers].[USA]"]);
  });

  test("walks multiple ancestor levels — Country + State Province both restrict City", () => {
    const result = ancestorSelections(
      [
        active("Country", ["[Customer].[Customers].[USA]"]),
        active("State Province", ["[Customer].[Customers].[USA].[OR]"]),
      ],
      customerSchema(),
      SALES,
      childTarget("City"),
    );
    // Both ancestors contribute; the deepest one is the most-restrictive
    // prefix, but we expose all so downstream can apply OR-of-prefix.
    expect(result.sort()).toEqual([
      "[Customer].[Customers].[State Province].dummy",
      "[Customer].[Customers].[USA]",
      "[Customer].[Customers].[USA].[OR]",
    ].filter((s) => !s.endsWith("dummy")));
  });

  test("ignores filters whose level is at or below the child", () => {
    const result = ancestorSelections(
      [
        active("State Province", ["[Customer].[Customers].[USA].[OR]"]),
        active("City", ["[Customer].[Customers].[USA].[OR].[Portland]"]),
      ],
      customerSchema(),
      SALES,
      childTarget("State Province"), // self-level — no ancestor possible
    );
    expect(result).toEqual([]);
  });

  test("ignores filters on a different hierarchy", () => {
    const out = ancestorSelections(
      [
        {
          id: "gender",
          source: { kind: "widget", tileId: "wG" },
          filter: { dimension: "Customer", hierarchy: "Gender", level: "(All)", members: ["[Customer].[Gender].[F]"] },
        },
      ],
      customerSchema(),
      SALES,
      childTarget("State Province"),
    );
    expect(out).toEqual([]);
  });

  test("returns empty list when schema is null", () => {
    expect(ancestorSelections([active("Country", ["[X]"])], null, SALES, childTarget("State Province"))).toEqual([]);
  });

  test("returns empty list when child level isn't in the schema", () => {
    expect(
      ancestorSelections(
        [active("Country", ["[X]"])],
        customerSchema(),
        SALES,
        { dimension: "Customer", hierarchy: "Customers", level: "Made Up", members: [] },
      ),
    ).toEqual([]);
  });

  test("dedupes overlapping ancestor selections", () => {
    const out = ancestorSelections(
      [
        active("Country", ["[Customer].[Customers].[USA]", "[Customer].[Customers].[USA]"]),
      ],
      customerSchema(),
      SALES,
      childTarget("State Province"),
    );
    expect(out).toEqual(["[Customer].[Customers].[USA]"]);
  });
});

describe("memberDescendsFromAny", () => {
  test("matches descendant via prefix", () => {
    expect(
      memberDescendsFromAny(
        "[Customer].[Customers].[USA].[OR]",
        ["[Customer].[Customers].[USA]"],
      ),
    ).toBe(true);
  });

  test("rejects when member is the parent itself", () => {
    expect(
      memberDescendsFromAny(
        "[Customer].[Customers].[USA]",
        ["[Customer].[Customers].[USA]"],
      ),
    ).toBe(false);
  });

  test("rejects unrelated member", () => {
    expect(
      memberDescendsFromAny(
        "[Customer].[Customers].[Mexico].[DF]",
        ["[Customer].[Customers].[USA]"],
      ),
    ).toBe(false);
  });

  test("multi-parent OR semantics — descends if any parent matches", () => {
    expect(
      memberDescendsFromAny(
        "[Customer].[Customers].[USA].[OR]",
        [
          "[Customer].[Customers].[Mexico]",
          "[Customer].[Customers].[USA]",
        ],
      ),
    ).toBe(true);
  });

  test("empty parents list = no constraint (still false to descend from nothing)", () => {
    expect(memberDescendsFromAny("[Anything]", [])).toBe(false);
  });

  test("guards against the prefix-confusion case", () => {
    // [USA].[OR2] must not be a false descendant of [USA].[OR]
    expect(
      memberDescendsFromAny(
        "[Customer].[Customers].[USA].[OR2]",
        ["[Customer].[Customers].[USA].[OR]"],
      ),
    ).toBe(false);
  });
});
