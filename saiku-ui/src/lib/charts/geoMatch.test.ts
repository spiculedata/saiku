import { describe, it, expect } from "vitest";
import {
  aliasGeoName,
  matchGeoName,
  geoCoverage,
  geoCoverageNotice,
  COUNTRY_ALIASES,
} from "$lib/charts/geoMatch";

// A few exact feature names from static/geo/world.json (Natural Earth 110m).
const FEATURES = [
  "United States of America",
  "Canada",
  "Mexico",
  "United Kingdom",
  "South Korea",
  "Czechia",
  "Côte d'Ivoire",
  "France",
];

describe("aliasGeoName", () => {
  it("maps common synonyms to the exact feature name", () => {
    expect(aliasGeoName("USA")).toBe("United States of America");
    expect(aliasGeoName("US")).toBe("United States of America");
    expect(aliasGeoName("United States")).toBe("United States of America");
    expect(aliasGeoName("UK")).toBe("United Kingdom");
    expect(aliasGeoName("Czech Republic")).toBe("Czechia");
    expect(aliasGeoName("Ivory Coast")).toBe("Côte d'Ivoire");
  });

  it("is case- and whitespace-insensitive", () => {
    expect(aliasGeoName("  usa ")).toBe("United States of America");
    expect(aliasGeoName("uSa")).toBe("United States of America");
  });

  it("passes through names that already match a feature (Canada, Mexico)", () => {
    expect(aliasGeoName("Canada")).toBe("Canada");
    expect(aliasGeoName("Mexico")).toBe("Mexico");
    expect(aliasGeoName("France")).toBe("France");
  });

  it("passes through an unknown name unchanged (trimmed) — never invents a match", () => {
    expect(aliasGeoName("  Atlantis ")).toBe("Atlantis");
  });
});

describe("matchGeoName", () => {
  it("resolves a synonym to the exact feature string present in the list", () => {
    expect(matchGeoName("USA", FEATURES)).toBe("United States of America");
    expect(matchGeoName("uk", FEATURES)).toBe("United Kingdom");
  });

  it("resolves a direct (case-insensitive) match", () => {
    expect(matchGeoName("canada", FEATURES)).toBe("Canada");
  });

  it("returns null when nothing matches (so the caller can drop / report it)", () => {
    expect(matchGeoName("Atlantis", FEATURES)).toBeNull();
    // alias target not in this feature list → still null
    expect(matchGeoName("South Korea", ["Canada", "Mexico"])).toBeNull();
  });
});

describe("COUNTRY_ALIASES table", () => {
  it("uses lowercased keys (lookup contract)", () => {
    for (const key of Object.keys(COUNTRY_ALIASES)) {
      expect(key).toBe(key.toLowerCase());
    }
  });
});

/*
 * Coverage reporting (saiku#1758). The choropleth resolves names against a
 * COUNTRY basemap, and ECharts silently ignores data it can't match. Bind a
 * cube's US states to it and you get a blank world map with exactly one shape
 * painted — Georgia, the country — which reads as a real (if odd) result. The
 * chart must be able to say how much of the data it actually placed.
 */
describe("geoCoverage (saiku#1758)", () => {
  const FEATURES = ["United States of America", "Georgia", "France", "Canada"];

  it("reports full coverage when every name matches", () => {
    expect(geoCoverage(["France", "Canada"], FEATURES)).toEqual({
      total: 2,
      matched: 2,
      unmatched: [],
    });
  });

  it("counts and names the misses", () => {
    const r = geoCoverage(["Illinois", "Michigan", "Georgia"], FEATURES)!;
    expect(r.total).toBe(3);
    expect(r.matched).toBe(1);
    expect(r.unmatched).toEqual(["Illinois", "Michigan"]);
  });

  it("counts an alias hit as matched", () => {
    expect(geoCoverage(["USA"], FEATURES)!.matched).toBe(1);
  });

  it("is empty-safe", () => {
    expect(geoCoverage([], FEATURES)).toEqual({
      total: 0,
      matched: 0,
      unmatched: [],
    });
  });

  it("treats an unregistered (empty) feature list as unknown, not as total failure", () => {
    expect(geoCoverage(["France"], [])).toBeNull();
  });
});

describe("geoCoverageNotice (saiku#1758)", () => {
  it("is silent when everything matched", () => {
    expect(
      geoCoverageNotice({ total: 4, matched: 4, unmatched: [] }),
    ).toBeNull();
  });

  it("is silent when there is nothing to report", () => {
    expect(geoCoverageNotice(null)).toBeNull();
  });

  it("names the misses when a few fail", () => {
    const n = geoCoverageNotice({
      total: 16,
      matched: 1,
      unmatched: ["Illinois", "Michigan", "Ohio"],
    });
    expect(n).toContain("1 of 16");
    expect(n).toContain("Illinois");
  });

  it("truncates a long miss list rather than dumping every name", () => {
    const unmatched = Array.from({ length: 20 }, (_, i) => `State ${i}`);
    const n = geoCoverageNotice({ total: 20, matched: 0, unmatched })!;
    expect(n).toContain("0 of 20");
    expect(n.length).toBeLessThan(200);
  });
});
