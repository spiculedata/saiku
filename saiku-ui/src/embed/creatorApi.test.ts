/*
 * saiku#1435 — the Creator Mode client.
 *
 * The server is the security boundary (AuthoringQueryValidator refuses anything
 * outside the pinned cube's catalogue), so the client's job is narrower but
 * still load-bearing: emit a query document that is TYPED ONLY. If
 * buildCreatorQuery ever grew an mdx / filter / parameter field, the server
 * would start rejecting the visitor's own query — so those are asserted absent
 * here rather than left to review.
 */
import { describe, expect, it, vi, afterEach } from 'vitest';
import {
	buildCreatorQuery,
	creatorErrorMessage,
	deleteAuthoringObject,
	fetchAuthoringContext,
	fetchAuthoringObjects,
	previewAuthoringQuery,
	saveAuthoringDashboard,
	saveAuthoringQuery
} from './creatorApi';
import { EmbedFetchError } from './api';

const CUBE = 'foodmart/foodmart/foodmart/sales';

function mockFetch(body: unknown, status = 200) {
	const fn = vi.fn(async (_url: string, _init?: RequestInit) => ({
		ok: status >= 200 && status < 300,
		status,
		json: async () => body
	}));
	vi.stubGlobal('fetch', fn);
	return fn;
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('buildCreatorQuery', () => {
	it('emits one rows hierarchy per picked level, keyed by level unique name', () => {
		const q = buildCreatorQuery({
			rows: [{ level: '[Store].[Store]', members: ['[Store].[USA]'] }],
			measures: ['[Measures].[Unit Sales]']
		}) as any;
		const rows = q.queryModel.axes.ROWS.hierarchies;
		expect(rows).toHaveLength(1);
		expect(rows[0].name).toBe('[Store].[Store]');
		expect(rows[0].levels['[Store].[Store]'].selection.members[0].uniqueName).toBe('[Store].[USA]');
	});

	it('treats an empty member list as "every member"', () => {
		const q = buildCreatorQuery({
			rows: [{ level: '[Store].[Store]', members: [] }],
			measures: []
		}) as any;
		const sel = q.queryModel.axes.ROWS.hierarchies[0].levels['[Store].[Store]'].selection;
		expect(sel.members).toEqual([]);
		expect(sel.type).toBe('INCLUSION');
	});

	it('carries the measures into both the columns axis and the details block', () => {
		const q = buildCreatorQuery({
			rows: [],
			measures: ['[Measures].[Unit Sales]', '[Measures].[Store Cost]']
		}) as any;
		expect(q.queryModel.details.measures.map((m: any) => m.uniqueName)).toEqual([
			'[Measures].[Unit Sales]',
			'[Measures].[Store Cost]'
		]);
		expect(Object.keys(q.queryModel.axes.COLUMNS.hierarchies[0].levels)).toHaveLength(2);
	});

	it('omits the measures axis entirely when nothing is selected', () => {
		// An empty "[Measures]" hierarchy would be refused server-side, and the
		// resulting error message would be about pivoting, not about measures.
		const q = buildCreatorQuery({ rows: [], measures: [] }) as any;
		expect(q.queryModel.axes.COLUMNS.hierarchies).toEqual([]);
		expect(q.queryModel.details.measures).toEqual([]);
	});

	it('never emits an injection channel', () => {
		const q = buildCreatorQuery({
			rows: [{ level: '[Store].[Store]', members: ['[Store].[USA]'] }],
			measures: ['[Measures].[Unit Sales]']
		}) as any;
		expect(q.type).toBe('QUERYMODEL');
		expect(q.mdx).toBeUndefined();
		expect(q.parameters).toEqual({});
		expect(q.typedParameters).toEqual([]);
		const model = q.queryModel;
		expect(model.calculatedMembers ?? []).toEqual([]);
		expect(model.calculatedMeasures ?? []).toEqual([]);
		expect(model.namedSets ?? []).toEqual([]);
		for (const axis of Object.values(model.axes) as any[]) {
			expect(axis.mdx).toBeUndefined();
			expect(axis.filters ?? []).toEqual([]);
			expect(axis.sortEvaluationLiteral ?? null).toBeNull();
		}
	});
});

describe('creator requests', () => {
	it('sends the token as a header, never as a query param', async () => {
		const fn = mockFetch({ tenantId: 'acme' });
		await fetchAuthoringContext('', CUBE, 'tok-123');
		const [url, init] = fn.mock.calls[0];
		expect(url).toContain(
			'/rest/saiku/api/embed/authoring/foodmart/foodmart/foodmart/sales/context'
		);
		expect(url).not.toContain('tok-123');
		expect((init!.headers as Record<string, string>)['X-Saiku-Embed-Token']).toBe('tok-123');
		expect(init!.credentials).toBe('omit');
	});

	it('never sends a repository path on a save', async () => {
		const fn = mockFetch({ path: '/homes/admin/embed-guest-acme/Report.saiku' });
		await saveAuthoringQuery(
			'',
			CUBE,
			'Report',
			{ rows: [], measures: ['[Measures].[Unit Sales]'] },
			'tok'
		);
		const [url, init] = fn.mock.calls[0];
		expect(url).toContain('/query');
		expect(url).not.toContain('homes');
		// The name travels in the body; the server derives the folder.
		const body = JSON.parse(init!.body as string);
		expect(body.name).toBe('Report');
		expect(body).not.toHaveProperty('path');
	});

	it('references the saved query from the dashboard tile', async () => {
		const fn = mockFetch({ path: '/homes/admin/embed-guest-acme/Report.saikudash' });
		await saveAuthoringDashboard(
			'',
			CUBE,
			'Report',
			{ rows: [], measures: ['[Measures].[Unit Sales]'] },
			'/homes/admin/embed-guest-acme/Report (query).saiku',
			'bar',
			'tok'
		);
		const [, init] = fn.mock.calls[0];
		const body = JSON.parse(init!.body as string);
		expect(body.dashboard.layout.tiles[0].query).toEqual({
			kind: 'reference',
			path: '/homes/admin/embed-guest-acme/Report (query).saiku'
		});
	});

	it('passes the requested preview format through', async () => {
		const fn = mockFetch({ format: 'matrix' });
		await previewAuthoringQuery(
			'',
			CUBE,
			{ rows: [], measures: ['[Measures].[Unit Sales]'] },
			'tok',
			'matrix'
		);
		expect(fn.mock.calls[0][0]).toContain('?format=matrix');
	});

	it('deletes by name, never by path', async () => {
		const fn = mockFetch({});
		await deleteAuthoringObject('', CUBE, 'Report.saiku', 'tok');
		const [url, init] = fn.mock.calls[0];
		expect(init!.method).toBe('DELETE');
		expect(url).toContain('name=Report.saiku');
		expect(url).not.toContain('homes');
	});

	it('lists the tenant folder', async () => {
		const fn = mockFetch({
			tenantId: 'acme',
			scopePath: '/homes/admin/embed-guest-acme',
			objects: []
		});
		const out = await fetchAuthoringObjects('', CUBE, 'tok');
		expect(out.tenantId).toBe('acme');
		expect(fn.mock.calls[0][0]).toContain('/objects');
	});

	it('requires a cube reference', async () => {
		mockFetch({});
		await expect(fetchAuthoringContext('', '', 'tok')).rejects.toThrow(/cube reference/i);
	});
});

describe('creatorErrorMessage', () => {
	it('keeps a bad link indistinguishable from any other auth failure', () => {
		const e = new EmbedFetchError(401, {
			status: 'EMBED_INVALID',
			error: 'Embed link is invalid.'
		});
		expect(creatorErrorMessage(e)).toBe('This analytics surface is unavailable.');
	});

	it('surfaces a 400 message, which is about the request not the link', () => {
		const e = new EmbedFetchError(400, {
			status: 'VALIDATION_ERROR',
			error: 'at least one measure is required'
		});
		expect(creatorErrorMessage(e)).toBe('at least one measure is required');
	});

	it('falls back for an unknown failure', () => {
		expect(creatorErrorMessage(new Error('boom'))).toBe('Request failed.');
	});
});
