/*
 * Client-side CSV export of an MDX workspace cellset.
 *
 * saiku#1985 — the workspace toolbar's Export → CSV used to open
 * `GET /saiku/api/query/{name}/export/csv`, which reads the already-cached
 * server-side CellSet and runs `CsvExporter` over it. The SPA already holds
 * the very same result in memory (`query.result.cellset`, whether it arrived
 * as JSON or was rebuilt from Arrow), so the round-trip is pure latency plus
 * launcher load. This module serialises the in-memory cellset instead, using
 * a Blob + transient `<a download>` — same UX as the Ossie path
 * (`$lib/ossie/exportCsv`).
 *
 * Layout parity with the server `CsvExporter.getCsv(CellDataSet, …)` path:
 *  - one header line, whose per-column label is the column-header values
 *    joined top-down with `/` (the server walks the header rows upwards and
 *    prepends, which yields the same order);
 *  - the leading row-header ("corner") columns are emitted as empty header
 *    fields so the header line stays column-aligned with the body lines;
 *  - every field is enclosed in `"` with embedded quotes doubled, and rows
 *    are CRLF-terminated;
 *  - formula neutralisation (CWE-1236) is ported from the server so Excel /
 *    Sheets don't evaluate attacker-influenced cube text as a formula.
 *
 * Intentional divergences from the server export (documented, deliberate):
 *  - Row-header members are emitted with parent fill-forward, i.e. the label
 *    the user actually sees in the grid. The server's `flattened` formatter
 *    collapses a hierarchy to the leaf member caption, which loses the parent
 *    context when the CSV is re-sorted or filtered downstream.
 *  - Values are the formatted strings held in the cellset
 *    (`saiku.web.export.csv.useFormattedValue` defaults to true server-side
 *    too, so this matches the default path). There is no raw-number option:
 *    the browser holds only the formatted value plus an optional `raw`
 *    property the server sets for its own raw mode.
 *  - Query-model visual totals/sub-totals are NOT recalculated. They are
 *    already present in the cellset the grid renders, so the CSV matches the
 *    screen, but the server's `calculateTotals` fallback (which can add
 *    totals the client never received) is not reproduced.
 */
import type { CellEntry, QueryResult } from '$lib/api/query';
import { parseCellset } from '$lib/views/cellsetUtils';

export interface CsvExportOptions {
	/** Field separator. Mirrors `saiku.web.export.csv.delimiter` (default `,`). */
	delimiter?: string;
	/** Field enclosure. Mirrors `saiku.web.export.csv.textEscape` (default `"`). */
	enclosing?: string;
}

/** True for an optionally signed, optionally currency-prefixed number
 *  (thousands separators, decimal and percent tolerated) — those stay
 *  un-neutralised so a legitimate `-1,234.50` measure exports as a number
 *  rather than degrading to text. Port of `CsvExporter.isPlainNumber`. */
export function isPlainNumber(value: string): boolean {
	return /^[-+]?[$€£¥]?(\d+|\d{1,3}(,\d{3})+)(\.\d+)?%?$/.test(value);
}

/**
 * CSV / formula-injection neutralisation (OWASP, CWE-1236), ported from
 * `CsvExporter.neutralizeCsvFormula`. A cell whose text begins with
 * `= + - @` (or a leading TAB / CR) is evaluated as a formula by Excel /
 * Google Sheets / LibreOffice, so attacker-influenceable cube data (member
 * captions, fact text) could run `=cmd|'/C calc'!A0` or exfiltrate via
 * `=WEBSERVICE(...)`. Prefixing a single quote forces the spreadsheet to
 * treat the field as literal text.
 */
export function neutralizeCsvFormula(value: string): string {
	if (!value) return value;
	const first = value[0];
	const risky =
		first === '=' ||
		first === '+' ||
		first === '-' ||
		first === '@' ||
		first === '\t' ||
		first === '\r';
	return risky && !isPlainNumber(value) ? `'${value}` : value;
}

