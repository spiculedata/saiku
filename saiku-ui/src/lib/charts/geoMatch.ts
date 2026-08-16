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
	usa: 'United States of America',
	us: 'United States of America',
	'u.s.': 'United States of America',
	'u.s.a.': 'United States of America',
	'united states': 'United States of America',
	'united states of america (the)': 'United States of America',
	america: 'United States of America',
	// United Kingdom
	uk: 'United Kingdom',
	'u.k.': 'United Kingdom',
	'great britain': 'United Kingdom',
	britain: 'United Kingdom',
	england: 'United Kingdom',
	// Korea
	'south korea': 'South Korea',
	korea: 'South Korea',
	'republic of korea': 'South Korea',
	'korea, south': 'South Korea',
	'north korea': 'North Korea',
	dprk: 'North Korea',
	'korea, north': 'North Korea',
	// Russia
	russia: 'Russia',
	'russian federation': 'Russia',
	// Czechia
	'czech republic': 'Czechia',
	czechia: 'Czechia',
	// Côte d'Ivoire
	'ivory coast': "Côte d'Ivoire",
	"cote d'ivoire": "Côte d'Ivoire",
	// Congo
	'democratic republic of the congo': 'Dem. Rep. Congo',
	'dr congo': 'Dem. Rep. Congo',
	drc: 'Dem. Rep. Congo',
	'congo-kinshasa': 'Dem. Rep. Congo',
	'republic of the congo': 'Congo',
	'congo-brazzaville': 'Congo',
	// Misc common
	burma: 'Myanmar',
	'lao pdr': 'Laos',
	macedonia: 'North Macedonia',
	'bosnia and herzegovina': 'Bosnia and Herz.',
	bosnia: 'Bosnia and Herz.',
	'syrian arab republic': 'Syria',
	'united republic of tanzania': 'Tanzania',
	'united arab emirates (the)': 'United Arab Emirates',
	uae: 'United Arab Emirates'
};

/** Normalise a raw OLAP caption to a candidate GeoJSON feature name:
 *  trim + apply the alias table (case-insensitive). Names that already
 *  match a feature pass straight through, so ECharts resolves them; names
 *  with no alias and no direct feature match simply won't render (ECharts
 *  ignores unmatched data) — a missing country, never a wrong one. */
export function aliasGeoName(raw: string): string {
	const trimmed = (raw ?? '').trim();
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

/* -------------------------------------------------------------------------
 * Coverage reporting (saiku#1758)
 *
 * ECharts silently drops map data whose name matches no feature, which is the
 * right rendering behaviour (a missing country, never a wrong one) but a poor
 * reporting one: binding a cube's US states to the country basemap painted
 * exactly one shape — Georgia, the COUNTRY — and said nothing. One shaded
 * country out of sixteen rows looks like a result, and a US state landing on
 * the wrong continent is worse than a blank map.
 * ---------------------------------------------------------------------- */

/** How much of a map tile's data actually landed on the basemap. */
export interface GeoCoverage {
	/** Row names offered to the map. */
	total: number;
	/** How many resolved to a feature. */
	matched: number;
	/** The names that didn't, in row order. */
	unmatched: string[];
}

/** Measure how many of `names` resolve against `featureNames`.
 *
 *  Returns null when the feature list is empty — the map hasn't registered
 *  yet, and "nothing matched" would be a lie about the data rather than a
 *  fact about it. */
export function geoCoverage(
	names: readonly string[],
	featureNames: readonly string[]
): GeoCoverage | null {
	if (featureNames.length === 0) return null;
	const unmatched: string[] = [];
	let matched = 0;
	for (const n of names) {
		if (matchGeoName(n, featureNames)) matched++;
		else unmatched.push(n);
	}
	return { total: names.length, matched, unmatched };
}

/** How many unmatched names to name before summarising the rest. */
const NOTICE_SAMPLE = 3;

/** A one-line, human notice for a partial match — or null when there's nothing
 *  worth saying (full coverage, or no data). Deliberately states the ratio
 *  first: "1 of 16 categories" is the fact that reframes the picture. */
export function geoCoverageNotice(coverage: GeoCoverage | null): string | null {
	if (!coverage || coverage.total === 0) return null;
	if (coverage.unmatched.length === 0) return null;
	const sample = coverage.unmatched.slice(0, NOTICE_SAMPLE).join(', ');
	const rest = coverage.unmatched.length - NOTICE_SAMPLE;
	const tail = rest > 0 ? `, +${rest} more` : '';
	return `${coverage.matched} of ${coverage.total} categories matched the country map (no match: ${sample}${tail}). This map plots countries — sub-national names won't place.`;
}
