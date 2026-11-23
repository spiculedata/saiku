/*
 * Wire-shape tests for the vendor-model import client (saiku#1730).
 *
 * The import flow is three calls with a hard ordering — list formats, convert (persists
 * nothing), save (persists) — and the browser is the only place the ordering is enforced.
 * These tests pin the request bodies and the fact that a convert never smuggles a save
 * flag, so a future refactor can't accidentally turn the preview step into a write.
 */
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
	importOssieModel,
	listOssieImportFormats,
	saveOssieImport,
	type OssieImportResult
} from './ossie';

const RESULT: OssieImportResult = {
	formatId: 'lookml',
	modelName: 'Sales',
	yaml: 'version: 0.2.0.dev0\nsemantic_model: []\n',
	validation: {
		datasetCount: 2,
		fieldCount: 5,
		metricCount: 1,
		relationshipCount: 1,
		errorCount: 0,
		warningCount: 1,
		infoCount: 0,
		datasets: [{ name: 'orders', source: 'public.orders', fieldCount: 3, primaryKeyCount: 1 }],
		diagnostics: [
			{
				severity: 'WARNING',
				code: 'DERIVED_TABLE',
				element: 'orders_current',
				message: 'derived_table imports as a plain table reference'
			}
		]
	}
};

function mockFetch(response: unknown, ok = true, status = 200) {
	const spy = vi.fn().mockResolvedValue({
		ok,
		status,
		statusText: status === 200 ? 'OK' : 'Error',
		json: async () => response,
		text: async () => (typeof response === 'string' ? response : JSON.stringify(response))
	});
	vi.stubGlobal('fetch', spy);
	return spy;
}

afterEach(() => {
	vi.unstubAllGlobals();
	vi.restoreAllMocks();
});

describe('listOssieImportFormats', () => {
	test('hits the formats endpoint and returns the picker list', async () => {
		const spy = mockFetch([
			{ id: 'lookml', displayName: 'LookML', description: 'x', fileExtensions: ['.view'] }
		]);
		const formats = await listOssieImportFormats();
		expect(formats[0].id).toBe('lookml');
		expect(spy.mock.calls[0][0]).toBe('/rest/saiku/api/ossie/import/formats');
	});
});

describe('importOssieModel', () => {
	test('posts format + files as JSON and returns the model, YAML and report', async () => {
		const spy = mockFetch(RESULT);
		const result = await importOssieModel({
			format: 'lookml',
			modelName: 'Sales',
			files: [{ name: 'orders.view', content: 'view: orders {}' }]
		});
		expect(result.yaml).toContain('semantic_model');
		expect(result.validation.diagnostics[0].code).toBe('DERIVED_TABLE');

		const [url, init] = spy.mock.calls[0];
		expect(url).toBe('/rest/saiku/api/ossie/import');
		expect(init.method).toBe('POST');
		expect(JSON.parse(init.body)).toEqual({
			format: 'lookml',
			modelName: 'Sales',
			files: [{ name: 'orders.view', content: 'view: orders {}' }]
		});
	});

	test('carries no save flag — the convert step must never persist', async () => {
		const spy = mockFetch(RESULT);
		await importOssieModel({ format: 'lookml', files: [] });
		const body = JSON.parse(spy.mock.calls[0][1].body);
		// modelName is dropped when unset (JSON.stringify drops undefined), which is what
		// lets the server derive one from the upload.
		expect(Object.keys(body).sort()).toEqual(['files', 'format']);
		expect(body.yaml).toBeUndefined();
	});

	test('surfaces the server error text rather than a bare status', async () => {
		mockFetch({ error: "Unknown import format 'tableau'." }, false, 400);
		await expect(importOssieModel({ format: 'tableau', files: [] })).rejects.toThrow(
			"Unknown import format 'tableau'."
		);
	});
});

describe('saveOssieImport', () => {
	test('posts the confirmed YAML and returns the written path', async () => {
		const spy = mockFetch({ path: '/saiku-home/semantic-models/sales.ossie.yaml' });
		const saved = await saveOssieImport({ modelName: 'Sales', yaml: RESULT.yaml, overwrite: true });
		expect(saved.path).toBe('/saiku-home/semantic-models/sales.ossie.yaml');
		const [url, init] = spy.mock.calls[0];
		expect(url).toBe('/rest/saiku/api/ossie/import/save');
		expect(JSON.parse(init.body)).toEqual({
			modelName: 'Sales',
			yaml: RESULT.yaml,
			overwrite: true
		});
	});

	test('defaults overwrite to false so a second import cannot clobber a model silently', async () => {
		const spy = mockFetch({ path: '/x' });
		await saveOssieImport({ modelName: 'Sales', yaml: RESULT.yaml });
		expect(JSON.parse(spy.mock.calls[0][1].body).overwrite).toBe(false);
	});
});