/** Quote a single field: neutralise formulas, double embedded quotes, enclose. */
function field(value: string | null | undefined, enclosing: string): string {
	const raw = value == null || value === 'null' ? '' : String(value);
	return enclosing + neutralizeCsvFormula(raw).replace(/"/g, '""') + enclosing;
}

/** Blank for a cellset cell the server treats as empty (`''` and the literal `null`). */
function isBlank(cell: CellEntry | undefined): boolean {
	const v = cell?.value;
	return v == null || v === '' || v === 'null';
}

function line(fields: string[], delimiter: string): string {
	return fields.join(delimiter) + '\r\n';
}

/**
 * Serialise an MDX cellset to a CSV string in the server `CsvExporter` layout.
 *
 * Row headers are filled forward from the nearest non-blanc ancestor in the
 * same column, so a hierarchy that the server deduplicated (`1997`, then
 * blanks for its months) exports one fully-qualified label per row.
 */
export function cellsetToCsv(
	result: QueryResult | null | undefined,
	opts: CsvExportOptions = {}
): string {
	const delimiter = opts.delimiter ?? ',';
	const enclosing = opts.enclosing ?? '"';

	const parsed = parseCellset(result ?? { cellset: [] });
	if (parsed.cells.length === 0) return '';

	const { headerRowCount, rowHeaderColCount, columnHeaderRows, bodyRows, dataRows } = parsed;
	const dataColCount = dataRows.reduce((n, r) => Math.max(n, r.length), 0);

	let out = '';

	// Header line — only when the result actually has column headers. A
	// measures-only query (no COLUMNS dimension) has none, and the server
	// omits the line there too rather than emitting a row of blanks.
	if (headerRowCount > 0) {
		const cols: string[] = [];
		// Corner fields keep the header line column-aligned with the body.
		for (let c = 0; c < rowHeaderColCount; c++) cols.push(field('', enclosing));
		for (let c = 0; c < dataColCount; c++) {
			const parts: string[] = [];
			for (const headerRow of columnHeaderRows) {
				const cell = headerRow[rowHeaderColCount + c];
				if (!isBlank(cell)) parts.push(String(cell?.value));
			}
			cols.push(field(parts.join('/'), enclosing));
		}
		out += line(cols, delimiter);
	}

	// Body lines — row headers with parent fill-forward, then the data cells.
	const lastKnownRowHeader: string[] = [];
	for (let r = 0; r < bodyRows.length; r++) {
		const cols: string[] = [];
		for (let c = 0; c < rowHeaderColCount; c++) {
			const cell = bodyRows[r]?.[c];
			if (isBlank(cell)) {
				cols.push(field(lastKnownRowHeader[c] ?? '', enclosing));
			} else {
				lastKnownRowHeader[c] = String(cell?.value);
				cols.push(field(lastKnownRowHeader[c], enclosing));
			}
		}
		const dataRow = dataRows[r] ?? [];
		for (let c = 0; c < dataColCount; c++) {
			const cell = dataRow[c];
			cols.push(field(cell?.value, enclosing));
		}
		out += line(cols, delimiter);
	}

	return out;
}

/** Filesystem-safe download name for a query, defaulting to the server's
 *  `saiku.web.export.csv.name` (`saiku-export`). */
export function csvDownloadName(queryName: string | null | undefined): string {
	const safe = (queryName ?? '')
		.trim()
		.replace(/[\\/:*?"<>|]+/g, '-')
		.replace(/\s+/g, ' ')
		.replace(/^\.+/, '')
		.slice(0, 100);
	return `${safe || 'saiku-export'}.csv`;
}

/**
 * Trigger a browser download of the CSV. Blob-backed object URL + a transient
 * `<a download>` element — no server hop, no third-party lib. Same helper
 * shape as the Ossie path.
 */
export function downloadCsv(filename: string, csv: string): void {
	const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
	const url = URL.createObjectURL(blob);
	const a = document.createElement('a');
	a.href = url;
	a.download = filename;
	document.body.appendChild(a);
	a.click();
	document.body.removeChild(a);
	URL.revokeObjectURL(url);
}
