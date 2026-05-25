/*
 * Unit tests for normaliseDashboardPath — the path-rewriter that issue #945
 * relies on to keep new dashboards inside the user's home, even when the
 * user reaches the editor via a hand-typed URL.
 *
 * Plus cloneDashboardWithFreshIds for issue #939 (catalogue Duplicate
 * action) — the pure half of the duplicate flow.
 */

import { describe, expect, it } from "vitest";

import {
  cloneDashboardWithFreshIds,
  cloneTileWithFreshId,
  normaliseDashboardPath,
  type Dashboard,
  type DashboardTile,
} from "./dashboards";

describe("normaliseDashboardPath", () => {
  it("prefixes a bare filename with homes/<user>", () => {
    expect(normaliseDashboardPath("oops.saikudash", "juan")).toBe("homes/juan/oops.saikudash");
  });

  it("prefixes a relative subfolder path with homes/<user>", () => {
    expect(normaliseDashboardPath("marketing/q4.saikudash", "juan")).toBe(
      "homes/juan/marketing/q4.saikudash",
    );
  });

  it("leaves an existing homes/... path untouched", () => {
    expect(normaliseDashboardPath("homes/juan/foo.saikudash", "juan")).toBe(
      "homes/juan/foo.saikudash",
    );
    expect(normaliseDashboardPath("homes/admin/x.saikudash", "juan")).toBe(
      "homes/admin/x.saikudash",
    );
  });

  it("strips a leading slash and otherwise preserves an explicit absolute path", () => {
    expect(normaliseDashboardPath("/marketing/q4.saikudash", "juan")).toBe(
      "marketing/q4.saikudash",
    );
  });

  it("trims surrounding whitespace before deciding", () => {
    expect(normaliseDashboardPath("  oops.saikudash  ", "juan")).toBe("homes/juan/oops.saikudash");
  });

  it("throws on an empty or whitespace-only path", () => {
    expect(() => normaliseDashboardPath("", "juan")).toThrow(/required/);
    expect(() => normaliseDashboardPath("   ", "juan")).toThrow(/required/);
  });

  it("throws on a relative path with no current user", () => {
    expect(() => normaliseDashboardPath("oops.saikudash", "")).toThrow(/no current user/);
  });

  it("allows a leading-slash path even without a current user (admin save-as)", () => {
    expect(normaliseDashboardPath("/shared/exec.saikudash", "")).toBe("shared/exec.saikudash");
  });

  it("allows a homes/... path even without a current user", () => {
    expect(normaliseDashboardPath("homes/other/x.saikudash", "")).toBe("homes/other/x.saikudash");
  });
});

