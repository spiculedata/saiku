import type { CellEntry, QueryResult } from '$lib/api/query';

export interface ParsedCellset {
	/** Number of COLUMN_HEADER rows at the top. */
	headerRowCount: number;
	/** Number of ROW_HEADER columns on the left (pinned). */
	rowHeaderColCount: number;
	/** Raw cellset reference. */
	cells: CellEntry[][];
	/** Derived header cells per column: one array per column header row. */
	columnHeaderRows: CellEntry[][];
	/** Derived row-header cells per body row (length == rowCount * rowHeaderColCount). */
	bodyRows: CellEntry[][];
	/** Data values for each body row, indexed by data column. */
	dataRows: CellEntry[][];
	/** Flattened labels for body rows (for chart categories). */
	rowCategories: string[];
	/** Flattened labels for data columns (for chart series). */
	columnCategories: string[];
}

export function parseCellset(result: QueryResult): ParsedCellset {
	const cells = result.cellset ?? [];
	if (cells.length === 0) {
		return {
			headerRowCount: 0,
			rowHeaderColCount: 0,
			cells,
			columnHeaderRows: [],
			bodyRows: [],
			dataRows: [],
			rowCategories: [],
			columnCategories: []
		};
	}

	let headerRowCount = 0;
	for (const row of cells) {
		const onlyHeaders = row.every(
			(c) => c.type === 'COLUMN_HEADER' || c.type === 'ROW_HEADER_HEADER' || c.type === 'EMPTY'
		);
		if (
			onlyHeaders &&
			row.some((c) => c.type === 'COLUMN_HEADER' || c.type === 'ROW_HEADER_HEADER')
		) {
			headerRowCount += 1;
		} else {
			break;
		}
	}

	let rowHeaderColCount = 0;
	if (cells.length > headerRowCount) {
		const firstBody = cells[headerRowCount];
		for (const c of firstBody) {
			if (c.type === 'ROW_HEADER') rowHeaderColCount += 1;
			else break;
		}
	}

	const columnHeaderRows = cells.slice(0, headerRowCount);
	const body = cells.slice(headerRowCount);
	const bodyRows = body.map((r) => r.slice(0, rowHeaderColCount));
	const dataRows = body.map((r) => r.slice(rowHeaderColCount));

	const rowCategories = bodyRows.map((r) =>
		r
			.map((c) => c.value ?? '')
			.filter(Boolean)
			.join(' / ')
	);
	const columnCategories = (dataRows[0] ?? []).map((_, colIdx) => {
		const parts: string[] = [];
		for (const headerRow of columnHeaderRows) {
			const c = headerRow[rowHeaderColCount + colIdx];
			if (c && c.value) parts.push(c.value);
		}
		return parts.join(' / ');
	});

	return {
		headerRowCount,
		rowHeaderColCount,
		cells,
		columnHeaderRows,
		bodyRows,
		dataRows,
		rowCategories,
		columnCategories
	};
}

/**
 * For each row, mark row-header cells whose value is a duplicate of the cell directly above in
 * the same column (Mondrian already sends empty strings for repeated parents, but older cellsets
 * sometimes repeat the parent value — treat both as "null"/indent cells to match legacy
 * Saiku's row_null rendering).
 */
export function rowHeaderDisplay(parsed: ParsedCellset): { isNull: boolean }[][] {
	const out: { isNull: boolean }[][] = [];
	for (let r = 0; r < parsed.bodyRows.length; r++) {
		const row: { isNull: boolean }[] = [];
		for (let c = 0; c < parsed.rowHeaderColCount; c++) {
			const here = parsed.bodyRows[r][c];
			const above = r > 0 ? parsed.bodyRows[r - 1][c] : undefined;
			const hereVal = here?.value ?? '';
			const aboveVal = above?.value ?? '';
			const hereUn = here?.properties?.uniquename;
			const aboveUn = above?.properties?.uniquename;
			const isNull =
				hereVal === '' ||
				(above != null && hereUn != null && aboveUn != null && hereUn === aboveUn);
			row.push({ isNull });
		}
		out.push(row);
	}
	return out;
}

export function toNumber(cell: CellEntry | undefined): number | null {
	if (!cell || cell.value == null || cell.value === '') return null;
	const rawProp = cell.properties?.raw;
	const src = rawProp ?? cell.value;
	const n = Number(String(src).replace(/[, ]/g, ''));
	return Number.isFinite(n) ? n : null;
}

export interface LeafRows {
	/** Indices into `parsed.bodyRows` / `parsed.dataRows` for leaf-level rows. */
	indices: number[];
	/** Hierarchical labels for each leaf row, including parent context that
	 *  Mondrian elided via dedup (e.g. "2024 / Q1", "2024 / Q2"). */
	labels: string[];
}

/**
 * Returns the indices of rows whose row-header sits at the deepest level
 * of the hierarchy on ROWS — i.e. leaf rows — together with their
 * parent-prefixed labels.
 *
 * Charts call this with `hideRollupRows = true` to drop the year/quarter-
 * style rollup rows that otherwise dominate the bar heights and make the
 * leaf detail unreadable. The grid keeps showing all rows.
 *
 * Row "depth" is determined from the Mondrian uniquename on the deepest
 * row-header cell — segment count in
 *  `[Time].[Time].[1997]` (3) vs `[Time].[Time].[1997].[1]` (4). This
 * works whether the cellset packs the hierarchy into a single row-header
 * column (a single Time hierarchy spanning Year+Month — every row has
 * one cell, but Year cells are shallower) or into multiple columns
 * (legacy Year/Quarter shape — multi-col with the deeper column empty
 * on rollup rows). If no uniquename is present, falls back to "deepest
 * non-empty column index" — accurate for the multi-col case and a
 * graceful no-op when there's no hierarchy at all.
 */
