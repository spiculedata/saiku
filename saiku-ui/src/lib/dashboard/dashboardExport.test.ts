import { describe, expect, test } from "vitest";

import {
  buildExportFilename,
  buildFilterSummary,
  memberCaption,
  paginate,
  sanitiseFilenameStem,
  timestampStamp,
} from "./dashboardExport";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";

function clickFilter(
  dimension: string,
  members: string[],
  hierarchy = dimension,
  level = dimension,
): ActiveFilter {
  return {
    id: `f-${dimension}`,
    source: { kind: "click", tileId: "t1" },
    filter: { dimension, hierarchy, level, members },
  };
}

describe("memberCaption", () => {
  test("strips brackets and returns the last segment", () => {
    expect(memberCaption("[Time].[Time].[1997].[Q2]")).toBe("Q2");
    expect(memberCaption("[Store].[Store].[USA]")).toBe("USA");
  });

  test("returns trimmed input when not bracketed", () => {
    expect(memberCaption("  Plain  ")).toBe("Plain");
  });

  test("handles an empty bracketed segment", () => {
    expect(memberCaption("[Store].[]")).toBe("");
  });
});

describe("buildFilterSummary", () => {
  test("empty filter set yields an empty string", () => {
    expect(buildFilterSummary([])).toBe("");
  });

  test("skips filters with no selected members (any)", () => {
    expect(buildFilterSummary([clickFilter("[Store]", [])])).toBe("");
  });

  test("renders dimension caption with captioned members", () => {
    const out = buildFilterSummary([
      clickFilter("[Store]", ["[Store].[USA]", "[Store].[Canada]"]),
    ]);
    expect(out).toBe("Store: USA, Canada");
  });

  test("joins multiple filters with a middle dot", () => {
    const out = buildFilterSummary([
      clickFilter("[Store]", ["[Store].[USA]"]),
      clickFilter("[Time]", ["[Time].[Time].[1997].[Q2]"]),
    ]);
    expect(out).toBe("Store: USA · Time: Q2");
  });

  test("collapses excess members to +N more", () => {
    const out = buildFilterSummary(
      [clickFilter("[Store]", ["[Store].[A]", "[Store].[B]", "[Store].[C]", "[Store].[D]"])],
      { maxMembersPerFilter: 2 },
    );
    expect(out).toBe("Store: A, B +2 more");
  });

  test("de-duplicates members before counting", () => {
    const out = buildFilterSummary([
      clickFilter("[Store]", ["[Store].[USA]", "[Store].[USA]"]),
    ]);
    expect(out).toBe("Store: USA");
  });
});

describe("sanitiseFilenameStem", () => {
  test("lowercases and hyphenates", () => {
    expect(sanitiseFilenameStem("Sales Overview 2026")).toBe("sales-overview-2026");
  });

  test("strips leading/trailing separators", () => {
    expect(sanitiseFilenameStem("  !!Q2!!  ")).toBe("q2");
  });

  test("falls back to 'dashboard' for empty / symbol-only input", () => {
    expect(sanitiseFilenameStem("")).toBe("dashboard");
    expect(sanitiseFilenameStem("***")).toBe("dashboard");
  });
});

describe("timestampStamp", () => {
  test("formats local YYYYMMDD-HHmm with zero padding", () => {
    const d = new Date(2026, 5, 7, 9, 5); // 2026-06-07 09:05 local
    expect(timestampStamp(d)).toBe("20260607-0905");
  });
});

describe("buildExportFilename", () => {
  test("composes stem, stamp and extension", () => {
    const d = new Date(2026, 5, 7, 15, 30);
    expect(buildExportFilename("Sales Overview", "png", d)).toBe(
      "sales-overview-20260607-1530.png",
    );
    expect(buildExportFilename("Sales Overview", "pdf", d)).toBe(
      "sales-overview-20260607-1530.pdf",
    );
  });
});

describe("paginate", () => {
  test("single page when image fits", () => {
    expect(paginate(500, 800)).toEqual([{ sourceY: 0, sliceHeight: 500 }]);
  });

  test("exact fit is a single page", () => {
    expect(paginate(800, 800)).toEqual([{ sourceY: 0, sliceHeight: 800 }]);
  });

  test("splits a tall image into non-overlapping bands", () => {
    const slices = paginate(2000, 800);
    expect(slices).toEqual([
      { sourceY: 0, sliceHeight: 800 },
      { sourceY: 800, sliceHeight: 800 },
      { sourceY: 1600, sliceHeight: 400 },
    ]);
  });

  test("slices fully tile the source height with no gaps", () => {
    const slices = paginate(1850, 600);
    const covered = slices.reduce((sum, s) => sum + s.sliceHeight, 0);
    expect(covered).toBe(1850);
    for (let i = 1; i < slices.length; i++) {
      expect(slices[i].sourceY).toBe(slices[i - 1].sourceY + slices[i - 1].sliceHeight);
    }
  });

  test("degenerate page height yields a single full-image slice", () => {
    expect(paginate(500, 0)).toEqual([{ sourceY: 0, sliceHeight: 500 }]);
  });
});
