/**
 * saiku#1985 — the workspace Export → CSV toolbar item must serialise the
 * in-memory cellset in the browser instead of opening the REST export URL.
 * WorkspaceToolbar imports 26 stores and renders its Export menu behind
 * internal state, so this pins the source contract (same source-assertion
 * approach as WorkspaceToolbarEmailExport.test.ts) rather than mounting.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const SOURCE = readFileSync(
	fileURLToPath(new URL('./WorkspaceToolbar.svelte', import.meta.url)),
	'utf8'
);

describe('workspace Export → CSV runs client-side', () => {
	it('imports the cellset CSV exporter', () => {
		expect(SOURCE).toContain(
			"import { cellsetToCsv, csvDownloadName, downloadCsv } from '$lib/cellset/exportCsv';"
		);
	});

	it('short-circuits the csv kind before the window.open round-trip', () => {
		expect(SOURCE).toMatch(
			/function exportCurrent\(kind: 'xls' \| 'csv' \| 'pdf'\)[\s\S]*?if \(kind === 'csv'\) \{[\s\S]*?exportCsv\(\);[\s\S]*?return;[\s\S]*?window\.open\(/
		);
	});

	it('exports the live query result to a blob download', () => {
		expect(SOURCE).toMatch(
			/function exportCsv\(\)[\s\S]*?cellsetToCsv\(query\.result\)[\s\S]*?downloadCsv\(csvDownloadName\(query\.current\?\.name\), csv\);/
		);
	});

	it('keeps the run-before-export guard, and warns when the result is empty', () => {
		// No query selected at all — unchanged legacy guard.
		expect(SOURCE).toMatch(
			/function exportCurrent\([\s\S]*?if \(!query\.current\) \{[\s\S]*?warning\.runBeforeExport[\s\S]*?\}/
		);
		// Query present but never run / empty cellset — same warning, no download.
		expect(SOURCE).toMatch(
			/function exportCsv\(\)[\s\S]*?const csv = cellsetToCsv\(query\.result\);[\s\S]*?if \(!csv\) \{[\s\S]*?warning\.runBeforeExport/
		);
	});

	it('leaves xls / pdf on the server (server-side document generation)', () => {
		// Only the csv kind is diverted; the other two still hit the REST URL.
		expect(SOURCE).toContain(
			"window.open(`/rest/saiku/api/query/${name}/export/${kind}`, '_blank');"
		);
	});
});
