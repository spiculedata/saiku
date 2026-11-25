/*
 * Wire-contract tests for the raw Ossie YAML read/write client (#1428 Model IDE's Ossie
 * slice). Locks the details the IDE depends on:
 *  - GET returns the body as TEXT (`OssieSchemaResource` produces text/plain, not JSON);
 *  - GET 404 is "no schema saved yet" and maps to null, not an error;
 *  - PUT sends the raw document as text/plain (the server `@Consumes(TEXT_PLAIN)` — the
 *    same 415 trap saiku#1655 hit on the Mondrian multipart upload);
 *  - a 400 rejection surfaces the server's `error` message, not the bare status.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { getOssieSchemaYaml, saveOssieSchemaYaml } from './adminOssieSchema';

const YAML = 'semantic_model:\n- name: Flights\n  datasets: []\n';

describe('getOssieSchemaYaml', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
	});

	test('GETs the datasource YAML as text at the admin ossie-schema path', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValue(
				new Response(YAML, { status: 200, headers: { 'content-type': 'text/plain' } })
			);
		globalThis.fetch = fetchMock;

		const out = await getOssieSchemaYaml('flights');

		expect(out).toBe(YAML);
		const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/rest/saiku/admin/ossie/schema/flights');
		expect(init.method).toBeUndefined();
		expect(init.credentials).toBe('include');
	});

	test('maps 404 (no schema saved yet) to null instead of throwing', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('not found', { status: 404 }));

		await expect(getOssieSchemaYaml('flights')).resolves.toBeNull();
	});

	test('other failures throw', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('denied', { status: 403 }));

		await expect(getOssieSchemaYaml('flights')).rejects.toThrow('ossie schema fetch -> 403');
	});
});

describe('saveOssieSchemaYaml', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
	});

	test('PUTs the raw document as text/plain with no JSON wrapping', async () => {
		const fetchMock = vi
			.fn()
			.mockResolvedValue(new Response(JSON.stringify({ status: 'OK' }), { status: 200 }));
		globalThis.fetch = fetchMock;

		await saveOssieSchemaYaml('flights', YAML);

		const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/rest/saiku/admin/ossie/schema/flights');
		expect(init.method).toBe('PUT');
		expect(init.credentials).toBe('include');
		const headers = init.headers as Record<string, string>;
		expect(headers['Content-Type']).toBe('text/plain');
		expect(init.body).toBe(YAML);
	});

	test('surfaces the server validation message on a 400 rejection', async () => {
		globalThis.fetch = vi
			.fn()
			.mockResolvedValue(
				new Response(
					JSON.stringify({ status: 'VALIDATION_ERROR', error: 'invalid Ossie YAML: boom' }),
					{ status: 400 }
				)
			);

		await expect(saveOssieSchemaYaml('flights', 'broken: [')).rejects.toThrow(
			'Save rejected: invalid Ossie YAML: boom'
		);
	});

	test('falls back to the bare status when the error body is not JSON', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('oops', { status: 500 }));

		await expect(saveOssieSchemaYaml('flights', YAML)).rejects.toThrow('Save rejected: 500');
	});
});
