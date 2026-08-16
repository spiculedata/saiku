/*
 * Unit tests for the pure suggestions-feed model helpers (right-pane).
 *
 * Exercises grouping by op type, stable group ordering, empty-group omission,
 * confidence-tier boundaries, and the human-readable describeOp strings the
 * SuggestionCard renders for each op variant.
 */

import { describe, expect, it } from 'vitest';

import {
	confidenceTier,
	describeOp,
	filterHighConfidence,
	groupOps,
	type FeedSuggestionOp
} from './suggestionsFeed.model';

function renameOp(
	path: string,
	oldCaption: string,
	newCaption: string,
	confidence = 0.9
): FeedSuggestionOp {
	return {
		op: 'rename',
		targetPath: path,
		oldCaption,
		newCaption,
		description: null,
		confidence,
		rationale: 'friendlier caption'
	};
}

function hierarchyOp(path: string, confidence = 0.85): FeedSuggestionOp {
	return {
		op: 'hierarchy',
		targetPath: path,
		hierarchyName: 'Geography',
		levelColumns: ['country', 'state', 'city'],
		confidence,
		rationale: 'country-state-city pattern'
	};
}

function aggregatorOp(
	path: string,
	oldAgg: string,
	newAgg: string,
	confidence = 0.7
): FeedSuggestionOp {
	return {
		op: 'aggregator',
		targetPath: path,
		oldAggregator: oldAgg,
		newAggregator: newAgg,
		confidence,
		rationale: 'amount column typically summed'
	};
}

function degenerateDimOp(path: string, confidence = 0.6): FeedSuggestionOp {
	return {
		op: 'degenerateDim',
		targetPath: path,
		factColumn: 'invoice_no',
		dimName: 'Invoice',
		confidence,
		rationale: 'high-cardinality non-numeric fact column'
	};
}

function ignoreOp(path: string, confidence = 0.4): FeedSuggestionOp {
	return {
		op: 'ignore',
		targetPath: path,
		confidence,
		rationale: 'column appears to be an internal audit field'
	};
}

describe('groupOps', () => {
	it('returns 5 groups for a mixed fixture, empty groups omitted', () => {
		const ops: FeedSuggestionOp[] = [
			renameOp('cubes/Sales/measures/Amount', 'Amount', 'Total Sales'),
			hierarchyOp('cubes/Sales/dimensions/Customer'),
			aggregatorOp('cubes/Sales/measures/Amount', 'AVG', 'SUM'),
			degenerateDimOp('cubes/Sales'),
			ignoreOp('cubes/Sales/measures/_Audit')
		];
		const groups = groupOps(ops);
		expect(groups).toHaveLength(5);
		expect(groups.map((g) => g.type)).toEqual([
			'rename',
			'hierarchy',
			'aggregator',
			'degenerateDim',
			'ignore'
		]);
		// each group has exactly one op in this fixture
		for (const g of groups) expect(g.ops).toHaveLength(1);
	});

	it('omits empty groups and preserves stable order across subsets', () => {
		const ops: FeedSuggestionOp[] = [
			ignoreOp('cubes/Sales/measures/_x'),
			renameOp('cubes/Sales', 'Sales', 'Net Sales')
		];
		const groups = groupOps(ops);
		expect(groups.map((g) => g.type)).toEqual(['rename', 'ignore']);
	});

	it('groups multiple ops of the same type', () => {
		const ops: FeedSuggestionOp[] = [
			renameOp('cubes/A', 'A', 'Alpha'),
			renameOp('cubes/B', 'B', 'Bravo'),
			hierarchyOp('cubes/A/dimensions/Geo')
		];
		const groups = groupOps(ops);
		expect(groups).toHaveLength(2);
		expect(groups[0].type).toBe('rename');
		expect(groups[0].ops).toHaveLength(2);
		expect(groups[1].type).toBe('hierarchy');
	});

	it('returns [] for an empty ops list', () => {
		expect(groupOps([])).toEqual([]);
	});

	it('assigns a non-empty human-readable title to every group', () => {
		const ops: FeedSuggestionOp[] = [
			renameOp('p', 'x', 'y'),
			hierarchyOp('p'),
			aggregatorOp('p', 'AVG', 'SUM'),
			degenerateDimOp('p'),
			ignoreOp('p')
		];
		for (const g of groupOps(ops)) {
			expect(g.title.length).toBeGreaterThan(0);
		}
	});
});

describe('confidenceTier', () => {
	it('maps ≥0.8 to high, ≥0.5 to medium, else low', () => {
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.8))).toBe('high');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.95))).toBe('high');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.5))).toBe('medium');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.79))).toBe('medium');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.49999))).toBe('low');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0))).toBe('low');
	});

	it('handles exact boundary at 0.8 as high and 0.5 as medium', () => {
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.8))).toBe('high');
		expect(confidenceTier(renameOp('p', 'a', 'b', 0.5))).toBe('medium');
	});
});

describe('filterHighConfidence', () => {
	it('returns only ops with tier === high', () => {
		const ops: FeedSuggestionOp[] = [
			renameOp('a', 'x', 'y', 0.9), // high
			renameOp('b', 'x', 'y', 0.6), // medium
			renameOp('c', 'x', 'y', 0.3), // low
			renameOp('d', 'x', 'y', 0.8) // high (boundary)
		];
		const high = filterHighConfidence(ops);
		expect(high.map((o) => o.targetPath)).toEqual(['a', 'd']);
	});
});

describe('describeOp', () => {
	it('renders a rename preview', () => {
		const d = describeOp(renameOp('p', 'Amount', 'Total Sales'));
		expect(d.before).toBe('Amount');
		expect(d.after).toBe('Total Sales');
		expect(d.rationale).toMatch(/caption/i);
	});

	it('renders an aggregator preview', () => {
		const d = describeOp(aggregatorOp('p', 'AVG', 'SUM'));
		expect(d.before).toBe('AVG');
		expect(d.after).toBe('SUM');
	});

	it('renders a hierarchy preview with the level columns', () => {
		const d = describeOp(hierarchyOp('p'));
		expect(d.after).toContain('country');
		expect(d.after).toContain('city');
	});

	it('renders a degenerateDim preview', () => {
		const d = describeOp(degenerateDimOp('p'));
		expect(d.after).toContain('Invoice');
		expect(d.before).toContain('invoice_no');
	});

	it('renders an ignore preview', () => {
		const d = describeOp(ignoreOp('p'));
		expect(d.after.toLowerCase()).toContain('drop');
	});

	it('always returns non-empty strings', () => {
		const all: FeedSuggestionOp[] = [
			renameOp('p', 'a', 'b'),
			hierarchyOp('p'),
			aggregatorOp('p', 'AVG', 'SUM'),
			degenerateDimOp('p'),
			ignoreOp('p')
		];
		for (const op of all) {
			const d = describeOp(op);
			expect(d.before.length).toBeGreaterThan(0);
			expect(d.after.length).toBeGreaterThan(0);
			expect(d.rationale).toBe(op.rationale);
		}
	});
});
