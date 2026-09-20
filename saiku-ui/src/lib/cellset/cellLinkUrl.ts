import type { CellEntry, ThinHierarchy, ThinQueryModel } from '$lib/api/query';
import type { ParsedCellset } from '$lib/views/cellsetUtils';

/** Header coordinates for one cellset intersection (row headers + column headers). */
export interface CellLinkCoord {
	caption: string;
	uniqueName?: string;
	dimension?: string;
	hierarchy?: string;
	level?: string;
}

const PLACEHOLDER = /\{([^{}]+)\}/g;
const UNIQUE_SUFFIX = '.uniqueName';

/**
 * Substitute `{Dim}` / `{Dim.uniqueName}` / `{Measures}` in an admin-authored
 * HTTP(S) template. Unmatched placeholders become empty. Returns {@code null}
 * when the template is missing or is not `http:` / `https:` (rejects `javascript:`).
 */
export function buildCellLinkUrl(template: string | null | undefined, coords: CellLinkCoord[]): string | null {
	if (template == null) return null;
	const raw = template.trim();
	if (!raw) return null;
	if (!startsWithHttp(raw)) return null;

	const filled = raw.replace(PLACEHOLDER, (_m, token: string) => {
		const key = String(token).trim();
		const unique = key.endsWith(UNIQUE_SUFFIX);
		const name = unique ? key.slice(0, -UNIQUE_SUFFIX.length) : key;
		const matches = coords.filter((c) => tokenMatches(c, name));
		if (matches.length === 0) return '';
		if (unique) {
			return distinct(matches.map((c) => c.uniqueName ?? '').filter(Boolean))
				.map(encodeURIComponent)
				.join(',');
		}
		return distinct(matches.map(coordCaption).filter(Boolean)).map(encodeURIComponent).join(',');
	});

	if (!isHttpUrl(filled)) return null;
	return filled;
}

export function coordFromHeader(cell: CellEntry): CellLinkCoord {
	return {
		caption: cell.value ?? '',
		uniqueName: cell.properties?.uniquename,
		dimension: cell.properties?.dimension,
		hierarchy: cell.properties?.hierarchy,
		level: cell.properties?.level
	};
}

export function coordsForIntersection(rowHeaders: CellEntry[], columnHeaders: CellEntry[]): CellLinkCoord[] {
	return [...rowHeaders, ...columnHeaders].map(coordFromHeader);
}

/** Row/column indices are into {@link ParsedCellset.bodyRows} / {@code dataRows}. */
export function coordsAtIntersection(
	parsed: ParsedCellset,
	row: number,
	col: number,
	model?: ThinQueryModel | null
): CellLinkCoord[] {
	const rowHdrs: CellEntry[] = [];
	for (let c = 0; c < parsed.rowHeaderColCount; c++) {
		rowHdrs.push(rowHeaderLookingUp(parsed, row, c));
	}
	const colHdrs: CellEntry[] = [];
	for (let r = 0; r < parsed.columnHeaderRows.length; r++) {
		colHdrs.push(columnHeaderLookingLeft(parsed, r, col));
	}
	const coords = coordsForIntersection(rowHdrs, colHdrs);
	return model ? enrichFromQueryModel(coords, parsed, model) : coords;
}

function headerHasMember(cell: CellEntry | undefined): boolean {
	if (!cell) return false;
	if (cell.value && cell.value.length > 0) return true;
	const un = cell.properties?.uniquename?.trim();
	return !!un;
}

/** Mondrian blanks repeated parent captions; walk up to the last non-empty header. */
function rowHeaderLookingUp(parsed: ParsedCellset, row: number, col: number): CellEntry {
	for (let r = row; r >= 0; r--) {
		const c = parsed.bodyRows[r]?.[col];
		if (headerHasMember(c)) return c;
	}
	return parsed.bodyRows[row]?.[col] ?? { value: '', type: 'ROW_HEADER' };
}

