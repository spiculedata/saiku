/*
 * Rollup-row detection for `records`-format query results (saiku#1802).
 *
 * A multi-level row axis comes back with subtotal rows interleaved among the
 * leaves. In the cellset shape the workspace reads, depth is explicit and
 * `deriveLeafRows` uses it; in the `records` shape the tiles consume, the only
 * signal is that a rollup leaves its DEEPER header cells blank:
 *
 *   { "Store State": "BC", "Store Name": "",         "Store Sqft": 57564 }  <- rollup
 *   { "Store State": "BC", "Store Name": "Store 19", "Store Sqft": 23112 }  <- leaf
 *
 * Two tiles need the same answer and must not disagree about it:
 *
 *   - the CHART tile drops rollups before drawing, because a subtotal is the
 *     sum of the bars beside it and dwarfs every one of them (saiku#1797);
 *   - the TABLE tile keeps them — a table is where a subtotal is actually
 *     wanted — but must exclude them from the conditional-format value basis,
 *     or every leaf is compared against its own parent and reads as small.
 *
 * Pure: no DOM, no fetches.
 */

import type { AiCell } from '$lib/api/aiQuery';

/** A row in a `records`-format response: header captions + measure cells.
 *  Structurally identical to `AiQueryResponse.data[n]` so both tiles can hand
 *  their rows straight in. A key absent at runtime reads as `undefined`, which
 *  {@link isRollupRow} coerces the same way it coerces a blank. */
export type RecordRow = Record<string, AiCell | string>;

function isAiCell(v: unknown): v is AiCell {
	return typeof v === 'object' && v !== null && 'formatted' in (v as object);
}

/**
 * The row-header column keys, in insertion order — every key of the first row
 * whose value is NOT a measure cell. Empty for a result with no rows.
 *
 * The first row is representative: /ai/query serialises every row of a result
 * with the same key set.
 */
export function headerKeysOf(rows: readonly RecordRow[]): string[] {
	const first = rows[0];
	if (!first) return [];
	return Object.keys(first).filter((k) => !isAiCell(first[k]));
}

/**
 * Is `row` a subtotal rather than a leaf?
 *
 * False whenever there is only one header column: a single-level axis has no
 * subtotals, and a blank caption there is a member legitimately captioned "" —
 * treating it as a rollup would silently drop real data.
 */
export function isRollupRow(row: RecordRow, headerKeys: readonly string[]): boolean {
	if (headerKeys.length < 2) return false;
	return headerKeys.some((k) => String(row[k] ?? '').length === 0);
}

/**
 * Split `rows` into leaves and rollups.
 *
 * `allRollups` reports the case where nothing is a leaf. Callers that DROP
 * rollups must stand down then: a result that is entirely subtotals is a
 * legitimate shape (a query at a rolled-up grain), and filtering it would
 * blank the tile rather than tidy it.
 */
export function partitionRollups(rows: readonly RecordRow[]): {
	headerKeys: string[];
	leaves: RecordRow[];
	rollups: RecordRow[];
	allRollups: boolean;
} {
	const headerKeys = headerKeysOf(rows);
	const leaves: RecordRow[] = [];
	const rollups: RecordRow[] = [];
	for (const row of rows) {
		(isRollupRow(row, headerKeys) ? rollups : leaves).push(row);
	}
	return {
		headerKeys,
		leaves,
		rollups,
		allRollups: rows.length > 0 && leaves.length === 0
	};
}
