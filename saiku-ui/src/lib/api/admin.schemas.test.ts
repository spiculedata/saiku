/*
 * Regression tests for the schema upload wire contract (saiku#1655).
 *
 * The bug: adminSchemas.upload() posted application/x-www-form-urlencoded
 * fields (`name` + `xml`), but the server's AdminResource `POST /admin/schema/{id}`
 * is @Consumes("multipart/form-data") reading @FormDataParam("file") (InputStream)
 * + @FormDataParam("name"). Jersey rejected every Cube Designer save and
 * Admin > Schemas upload with HTTP 415 at the media-type gate. These tests lock
 * the multipart shape so the body always carries a `file` part with the XML
 * payload plus a `name` field, with no hand-set Content-Type header (the browser
 * must generate the multipart boundary itself).
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { adminSchemas } from './admin';

const XML = '<?xml version="1.0"?><Schema name="TestSchema"><Cube name="C"/></Schema>';

describe('adminSchemas.upload', () => {
	let originalFetch: typeof globalThis.fetch;

	beforeEach(() => {
		originalFetch = globalThis.fetch;
	});
	afterEach(() => {
		globalThis.fetch = originalFetch;
	});

	test('POSTs multipart FormData with a `file` part carrying the XML and a `name` field', async () => {
		const fetchMock = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }));
		globalThis.fetch = fetchMock;

		await adminSchemas.upload('TestSchema', XML);

		expect(fetchMock).toHaveBeenCalledTimes(1);
		const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/rest/saiku/admin/schema/TestSchema');
		expect(init.method).toBe('POST');
		expect(init.credentials).toBe('include');

		const body = init.body;
		expect(body).toBeInstanceOf(FormData);
		const form = body as FormData;

		// The `file` part must be a Blob/File whose bytes are the schema XML.
		const file = form.get('file');
		expect(file).toBeInstanceOf(Blob);
		expect(await (file as Blob).text()).toBe(XML);
		expect((file as File).name).toBe('TestSchema.xml');

		// AdminResource builds `/datasources/{name}.xml` from the `name` field.
		expect(form.get('name')).toBe('TestSchema');
	});

	test('does not hand-set a Content-Type header (browser must own the multipart boundary)', async () => {
		const fetchMock = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }));
		globalThis.fetch = fetchMock;

		await adminSchemas.upload('TestSchema', XML);

		const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
		const headers = new Headers(init.headers ?? {});
		expect(headers.get('Content-Type')).toBeNull();
	});

	test('URL-encodes the schema name in the path', async () => {
		const fetchMock = vi.fn().mockResolvedValue(new Response('[]', { status: 200 }));
		globalThis.fetch = fetchMock;

		await adminSchemas.upload('My Schema', XML);

		const [url] = fetchMock.mock.calls[0] as [string];
		expect(url).toBe('/rest/saiku/admin/schema/My%20Schema');
	});

	test('throws with the status when the server rejects the upload', async () => {
		globalThis.fetch = vi.fn().mockResolvedValue(new Response('nope', { status: 415 }));
		await expect(adminSchemas.upload('TestSchema', XML)).rejects.toThrow('schema upload -> 415');
	});
});
