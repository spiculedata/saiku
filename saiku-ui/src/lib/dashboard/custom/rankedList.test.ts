/*
 * Tests for the `ranked-list` renderer's pure projection + validator.
 *
 * The shape under test is the FoodMart Ops "Movers" card: a product department
 * per row, a month-over-month growth measure, six rows, coloured by sign.
 */

import { describe, expect, it } from "vitest";
import {
  DEFAULT_LIMIT,
  cellNumber,
  cellText,
  normaliseLimit,
  pickColumns,
  projectRankedList,
  validateRankedListConfig,
} from "./rankedList";

/** Records in the shape /ai/query returns: caption strings + typed cells. */
const MOVERS = [
  { Department: "Produce", Growth: { value: 0.226, formatted: "+22.6%" } },
  { Department: "Dairy", Growth: { value: -0.084, formatted: "-8.4%" } },
  { Department: "Beverages", Growth: { value: 0.141, formatted: "+14.1%" } },
  { Department: "Snack Foods", Growth: { value: 0.097, formatted: "+9.7%" } },
  { Department: "Frozen Foods", Growth: { value: -0.032, formatted: "-3.2%" } },
  { Department: "Baking Goods", Growth: { value: 0.06, formatted: "+6.0%" } },
  { Department: "Deli", Growth: { value: 0.011, formatted: "+1.1%" } },
];

describe("cellNumber / cellText", () => {
  it("reads plain numbers, typed cells and numeric strings", () => {
    expect(cellNumber(12)).toBe(12);
    expect(cellNumber({ value: 3.5, formatted: "3.5" })).toBe(3.5);
    expect(cellNumber("1,234")).toBe(1234);
    expect(cellNumber("6.1%")).toBe(6.1);
  });

  it("returns null for things that aren't numbers", () => {
    expect(cellNumber("Produce")).toBeNull();
    expect(cellNumber(null)).toBeNull();
    expect(cellNumber(undefined)).toBeNull();
    expect(cellNumber("")).toBeNull();
    expect(cellNumber(Number.NaN)).toBeNull();
  });

  it("prefers the server's formatted string", () => {
    expect(cellText({ value: 0.226, formatted: "+22.6%" })).toBe("+22.6%");
    expect(cellText({ value: 5 })).toBe("5");
    expect(cellText("Produce")).toBe("Produce");
    expect(cellText(null)).toBe("");
  });
});

describe("normaliseLimit", () => {
  it("defaults anything unusable", () => {
    for (const bad of [undefined, null, 0, -3, Number.NaN, "6"]) {
      expect(normaliseLimit(bad)).toBe(DEFAULT_LIMIT);
    }
  });

  it("caps absurd limits rather than rendering unbounded", () => {
    expect(normaliseLimit(10_000)).toBe(100);
  });
});

describe("pickColumns", () => {
  it("infers label + value with no configuration", () => {
    expect(pickColumns(MOVERS, {})).toEqual({
      labelColumn: "Department",
      valueColumn: "Growth",
    });
  });

  it("honours explicit column names", () => {
    const recs = [{ a: "x", b: 1, c: 2 }];
    expect(pickColumns(recs, { labelColumn: "a", valueColumn: "c" })).toEqual({
      labelColumn: "a",
      valueColumn: "c",
    });
  });

  it("ignores a configured column that isn't in the result", () => {
    expect(pickColumns(MOVERS, { valueColumn: "Nope" }).valueColumn).toBe("Growth");
  });

  it("returns nulls for an empty result", () => {
    expect(pickColumns([], {})).toEqual({ labelColumn: null, valueColumn: null });
  });
});

