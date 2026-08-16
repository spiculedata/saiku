/*
 * Row-action helpers for the datasources admin view.
 *
 * Extracted as pure functions so the gating rule for the "Generate schema"
 * entry point is unit-testable without mounting the Svelte component.
 *
 * Heuristic for "has a Mondrian schema attached": `schemaName` is a
 * non-empty, non-whitespace string. That matches the field surfaced by the
 * `/rest/saiku/admin/datasources` response (see `AdminDatasource`) and by the
 * edit form — blank / null / missing all mean "no schema yet".
 */

export interface GenerateSchemaTarget {
	id: string;
	/** Wire discriminator: `"MONDRIAN" | "XMLA" | "OSSIE"`. Optional so existing
	 *  callers/tests can omit it; absent is treated as a Mondrian-style source. */
	connectiontype?: string;
	/** Legacy client-side dropdown value, which also carries "OSSIE". Checked as
	 *  a fallback because older saved forms set this and not `connectiontype`. */
	type?: string;
	/** Datasource NAME — the key the backend resolves (getDatasource keys by
	 *  name, not id), so the cube-designer route must carry the name. Optional
	 *  here only so tests can omit it; real AdminDatasource always has one. */
	name?: string;
	schemaName?: string | null;
}

export function canGenerateSchema(ds: GenerateSchemaTarget): boolean {
	const name = ds.schemaName;
	if (name === undefined || name === null) return true;
	return name.trim().length === 0;
}

/**
 * App-relative (base-less) href for the cube-designer entry. The datasource
 * NAME — not the id — is used as the route param because the backend resolves
 * datasources by name (DatasourceService.getDatasource keys the map on name);
 * passing the UUID id yields a 500 "no Saiku datasource named …". The caller
 * must prefix the SvelteKit `base` (the app is served under `/ui`).
 */
export function generateSchemaHref(ds: Pick<GenerateSchemaTarget, 'id' | 'name'>): string {
	return `/admin/cube-designer/${encodeURIComponent(ds.name ?? ds.id)}`;
}

/**
 * Label for the cube-designer entry-point button. First-run when the data
 * source has no Mondrian schema yet; edit mode when one is attached.
 */
export function generateSchemaLabel(ds: GenerateSchemaTarget): string {
	return canGenerateSchema(ds) ? 'Design cube' : 'Edit cube schema';
}

/**
 * Does the Mondrian cube designer apply to this data source?
 *
 * saiku#1841: it was offered for EVERY data source, Ossie included. The
 * designer is a Mondrian XML tool: pointed at an Ossie source it opened
 * happily, showed "0 tables · 0 joins" with nothing but INFORMATION_SCHEMA in
 * the table list, and never loaded the semantic model — because there isn't a
 * Mondrian schema to load. A Save from that state would have written a
 * Mondrian schema onto a source that isn't one.
 *
 * Ossie models are authored as YAML on disk and referenced by path (the path
 * field is on the data source's own Edit form). The REST surface is read-only —
 * AiOssieResource exposes /models, /schema, /graph, /entity, /ontology and
 * /search as GET, and its only POSTs are /query and /ask — so there is no
 * Ossie-model write endpoint for a designer to target even if one existed.
 *
 * Until Ossie model editing is a real feature, don't offer an action that
 * cannot do the thing its label promises.
 */
export function supportsCubeDesigner(ds: GenerateSchemaTarget): boolean {
	return ds.connectiontype !== 'OSSIE' && ds.type !== 'OSSIE';
}
