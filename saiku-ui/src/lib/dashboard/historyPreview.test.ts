import { describe, expect, it } from 'vitest';
import { buildHistoryPreviewUrl, parseHistoryPreviewParams } from './historyPreview';

describe('buildHistoryPreviewUrl', () => {
	it('encodes dashboard + version into the query string', () => {
		expect(buildHistoryPreviewUrl('dashboards/q.saikudash', 'v-1', { base: '/ui' })).toBe(
			'/ui/preview?dashboard=dashboards%2Fq.saikudash&version=v-1'
		);
	});

	it('includes origin when provided, root-relative otherwise', () => {
		expect(buildHistoryPreviewUrl('a.saikudash', 'v1', { origin: 'https://h', base: '/ui' })).toBe(
			'https://h/ui/preview?dashboard=a.saikudash&version=v1'
		);
		expect(buildHistoryPreviewUrl('a.saikudash', 'v1')).toBe(
			'/preview?dashboard=a.saikudash&version=v1'
		);
	});
});

describe('parseHistoryPreviewParams', () => {
	it("round-trips the built URL's query", () => {
		const url = new URL('https://h/ui/preview?dashboard=dashboards%2Fq.saikudash&version=v-1');
		expect(parseHistoryPreviewParams(url.searchParams)).toEqual({
			dashboard: 'dashboards/q.saikudash',
			version: 'v-1'
		});
	});

	it('returns null when a param is missing', () => {
		expect(parseHistoryPreviewParams('dashboard=a.saikudash')).toBeNull();
		expect(parseHistoryPreviewParams('version=v1')).toBeNull();
		expect(parseHistoryPreviewParams('')).toBeNull();
	});
});
