/**
 * Nationality normalisation for the Borderlines corridor view.
 *
 * The Benafide `persons.nationality` column is free-text and arrives in two
 * shapes — adjectival demonyms ("British", "Romanian") and ISO-2 codes ("GB",
 * "RO") — often for the same country ("British" / "English" / "GB" all → GB).
 * Saiku does the heavy grouping; this maps the raw label onto a canonical
 * { code, name } so owner-country → company-jurisdiction corridors fold cleanly.
 *
 * Company `entities.jurisdiction` is already ISO-2, so it only needs a display
 * name (COUNTRY_NAME) — no demonym mapping.
 */

export interface Country {
	code: string;
	name: string;
}

/** ISO-2 → display name. Covers the jurisdictions + owner countries in the data. */
export const COUNTRY_NAME: Record<string, string> = {
	GB: 'United Kingdom',
	IE: 'Ireland',
	RO: 'Romania',
	PL: 'Poland',
	PK: 'Pakistan',
	IN: 'India',
	CN: 'China',
	IT: 'Italy',
	SK: 'Slovakia',
	NG: 'Nigeria',
	PH: 'Philippines',
	TR: 'Turkey',
	FR: 'France',
	DE: 'Germany',
	BG: 'Bulgaria',
	ES: 'Spain',
	US: 'United States',
	PT: 'Portugal',
	LT: 'Lithuania',
	AU: 'Australia',
	NL: 'Netherlands',
	BD: 'Bangladesh',
	MA: 'Morocco',
	HU: 'Hungary',
	GR: 'Greece',
	UA: 'Ukraine',
	ZA: 'South Africa',
	LV: 'Latvia',
	SE: 'Sweden',
	ZW: 'Zimbabwe',
	IR: 'Iran',
	CA: 'Canada',
	EG: 'Egypt',
	BE: 'Belgium',
	CZ: 'Czechia',
	EE: 'Estonia',
	DK: 'Denmark',
	NO: 'Norway',
	FI: 'Finland',
	RU: 'Russia',
	LU: 'Luxembourg',
	CY: 'Cyprus',
	MT: 'Malta',
	CH: 'Switzerland',
	AT: 'Austria',
	HR: 'Croatia',
	SI: 'Slovenia',
	AE: 'United Arab Emirates',
	HK: 'Hong Kong',
	SG: 'Singapore',
	NZ: 'New Zealand',
	BR: 'Brazil',
	MX: 'Mexico',
	JP: 'Japan',
	KR: 'South Korea'
};

/** Adjectival demonym (lower-cased) → ISO-2 code. Also folds UK sub-nations onto GB. */
const DEMONYM: Record<string, string> = {
	british: 'GB',
	english: 'GB',
	scottish: 'GB',
	welsh: 'GB',
	'northern irish': 'GB',
	'united kingdom': 'GB',
	uk: 'GB',
	irish: 'IE',
	romanian: 'RO',
	polish: 'PL',
	pakistani: 'PK',
	indian: 'IN',
	chinese: 'CN',
	italian: 'IT',
	slovak: 'SK',
	slovakian: 'SK',
	nigerian: 'NG',
	filipino: 'PH',
	philippine: 'PH',
	turkish: 'TR',
	french: 'FR',
	german: 'DE',
	bulgarian: 'BG',
	spanish: 'ES',
	american: 'US',
	'united states': 'US',
	portuguese: 'PT',
	lithuanian: 'LT',
	australian: 'AU',
	dutch: 'NL',
	netherlands: 'NL',
	bangladeshi: 'BD',
	moroccan: 'MA',
	hungarian: 'HU',
	greek: 'GR',
	ukrainian: 'UA',
	'south african': 'ZA',
	latvian: 'LV',
	swedish: 'SE',
	zimbabwean: 'ZW',
	iranian: 'IR',
	canadian: 'CA',
	egyptian: 'EG',
	belgian: 'BE',
	czech: 'CZ',
	estonian: 'EE',
	danish: 'DK',
	norwegian: 'NO',
	finnish: 'FI',
	russian: 'RU',
	cypriot: 'CY',
	maltese: 'MT',
	swiss: 'CH',
	austrian: 'AT',
	croatian: 'HR',
	slovenian: 'SI',
	emirati: 'AE',
	singaporean: 'SG',
	'new zealand': 'NZ',
	brazilian: 'BR',
	mexican: 'MX',
	japanese: 'JP',
	// bare 3-letter codes seen in the data
	est: 'EE',
	gbr: 'GB',
	irl: 'IE'
};

/**
 * Normalise a raw nationality value to a canonical country, or null when it is
 * blank or an unmapped long-tail demonym (excluded from the corridor view).
 */
export function normaliseNationality(raw: string | null | undefined): Country | null {
	if (!raw) return null;
	const trimmed = raw.trim();
	if (trimmed === '') return null;
	const lower = trimmed.toLowerCase();

	const byDemonym = DEMONYM[lower];
	if (byDemonym) return { code: byDemonym, name: COUNTRY_NAME[byDemonym] ?? byDemonym };

	// Already an ISO-2 code (possibly unnamed) — accept as-is.
	if (/^[a-z]{2}$/.test(lower)) {
		const code = lower.toUpperCase();
		return { code, name: COUNTRY_NAME[code] ?? code };
	}
	return null;
}

/** Display name for a company jurisdiction (already ISO-2). */
export function jurisdictionName(code: string): string {
	return COUNTRY_NAME[code] ?? code;
}
