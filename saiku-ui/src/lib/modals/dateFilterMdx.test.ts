import { describe, it, expect } from "vitest";
import {
  buildRelativeMdx,
  buildAbsoluteMdx,
  isRelativeValid,
  isAbsoluteValid,
  memberForDate,
  looksLikeTimeHierarchy,
} from "./dateFilterMdx";

const HIER = "[Time].[Time]";
const DAY_LEVEL = "[Time].[Time].[Day]";

describe("buildRelativeMdx", () => {
  it("TODAY → current-member singleton", () => {
    expect(buildRelativeMdx({ preset: "TODAY", hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("{[Time].[Time].CurrentMember}");
  });

  it("YESTERDAY → CurrentMember.Lag(1)", () => {
    expect(buildRelativeMdx({ preset: "YESTERDAY", hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("{[Time].[Time].CurrentMember.Lag(1)}");
  });

  it("LAST_N_DAYS → LastPeriods(n, level.CurrentMember)", () => {
    expect(buildRelativeMdx({ preset: "LAST_N_DAYS", n: 7, hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("LastPeriods(7, [Time].[Time].[Day].CurrentMember)");
  });

  it("LAST_N_WEEKS with a Week level", () => {
    expect(buildRelativeMdx({ preset: "LAST_N_WEEKS", n: 4, hierarchy: HIER, level: "[Time].[Time].[Week]" }))
      .toBe("LastPeriods(4, [Time].[Time].[Week].CurrentMember)");
  });

  it("LAST_N_MONTHS substitutes n>=1 on bad input so preview still compiles", () => {
    expect(buildRelativeMdx({ preset: "LAST_N_MONTHS", n: 0, hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("LastPeriods(1, [Time].[Time].[Day].CurrentMember)");
  });

  it("LAST_N_QUARTERS emits LastPeriods", () => {
    expect(buildRelativeMdx({ preset: "LAST_N_QUARTERS", n: 2, hierarchy: HIER, level: "[Time].[Time].[Quarter]" }))
      .toBe("LastPeriods(2, [Time].[Time].[Quarter].CurrentMember)");
  });

  it("LAST_N_YEARS emits LastPeriods", () => {
    expect(buildRelativeMdx({ preset: "LAST_N_YEARS", n: 3, hierarchy: HIER, level: "[Time].[Time].[Year]" }))
      .toBe("LastPeriods(3, [Time].[Time].[Year].CurrentMember)");
  });

  it("ROLLING_N is structurally identical to LAST_N_* (grain lives on the level ref)", () => {
    expect(buildRelativeMdx({ preset: "ROLLING_N", n: 5, hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("LastPeriods(5, [Time].[Time].[Day].CurrentMember)");
  });

  it("MONTH_TO_DATE → PeriodsToDate over [Time].[Month]", () => {
    expect(buildRelativeMdx({ preset: "MONTH_TO_DATE", hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("PeriodsToDate([Time].[Month], [Time].[Time].CurrentMember)");
  });

  it("QUARTER_TO_DATE idiom", () => {
    expect(buildRelativeMdx({ preset: "QUARTER_TO_DATE", hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("PeriodsToDate([Time].[Quarter], [Time].[Time].CurrentMember)");
  });

  it("YEAR_TO_DATE idiom", () => {
    expect(buildRelativeMdx({ preset: "YEAR_TO_DATE", hierarchy: HIER, level: DAY_LEVEL }))
      .toBe("PeriodsToDate([Time].[Year], [Time].[Time].CurrentMember)");
  });
});

describe("buildAbsoluteMdx", () => {
  it("Foodmart-shape path for Year/Quarter/Month/Day levels", () => {
    const mdx = buildAbsoluteMdx({
      from: "2024-01-15",
      to: "2024-03-31",
      hierarchy: HIER,
      level: "[Time].[Time].[Month]",
    });
    expect(mdx).toBe("{[Time].[Time].[2024].[Q1].[January].[15] : [Time].[Time].[2024].[Q1].[March].[31]}");
  });

  it("ISO-leaf shape for date-looking levels", () => {
    const mdx = buildAbsoluteMdx({
      from: "2024-01-15",
      to: "2024-03-31",
      hierarchy: HIER,
      level: DAY_LEVEL, // "Day" → treated as ISO-leaf (no year/month/quarter tokens)
    });
    expect(mdx).toBe("{[Time].[Time].[2024-01-15] : [Time].[Time].[2024-03-31]}");
  });

  it("prior-period comparison adds a shifted UNION set", () => {
    const mdx = buildAbsoluteMdx({
      from: "2024-01-01",
      to: "2024-01-07",
      hierarchy: HIER,
      level: DAY_LEVEL,
      compare: "PRIOR_PERIOD",
    });
    // 7-day span → both endpoints shift by 7.
    expect(mdx).toBe(
      "UNION({[Time].[Time].[2024-01-01] : [Time].[Time].[2024-01-07]}, {[Time].[Time].[2024-01-01].Lag(7) : [Time].[Time].[2024-01-07].Lag(7)})",
    );
  });

  it("prior-year comparison shifts by 365", () => {
    const mdx = buildAbsoluteMdx({
      from: "2024-06-01",
      to: "2024-06-07",
      hierarchy: HIER,
      level: DAY_LEVEL,
      compare: "PRIOR_YEAR",
    });
    expect(mdx).toBe(
      "UNION({[Time].[Time].[2024-06-01] : [Time].[Time].[2024-06-07]}, {[Time].[Time].[2024-06-01].Lag(365) : [Time].[Time].[2024-06-07].Lag(365)})",
    );
  });
});

describe("memberForDate", () => {
  it("Foodmart-shape when level caption mentions quarter/month/year", () => {
    expect(memberForDate("2024-07-04", HIER, "[Time].[Time].[Month]"))
      .toBe("[Time].[Time].[2024].[Q3].[July].[4]");
  });
  it("ISO-leaf otherwise", () => {
    expect(memberForDate("2024-07-04", HIER, "[Time].[Time].[Day]"))
      .toBe("[Time].[Time].[2024-07-04]");
  });
});

describe("validation", () => {
  it("rejects n=0 / negative / non-numeric for LAST_N_*", () => {
    expect(isRelativeValid({ preset: "LAST_N_DAYS", n: 0, hierarchy: HIER, level: DAY_LEVEL })).toBe(false);
    expect(isRelativeValid({ preset: "LAST_N_DAYS", n: -1, hierarchy: HIER, level: DAY_LEVEL })).toBe(false);
    expect(isRelativeValid({ preset: "LAST_N_DAYS", hierarchy: HIER, level: DAY_LEVEL })).toBe(false);
  });
  it("accepts positive n", () => {
    expect(isRelativeValid({ preset: "LAST_N_DAYS", n: 5, hierarchy: HIER, level: DAY_LEVEL })).toBe(true);
  });
  it("TODAY/YESTERDAY/MTD/QTD/YTD ignore n", () => {
    expect(isRelativeValid({ preset: "TODAY", hierarchy: HIER, level: DAY_LEVEL })).toBe(true);
    expect(isRelativeValid({ preset: "YEAR_TO_DATE", hierarchy: HIER, level: DAY_LEVEL })).toBe(true);
  });
  it("absolute requires from<=to", () => {
    expect(isAbsoluteValid({ from: "2024-01-01", to: "2024-01-01" })).toBe(true);
    expect(isAbsoluteValid({ from: "2024-02-01", to: "2024-01-01" })).toBe(false);
    expect(isAbsoluteValid({ from: "", to: "2024-01-01" })).toBe(false);
    expect(isAbsoluteValid({ from: "notadate", to: "2024-01-01" })).toBe(false);
  });
});

describe("looksLikeTimeHierarchy", () => {
  it("matches common time-ish captions", () => {
    expect(looksLikeTimeHierarchy("Time")).toBe(true);
    expect(looksLikeTimeHierarchy("Order Date")).toBe(true);
    expect(looksLikeTimeHierarchy("Fiscal Year")).toBe(true);
    expect(looksLikeTimeHierarchy("Customer")).toBe(false);
  });
  it("returns true for undefined caption (err on the side of showing)", () => {
    expect(looksLikeTimeHierarchy(undefined)).toBe(true);
  });
});