describe("cloneDashboardWithFreshIds", () => {
  /** A representative source dashboard with two tiles — enough to
   *  exercise the recursive id-rewrite and verify the rest of the
   *  shape (cube binding, query body, position) survives untouched. */
  function makeSource(): Dashboard {
    return {
      id: "src-dash-id",
      name: "Quarterly sales",
      version: 1,
      layout: {
        cols: 12,
        tiles: [
          {
            id: "tile-1",
            x: 0,
            y: 0,
            w: 6,
            h: 4,
            type: "chart",
            chartType: "bar",
            cube: { connectionName: "c", catalog: "C", schema: "S", cubeName: "Sales" },
            query: { kind: "inline", body: { measures: [{ name: "Store Sales" }] } },
          },
          {
            id: "tile-2",
            x: 6,
            y: 0,
            w: 3,
            h: 2,
            type: "kpi",
            kpi: { measure: "[Measures].[Store Sales]", format: "currency" },
          },
        ],
      },
      filters: [
        { dimension: "Time", hierarchy: "Time", level: "Year", members: ["[Time].[2024]"] },
      ],
    };
  }

  it("mints a fresh top-level dashboard id", () => {
    const src = makeSource();
    const copy = cloneDashboardWithFreshIds(src);
    expect(copy.id).not.toBe(src.id);
    expect(copy.id.length).toBeGreaterThan(0);
  });

  it("mints fresh ids for every tile, in the same order", () => {
    const src = makeSource();
    const copy = cloneDashboardWithFreshIds(src);
    const srcIds = src.layout.tiles.map((t) => t.id);
    const copyIds = copy.layout.tiles.map((t) => t.id);
    expect(copyIds).toHaveLength(srcIds.length);
    for (let i = 0; i < copyIds.length; i++) {
      expect(copyIds[i]).not.toBe(srcIds[i]);
    }
    // Distinct tile ids within the copy.
    expect(new Set(copyIds).size).toBe(copyIds.length);
  });

  it("appends ' (copy)' to the name when newName isn't supplied", () => {
    const copy = cloneDashboardWithFreshIds(makeSource());
    expect(copy.name).toBe("Quarterly sales (copy)");
  });

  it("uses the explicit newName when supplied", () => {
    const copy = cloneDashboardWithFreshIds(makeSource(), "Exec view");
    expect(copy.name).toBe("Exec view");
  });

  it("preserves every non-id field on tiles (position, cube, query, kpi)", () => {
    const src = makeSource();
    const copy = cloneDashboardWithFreshIds(src);
    for (let i = 0; i < src.layout.tiles.length; i++) {
      const a = src.layout.tiles[i];
      const b = copy.layout.tiles[i];
      const { id: _aId, ...aRest } = a;
      const { id: _bId, ...bRest } = b;
      void _aId;
      void _bId;
      expect(bRest).toEqual(aRest);
    }
  });

  it("preserves dashboard-level filters verbatim", () => {
    const src = makeSource();
    const copy = cloneDashboardWithFreshIds(src);
    expect(copy.filters).toEqual(src.filters);
  });

  it("does not mutate the source dashboard", () => {
    const src = makeSource();
    const snapshot = JSON.parse(JSON.stringify(src));
    cloneDashboardWithFreshIds(src);
    expect(src).toEqual(snapshot);
  });
});

describe("cloneTileWithFreshId", () => {
  function makeChartTile(): DashboardTile {
    return {
      id: "src-tile",
      x: 2,
      y: 3,
      w: 6,
      h: 4,
      type: "chart",
      title: "Sales by region",
      chartType: "bar",
      cube: { connectionName: "c", catalog: "C", schema: "S", cubeName: "Sales" },
      query: { kind: "inline", body: { measures: [{ name: "Store Sales" }] } },
    };
  }

  function makeUntitledKpiTile(): DashboardTile {
    return {
      id: "src-kpi",
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      type: "kpi",
      kpi: { measure: "[Measures].[Store Sales]", format: "currency" },
    };
  }

  it("mints a fresh tile id by default", () => {
    const src = makeChartTile();
    const copy = cloneTileWithFreshId(src);
    expect(copy.id).not.toBe(src.id);
    expect(copy.id.length).toBeGreaterThan(0);
  });

  it("honours an explicit newId override", () => {
    const copy = cloneTileWithFreshId(makeChartTile(), { newId: "my-fixed-id" });
    expect(copy.id).toBe("my-fixed-id");
  });

  it("appends ' (copy)' to a titled tile's title by default", () => {
    const copy = cloneTileWithFreshId(makeChartTile());
    expect(copy.title).toBe("Sales by region (copy)");
  });

  it("leaves an untitled tile's title undefined so the defaultTitle fallback still applies", () => {
    const copy = cloneTileWithFreshId(makeUntitledKpiTile());
    expect(copy.title).toBeUndefined();
  });

  it("honours an explicit titleSuffix override", () => {
    const copy = cloneTileWithFreshId(makeChartTile(), { titleSuffix: " — duplicate" });
    expect(copy.title).toBe("Sales by region — duplicate");
  });

  it("preserves every non-id field on the source tile", () => {
    const src = makeChartTile();
    const copy = cloneTileWithFreshId(src, { newId: "new" });
    const { id: _aId, title: _aTitle, ...aRest } = src;
    const { id: _bId, title: _bTitle, ...bRest } = copy;
    void _aId;
    void _bId;
    void _aTitle;
    void _bTitle;
    expect(bRest).toEqual(aRest);
  });

  it("does not mutate the source tile", () => {
    const src = makeChartTile();
    const snapshot = JSON.parse(JSON.stringify(src));
    cloneTileWithFreshId(src);
    expect(src).toEqual(snapshot);
  });
});
