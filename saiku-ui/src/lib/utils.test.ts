import { describe, expect, it } from 'vitest';
import { mdxSegment } from './utils';

/**
 * saiku#1286 — MDX bracket-segment escaping. A TimeCalc (or any member) name
 * containing `]` must produce a well-formed `[...]` segment via the MDX `]]`
 * escape, not a malformed reference the server silently drops.
 */
describe('mdxSegment', () => {
	it('wraps a plain name', () => {
		expect(mdxSegment('YTD Sales')).toBe('[YTD Sales]');
	});

	it('escapes an embedded closing bracket', () => {
		expect(mdxSegment('Growth [%] YoY')).toBe('[Growth [%]] YoY]');
	});

	it('escapes multiple closing brackets', () => {
		expect(mdxSegment('a]b]c')).toBe('[a]]b]]c]');
	});

	it('leaves opening brackets alone (only ] terminates a segment in MDX)', () => {
		expect(mdxSegment('[weird')).toBe('[[weird]');
	});

	it('handles the empty string', () => {
		expect(mdxSegment('')).toBe('[]');
	});
});
