import { describe, expect, test } from 'vitest';
import { diffCubeNames } from './cubeDiff';

describe('diffCubeNames', () => {
	test('no change', () => {
		expect(diffCubeNames(['A', 'B'], ['A', 'B'])).toEqual({
			added: [],
			removed: [],
			unchanged: ['A', 'B']
		});
	});

	test('a new cube added', () => {
		expect(diffCubeNames(['A'], ['A', 'B'])).toEqual({
			added: ['B'],
			removed: [],
			unchanged: ['A']
		});
	});

	test('a cube removed', () => {
		expect(diffCubeNames(['A', 'B'], ['A'])).toEqual({
			added: [],
			removed: ['B'],
			unchanged: ['A']
		});
	});

	test('empty before (first save)', () => {
		expect(diffCubeNames([], ['A'])).toEqual({ added: ['A'], removed: [], unchanged: [] });
	});

	test('everything removed', () => {
		expect(diffCubeNames(['A', 'B'], [])).toEqual({
			added: [],
			removed: ['A', 'B'],
			unchanged: []
		});
	});
});
