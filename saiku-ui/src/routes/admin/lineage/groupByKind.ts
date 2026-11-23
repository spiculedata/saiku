import type { LineageDependent } from '$lib/api/admin';

export interface LineageGroup {
	kind: LineageDependent['kind'];
	label: string;
	items: LineageDependent[];
}

const KIND_LABEL: Record<LineageDependent['kind'], string> = {
	dashboard: 'Dashboards',
	'saved-query': 'Saved queries',
	'calc-measure': 'Calculated measures'
};

const KIND_ORDER: LineageDependent['kind'][] = ['dashboard', 'saved-query', 'calc-measure'];

/** Groups lineage results by kind in a fixed display order, dropping empty groups. */
export function groupByKind(results: LineageDependent[]): LineageGroup[] {
	return KIND_ORDER.map((kind) => ({
		kind,
		label: KIND_LABEL[kind],
		items: results.filter((r) => r.kind === kind)
	})).filter((g) => g.items.length > 0);
}
