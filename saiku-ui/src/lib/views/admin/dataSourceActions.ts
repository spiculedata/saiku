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
