import { describe, it, expect, vi } from 'vitest';
import { ossieQuery } from '../src/lib/server/saiku';

describe('ossieQuery', () => {
	it('POSTs a shelf-state body with basic auth and returns records', async () => {
		const f = vi.fn(async () => ({
			ok: true,
			status: 200,
			json: async () => ({ records: [{ 'entity.jurisdiction': 'GB', ownership_count: { value: 20555155, formatted: '20555155' } }] })
		})) as unknown as typeof fetch;
		const out = await ossieQuery(
			{ rows: [{ dataset: 'entity', field: 'jurisdiction' }], values: [{ metric: 'ownership_count' }] },
			{ fetch: f, base: 'http://x', user: 'admin', pass: 'admin' }
		);
		expect(out.records[0]['entity.jurisdiction']).toBe('GB');
		const call = (f as any).mock.calls[0];
		expect(call[0]).toContain('/rest/saiku/api/ai/ossie/query');
		expect(call[1].method).toBe('POST');
		expect(call[1].headers.authorization).toMatch(/^Basic /);
	});
});
