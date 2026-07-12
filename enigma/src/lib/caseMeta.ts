/** Display metadata for case lifecycle states — shared by the list + detail views. */
import type { CaseStatus, CasePriority } from '$lib/caseTypes';

export interface Meta {
	label: string;
	color: string;
}

export const STATUS_META: Record<CaseStatus, Meta> = {
	open: { label: 'Open', color: '#57d6e6' },
	in_review: { label: 'In review', color: '#f5b544' },
	escalated: { label: 'Escalated', color: '#ff5d6c' },
	closed: { label: 'Closed', color: '#5fe0a0' }
};

export const PRIORITY_META: Record<CasePriority, Meta> = {
	low: { label: 'Low', color: '#8b90a3' },
	normal: { label: 'Normal', color: '#c8ccda' },
	high: { label: 'High', color: '#ff5d6c' }
};

export const STATUS_ORDER: CaseStatus[] = ['open', 'in_review', 'escalated', 'closed'];

/** Human label + icon for an activity-timeline entry kind. */
export const ACTIVITY_META: Record<string, { label: string; icon: string }> = {
	created: { label: 'Case opened', icon: '◆' },
	status: { label: 'Status changed', icon: '⇄' },
	priority: { label: 'Priority changed', icon: '▲' },
	assigned: { label: 'Assignment', icon: '☺' },
	note: { label: 'Note', icon: '✎' },
	reopened: { label: 'Reopened', icon: '↺' }
};

export function activityMeta(kind: string): { label: string; icon: string } {
	return ACTIVITY_META[kind] ?? { label: kind, icon: '•' };
}
