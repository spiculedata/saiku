import type { PageServerLoad } from './$types';
import { ossieSignals, type Signals } from '$lib/server/saiku';
import type { ChartRow } from '$lib/types';

const EMPTY: Signals = {
	stats: { totalFlags: 0, sanctionFlags: 0, highRiskEntities: 0, distinctTopics: 0 },
	topRisk: [],
	flags: [],
	topics: []
};

/** Special-cased category slugs the generic humaniser wouldn't get right. */
const TOPIC_LABELS: Record<string, string> = {
	poi: 'Person of interest',
	'sanction.linked': 'Sanction-linked',
	'export.control': 'Export control',
	'export.risk': 'Export risk'
};

/** Turn a screening-category slug ("export.control", "poi") into a readable label. */
function humaniseTopic(raw: string): string {
	const known = TOPIC_LABELS[raw.toLowerCase()];
	if (known) return known;
	const words = raw.replace(/[._-]/g, ' ').trim();
	return words.charAt(0).toUpperCase() + words.slice(1);
}

export const load: PageServerLoad = async ({ fetch }) => {
	const signals = (await ossieSignals(60, 20, { fetch })) ?? EMPTY;
	const topicRows: ChartRow[] = signals.topics.map((t) => ({
		label: humaniseTopic(t.topic),
		value: t.count
	}));
	return { signals, topicRows };
};