describe("projectRankedList", () => {
  it("ranks from 1 and caps at the default limit", () => {
    const rows = projectRankedList(MOVERS);
    expect(rows).toHaveLength(DEFAULT_LIMIT);
    expect(rows.map((r) => r.rank)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it("preserves query order by default", () => {
    expect(projectRankedList(MOVERS).map((r) => r.label)).toEqual([
      "Produce",
      "Dairy",
      "Beverages",
      "Snack Foods",
      "Frozen Foods",
      "Baking Goods",
    ]);
  });

  it("carries the server's formatted value through", () => {
    expect(projectRankedList(MOVERS)[0].formatted).toBe("+22.6%");
  });

  it("colours by sign under the signed tone", () => {
    const rows = projectRankedList(MOVERS);
    expect(rows.find((r) => r.label === "Produce")?.tone).toBe("positive");
    expect(rows.find((r) => r.label === "Dairy")?.tone).toBe("negative");
  });

  it("treats zero as flat, not positive", () => {
    const rows = projectRankedList([{ d: "Flat", v: { value: 0, formatted: "0.0%" } }]);
    expect(rows[0].tone).toBe("flat");
  });

  it("leaves everything flat under tone:none", () => {
    const rows = projectRankedList(MOVERS, { tone: "none" });
    expect(rows.every((r) => r.tone === "flat")).toBe(true);
  });

  /* Sort must run over the WHOLE result, not the already-truncated head —
   * otherwise "top 3" silently means "first 3 rows, re-ordered". */
  it("sorts before truncating so top-N is the real top-N", () => {
    const rows = projectRankedList(MOVERS, { sort: "desc", limit: 3 });
    expect(rows.map((r) => r.label)).toEqual(["Produce", "Beverages", "Snack Foods"]);
    expect(rows.map((r) => r.rank)).toEqual([1, 2, 3]);
  });

  it("sorts ascending on request", () => {
    const rows = projectRankedList(MOVERS, { sort: "asc", limit: 2 });
    expect(rows.map((r) => r.label)).toEqual(["Dairy", "Frozen Foods"]);
  });

  it("sinks valueless rows to the bottom when sorting", () => {
    const recs = [
      { d: "None", v: "n/a" },
      { d: "Big", v: 10 },
      { d: "Small", v: 1 },
    ];
    expect(projectRankedList(recs, { sort: "desc" }).map((r) => r.label)).toEqual([
      "Big",
      "Small",
      "None",
    ]);
  });

  it("returns an empty list for empty / missing records", () => {
    expect(projectRankedList([])).toEqual([]);
    expect(projectRankedList(null)).toEqual([]);
    expect(projectRankedList(undefined)).toEqual([]);
  });

  it("still renders labels when the query has no numeric column", () => {
    const rows = projectRankedList([{ d: "Only" }, { d: "Text" }]);
    expect(rows.map((r) => r.label)).toEqual(["Only", "Text"]);
    expect(rows[0].value).toBeNull();
  });
});

describe("validateRankedListConfig", () => {
  it("accepts an absent config and returns the defaults", () => {
    const r = validateRankedListConfig(undefined);
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.value.limit).toBe(DEFAULT_LIMIT);
      expect(r.value.sort).toBe("none");
      expect(r.value.tone).toBe("signed");
      expect(r.value.showRank).toBe(true);
    }
  });

  it("accepts a full valid config", () => {
    const r = validateRankedListConfig({
      labelColumn: "Department",
      valueColumn: "Growth",
      limit: 6,
      sort: "desc",
      tone: "signed",
      subtitle: "Product department · MoM",
      showRank: true,
    });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.value.subtitle).toBe("Product department · MoM");
  });

  it("normalises an out-of-range limit rather than rejecting it", () => {
    const r = validateRankedListConfig({ limit: 0 });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.value.limit).toBe(DEFAULT_LIMIT);
  });

  it.each([
    [{ sort: "sideways" }, "sort"],
    [{ tone: "rainbow" }, "tone"],
    [{ showRank: "yes" }, "showRank"],
    [{ limit: "6" }, "limit"],
    [{ labelColumn: 3 }, "labelColumn"],
  ])("rejects a wrong-typed field (%o)", (bad, field) => {
    const r = validateRankedListConfig(bad);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.error).toContain(field);
  });

  it("rejects non-objects", () => {
    expect(validateRankedListConfig([]).ok).toBe(false);
    expect(validateRankedListConfig("nope").ok).toBe(false);
  });
});
