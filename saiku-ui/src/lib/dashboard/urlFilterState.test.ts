import { describe, expect, test } from "vitest";

import { decodeFilterParams, encodeActiveFilters } from "./urlFilterState";
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
      active("Time", "Time", "Quarter", ["[Time].[Time].[1997].[Q3]"], { kind: "click", tileId: "t1" }),
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
    const decoded = decodeFilterParams(new URL(`https://x${encoded}`).searchParams);
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
    params.append("f", "Country/Country/Country=[Country].[Country].[USA],[Country].[Country].[Canada]");
    const decoded = decodeFilterParams(params);
    expect(decoded[0].members).toEqual([
      "[Country].[Country].[USA]",
      "[Country].[Country].[Canada]",
    ]);
  });
});
