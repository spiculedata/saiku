import type { PageServerLoad } from './$types';
import { ossieQuery } from '$lib/server/saiku';
import { normaliseNationality, jurisdictionName } from '$lib/countries';

/** Pairs pulled from Saiku before folding — the tail past this is negligible. */
const PAIR_LIMIT = 500;
/** How many cross-border corridors to draw in the sankey / list. */
const SANKEY_CORRIDORS = 16;
const LIST_CORRIDORS = 12;

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

export interface Corridor {
	ownerCode: string;
	ownerName: string;
	jurisCode: string;
	jurisName: string;
	count: number;
}
export interface FlowData {
	nodes: { name: string }[];
	links: { source: string; target: string; value: number }[];
}

// A trailing hair-space keeps a jurisdiction node distinct from an owner node of
// the same country name (ECharts sankey requires unique node names) — invisible.
const RIGHT = ' ';

export const load: PageServerLoad = async ({ fetch }) => {
	const result = await ossieQuery(
		{
			rows: [
				{ dataset: 'person', field: 'nationality' },
				{ dataset: 'entity', field: 'jurisdiction' }
			],
			values: [{ metric: 'ownership_count' }],
			sorts: [{ metric: 'ownership_count', direction: 'DESC' }],
			limit: PAIR_LIMIT
		},
		{ fetch }
	);

	// Fold raw nationality×jurisdiction pairs onto canonical owner-country → jurisdiction corridors.
	const folded = new Map<string, Corridor>();
	let crossBorder = 0;
	let domestic = 0;
	for (const rec of result.records) {
		const owner = normaliseNationality(String(rec['person.nationality'] ?? ''));
		const jurisCode = String(rec['entity.jurisdiction'] ?? '').trim();
		if (!owner || jurisCode === '') continue;
		const count = metricValue(rec['ownership_count']);
		if (count <= 0) continue;

		if (owner.code === jurisCode) {
			domestic += count;
			continue;
		}
		crossBorder += count;
		const key = `${owner.code}>${jurisCode}`;
		const existing = folded.get(key);
		if (existing) {
			existing.count += count;
		} else {
			folded.set(key, {
				ownerCode: owner.code,
				ownerName: owner.name,
				jurisCode,
				jurisName: jurisdictionName(jurisCode),
				count
			});
		}
	}

	const corridors = [...folded.values()].sort((a, b) => b.count - a.count);
	const ownerCountries = new Set(corridors.map((c) => c.ownerCode));

	// Sankey from the top corridors: left = owner countries, right = jurisdictions.
	const top = corridors.slice(0, SANKEY_CORRIDORS);
	const nodeNames = new Set<string>();
	for (const c of top) {
		nodeNames.add(c.ownerName);
		nodeNames.add(c.jurisName + RIGHT);
	}
	const flow: FlowData = {
		nodes: [...nodeNames].map((name) => ({ name })),
		links: top.map((c) => ({ source: c.ownerName, target: c.jurisName + RIGHT, value: c.count }))
	};

	const total = crossBorder + domestic;
	return {
		flow,
		corridors: corridors.slice(0, LIST_CORRIDORS),
		stats: {
			crossBorder,
			foreignSharePct: total > 0 ? Math.round((crossBorder / total) * 100) : 0,
			ownerCountries: ownerCountries.size,
			topCorridor: corridors[0] ?? null
		}
	};
};
