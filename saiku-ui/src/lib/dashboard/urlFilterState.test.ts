import { describe, expect, test } from "vitest";

import {
  decodeAppFilterState,
  decodeFilterParams,
  encodeActiveFilters,
  encodeAppFilterState,
  pageFilterParam,
} from "./urlFilterState";
import type { DashboardFilter } from "$lib/api/dashboards";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";

function active(
  dim: string,
  hier: string,
  level: string,
  members: string[],
  source: ActiveFilter["source"] = { kind: "panel", filterId: "p1" },
): ActiveFilter {
  return {
    id: `${dim}-${hier}-${level}`,
    source,
    filter: { dimension: dim, hierarchy: hier, level, members },
  };
}

describe("encodeActiveFilters", () => {
  test("returns empty string when no filters carry members", () => {
    expect(encodeActiveFilters([])).toBe("");
    expect(encodeActiveFilters([active("Time", "Time", "Year", [])])).toBe("");
  });

  test("encodes one f param per filter with comma-joined members", () => {
    const out = encodeActiveFilters([
      active("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q2]"]),
      active(
        "Country",
        "Country",
        "Country",
        ["[Country].[Country].[USA]", "[Country].[Country].[Canada]"],
        { kind: "click", tileId: "t1" },
      ),
    ]);
    expect(out.startsWith("?")).toBe(true);
    const params = new URL(`https://x${out}`).searchParams.getAll("f");
    expect(params).toContain("Time/Time/Quarter=[Time].[Time].[1997].[Q2]");
    expect(params).toContain(
      "Country/Country/Country=[Country].[Country].[USA],[Country].[Country].[Canada]",
    );
  });

  test("dedupes filters that target the same dim/hier/level (defensive)", () => {
    const out = encodeActiveFilters([
      active("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q2]"]),
      active("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q3]"], {
        kind: "click",
        tileId: "t1",
      }),
    ]);
    const params = new URL(`https://x${out}`).searchParams.getAll("f");
    expect(params).toHaveLength(1);
  });
});

describe("decodeFilterParams", () => {
  test("round-trips encode → decode for typical cases", () => {
    const filters = [
      active("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q2]"]),
      active("Country", "Country", "Country", ["[Country].[Country].[USA]"]),
    ];
    const encoded = encodeActiveFilters(filters);
    const decoded = decodeFilterParams(
      new URL(`https://x${encoded}`).searchParams,
    );
    expect(decoded).toEqual([
      {
        dimension: "Time",
        hierarchy: "Time",
        level: "Quarter",
        members: ["[Time].[Time].[1997].[Q2]"],
      },
      {
        dimension: "Country",
        hierarchy: "Country",
        level: "Country",
        members: ["[Country].[Country].[USA]"],
      },
    ]);
  });

  test("skips malformed entries", () => {
    const params = new URLSearchParams();
    params.append("f", "no-equals-sign-here");
    params.append("f", "too/few=");
    params.append("f", "/empty/segment=[X]");
    params.append("f", "Time/Time/Quarter=[Time].[Time].[1997].[Q2]");
    const decoded = decodeFilterParams(params);
    expect(decoded).toEqual([
      {
        dimension: "Time",
        hierarchy: "Time",
        level: "Quarter",
        members: ["[Time].[Time].[1997].[Q2]"],
      },
    ]);
  });

  test("splits comma-separated member lists", () => {
    const params = new URLSearchParams();
    params.append(
      "f",
      "Country/Country/Country=[Country].[Country].[USA],[Country].[Country].[Canada]",
    );
    const decoded = decodeFilterParams(params);
    expect(decoded[0].members).toEqual([
      "[Country].[Country].[USA]",
      "[Country].[Country].[Canada]",
    ]);
  });
});

/* -------------------------------------------------------------------------
 * App Builder per-page namespacing (Task 7). The active page rides on `p`;
 * each page's filters live under a distinct `f~<pageId>` param so switching
 * pages preserves every page's filters and the whole thing round-trips.
 * ------------------------------------------------------------------------- */

function filter(
  dim: string,
  hier: string,
  level: string,
  members: string[],
): DashboardFilter {
  return { dimension: dim, hierarchy: hier, level, members };
}

describe("pageFilterParam", () => {
  test("namespaces the filter param by page id, distinct from bare `f`", () => {
    expect(pageFilterParam("page-1")).toBe("f~page-1");
    expect(pageFilterParam("page-1")).not.toBe("f");
  });
});

