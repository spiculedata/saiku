/**
 * Publish helpers for the OSS Cube Designer host route.
 *
 * Saving a designed cube is three steps, not one, and the route used to do only the first:
 * upload the schema XML, attach it to the datasource, refresh the connection. Without step 2
 * the file sat in the repository and the cube never appeared in Studio — the admin had to know
 * to go and paste the path into the datasource by hand (saiku#1860).
 *
 * The pure string-shaping parts live here so they are unit-testable without a Svelte harness.
 */

// saiku#1117: buildLaunchUrl/LaunchCoordinates moved to $lib/api/starterCube so the quickstart
// CSV-upload route can build the same starter-cube URL without an inter-route import. Re-exported
// here so this route's existing imports (and publish.test.ts) don't need to change.
export { buildLaunchUrl, type LaunchCoordinates } from '$lib/api/starterCube';

/**
 * The repository path `AdminResource.uploadSchema` writes a schema to.
 *
 * It derives the path from the `name` form field alone, as `/datasources/{name}.xml`, and
 * accepts no path of its own — so this must stay in step with `adminSchemas.upload`. The
 * value is what goes in the datasource's `schema` field; `MondrianCatalogResolver` on the
 * server resolves it verbatim.
 */
export function repositorySchemaPath(schemaName: string): string {
	return `/datasources/${schemaName}.xml`;
}

/**
 * Pick the name to save the schema under.
 *
 * The designer's own label wins when the user set one. Falling back to the datasource id
 * keeps the file, the `<Schema name>` and the catalog users see all agreeing — the previous
 * code let the file be `foo-cube` while the schema inside stayed the export default
 * `Untitled`, and it was `Untitled` that surfaced as the catalog name (saiku#1861).
 */
export function resolveSchemaName(label: string | undefined | null, dataSourceId: string): string {
	const trimmed = (label ?? '').trim();
	return trimmed || `${dataSourceId}-cube`;
}
