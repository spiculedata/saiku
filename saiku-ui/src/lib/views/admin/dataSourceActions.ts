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
  schemaName?: string | null;
}

export function canGenerateSchema(ds: GenerateSchemaTarget): boolean {
  const name = ds.schemaName;
  if (name === undefined || name === null) return true;
  return name.trim().length === 0;
}

export function generateSchemaHref(ds: Pick<GenerateSchemaTarget, "id">): string {
  return `/admin/schema-generator/${encodeURIComponent(ds.id)}`;
}
