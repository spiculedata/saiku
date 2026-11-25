/*
 * Client for the raw Ossie (M4) semantic-model YAML read/write endpoints
 * (`/rest/saiku/admin/ossie/schema/{datasource}`, saiku#1121) — the backend the Model IDE's
 * Ossie YAML slice (#1428) edits against.
 *
 * The path parameter is the OSSIE datasource's NAME (`connectionname` on the admin wire):
 * `OssieSchemaResource` passes it to `DatasourceService.getDatasource`, which keys by
 * connection name, not by the UUID `id` — the same misnomer tripped `adminDatasources.refresh`
 * (saiku#1862). Don't pass `ds.id`.
 *
 * GET returns `null` when the server answers 404: `OssieSchemaResource` distinguishes "the
 * datasource exists but no YAML file is on disk yet" from other failures, and the caller (the
 * Model IDE) turns that into a starter template rather than an error.
 */

const BASE = '/rest/saiku/admin/ossie/schema';

/** The datasource's current raw YAML, verbatim; `null` when nothing is saved yet (404). */
export async function getOssieSchemaYaml(datasourceName: string): Promise<string | null> {
	const res = await fetch(`${BASE}/${encodeURIComponent(datasourceName)}`, {
		credentials: 'include'
	});
	if (res.status === 404) return null;
	if (!res.ok) throw new Error(`ossie schema fetch -> ${res.status}`);
	return res.text();
}

/**
 * Overwrite the datasource's YAML. The server validates the document with `OssieYamlReader`
 * before writing and rejects a malformed body with 400 — the thrown Error carries the
 * server's message so the editor can surface it under the Problems pane instead of a bare
 * status code.
 */
export async function saveOssieSchemaYaml(datasourceName: string, yaml: string): Promise<void> {
	const res = await fetch(`${BASE}/${encodeURIComponent(datasourceName)}`, {
		method: 'PUT',
		credentials: 'include',
		headers: { 'Content-Type': 'text/plain' },
		body: yaml
	});
	if (res.ok) return;
	let detail = `${res.status}`;
	try {
		const parsed = (await res.json()) as { error?: string };
		if (parsed?.error) detail = parsed.error;
	} catch {
		// Non-JSON error body — fall back to the bare status.
	}
	throw new Error(`Save rejected: ${detail}`);
}
