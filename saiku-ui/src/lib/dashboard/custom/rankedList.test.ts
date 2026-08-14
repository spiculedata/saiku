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
    expect(pickColumns(MOVERS, { valueColumn: "Nope" }).valueColumn).toBe(
      "Growth",
    );
  });

  it("returns nulls for an empty result", () => {
    expect(pickColumns([], {})).toEqual({
      labelColumn: null,
      valueColumn: null,
    });
  });

  /*
   * saiku#1756. Column detection sniffed whether a cell PARSES as a number,
   * so a dimension whose captions happen to look numeric — a prescriber decile
   * ("10.0"), a year, a store number, a size band — was picked as the value and
   * the measure became the label. The card rendered currency as its label and
   * the decile as its value, with no error.
   *
   * A measure arrives as the typed { value, formatted } envelope, and that is
   * what identifies it — not what its text parses to.
   */
  it("prefers the typed measure cell over a numeric-looking caption (saiku#1756)", () => {
    const decileRows = [
      {
        Decile: "10.0",
        "Net Revenue": { value: 19824863.52, formatted: "$19,824,863.52" },
      },
      {
        Decile: "9.0",
        "Net Revenue": { value: 12391476.06, formatted: "$12,391,476.06" },
      },
    ];
    expect(pickColumns(decileRows, {})).toEqual({
      labelColumn: "Decile",
      valueColumn: "Net Revenue",
    });
  });

  it("still infers correctly when the caption column comes second", () => {
    const rows = [
      { "Net Revenue": { value: 10, formatted: "$10" }, Year: "2024" },
    ];
    expect(pickColumns(rows, {})).toEqual({
      labelColumn: "Year",
      valueColumn: "Net Revenue",
    });
  });

  it("falls back to numeric sniffing when no typed cell is present", () => {
    const plain = [
      { Region: "West", Total: 120 },
      { Region: "South", Total: 90 },
    ];
    expect(pickColumns(plain, {})).toEqual({
      labelColumn: "Region",
      valueColumn: "Total",
    });
  });

  it("still picks a numeric-string value column when that is all there is", () => {
    const rows = [{ Region: "West", Total: "120" }];
    expect(pickColumns(rows, {})).toEqual({
      labelColumn: "Region",
      valueColumn: "Total",
    });
  });

  it("projects the decile ladder the right way round end to end (saiku#1756)", () => {
    const decileRows = [
      {
        Decile: "9.0",
        "Net Revenue": { value: 12391476.06, formatted: "$12,391,476.06" },
      },
      {
        Decile: "10.0",
        "Net Revenue": { value: 19824863.52, formatted: "$19,824,863.52" },
      },
    ];
    const rows = projectRankedList(decileRows, { sort: "desc" });
    expect(rows[0].label).toBe("10.0");
    expect(rows[0].formatted).toBe("$19,824,863.52");
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
    const rows = projectRankedList([
      { d: "Flat", v: { value: 0, formatted: "0.0%" } },
    ]);
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
    expect(rows.map((r) => r.label)).toEqual([
      "Produce",
      "Beverages",
      "Snack Foods",
    ]);
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
    expect(
      projectRankedList(recs, { sort: "desc" }).map((r) => r.label),
    ).toEqual(["Big", "Small", "None"]);
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
