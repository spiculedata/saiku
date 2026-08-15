/*
 * Unit tests for the drillthrough CSV export URL builder (issue #1051).
 * Pure string construction — no network, no DOM.
 */
import { describe, expect, test } from 'vitest';
import { aiDrillthroughCsvUrl } from './aiQuery';

describe('aiDrillthroughCsvUrl', () => {
	test('no options -> bare export path', () => {
		expect(aiDrillthroughCsvUrl('q-123')).toBe(
			'/rest/saiku/api/ai/query/q-123/drillthrough/export/csv'
		);
	});

	test('encodes the queryId', () => {
		expect(aiDrillthroughCsvUrl('a b/c')).toBe(
			'/rest/saiku/api/ai/query/a%20b%2Fc/drillthrough/export/csv'
		);
	});

	test('serialises position, maxRows and returns', () => {
		const url = aiDrillthroughCsvUrl('q1', {
			position: '2:1',
			maxRows: 10000,
			returns: ['[Time].[Year]', '[Measures].[Unit Sales]']
		});
		const qs = new URLSearchParams(url.split('?')[1]);
		expect(url.startsWith('/rest/saiku/api/ai/query/q1/drillthrough/export/csv?')).toBe(true);
		expect(qs.get('position')).toBe('2:1');
		expect(qs.get('maxrows')).toBe('10000');
		expect(qs.get('returns')).toBe('[Time].[Year],[Measures].[Unit Sales]');
	});

	test('omits empty returns array', () => {
		const url = aiDrillthroughCsvUrl('q1', { returns: [] });
		expect(url).toBe('/rest/saiku/api/ai/query/q1/drillthrough/export/csv');
	});

	test('maxRows=0 is still serialised (not treated as absent)', () => {
		const url = aiDrillthroughCsvUrl('q1', { maxRows: 0 });
		expect(new URLSearchParams(url.split('?')[1]).get('maxrows')).toBe('0');
	});
});
