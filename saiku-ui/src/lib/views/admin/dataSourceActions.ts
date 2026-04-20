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

export function generateSchemaHref(
  ds: Pick<GenerateSchemaTarget, "id">,
): string {
  return `/admin/schema-generator/${encodeURIComponent(ds.id)}`;
}

/**
 * Label for the schema-generator entry-point button.
 *
 * When the data source already has a Mondrian schema attached, the button
 * enters re-run / drift-detection mode — the backend will reconcile the fresh
 * introspection against the stored sidecar and the UI will surface any
 * detected changes. Otherwise the button kicks off a first-run generation.
 */
export function generateSchemaLabel(ds: GenerateSchemaTarget): string {
  return canGenerateSchema(ds)
    ? "Generate schema"
    : "Regenerate / check for drift";
}
