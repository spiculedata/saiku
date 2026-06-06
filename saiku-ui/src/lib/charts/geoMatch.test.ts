import { describe, it, expect } from "vitest";
import { aliasGeoName, matchGeoName, COUNTRY_ALIASES } from "$lib/charts/geoMatch";

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
