/**
 * OSS Saiku host implementation of the cube-designer backend seam.
 *
 * Maps each {@link CubeDesignerBackend} method onto Saiku's own REST surface —
 * the three dedicated endpoints added for the designer
 * (`/rest/saiku/admin/cube-designer/*`) plus the existing repository resource.
 * Cookie-based auth (`credentials: "include"`), same as every other saiku-ui
 * api client.
 *
 * A few responses are reshaped to the exact JSON the designer's client code
 * expects (it was written against the Cloud gateway's shapes):
 *  - `profileConnection`: introspect returns `columns[].type`; the designer's
 *    `parseProfileTables` wants `columns[].sqlType`.
 *  - `loadSchema`: the repository returns raw XML; the importer wants
 *    `{ mondrianXml }`.
 *  - `convertSchema`: the designer sends `{ mondrianXml, connectionId }`; the
 *    OSS endpoint wants `{ mondrianXml, dataSourceId }`, and returns a plain
 *    failure token on 4xx which we wrap as `{ message }`.
 *  - `tryQuery`: OSS has no run-against-an-unsaved-proposal endpoint yet, so the
 *    Try-a-query tab is unavailable in this build (graceful 501).
 */
import type { CubeDesignerBackend, CubeDesignerAI } from './backend';

const REST_BASE = '/rest/saiku';
const BASE = `${REST_BASE}/admin/cube-designer`;
const CREDS: RequestInit = { credentials: 'include' };
const JSON_HEADERS = { 'Content-Type': 'application/json' } as const;

function jsonResponse(body: unknown, status = 200): Response {
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'Content-Type': 'application/json' }
	});
}

/** Friendly text for the convert endpoint's typed failure tokens. */
function convertMessage(token: string): string {
	switch (token.trim()) {
		case 'tables_missing':
			return 'The schema references a table that does not exist in the warehouse.';
		case 'connection_failed':
			return 'Could not connect to the warehouse to upgrade the schema.';
		case 'not_upgradable':
			return 'The schema could not be upgraded to Mondrian 4.';
		default:
			return 'The schema could not be converted.';
	}
}

type IntrospectResponse = {
	tables: Array<{
		schema: string | null;
		name: string;
		columns: Array<{ name: string; type: string | null }>;
	}>;
};

/**
 * OSS edit mode (saiku#1634): fetch a datasource's already-attached Mondrian schema XML so the host
 * can hydrate the canvas instead of opening blank. Returns the raw Response — 200 `{ mondrianXml,
 * label }` when a schema is attached and resolvable, 404 otherwise (⇒ new-cube target, stay blank).
 *
 * Deliberately NOT part of the shared {@link CubeDesignerBackend} seam: Cloud has its own
 * library-based edit flow (`ImportController`), so this stays OSS host glue.
 */
export function fetchDatasourceSchema(dataSourceId: string): Promise<Response> {
	return fetch(`${BASE}/schema/${encodeURIComponent(dataSourceId)}`, CREDS);
}

export const ossCubeDesignerBackend: CubeDesignerBackend = {
	async profileConnection(connectionId) {
		const r = await fetch(`${BASE}/introspect/${encodeURIComponent(connectionId)}`, CREDS);
		if (!r.ok) return r; // let the caller surface the HTTP error
		const data = (await r.json()) as IntrospectResponse;
		// Reshape to the `{ tables: [{ schema, name, columns: [{ name, sqlType }] }] }`
		// envelope parseProfileTables consumes (column `type` → `sqlType`).
		const tables = (data.tables ?? []).map((t) => ({
			schema: t.schema,
			name: t.name,
			columns: (t.columns ?? []).map((c) => ({
				name: c.name,
				sqlType: c.type ?? 'unknown'
			}))
		}));
		return jsonResponse({ tables });
	},

	sample(connectionId, table, limit) {
		const url =
			`${BASE}/sample/${encodeURIComponent(connectionId)}` +
			`?table=${encodeURIComponent(table)}&limit=${limit}`;
		return fetch(url, CREDS);
	},

	tryQuery() {
		// No OSS endpoint runs a query against an unsaved proposal (Cloud does this
		// gateway-side). Save the schema, then query it in Studio.
		return Promise.resolve(
			jsonResponse({ message: 'Query preview is not available in this build.' }, 501)
		);
	},

	async loadSchema(entryId) {
		const r = await fetch(
			`${REST_BASE}/api/repository/resource?file=${encodeURIComponent(entryId)}`,
			CREDS
		);
		if (!r.ok) return r;
		const xml = await r.text();
		return jsonResponse({ mondrianXml: xml });
	},

	async convertSchema(body) {
		const b = (body ?? {}) as { mondrianXml?: string; connectionId?: string };
		const r = await fetch(`${BASE}/convert`, {
			method: 'POST',
			...CREDS,
			headers: JSON_HEADERS,
			body: JSON.stringify({
				mondrianXml: b.mondrianXml,
				dataSourceId: b.connectionId
			})
		});
		if (r.ok) return r; // { mondrianXml }
		// OSS returns a plain-text failure token (e.g. "tables_missing"); wrap as
		// JSON so the importer's `{ message }` error path shows something useful.
		const token = await r.text().catch(() => '');
		return jsonResponse({ message: convertMessage(token) }, r.status);
	}
};

/**
 * OSS AI adapter — points the DimSum agent turn at Saiku's schema-authoring
 * endpoint. dimsum-agent's `postDimSumTurn` posts `{messages, canvasSummary}` to
 * its built-in URL; the injected `fetchImpl` redirects that to
 * `/rest/saiku/admin/cube-designer/turn` (which returns `{content: [...]}`). The
 * server 503s when no API key is configured, and the designer surfaces that.
 */
export const ossCubeDesignerAI: CubeDesignerAI = {
	fetchImpl: (_input, init) => fetch(`${BASE}/turn`, { ...(init ?? {}), ...CREDS })
};
