import { describe, it, expect, vi } from 'vitest';
import { searchEntities, getEntity } from '../src/lib/server/benafide';

function mockFetch(json: unknown, ok = true) {
	return vi.fn(async () => ({ ok, status: ok ? 200 : 500, json: async () => json })) as unknown as typeof fetch;
}

describe('searchEntities', () => {
	it('maps the /v1/entities response to SearchResult[]', async () => {
		const f = mockFetch([{ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' }]);
		const out = await searchEntities('acme', { fetch: f, base: 'http://x' });
		expect(out).toEqual([{ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' }]);
		expect(f).toHaveBeenCalledWith(expect.stringContaining('/v1/entities?'), expect.anything());
	});
	it('returns [] on a non-ok response instead of throwing', async () => {
		const out = await searchEntities('x', { fetch: mockFetch({}, false), base: 'http://x' });
		expect(out).toEqual([]);
	});
});

describe('getEntity', () => {
	it('returns the entity JSON', async () => {
		const f = mockFetch({ id: 'CH-1', name: 'ACME LTD', jurisdiction: 'GB', status: 'active' });
		const e = await getEntity('CH-1', { fetch: f, base: 'http://x' });
		expect(e?.name).toBe('ACME LTD');
	});
});
