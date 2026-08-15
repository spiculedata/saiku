/*
 * Pure helpers that back the schema-generator right-pane suggestions feed.
 *
 * The components (SuggestionsFeed.svelte / SuggestionCard.svelte) stay thin;
 * grouping, confidence bucketing, and the "before → after" preview strings
 * live here so they can be unit-tested without a browser.
 *
 * Op types are reused directly from `$lib/api/schemaGen` — the client is now
 * the single source of truth for the backend SuggestionOp contract. We keep
 * `FeedSuggestionOp` / `FeedSuggestionView` aliases so downstream imports
 * don't have to change, and so we retain a handle to rename later if the feed
 * ever diverges from the transport shape.
 */

import type { SuggestionOp, SuggestionView } from '$lib/api/schemaGen';

/** Discriminator values for the five op variants. Stable group ordering follows this list. */
export type OpType = 'rename' | 'hierarchy' | 'aggregator' | 'degenerateDim' | 'ignore';

export type FeedSuggestionOp = SuggestionOp;
export type FeedSuggestionView = SuggestionView;

export type ConfidenceTier = 'high' | 'medium' | 'low';

export interface OpGroup {
	type: OpType;
	title: string;
	ops: FeedSuggestionOp[];
}

/**
 * Stable group ordering — rename first (highest-value, lowest-risk), then
 * hierarchy, aggregator, degenerate-dim, and finally ignore (destructive, last).
 */
const GROUP_ORDER: readonly OpType[] = [
	'rename',
	'hierarchy',
	'aggregator',
	'degenerateDim',
	'ignore'
];

const GROUP_TITLES: Record<OpType, string> = {
	rename: 'Renames',
	hierarchy: 'Hierarchies',
	aggregator: 'Aggregators',
	degenerateDim: 'Degenerate dimensions',
	ignore: 'Ignore'
};

/**
 * Group a flat op list by discriminator. Empty groups are omitted; non-empty
 * groups appear in {@link GROUP_ORDER} order regardless of input sequence.
 */
export function groupOps(ops: FeedSuggestionOp[]): OpGroup[] {
	const buckets = new Map<OpType, FeedSuggestionOp[]>();
	for (const op of ops) {
		const list = buckets.get(op.op) ?? [];
		list.push(op);
		buckets.set(op.op, list);
	}
	const out: OpGroup[] = [];
	for (const type of GROUP_ORDER) {
		const list = buckets.get(type);
		if (list && list.length > 0) {
			out.push({ type, title: GROUP_TITLES[type], ops: list });
		}
	}
	return out;
}

/**
 * Bucket an op's confidence into {@code high} (≥0.8), {@code medium} (≥0.5),
 * or {@code low}. Boundary values land in the higher bucket.
 */
export function confidenceTier(op: FeedSuggestionOp): ConfidenceTier {
	const c = op.confidence;
	if (c >= 0.8) return 'high';
	if (c >= 0.5) return 'medium';
	return 'low';
}

/** Convenience wrapper for bulk-accept: returns only the high-confidence ops. */
export function filterHighConfidence(ops: FeedSuggestionOp[]): FeedSuggestionOp[] {
	return ops.filter((o) => confidenceTier(o) === 'high');
}

/**
 * Produce human-readable {@code before / after / rationale} strings for a
 * suggestion card. Every variant returns non-empty {@code before} and
 * {@code after} fields.
 */
export function describeOp(op: FeedSuggestionOp): {
	before: string;
	after: string;
	rationale: string;
} {
	switch (op.op) {
		case 'rename':
			return {
				before: op.oldCaption,
				after: op.newCaption,
				rationale: op.rationale
			};
		case 'aggregator':
			return {
				before: op.oldAggregator,
				after: op.newAggregator,
				rationale: op.rationale
			};
		case 'hierarchy':
			return {
				before: '(no hierarchy)',
				after: `${op.hierarchyName}: ${op.levelColumns.join(' › ')}`,
				rationale: op.rationale
			};
		case 'degenerateDim':
			return {
				before: op.factColumn,
				after: `${op.dimName} (degenerate dim)`,
				rationale: op.rationale
			};
		case 'ignore':
			return {
				before: op.targetPath,
				after: 'drop from schema',
				rationale: op.rationale
			};
	}
}
