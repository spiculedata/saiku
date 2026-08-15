/*
 * Markdown digest of a rendered cellset for the AI Ask drawer.
 *
 * Turns the 2D `CellEntry[][]` grid into a compact markdown table the LLM can reason about.
 * Used to seed the `cellsetDigest` field of /ai/ask so the model can pick the `emit_insight`
 * (trend analysis) or `emit_view_change` (right chart for the data) tools rather than always
 * having to fall back to `emit_query`.
 *
 * Privacy: the server enforces AI policy (schema-only / aggregated / row-level) and strips the
 * digest if the policy doesn't permit data passthrough — this client-side helper does no policy
 * enforcement of its own; it just builds the most useful text we can send.
 *
 * Token budget: 8K chars on the server side (truncated there). We cap rows at 50 and column-header
 * rows at 4 here so a typical run is well under that ceiling. Wide cellsets get the full first 50
 * data rows; deep ones see them all up to 50.
 */

import type { CellEntry, QueryResult } from '$lib/api/query';

const MAX_DATA_ROWS = 50;
const MAX_HEADER_ROWS = 4;

/**
 * Build the markdown digest. Returns "" when there's nothing useful to send (no cellset, no rows,
 * or all-empty grid). Drawer should omit the field when this returns "".
 */
export function buildCellsetDigest(result: QueryResult | null | undefined): string {
	if (!result || !result.cellset || result.cellset.length === 0) return '';

	const grid = result.cellset;
	// Header rows = top rows where every populated cell is a COLUMN_HEADER or ROW_HEADER_HEADER (the
	// corner pad). A pure data row would have at least one DATA_CELL.
	let headerRowCount = 0;
	for (const row of grid) {
		if (row.some((c) => c.type === 'DATA_CELL')) break;
		headerRowCount++;
		if (headerRowCount >= MAX_HEADER_ROWS) break;
	}
	if (headerRowCount === 0) {
		// Defensive: if the cellset has no header rows (shouldn't happen with a queryModel render),
		// synthesise an empty header so the table renders cleanly.
		headerRowCount = 0;
	}

	const headerRows = grid.slice(0, headerRowCount);
	const dataRows = grid.slice(headerRowCount, headerRowCount + MAX_DATA_ROWS);
	if (dataRows.length === 0) return '';

	const colCount = Math.max(...grid.map((r) => r.length));
	const truncated = grid.length - headerRowCount > MAX_DATA_ROWS;

	// For each header row, render `| h1 | h2 | ... |` so it appears as a markdown table head.
	// Stack multi-row headers vertically by emitting one row each — markdown tables only render
	// the FIRST header row visually but we keep the structure verbatim so the LLM sees the full
	// hierarchy (Year / Quarter / Month nesting on a time axis, etc).
	const lines: string[] = [];
	lines.push(`Cellset: ${grid.length - headerRowCount} data rows × ${colCount} columns.`);
	if (truncated) {
		lines.push(`(Showing first ${MAX_DATA_ROWS} of ${grid.length - headerRowCount} rows.)`);
	}
	lines.push('');

	for (const row of headerRows) {
		lines.push(formatRow(row, colCount));
	}
	if (headerRowCount > 0) {
		// Separator row matching markdown spec (3 dashes per column).
		lines.push('| ' + Array.from({ length: colCount }, () => '---').join(' | ') + ' |');
	}
	for (const row of dataRows) {
		lines.push(formatRow(row, colCount));
	}

	return lines.join('\n');
}

function formatRow(row: CellEntry[], width: number): string {
	const cells: string[] = [];
	for (let i = 0; i < width; i++) {
		const cell = row[i];
		cells.push(cellText(cell));
	}
	return '| ' + cells.join(' | ') + ' |';
}

function cellText(cell: CellEntry | undefined): string {
	if (!cell) return '';
	if (cell.type === 'EMPTY') return '';
	// Strip pipes (would break the markdown table) and collapse whitespace.
	return (cell.value ?? '').replace(/\|/g, '/').replace(/\s+/g, ' ').trim();
}
