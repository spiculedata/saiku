import { describe, expect, it } from 'vitest';
import { groupByKind } from './groupByKind';
import type { LineageDependent } from '$lib/api/admin';

function dep(kind: LineageDependent['kind'], name: string): LineageDependent {
	return { kind, name, path: `/x/${name}`, lastModified: 0 };
}

describe('groupByKind', () => {
	it('groups by kind in dashboard, saved-query, calc-measure order', () => {
		const groups = groupByKind([
			dep('calc-measure', 'Profit'),
			dep('saved-query', 'q1'),
			dep('dashboard', 'd1'),
			dep('dashboard', 'd2')
		]);

		expect(groups.map((g) => g.kind)).toEqual(['dashboard', 'saved-query', 'calc-measure']);
		expect(groups[0].items).toHaveLength(2);
		expect(groups[1].items).toHaveLength(1);
		expect(groups[2].items).toHaveLength(1);
	});

	it('drops empty groups', () => {
		const groups = groupByKind([dep('dashboard', 'd1')]);
		expect(groups).toHaveLength(1);
		expect(groups[0].kind).toBe('dashboard');
	});

	it('returns no groups for an empty result set', () => {
		expect(groupByKind([])).toEqual([]);
	});
});