describe("encodeAppFilterState", () => {
  test("returns empty string with no active page and no filters", () => {
    expect(encodeAppFilterState(null, {})).toBe("");
  });

  test("encodes the active page under `p`", () => {
    const out = encodeAppFilterState("overview", {});
    const params = new URL(`https://x${out}`).searchParams;
    expect(params.get("p")).toBe("overview");
  });

  test("namespaces each page's filters under `f~<pageId>`", () => {
    const out = encodeAppFilterState("sales", {
      sales: [filter("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q2]"])],
      ops: [filter("Store", "Store", "Country", ["[Store].[Store].[USA]"])],
    });
    const params = new URL(`https://x${out}`).searchParams;
    expect(params.get("p")).toBe("sales");
    expect(params.getAll("f~sales")).toEqual([
      "Time/Time/Quarter=[Time].[Time].[1997].[Q2]",
    ]);
    expect(params.getAll("f~ops")).toEqual([
      "Store/Store/Country=[Store].[Store].[USA]",
    ]);
    // No collision with the single-dashboard `f` param.
    expect(params.getAll("f")).toEqual([]);
  });

  test("drops a page whose filters carry no members", () => {
    const out = encodeAppFilterState("a", {
      a: [filter("Time", "Time", "Year", [])],
    });
    const params = new URL(`https://x${out}`).searchParams;
    expect(params.get("p")).toBe("a");
    expect(params.getAll("f~a")).toEqual([]);
  });
});

describe("decodeAppFilterState", () => {
  test("round-trips encode → decode, preserving per-page isolation", () => {
    const filtersByPage = {
      sales: [
        filter("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q2]"]),
        filter("Store", "Store", "Country", [
          "[Store].[Store].[USA]",
          "[Store].[Store].[Canada]",
        ]),
      ],
      ops: [
        filter("Product", "Product", "Family", ["[Product].[Product].[Drink]"]),
      ],
    };
    const encoded = encodeAppFilterState("sales", filtersByPage);
    const decoded = decodeAppFilterState(
      new URL(`https://x${encoded}`).searchParams,
    );
    expect(decoded.activePageId).toBe("sales");
    expect(decoded.filtersByPage).toEqual(filtersByPage);
  });

  test("null active page when `p` is absent", () => {
    const decoded = decodeAppFilterState(new URLSearchParams());
    expect(decoded.activePageId).toBeNull();
    expect(decoded.filtersByPage).toEqual({});
  });

  test("ignores the bare `f` param (single-dashboard scheme)", () => {
    const params = new URLSearchParams();
    params.set("p", "home");
    params.append("f", "Time/Time/Quarter=[Time].[Time].[1997].[Q2]");
    const decoded = decodeAppFilterState(params);
    expect(decoded.activePageId).toBe("home");
    expect(decoded.filtersByPage).toEqual({});
  });
});

/*
 * App-level context scope in the URL (saiku#1754). The App Builder's header
 * context pill is app chrome, so it round-trips once as `ctx=<label>` rather
 * than being duplicated into every page's `f~<pageId>` slot. The label (not the
 * filter) is what's carried, so the restored pill and the restored scope cannot
 * disagree — the shell re-applies the selection through its normal path.
 */
describe("app context scope (saiku#1754)", () => {
  test("encodes the context label alongside the active page", () => {
    const s = encodeAppFilterState("page-1", {}, "West");
    expect(new URLSearchParams(s).get("ctx")).toBe("West");
  });

  test("omits the param when there is no selection", () => {
    expect(
      new URLSearchParams(encodeAppFilterState("page-1", {}, null)).has("ctx"),
    ).toBe(false);
    expect(
      new URLSearchParams(encodeAppFilterState("page-1", {}, "")).has("ctx"),
    ).toBe(false);
  });

  test("round-trips a label containing separators", () => {
    const label = "All regions · National";
    const decoded = decodeAppFilterState(
      new URLSearchParams(encodeAppFilterState("p", {}, label)),
    );
    expect(decoded.contextLabel).toBe(label);
  });

  test("decodes to null when absent", () => {
    expect(
      decodeAppFilterState(new URLSearchParams("?p=page-1")).contextLabel,
    ).toBeNull();
  });

  test("does not disturb per-page filter round-tripping", () => {
    const byPage = {
      "page-1": [
        {
          dimension: "Geography",
          hierarchy: "Geography",
          level: "Region",
          members: ["[G].[West]"],
        },
      ],
    };
    const decoded = decodeAppFilterState(
      new URLSearchParams(encodeAppFilterState("page-1", byPage, "West")),
    );
    expect(decoded.filtersByPage["page-1"]).toHaveLength(1);
    expect(decoded.activePageId).toBe("page-1");
    expect(decoded.contextLabel).toBe("West");
  });
});
