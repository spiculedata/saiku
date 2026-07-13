/**
 * Case lifecycle vocabulary — client-safe (no server imports), so both the
 * server store and the browser components can share it.
 */
export const CASE_STATUSES = ['open', 'in_review', 'escalated', 'closed'] as const;
export type CaseStatus = (typeof CASE_STATUSES)[number];

export const CASE_PRIORITIES = ['low', 'normal', 'high'] as const;
export type CasePriority = (typeof CASE_PRIORITIES)[number];
