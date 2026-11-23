/*
 * Pure helpers for the quickstart CSV-upload page (saiku#1117).
 *
 * The page's job is almost entirely composition of already-shipped pieces — an upload endpoint
 * (`$lib/api/quickstart`), the existing datasource admin API (`$lib/api/admin`), and the existing
 * schema-generator store/client (`$lib/stores/schemaGen.svelte`, `$lib/api/schemaGen`). This
 * module holds the small amount of genuinely new glue in a form that's testable without a Svelte
 * harness.
 */

import type { AdminDatasource } from '$lib/api/admin';
import type { QuickstartUploadResponse } from '$lib/api/quickstart';
import type { Stage } from '$lib/api/schemaGen';

/**
 * Default table name shown in the form before upload, derived from the picked file's name.
 * Purely a UI nicety — the server is the source of truth (see `QuickstartNames` on the backend)
 * and may sanitise further or resolve a collision differently, so the actual name used is always
 * read back from the upload response, never assumed to match this.
 */
export function defaultTableNameFromFileName(fileName: string): string {
	const withoutExtension = fileName.replace(/\.csv$/i, '');
	const cleaned = withoutExtension.replace(/[^A-Za-z0-9_]+/g, '_').replace(/^_+|_+$/g, '');
	return cleaned || 'quickstart';
}

/**
 * Build the payload for `adminDatasources.create()` from a finished CSV upload.
 *
 * Uses the SAME name for the datasource connection and the Mondrian schema, so the
 * `Catalog=mondrian://<schemaName>` the server writes into the datasource's location at create
 * time resolves to exactly the path the schema-generator's save step writes later —
 * `/datasources/<schemaName>.xml` (see `MondrianCatalogResolver` on the backend). No separate
 * "attach schema to datasource" step is needed as a result, unlike the cube-designer route's
 * publish flow, which retrofits an already-existing datasource after the fact.
 */
export function buildDatasourcePayload(
	upload: QuickstartUploadResponse,
	schemaName: string
): AdminDatasource {
	return {
		id: '',
		name: schemaName,
		connectionName: '',
		type: 'OLAP',
		connectiontype: 'MONDRIAN',
		location: upload.jdbcUrl,
		driver: upload.driver,
		username: 'sa',
		password: '',
		schemaName,
		ossieYaml: ''
	};
}

/** True once the pipeline has reached a stage where saving is meaningful. */
export function isReadyToSave(stage: Stage | null): boolean {
	return stage === 'READY';
}

/** True once the pipeline has failed and the upload should be reported as a failure. */
export function isFailed(stage: Stage | null): boolean {
	return stage === 'FAILED';
}

/** Human-readable label for the progress indicator, reusing the schema-generator's stage names. */
export function stageLabel(stage: Stage | null): string {
	if (stage === null) return 'Uploading…';
	switch (stage) {
		case 'PENDING':
			return 'Starting…';
		case 'INTROSPECTING':
			return 'Reading your data…';
		case 'INFERRING':
			return 'Finding measures and dimensions…';
		case 'ENRICHING':
			return 'Polishing names…';
		case 'READY':
			return 'Saving your cube…';
		case 'SAVED':
			return 'Opening your workspace…';
		case 'FAILED':
			return 'Something went wrong';
	}
}
