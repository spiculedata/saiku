/*
 * Unit tests for semantic filter bindings (saiku#1803).
 */

import { describe, expect, it } from "vitest";
import type { CubeRef } from "$lib/api/dashboards";
import { ossieSource } from "$lib/dashboard/tileSource";
import {
  bindingFor,
  filterLabel,
  filterReachesSource,
  mdxFilterFor,
  ossieFilterFor,
  ossieFiltersFor,
  selectedCaptions,
  withBinding,
  withoutBinding,
  type SemanticFilter,
} from "$lib/dashboard/semanticFilter";

const STORE: CubeRef = {
  connectionName: "unknown_foodmart",
  catalog: "FoodMart",
  schema: "FoodMart",
  cubeName: "Store",
};
const WAREHOUSE: CubeRef = { ...STORE, cubeName: "Warehouse" };
const FLIGHTS = ossieSource("unknown_Flights", "Flights");

/** What every dashboard saved before #1803 looks like: one MDX target, members. */
function legacy(): SemanticFilter {
  return {
    dimension: "Store",
    hierarchy: "Stores",
    level: "Store Country",
    members: ["[Store].[Stores].[Mexico]"],
  };
}

/** The #1803 shape: a named concept mapped onto both kinds of source. */
function semantic(): SemanticFilter {
  return {
    ...legacy(),
    label: "Country",
    captions: ["Mexico"],
    bindings: [
      {
        kind: "mdx",
        cube: STORE,
        dimension: "Store",
        hierarchy: "Stores",
        level: "Store Country",
      },
      { kind: "ossie", cube: FLIGHTS, dataset: "airport", field: "country_code" },
    ],
  };
}

describe("selectedCaptions", () => {
  it("prefers the explicit caption list", () => {
    expect(selectedCaptions(semantic())).toEqual(["Mexico"]);
  });

  it("derives captions from MDX members so a legacy filter can drive an ossie binding", () => {
    // Without this a filter authored before #1803 would reach an Ossie tile
    // with nothing to select on, and quietly not narrow it.
    expect(selectedCaptions(legacy())).toEqual(["Mexico"]);
  });

  it("is empty when nothing is selected", () => {
    expect(selectedCaptions({ ...legacy(), members: [] })).toEqual([]);
  });
});

describe("bindingFor", () => {
  it("finds the binding matching the tile's source", () => {
    expect(bindingFor(semantic(), STORE)?.kind).toBe("mdx");
    expect(bindingFor(semantic(), FLIGHTS)?.kind).toBe("ossie");
  });

  it("returns null for a source the filter was never mapped onto", () => {
    // Warehouse is a cube, but this filter names a binding for Store only —
    // an explicit mapping means the author has said where it applies.
    expect(bindingFor(semantic(), WAREHOUSE)).toBeNull();
  });

  it("a legacy filter targets any mdx source", () => {
    expect(bindingFor(legacy(), STORE)?.kind).toBe("mdx");
    expect(bindingFor(legacy(), WAREHOUSE)?.kind).toBe("mdx");
  });

  it("a legacy filter reaches NO ossie source", () => {
    // It names no dataset or field; inventing one from the level name would be
    // guessing at the author's data model.
    expect(bindingFor(legacy(), FLIGHTS)).toBeNull();
    expect(filterReachesSource(legacy(), FLIGHTS)).toBe(false);
  });

  it("is null with no source", () => {
    expect(bindingFor(semantic(), null)).toBeNull();
  });
});

describe("mdxFilterFor", () => {
  it("carries members through for the filter's own legacy target", () => {
    const f = mdxFilterFor(legacy(), STORE);
    expect(f?.level).toBe("Store Country");
    expect(f?.members).toEqual(["[Store].[Stores].[Mexico]"]);
  });

  it("drops members when the binding points at a DIFFERENT level", () => {
    // A member unique name is scoped to the hierarchy it came from. Reusing
    // "[Store].[Stores].[Mexico]" against Warehouse's Store City would filter
    // on a member that doesn't exist there — captions get resolved instead.
    const f: SemanticFilter = {
      ...legacy(),
      captions: ["Mexico"],
      bindings: [
        {
          kind: "mdx",
          cube: WAREHOUSE,
          dimension: "Store",
          hierarchy: "Stores",
          level: "Store City",
        },
      ],
    };
    const out = mdxFilterFor(f, WAREHOUSE);
    expect(out?.level).toBe("Store City");
    expect(out?.members).toEqual([]);
    expect(out?.captions).toEqual(["Mexico"]);
  });

  it("is null for an ossie source", () => {
    expect(mdxFilterFor(semantic(), FLIGHTS)).toBeNull();
  });
});

describe("ossieFilterFor", () => {
  it("emits EQ for a single selection", () => {
    expect(ossieFilterFor(semantic(), FLIGHTS)).toEqual({
      dataset: "airport",
      field: "country_code",
      op: "EQ",
      value: "Mexico",
    });
  });

  it("emits IN for several", () => {
    const f = { ...semantic(), captions: ["Mexico", "Canada"] };
    expect(ossieFilterFor(f, FLIGHTS)).toEqual({
      dataset: "airport",
      field: "country_code",
      op: "IN",
      values: ["Mexico", "Canada"],
    });
  });

  it("is null when nothing is selected — a no-op, not a match-zero-rows filter", () => {
    const f = { ...semantic(), captions: [], members: [] };
    expect(ossieFilterFor(f, FLIGHTS)).toBeNull();
  });

  it("is null for an mdx source", () => {
    expect(ossieFilterFor(semantic(), STORE)).toBeNull();
  });
});

describe("ossieFiltersFor", () => {
  it("collects only the predicates that address the model", () => {
    const other: SemanticFilter = {
      dimension: "Time",
      hierarchy: "Time",
      level: "Year",
      members: [],
      captions: ["1998"],
      bindings: [{ kind: "ossie", cube: FLIGHTS, dataset: "flight", field: "year" }],
    };
    const exprs = ossieFiltersFor([legacy(), semantic(), other], FLIGHTS);
    // legacy() reaches no ossie source, so exactly two remain.
    expect(exprs.map((e) => e.field)).toEqual(["country_code", "year"]);
  });

  it("is empty for a model nothing maps onto", () => {
    expect(ossieFiltersFor([semantic()], ossieSource("c", "Other"))).toEqual([]);
  });
});

describe("withBinding / withoutBinding", () => {
  it("replaces the binding for one source and leaves the rest", () => {
    const updated = withBinding(semantic(), {
      kind: "ossie",
      cube: FLIGHTS,
      dataset: "carrier",
      field: "hub_state",
    });
    expect(updated.bindings).toHaveLength(2);
    expect(ossieFilterFor(updated, FLIGHTS)?.field).toBe("hub_state");
    expect(bindingFor(updated, STORE)?.kind).toBe("mdx");
  });

  it("does not mutate the input", () => {
    const f = semantic();
    withBinding(f, { kind: "ossie", cube: FLIGHTS, dataset: "x", field: "y" });
    expect(ossieFilterFor(f, FLIGHTS)?.field).toBe("country_code");
  });

  it("removes one binding", () => {
    const updated = withoutBinding(semantic(), FLIGHTS);
    expect(updated.bindings).toHaveLength(1);
    expect(filterReachesSource(updated, FLIGHTS)).toBe(false);
  });
});

describe("filterLabel", () => {
  it("prefers the concept name, else the level", () => {
    expect(filterLabel(semantic())).toBe("Country");
    expect(filterLabel(legacy())).toBe("Store Country");
    expect(filterLabel({ ...legacy(), label: "  " })).toBe("Store Country");
  });
});
