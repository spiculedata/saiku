/*
 * Unit tests for the chart screen-reader summary (#1090).
 */

import { describe, test, expect } from "vitest";
import { chartTypeLabel, chartSummary, chartAriaLabel } from "$lib/charts/a11y";
import type { ChartProjection } from "$lib/charts/build";

function sample(): ChartProjection {
  return {
    rowCategories: ["1997", "1998"],
    columnCategories: ["Store Sales", "Unit Sales"],
    matrix: [
      [565238.13, 266773],
      [612482.65, 282417],
    ],
  };
}

describe("chartTypeLabel", () => {
  test("maps known ids to their human label, falls back to Chart", () => {
    expect(chartTypeLabel("bar")).toBe("Bar");
    expect(chartTypeLabel("stackedBar")).toBe("Stacked bar");
    expect(chartTypeLabel("pie")).toBe("Pie");
    expect(chartTypeLabel("nonsense")).toBe("Chart");
  });
});

describe("chartSummary", () => {
  test("builds a caption naming the type, series, and category count", () => {
    const s = chartSummary("bar", "", sample());
    expect(s.empty).toBe(false);
    expect(s.caption).toContain("Bar chart");
    expect(s.caption).toContain("Store Sales, Unit Sales");
    expect(s.caption).toContain("2 categories");
  });

  test("includes the title in the caption when present", () => {
    const s = chartSummary("line", "Sales over time", sample());
    expect(s.caption).toContain("“Sales over time”");
    expect(s.caption).toContain("Line chart");
  });

  test("table headers have a blank corner then the series names", () => {
    const s = chartSummary("bar", "", sample());
    expect(s.headers).toEqual(["", "Store Sales", "Unit Sales"]);
  });

  test("one row per category, category label first then formatted values", () => {
    const s = chartSummary("bar", "", sample());
    expect(s.rows).toHaveLength(2);
    expect(s.rows[0][0]).toBe("1997");
    // Locale-formatted numbers (grouping); exact separator is locale-dependent
    // so assert the digits survive.
    expect(s.rows[0][1].replace(/[^0-9.]/g, "")).toBe("565238.13");
    expect(s.rows[1][0]).toBe("1998");
  });

  test("renders missing cells as an em-dash", () => {
    const gappy: ChartProjection = {
      rowCategories: ["a"],
      columnCategories: ["m"],
      matrix: [[null]],
    };
    const s = chartSummary("bar", "", gappy);
    expect(s.rows[0][1]).toBe("—");
  });

  test("singular 'category' for a single row", () => {
    const one: ChartProjection = { rowCategories: ["a"], columnCategories: ["m"], matrix: [[1]] };
    expect(chartSummary("bar", "", one).caption).toContain("1 category");
  });

  test("empty projection → empty=true, no-data caption, no rows", () => {
    const s = chartSummary("bar", "", { rowCategories: [], columnCategories: [], matrix: [] });
    expect(s.empty).toBe(true);
    expect(s.caption).toContain("no data");
    expect(s.rows).toEqual([]);
  });

  test("honors a custom formatter", () => {
    const s = chartSummary("bar", "", sample(), (v) => (v == null ? "n/a" : `$${v}`));
    expect(s.rows[0][1]).toBe("$565238.13");
  });
});

describe("chartAriaLabel", () => {
  test("returns the summary caption", () => {
    expect(chartAriaLabel("bar", "T", sample())).toBe(chartSummary("bar", "T", sample()).caption);
  });
});