export function deriveLeafRows(parsed: ParsedCellset): LeafRows {
	const n = parsed.bodyRows.length;
	if (n === 0 || parsed.rowHeaderColCount === 0) {
		return { indices: [], labels: [] };
	}

	// Walk forward once to compute every row's depth + its leaf-level
	// segment text. The segment is the cell value at the deepest level,
	// NOT the joined rowCategories label — joining would double-count
	// parents when Mondrian repeats them on every row instead of deduping.
	const depths: number[] = new Array(n);
	const ownSegments: string[] = new Array(n);
	for (let r = 0; r < n; r++) {
		const info = depthOfRow(parsed.bodyRows[r]);
		depths[r] = info.depth;
		ownSegments[r] = info.segment;
	}
	const maxDepth = Math.max(...depths);

	// No hierarchy — every row is at the same level. Keep them all so the
	// chart matches the grid for the trivial single-level case.
	if (depths.every((d) => d === maxDepth)) {
		return {
			indices: parsed.bodyRows.map((_, i) => i),
			labels: parsed.rowCategories.slice()
		};
	}

	// For each leaf row, build "parent / .../ leaf" from the most recent
	// segment seen at each level walked so far.
	const lastSegByDepth = new Map<number, string>();
	const indices: number[] = [];
	const labels: string[] = [];
	for (let r = 0; r < n; r++) {
		const d = depths[r];
		const seg = ownSegments[r];
		if (seg) lastSegByDepth.set(d, seg);
		if (d === maxDepth) {
			indices.push(r);
			const parts: string[] = [];
			for (let dd = 1; dd <= maxDepth; dd++) {
				const v = lastSegByDepth.get(dd);
				if (v) parts.push(v);
			}
			labels.push(parts.length ? parts.join(' / ') : seg);
		}
	}
	return { indices, labels };
}

interface RowDepth {
	/** 1-based depth in the hierarchy on rows. */
	depth: number;
	/** The cell value at that depth — used as the leaf segment when
	 *  reconstructing parent / leaf labels. */
	segment: string;
}

/** Depth + segment of the deepest cell in a row's row-header section.
 *  Uses the uniquename when available (count of bracketed segments),
 *  otherwise falls back to the deepest non-empty column index. */
function depthOfRow(row: CellEntry[]): RowDepth {
	for (let c = row.length - 1; c >= 0; c--) {
		const cell = row[c];
		const un = cell?.properties?.uniquename;
		if (un) {
			// `[Time].[Time].[1997].[1]` → 4 segments.
			const matches = un.match(/\.\[/g);
			return {
				depth: (matches?.length ?? 0) + 1,
				segment: cell?.value ?? ''
			};
		}
		if (cell?.value) {
			return { depth: c + 1, segment: cell.value };
		}
	}
	return { depth: 0, segment: '' };
}

/** Per-column axis assignment for a cartesian chart.
 *
 *  Returns the same length as {@code cols}, with each entry "left" or
 *  "right". The decision for column index `c`:
 *
 *    1. If the operator pinned this series in {@code seriesAxis}, use
 *       that — explicit picks always win.
 *    2. Else if {@code dualAxis} is off, use "left" (single-axis mode).
 *    3. Else compute max(|value|) for each series and put any whose
 *       value is less than {@code threshold} × the largest on "right".
 *
 *  Returns all-"left" when there's at most one series — there's no
 *  scale to compare against. */
export function assignSeriesAxes(
	cols: string[],
	matrix: ReadonlyArray<ReadonlyArray<number | null>>,
	opts: { dualAxis: boolean; seriesAxis: Record<string, 'left' | 'right'>; threshold: number }
): ('left' | 'right')[] {
	const n = cols.length;
	if (n === 0) return [];
	const out: ('left' | 'right')[] = new Array(n).fill('left');
	// Any explicit picks win — apply them first.
	for (let c = 0; c < n; c++) {
		const pin = opts.seriesAxis[cols[c]];
		if (pin === 'left' || pin === 'right') out[c] = pin;
	}
	if (!opts.dualAxis || n < 2) return out;

	const maxAbs: number[] = new Array(n).fill(0);
	for (let c = 0; c < n; c++) {
		for (let r = 0; r < matrix.length; r++) {
			const v = matrix[r]?.[c];
			if (v != null && Number.isFinite(v)) {
				const a = Math.abs(v);
				if (a > maxAbs[c]) maxAbs[c] = a;
			}
		}
	}
	const overall = Math.max(...maxAbs);
	if (!Number.isFinite(overall) || overall <= 0) return out;
	const cutoff = opts.threshold * overall;
	for (let c = 0; c < n; c++) {
		// Don't override an explicit pin.
		if (opts.seriesAxis[cols[c]]) continue;
		// `<=` not `<`: a series whose magnitude lands EXACTLY at the cutoff
		// (Balance £7500 vs Fees £75 → ratio = 1%) should split. The strict
		// `<` left it crushed on the left axis — see saiku 2026-06-07
		// screenshot bug.
		out[c] = maxAbs[c] <= cutoff ? 'right' : 'left';
	}
	return out;
}
