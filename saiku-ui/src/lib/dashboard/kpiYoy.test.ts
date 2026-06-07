/*
 * Unit tests for the KPI tile's year-over-year helpers (#992).
 *
 * Pure logic only — no DOM, no fetch. Mirrors the flat, chronologically
 * ordered member list /ai/members/search returns for a single level.
 */

import { describe, expect, it } from "vitest";

import {
  expandYearOverYearRows,
  parentKey,
  splitMemberSegments,
  yearAgoMemberKey,
  type LevelMember,
} from "./kpiYoy";

// Quarter level: every quarter across three years, in declaration order.
const quarters: LevelMember[] = [
  { uniqueName: "[Time].[1996].[Q1]", caption: "Q1" },
  { uniqueName: "[Time].[1996].[Q2]", caption: "Q2" },
  { uniqueName: "[Time].[1996].[Q3]", caption: "Q3" },
  { uniqueName: "[Time].[1996].[Q4]", caption: "Q4" },
  { uniqueName: "[Time].[1997].[Q1]", caption: "Q1" },
  { uniqueName: "[Time].[1997].[Q2]", caption: "Q2" },
  { uniqueName: "[Time].[1997].[Q3]", caption: "Q3" },
  { uniqueName: "[Time].[1997].[Q4]", caption: "Q4" },
  { uniqueName: "[Time].[1998].[Q1]", caption: "Q1" },
  { uniqueName: "[Time].[1998].[Q2]", caption: "Q2" },
];

describe("splitMemberSegments", () => {
  it("splits a bracketed unique name into segments", () => {
    expect(splitMemberSegments("[Time].[1997].[Q2]")).toEqual(["[Time]", "[1997]", "[Q2]"]);
  });

  it("handles bracketed segments containing dots", () => {
    expect(splitMemberSegments("[Time].[1997.Q2]")).toEqual(["[Time]", "[1997.Q2]"]);
  });

  it("falls back to a dot-split for non-bracketed names", () => {
    expect(splitMemberSegments("Time.1997.Q2")).toEqual(["Time", "1997", "Q2"]);
  });
});

describe("parentKey", () => {
  it("drops the last segment", () => {
    expect(parentKey("[Time].[1997].[Q2]")).toBe("[Time].[1997]");
  });

  it("returns empty for a single-segment member", () => {
    expect(parentKey("[Time]")).toBe("");
  });
});

describe("yearAgoMemberKey", () => {
  it("resolves the same period one year earlier", () => {
    expect(yearAgoMemberKey("[Time].[1997].[Q2]", quarters)).toBe("[Time].[1996].[Q2]");
    expect(yearAgoMemberKey("[Time].[1998].[Q1]", quarters)).toBe("[Time].[1997].[Q1]");
  });

  it("returns null for the earliest year (no parallel period)", () => {
    expect(yearAgoMemberKey("[Time].[1996].[Q2]", quarters)).toBeNull();
  });

  it("returns null when the target is not in the list", () => {
    expect(yearAgoMemberKey("[Time].[1997].[Q5]", quarters)).toBeNull();
  });

  it("returns null when the predecessor year has fewer children at that index", () => {
    // 1996 only has Q1; 1997 has Q1+Q2. Year-ago of 1997.Q2 (index 1) is
    // undefined because 1996 has no index-1 child.
    const sparse: LevelMember[] = [
      { uniqueName: "[Time].[1996].[Q1]", caption: "Q1" },
      { uniqueName: "[Time].[1997].[Q1]", caption: "Q1" },
      { uniqueName: "[Time].[1997].[Q2]", caption: "Q2" },
    ];
    expect(yearAgoMemberKey("[Time].[1997].[Q2]", sparse)).toBeNull();
    expect(yearAgoMemberKey("[Time].[1997].[Q1]", sparse)).toBe("[Time].[1996].[Q1]");
  });

  it("returns null on empty inputs", () => {
    expect(yearAgoMemberKey("", quarters)).toBeNull();
    expect(yearAgoMemberKey("[Time].[1997].[Q2]", [])).toBeNull();
  });

  it("works at the Month level by relative position, not caption", () => {
    const months: LevelMember[] = [
      { uniqueName: "[Time].[1996].[1]", caption: "January" },
      { uniqueName: "[Time].[1996].[2]", caption: "February" },
      { uniqueName: "[Time].[1996].[3]", caption: "March" },
      { uniqueName: "[Time].[1997].[1]", caption: "January" },
      { uniqueName: "[Time].[1997].[2]", caption: "February" },
      { uniqueName: "[Time].[1997].[3]", caption: "March" },
    ];
    expect(yearAgoMemberKey("[Time].[1997].[2]", months)).toBe("[Time].[1996].[2]");
  });
});

describe("expandYearOverYearRows", () => {
  it("includes each slicer member plus its year-ago counterpart, in chronological order", () => {
    expect(expandYearOverYearRows(["[Time].[1997].[Q2]"], quarters)).toEqual([
      "[Time].[1996].[Q2]",
      "[Time].[1997].[Q2]",
    ]);
  });

  it("de-duplicates overlapping expansions", () => {
    const out = expandYearOverYearRows(
      ["[Time].[1997].[Q2]", "[Time].[1998].[Q2]"],
      quarters,
    );
    expect(out).toEqual([
      "[Time].[1996].[Q2]",
      "[Time].[1997].[Q2]",
      "[Time].[1998].[Q2]",
    ]);
  });

  it("includes a slicer member alone when no year-ago exists", () => {
    expect(expandYearOverYearRows(["[Time].[1996].[Q2]"], quarters)).toEqual([
      "[Time].[1996].[Q2]",
    ]);
  });

  it("returns empty when there are no slicer members or no members", () => {
    expect(expandYearOverYearRows([], quarters)).toEqual([]);
    expect(expandYearOverYearRows(["[Time].[1997].[Q2]"], [])).toEqual([]);
  });
});
