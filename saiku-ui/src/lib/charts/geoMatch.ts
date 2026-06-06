/*
 * Geo name matching (#1071, map chart). OLAP cubes give us place *names*
 * ("USA", "UK"), not shapes — and those names rarely match a GeoJSON
 * feature's name verbatim ("United States of America", "United Kingdom").
 * This module bridges the two with a small alias table covering the common
 * mismatches, plus case/whitespace normalisation.
 *
 * Pure (no DOM/fetch/echarts), so it unit-tests cleanly and — being the one
 * piece independent of the chart-rendering pipeline — survives any future
 * refactor of the builder/components untouched.
 *
 * Targets are the EXACT feature names in static/geo/world.json (Natural
 * Earth 110m admin-0), verified against that file.
 */

/** Lowercased OLAP caption → exact Natural Earth admin-0 feature name.
 *  Only entries that actually differ from the feature name are listed;
 *  names that already match (Canada, Mexico, France, …) pass through. */
export const COUNTRY_ALIASES: Record<string, string> = {
  // United States
  usa: "United States of America",
  us: "United States of America",
  "u.s.": "United States of America",
  "u.s.a.": "United States of America",
  "united states": "United States of America",
  "united states of america (the)": "United States of America",
  america: "United States of America",
  // United Kingdom
  uk: "United Kingdom",
  "u.k.": "United Kingdom",
  "great britain": "United Kingdom",
  britain: "United Kingdom",
  england: "United Kingdom",
  // Korea
  "south korea": "South Korea",
  korea: "South Korea",
  "republic of korea": "South Korea",
  "korea, south": "South Korea",
  "north korea": "North Korea",
  dprk: "North Korea",
  "korea, north": "North Korea",
  // Russia
  russia: "Russia",
  "russian federation": "Russia",
  // Czechia
  "czech republic": "Czechia",
  czechia: "Czechia",
  // Côte d'Ivoire
  "ivory coast": "Côte d'Ivoire",
  "cote d'ivoire": "Côte d'Ivoire",
  // Congo
  "democratic republic of the congo": "Dem. Rep. Congo",
  "dr congo": "Dem. Rep. Congo",
  drc: "Dem. Rep. Congo",
  "congo-kinshasa": "Dem. Rep. Congo",
  "republic of the congo": "Congo",
  "congo-brazzaville": "Congo",
  // Misc common
  burma: "Myanmar",
  "lao pdr": "Laos",
  macedonia: "North Macedonia",
  "bosnia and herzegovina": "Bosnia and Herz.",
  bosnia: "Bosnia and Herz.",
  "syrian arab republic": "Syria",
  "united republic of tanzania": "Tanzania",
  "united arab emirates (the)": "United Arab Emirates",
  uae: "United Arab Emirates",
};

/** Normalise a raw OLAP caption to a candidate GeoJSON feature name:
 *  trim + apply the alias table (case-insensitive). Names that already
 *  match a feature pass straight through, so ECharts resolves them; names
 *  with no alias and no direct feature match simply won't render (ECharts
 *  ignores unmatched data) — a missing country, never a wrong one. */
export function aliasGeoName(raw: string): string {
  const trimmed = (raw ?? "").trim();
  return COUNTRY_ALIASES[trimmed.toLowerCase()] ?? trimmed;
}

/** Feature-name-aware match: resolve a raw caption to the EXACT feature
 *  name present in `featureNames`, or null if none matches (after alias +
 *  case-insensitive comparison). Used to validate / report coverage; the
 *  builder itself uses {@link aliasGeoName} since it has no feature list. */
export function matchGeoName(raw: string, featureNames: readonly string[]): string | null {
  const candidate = aliasGeoName(raw).toLowerCase();
  for (const fn of featureNames) {
    if (fn.toLowerCase() === candidate) return fn;
  }
  return null;
}
