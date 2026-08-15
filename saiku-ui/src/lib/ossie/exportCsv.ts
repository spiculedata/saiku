import type { OssieQueryResult } from '$lib/api/ossie';

/**
 * Escape a single CSV field per RFC 4180: wrap in quotes when the value contains a
 * comma / double-quote / newline; double any embedded quotes.
 */
function csvEscape(v: string | undefined | null): string {
	if (v == null) return '';
	const s = String(v);
	if (/[",\r\n]/.test(s)) {
		return `"${s.replace(/"/g, '""')}"`;
	}
	return s;
}

/**
 * Turn the projected {@link OssieQueryResult} into a CSV string. Header row is the
 * grid's column headers; body rows follow. Cells use `formattedValue` (falling back to
 * `rawValue`) — the same string the user sees in the grid, so downstream Excel /
 * spreadsheet imports match the on-screen numbers character-for-character.
 */
export function ossieResultToCsv(result: OssieQueryResult): string {
	const lines: string[] = [];
	const header = result.cellSetHeaders?.[0] ?? [];
	if (header.length > 0) {
		lines.push(header.map((h) => csvEscape(h.formattedValue ?? h.rawValue)).join(','));
	}
	for (const row of result.cellSetBody ?? []) {
		lines.push(row.map((c) => csvEscape(c.formattedValue ?? c.rawValue)).join(','));
	}
	// Trailing newline so the last row is terminated — matches Excel's export shape.
	return lines.join('\r\n') + '\r\n';
}

/**
 * Trigger a browser download of the CSV. Uses a Blob-backed object URL and a
 * transient <a download> element — no server hop, no third-party lib.
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
