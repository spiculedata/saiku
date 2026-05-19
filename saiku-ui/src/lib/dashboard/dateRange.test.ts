import { describe, expect, test } from "vitest";

import {
  expandDateRange,
  fromDateInputValue,
  parseMemberDateRange,
  toDateInputValue,
} from "./dateRange";

describe("parseMemberDateRange", () => {
  test("year-only member spans the calendar year", () => {
    const r = parseMemberDateRange("[Time].[Time].[1997]");
    expect(r).not.toBeNull();
    expect(r!.start).toEqual(new Date(1997, 0, 1));
    expect(r!.end.getFullYear()).toBe(1997);
    expect(r!.end.getMonth()).toBe(11);
    expect(r!.end.getDate()).toBe(31);
  });

  test("quarter narrows to three months", () => {
    const r = parseMemberDateRange("[Time].[Time].[1997].[Q2]");
    expect(r!.start).toEqual(new Date(1997, 3, 1)); // April 1
    expect(r!.end.getMonth()).toBe(5); // June
    expect(r!.end.getDate()).toBe(30);
  });

  test("numeric month under quarter", () => {
    const r = parseMemberDateRange("[Time].[Time].[1997].[Q1].[2]");
    expect(r!.start).toEqual(new Date(1997, 1, 1));
    expect(r!.end.getMonth()).toBe(1);
    expect(r!.end.getDate()).toBe(28); // 1997 not leap
  });

  test("English month name", () => {
    const r = parseMemberDateRange("[Time].[Time].[1997].[April]");
    expect(r!.start).toEqual(new Date(1997, 3, 1));
    expect(r!.end.getMonth()).toBe(3);
    expect(r!.end.getDate()).toBe(30);
  });

  test("short month name", () => {
    const r = parseMemberDateRange("[Time].[Time].[2000].[Feb]");
    expect(r!.start).toEqual(new Date(2000, 1, 1));
    expect(r!.end.getDate()).toBe(29); // 2000 leap
  });

  test("year + month + day", () => {
    const r = parseMemberDateRange("[Time].[Time].[1997].[Q1].[3].[15]");
    expect(r!.start).toEqual(new Date(1997, 2, 15));
    expect(r!.end.getMonth()).toBe(2);
    expect(r!.end.getDate()).toBe(15);
  });

  test("returns null when no year segment present", () => {
    expect(parseMemberDateRange("[Customer].[Country].[USA]")).toBeNull();
  });

  test("returns null for unparseable input", () => {
    expect(parseMemberDateRange("not-an-mdx-name")).toBeNull();
  });
});

describe("expandDateRange", () => {
  const QUARTERS = [
    { uniqueName: "[Time].[Time].[1997].[Q1]", caption: "Q1" },
    { uniqueName: "[Time].[Time].[1997].[Q2]", caption: "Q2" },
    { uniqueName: "[Time].[Time].[1997].[Q3]", caption: "Q3" },
    { uniqueName: "[Time].[Time].[1997].[Q4]", caption: "Q4" },
    { uniqueName: "[Time].[Time].[1998].[Q1]", caption: "Q1" },
  ];

  test("returns empty when either bound is null", () => {
    expect(expandDateRange(null, new Date(1997, 0, 1), QUARTERS)).toEqual([]);
    expect(expandDateRange(new Date(1997, 0, 1), null, QUARTERS)).toEqual([]);
  });

  test("intersects: window inside one quarter", () => {
    // Feb 15 1997 → falls in Q1 1997 only.
    const out = expandDateRange(new Date(1997, 1, 15), new Date(1997, 1, 15), QUARTERS);
    expect(out).toEqual(["[Time].[Time].[1997].[Q1]"]);
  });

  test("intersects: window covers multiple quarters", () => {
    // Feb 1997 → May 1997 → Q1 (overlaps Feb-Mar) + Q2 (overlaps Apr-May).
    const out = expandDateRange(new Date(1997, 1, 1), new Date(1997, 4, 31), QUARTERS);
    expect(out).toEqual(["[Time].[Time].[1997].[Q1]", "[Time].[Time].[1997].[Q2]"]);
  });

  test("intersects: window straddles year boundary", () => {
    // Dec 1997 → Feb 1998 → Q4 1997 + Q1 1998.
    const out = expandDateRange(new Date(1997, 11, 1), new Date(1998, 1, 28), QUARTERS);
    expect(out).toEqual(["[Time].[Time].[1997].[Q4]", "[Time].[Time].[1998].[Q1]"]);
  });

  test("skips members the parser can't place", () => {
    const mixed = [
      ...QUARTERS,
      { uniqueName: "[Customer].[Country].[USA]", caption: "USA" },
    ];
    const out = expandDateRange(new Date(1997, 0, 1), new Date(1997, 11, 31), mixed);
    expect(out).not.toContain("[Customer].[Country].[USA]");
    expect(out).toHaveLength(4);
  });
});

describe("toDateInputValue / fromDateInputValue", () => {
  test("round-trips through YYYY-MM-DD", () => {
    const d = new Date(1997, 1, 15);
    const s = toDateInputValue(d);
    expect(s).toBe("1997-02-15");
    const parsed = fromDateInputValue(s);
    expect(parsed?.getFullYear()).toBe(1997);
    expect(parsed?.getMonth()).toBe(1);
    expect(parsed?.getDate()).toBe(15);
  });

  test("null / empty inputs", () => {
    expect(toDateInputValue(null)).toBe("");
    expect(fromDateInputValue("")).toBeNull();
    expect(fromDateInputValue("garbage")).toBeNull();
    expect(fromDateInputValue("1997-13-01")).toBeNull(); // bad month
  });
});
