/*
 * Issue #930 — drillthrough on cell click.
 *
 * Pure helper that builds the `position` query-param the AI Query
 * drillthrough endpoint expects. The VERIFIED coordinate convention is
 * `"{columnIndex}:{rowIndex}"` — columnIndex is the 0-based index into the
 * result's measure columns, rowIndex the 0-based index into the result rows.
 * Column index first, row index second.
 *
 * Kept side-effect free (no DOM, no fetch) so it can be unit-tested under
 * the project's `node` vitest environment.
 */

import type { QueryResult, CellEntry } from '$lib/api/query';
import type { AiDrillthroughResult, AiCell } from '$lib/api/aiQuery';

/**
 * Build the `position` param for a drillthrough on a single aggregated cell.
 *
 * @returns `"{col}:{row}"`, or `null` when either index is not a
 *   non-negative integer (e.g. NaN, negative, fractional, or a background
 *   click where the renderer couldn't resolve a series/category index).
 */
export function drillthroughPosition(columnIndex: number, rowIndex: number): string | null {
	if (!isNonNegativeInt(columnIndex) || !isNonNegativeInt(rowIndex)) return null;
	return `${columnIndex}:${rowIndex}`;
}

function isNonNegativeInt(n: number): boolean {
	return Number.isInteger(n) && n >= 0;
}

/**
 * Adapt the flat {columns, rows} drillthrough payload into the `QueryResult`
 * shape DrillthroughResultModal already renders (a `cellset` whose first row
 * is the header). Reusing that modal keeps drillthrough output identical to
 * the workspace's. Numeric measure cells are tagged `properties.numeric =
 * "true"` so the modal right-aligns them, matching the workspace path.
 */
export function drillthroughToQueryResult(
	dt: AiDrillthroughResult,
	runtimeMs?: number
): QueryResult {
	const header: CellEntry[] = dt.columns.map((caption) => ({
		value: caption,
		type: 'COLUMN_HEADER'
	}));
	const body: CellEntry[][] = dt.rows.map((row) =>
		dt.columns.map((col) => {
			const cell = row[col] as AiCell | undefined;
			const numeric = cell && cell.value != null && typeof cell.value === 'number';
			return {
				value: cell?.formatted ?? '',
				type: 'DATA_CELL',
				...(numeric ? { properties: { numeric: 'true' } } : {})
			} satisfies CellEntry;
		})
	);
	return {
		cellset: [header, ...body],
		runtime: runtimeMs
	};
}
