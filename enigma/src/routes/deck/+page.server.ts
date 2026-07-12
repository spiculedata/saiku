import type { PageServerLoad } from './$types';
import { ossieQuery } from '$lib/server/saiku';
import type { ChartRow } from '$lib/types';

const INTEREST_TYPE_LABEL_MAX_LENGTH = 34;
const TRUNCATION_ELLIPSIS = '…';

/** A metric cell as returned by the Ossie aggregate query — `{ value, formatted }`. */
interface MetricCell {
	value?: number;
}

function metricValue(cell: unknown): number {
	if (cell != null && typeof cell === 'object' && 'value' in cell) {
		const v = (cell as MetricCell).value;
		return typeof v === 'number' ? v : 0;
	}
	return 0;
}

/** Maps aggregate query records into `{ label, value }[]`, optionally humanising the label. */
function rows(
	records: Record<string, unknown>[],
	dimKey: string,
	metricKey: string,
	humaniseLabel?: (raw: string) => string
): ChartRow[] {
	return records.map((r) => {
		const raw = String(r[dimKey] ?? '—');
		return {
			label: humaniseLabel ? humaniseLabel(raw) : raw,
			value: metricValue(r[metricKey])
		};
	});
}

/**
 * Interest-type slugs from the semantic model are long and hyphenated
 * (e.g. "ownership-of-shares-75-to-100-percent"). Humanise for display:
 * dashes → spaces, collapse whitespace, truncate to a card-friendly width.
 */
function humaniseInterestType(raw: string): string {
	const spaced = raw.replace(/-/g, ' ').replace(/\s+/g, ' ').trim();
	if (spaced.length <= INTEREST_TYPE_LABEL_MAX_LENGTH) return spaced;
	return spaced.slice(0, INTEREST_TYPE_LABEL_MAX_LENGTH - TRUNCATION_ELLIPSIS.length).trimEnd() + TRUNCATION_ELLIPSIS;
}

export const load: PageServerLoad = async ({ fetch }) => {
	const [jurisdictionResult, statusResult, interestTypeResult] = await Promise.all([
		ossieQuery(
			{
				rows: [{ dataset: 'entity', field: 'jurisdiction' }],
				values: [{ metric: 'ownership_count' }],
				sorts: [{ metric: 'ownership_count', direction: 'DESC' }],
				limit: 10
			},
			{ fetch }
		),
		ossieQuery(
			{
				rows: [{ dataset: 'entity', field: 'status' }],
				values: [{ metric: 'company_count' }],
				sorts: [{ metric: 'company_count', direction: 'DESC' }],
				limit: 8
			},
			{ fetch }
		),
		ossieQuery(
			{
				rows: [{ dataset: 'ownership_statement', field: 'interest_type' }],
				values: [{ metric: 'ownership_count' }],
				sorts: [{ metric: 'ownership_count', direction: 'DESC' }],
				limit: 10
			},
			{ fetch }
		)
	]);

	return {
		jurisdiction: rows(jurisdictionResult.records, 'entity.jurisdiction', 'ownership_count'),
		status: rows(statusResult.records, 'entity.status', 'company_count'),
		interestType: rows(
			interestTypeResult.records,
			'ownership_statement.interest_type',
			'ownership_count',
			humaniseInterestType
		)
	};
};
