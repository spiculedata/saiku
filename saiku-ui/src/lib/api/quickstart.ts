/*
 * REST client for the saiku#1117 quickstart CSV-upload endpoint.
 *
 * Mirrors `QuickstartResource` (saiku-web): one multipart POST that parses a CSV and loads it
 * into a fresh, dedicated H2 table. Everything after that — registering a datasource, running
 * the schema-generator pipeline, saving the schema — goes through the existing `adminDatasources`
 * (`$lib/api/admin`) and `schemaGen` (`$lib/api/schemaGen`) clients; this module stays as thin as
 * the resource it talks to.
 */

const BASE = '/rest/saiku/admin/quickstart';

export interface QuickstartColumn {
	name: string;
	type: string;
}

export interface QuickstartUploadResponse {
	jdbcUrl: string;
	driver: string;
	tableName: string;
	rowCount: number;
	columns: QuickstartColumn[];
}

/**
 * Upload `file` as CSV and load it into a fresh H2 table.
 *
 * @param tableName requested table name; the server sanitises it (and falls back to the file
 *     name) — always read the resolved name back from the response, never assume it matches
 *     what was requested.
 * @throws Error carrying the server's message (`CsvIngestException`'s text on a 400, e.g. a
 *     ragged row or a name collision) so the caller can show it directly to the user.
 */
export async function uploadCsv(
	file: File,
	tableName: string | undefined,
	fetcher: typeof fetch = fetch
): Promise<QuickstartUploadResponse> {
	const body = new FormData();
	body.append('file', file, file.name);
	if (tableName) {
		body.append('table', tableName);
	}
	const res = await fetcher(`${BASE}/upload`, {
		method: 'POST',
		credentials: 'include',
		// Deliberately no Content-Type header: the browser must generate the multipart
		// boundary itself (same rationale as `adminSchemas.upload` in $lib/api/admin).
		body
	});
	if (!res.ok) {
		const detail = (await res.text()).trim();
		throw new Error(detail || `quickstart upload -> ${res.status}`);
	}
	return (await res.json()) as QuickstartUploadResponse;
}
