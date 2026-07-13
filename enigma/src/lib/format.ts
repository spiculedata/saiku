interface RiskBand {
	label: string;
	color: string;
}

// Benafide's entity_risk.risk_score is a composite on roughly a 0..10 scale
// (most entities 0; flagged ones run ~2–8), not 0..1.
const HIGH_RISK_THRESHOLD = 6;
const MEDIUM_RISK_THRESHOLD = 3;

/**
 * Maps a risk score (~0..10) to a display band + CSS colour variable.
 * `null`/`undefined` scores are treated as unknown; 0 reads as Low.
 */
export function riskBand(score: number | null | undefined): RiskBand {
	if (score == null) return { label: 'Unknown', color: 'var(--dim)' };
	if (score >= HIGH_RISK_THRESHOLD) return { label: 'High', color: 'var(--red)' };
	if (score >= MEDIUM_RISK_THRESHOLD) return { label: 'Medium', color: 'var(--amber)' };
	return { label: 'Low', color: 'var(--green)' };
}

const GLOBE_FALLBACK = '🌐';

// ISO 3166-1 alpha-2 country codes. Anything outside this set (including
// non-standard/placeholder codes such as "ZZ") falls back to the globe.
const ISO_3166_1_ALPHA_2 = new Set([
	'AD', 'AE', 'AF', 'AG', 'AI', 'AL', 'AM', 'AO', 'AQ', 'AR', 'AS', 'AT', 'AU', 'AW', 'AX', 'AZ',
	'BA', 'BB', 'BD', 'BE', 'BF', 'BG', 'BH', 'BI', 'BJ', 'BL', 'BM', 'BN', 'BO', 'BQ', 'BR', 'BS',
	'BT', 'BV', 'BW', 'BY', 'BZ',
	'CA', 'CC', 'CD', 'CF', 'CG', 'CH', 'CI', 'CK', 'CL', 'CM', 'CN', 'CO', 'CR', 'CU', 'CV', 'CW',
	'CX', 'CY', 'CZ',
	'DE', 'DJ', 'DK', 'DM', 'DO', 'DZ',
	'EC', 'EE', 'EG', 'EH', 'ER', 'ES', 'ET',
	'FI', 'FJ', 'FK', 'FM', 'FO', 'FR',
	'GA', 'GB', 'GD', 'GE', 'GF', 'GG', 'GH', 'GI', 'GL', 'GM', 'GN', 'GP', 'GQ', 'GR', 'GS', 'GT',
	'GU', 'GW', 'GY',
	'HK', 'HM', 'HN', 'HR', 'HT', 'HU',
	'ID', 'IE', 'IL', 'IM', 'IN', 'IO', 'IQ', 'IR', 'IS', 'IT',
	'JE', 'JM', 'JO', 'JP',
	'KE', 'KG', 'KH', 'KI', 'KM', 'KN', 'KP', 'KR', 'KW', 'KY', 'KZ',
	'LA', 'LB', 'LC', 'LI', 'LK', 'LR', 'LS', 'LT', 'LU', 'LV', 'LY',
	'MA', 'MC', 'MD', 'ME', 'MF', 'MG', 'MH', 'MK', 'ML', 'MM', 'MN', 'MO', 'MP', 'MQ', 'MR', 'MS',
	'MT', 'MU', 'MV', 'MW', 'MX', 'MY', 'MZ',
	'NA', 'NC', 'NE', 'NF', 'NG', 'NI', 'NL', 'NO', 'NP', 'NR', 'NU', 'NZ',
	'OM',
	'PA', 'PE', 'PF', 'PG', 'PH', 'PK', 'PL', 'PM', 'PN', 'PR', 'PS', 'PT', 'PW', 'PY',
	'QA',
	'RE', 'RO', 'RS', 'RU', 'RW',
	'SA', 'SB', 'SC', 'SD', 'SE', 'SG', 'SH', 'SI', 'SJ', 'SK', 'SL', 'SM', 'SN', 'SO', 'SR', 'SS',
	'ST', 'SV', 'SX', 'SY', 'SZ',
	'TC', 'TD', 'TF', 'TG', 'TH', 'TJ', 'TK', 'TL', 'TM', 'TN', 'TO', 'TR', 'TT', 'TV', 'TW', 'TZ',
	'UA', 'UG', 'UM', 'US', 'UY', 'UZ',
	'VA', 'VC', 'VE', 'VG', 'VI', 'VN', 'VU',
	'WF', 'WS',
	'YE', 'YT',
	'ZA', 'ZM', 'ZW'
]);

const REGIONAL_INDICATOR_CODE_POINT_OFFSET = 0x1f1a5;

function toRegionalIndicatorFlag(code: string): string {
	return [...code]
		.map((char) => String.fromCodePoint(char.codePointAt(0)! + REGIONAL_INDICATOR_CODE_POINT_OFFSET))
		.join('');
}

/**
 * Renders an emoji flag for a known ISO-3166-1 alpha-2 jurisdiction code.
 * Falls back to a globe for null, unknown, or malformed codes.
 */
export function jurisdictionFlag(code: string | null | undefined): string {
	if (!code) return GLOBE_FALLBACK;
	const upper = code.trim().toUpperCase();
	if (!ISO_3166_1_ALPHA_2.has(upper)) return GLOBE_FALLBACK;
	return toRegionalIndicatorFlag(upper);
}
