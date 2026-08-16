import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ossCubeDesignerBackend } from './oss-backend';
import { parseProfileTables } from './profile-types';

/**
 * The OSS adapter maps CubeDesignerBackend onto Saiku's REST surface and reshapes
 * a few responses to the exact JSON the designer's client code expects. These pin
 * the transforms + URL mapping so a rename can't silently break the wiring.
 */
describe('ossCubeDesignerBackend', () => {
	let fetchMock: ReturnType<typeof vi.fn>;

	beforeEach(() => {
		fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);
	});
	afterEach(() => vi.unstubAllGlobals());

	function jsonOk(body: unknown): Response {
		return new Response(JSON.stringify(body), { status: 200 });
	}

	it('profileConnection introspect → the profile shape parseProfileTables consumes (type→sqlType)', async () => {
		fetchMock.mockResolvedValueOnce(
			jsonOk({
				tables: [
					{
						schema: 'public',
						name: 'customer',
						columns: [
							{ name: 'id', type: 'INTEGER' },
							{ name: 'name', type: 'VARCHAR' }
						]
					}
				]
			})
		);

		const resp = await ossCubeDesignerBackend.profileConnection('conn-1');
		const [url] = fetchMock.mock.calls[0] as [string];
		expect(url).toBe('/rest/saiku/admin/cube-designer/introspect/conn-1');

		// The reshaped body must round-trip through the designer's own parser.
		const tables = parseProfileTables(await resp.text());
		expect(tables).toHaveLength(1);
		expect(tables[0]).toMatchObject({ schema: 'public', name: 'customer' });
		expect(tables[0].columns).toEqual([
			{ name: 'id', sqlType: 'INTEGER' },
			{ name: 'name', sqlType: 'VARCHAR' }
		]);
	});

	it('sample builds the introspect sample URL with encoded table + limit', () => {
		fetchMock.mockResolvedValueOnce(jsonOk({ columns: [], rows: [] }));
		ossCubeDesignerBackend.sample('c1', 'public.sales', 25);
		const [url] = fetchMock.mock.calls[0] as [string];
		expect(url).toBe('/rest/saiku/admin/cube-designer/sample/c1?table=public.sales&limit=25');
	});

	it('convertSchema maps connectionId→dataSourceId and passes {mondrianXml}', async () => {
		fetchMock.mockResolvedValueOnce(jsonOk({ mondrianXml: '<Schema/>' }));
		await ossCubeDesignerBackend.convertSchema({
			mondrianXml: '<M3/>',
			connectionId: 'c1'
		});
		const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/rest/saiku/admin/cube-designer/convert');
		expect(init.method).toBe('POST');
		expect(JSON.parse(init.body as string)).toEqual({
			mondrianXml: '<M3/>',
			dataSourceId: 'c1'
		});
	});

	it('convertSchema wraps a 4xx failure token as { message }', async () => {
		fetchMock.mockResolvedValueOnce(new Response('tables_missing', { status: 422 }));
		const resp = await ossCubeDesignerBackend.convertSchema({
			mondrianXml: '<M3/>',
			connectionId: 'c1'
		});
		expect(resp.status).toBe(422);
		const body = (await resp.json()) as { message: string };
		expect(body.message).toMatch(/does not exist/i);
	});

	it('loadSchema wraps repository XML as { mondrianXml }', async () => {
		fetchMock.mockResolvedValueOnce(new Response('<Schema name="x"/>', { status: 200 }));
		const resp = await ossCubeDesignerBackend.loadSchema('/homes/home:admin/x.xml');
		const [url] = fetchMock.mock.calls[0] as [string];
		expect(url).toContain('/rest/saiku/api/repository/resource?file=');
		expect((await resp.json()) as { mondrianXml: string }).toEqual({
			mondrianXml: '<Schema name="x"/>'
		});
	});

	// saiku#1872: OSS used to answer 501 here. It now runs the UNSAVED schema server-side.
	it('tryQuery refuses locally when there is no schema to run, without calling the server', async () => {
		const resp = await ossCubeDesignerBackend.tryQuery({ connectionId: 'ds', mdx: 'SELECT' });

		expect(resp.status).toBe(400);
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it('tryQuery posts the exported XML and the datasource id', async () => {
		fetchMock.mockResolvedValueOnce(
			new Response(JSON.stringify({ columns: ['a'], rows: [['1']] }), { status: 200 })
		);

		await ossCubeDesignerBackend.tryQuery({
			connectionId: 'ds-1',
			mdx: 'SELECT FROM [Sales]',
			mondrianXml: '<Schema name="S"/>'
		});

		const [url, init] = fetchMock.mock.calls[0];
		expect(String(url)).toContain('/admin/cube-designer/try-query');
		expect(init.method).toBe('POST');
		const sent = JSON.parse(init.body as string);
		expect(sent.mondrianXml).toBe('<Schema name="S"/>');
		// The seam calls it connectionId; the endpoint calls it dataSourceId.
		expect(sent.dataSourceId).toBe('ds-1');
		expect(sent.mdx).toBe('SELECT FROM [Sales]');
	});

	it('tryQuery surfaces the server error message rather than a bare status', async () => {
		fetchMock.mockResolvedValueOnce(
			new Response(JSON.stringify({ error: 'MDX object [Measures].[Nope] not found' }), {
				status: 200
			})
		);

		const resp = await ossCubeDesignerBackend.tryQuery({
			connectionId: 'ds-1',
			mdx: 'SELECT',
			mondrianXml: '<Schema/>'
		});

		// A schema that does not work yet is normal mid-edit: 200 with an `error` the tab renders.
		expect(resp.status).toBe(200);
		expect((await resp.json()).error).toContain('not found');
	});
});