/** Same as row walk-up, for spanned column parents (Barrio over several measures). */
function columnHeaderLookingLeft(parsed: ParsedCellset, headerRow: number, dataCol: number): CellEntry {
	const start = parsed.rowHeaderColCount + dataCol;
	for (let c = start; c >= parsed.rowHeaderColCount; c--) {
		const cell = parsed.columnHeaderRows[headerRow]?.[c];
		if (cell && cell.type === 'COLUMN_HEADER' && headerHasMember(cell)) return cell;
	}
	return parsed.columnHeaderRows[headerRow]?.[start] ?? { value: '', type: 'COLUMN_HEADER' };
}

function distinct(values: string[]): string[] {
	const seen = new Set<string>();
	const out: string[] = [];
	for (const v of values) {
		if (seen.has(v)) continue;
		seen.add(v);
		out.push(v);
	}
	return out;
}

function enrichFromQueryModel(
	coords: CellLinkCoord[],
	parsed: ParsedCellset,
	model: ThinQueryModel
): CellLinkCoord[] {
	const rowHiers = model.axes.ROWS?.hierarchies ?? [];
	const colHiers = model.axes.COLUMNS?.hierarchies ?? [];
	const measuresOnCols = (model.details?.axis ?? 'COLUMNS') === 'COLUMNS' && (model.details?.measures?.length ?? 0) > 0;
	const out: CellLinkCoord[] = [];
	for (let i = 0; i < coords.length; i++) {
		const coord = coords[i];
		if (hasIdentity(coord)) {
			out.push(coord);
			continue;
		}
		if (i < parsed.rowHeaderColCount) {
			out.push(applyHierarchy(coord, rowHiers[i], false));
		} else {
			const colDepth = i - parsed.rowHeaderColCount;
			if (colDepth < colHiers.length) {
				out.push(applyHierarchy(coord, colHiers[colDepth], false));
			} else {
				out.push(applyHierarchy(coord, undefined, measuresOnCols || colHiers.length === 0));
			}
		}
	}
	return out;
}

function hasIdentity(coord: CellLinkCoord): boolean {
	return !!(coord.dimension || coord.hierarchy || coord.level || coord.uniqueName);
}

function applyHierarchy(coord: CellLinkCoord, hier: ThinHierarchy | undefined, asMeasure: boolean): CellLinkCoord {
	if (asMeasure) {
		return { ...coord, dimension: coord.dimension || 'Measures', hierarchy: coord.hierarchy || '[Measures]' };
	}
	if (!hier) return coord;
	return {
		...coord,
		dimension: coord.dimension || hier.dimension || mdxSegments(hier.name)[0] || hier.name,
		hierarchy: coord.hierarchy || hier.name
	};
}

function startsWithHttp(t: string): boolean {
	const s = t.toLowerCase();
	return s.startsWith('https://') || s.startsWith('http://');
}

function isHttpUrl(s: string): boolean {
	try {
		const u = new URL(s);
		return u.protocol === 'http:' || u.protocol === 'https:';
	} catch {
		return false;
	}
}

function tokenMatches(coord: CellLinkCoord, name: string): boolean {
	if (!name) return false;
	if (name === 'Measures' && looksLikeMeasure(coord)) return true;
	for (const k of [coord.dimension, coord.hierarchy, coord.level, coord.uniqueName]) {
		if (!k) continue;
		if (k === name) return true;
		for (const seg of mdxSegments(k)) {
			if (seg === name) return true;
		}
	}
	return false;
}

function looksLikeMeasure(coord: CellLinkCoord): boolean {
	for (const k of [coord.dimension, coord.hierarchy, coord.level, coord.uniqueName]) {
		if (!k) continue;
		if (k === 'Measures' || k === '[Measures]') return true;
		if (mdxSegments(k)[0] === 'Measures') return true;
	}
	return false;
}

function coordCaption(coord: CellLinkCoord): string {
	if (coord.caption) return coord.caption;
	const segs = coord.uniqueName ? mdxSegments(coord.uniqueName) : [];
	return segs.length ? segs[segs.length - 1] : '';
}

function mdxSegments(un: string): string[] {
	const t = un.trim();
	if (t.startsWith('[') && t.endsWith(']')) {
		return t.slice(1, -1).split('].[');
	}
	return [t];
}
