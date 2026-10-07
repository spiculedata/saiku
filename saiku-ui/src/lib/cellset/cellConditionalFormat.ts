import type { CellEntry } from '$lib/api/query';
import type { ConditionalFormatRule } from '$lib/api/dashboards';
import { cellFormatToStyle, formatCell, type CellFormat } from '$lib/dashboard/conditionalFormat';
import { parseFormattedCell } from '$lib/cellset/cellFormat';

/** Stored on ThinQuery.properties so a saved query keeps its bands (saiku#1986). */
export const CELL_CF_PROPERTY = 'saiku.cell.conditionalFormat';

const TYPES = new Set(['background', 'bar', 'font', 'icon']);
const MODES = new Set(['relative', 'absolute']);

export function readCellConditionalFormat(
	properties: Record<string, unknown> | null | undefined
): ConditionalFormatRule[] {
	const raw = properties?.[CELL_CF_PROPERTY];
	const list = typeof raw === 'string' ? parseJson(raw) : raw;
	if (!Array.isArray(list)) return [];
	const out: ConditionalFormatRule[] = [];
	for (const item of list) {
		const rule = asRule(item);
		if (rule) out.push(rule);
	}
	return out;
}

export function withCellConditionalFormat(
	properties: Record<string, unknown> | null | undefined,
	rules: ConditionalFormatRule[]
): Record<string, unknown> {
	const next = { ...(properties ?? {}) };
	const cleaned = rules.filter((r) => r.column.trim().length > 0);
	if (cleaned.length === 0) {
		delete next[CELL_CF_PROPERTY];
		return next;
	}
	next[CELL_CF_PROPERTY] = cleaned;
	return next;
}

/** Numeric value used for banding. Prefer the unformatted raw property. */
export function cellNumericValue(cell: CellEntry | undefined): number | null {
	if (!cell) return null;
	const raw = cell.properties?.raw;
	if (raw != null && raw !== '') {
		const n = Number(String(raw).replace(/[\s,]/g, ''));
		if (Number.isFinite(n)) return n;
	}
	const display = parseFormattedCell(cell.value).display.replace(/[\s,]/g, '');
	if (!display) return null;
	const n = Number(display);
	return Number.isFinite(n) ? n : null;
}

/**
 * Mondrian `|value|style=` colour is the text colour when the user rule
 * did not set one. A user background / bar / font rule still applies.
 * Schema text colour loses only when the rule itself sets `color`.
 */
export function mergeCellStyle(
	mondrianColor: string | undefined,
	fmt: CellFormat
): string | undefined {
	const color = fmt.color ?? mondrianColor;
	return cellFormatToStyle(color && color !== fmt.color ? { ...fmt, color } : fmt);
}

export function formatDataCell(
	rules: readonly ConditionalFormatRule[] | undefined,
	columnCaption: string,
	cell: CellEntry | undefined,
	columnValues: readonly unknown[]
): { style?: string; icon?: string; display: string } {
	const parsed = parseFormattedCell(cell?.value);
	const fmt = formatCell(rules, columnCaption, cellNumericValue(cell), columnValues);
	return {
		style: mergeCellStyle(parsed.color, fmt),
		icon: fmt.icon,
		display: parsed.display
	};
}

function parseJson(raw: string): unknown {
	try {
		return JSON.parse(raw);
	} catch {
		return undefined;
	}
}

function asRule(item: unknown): ConditionalFormatRule | null {
	if (!item || typeof item !== 'object') return null;
	const o = item as Record<string, unknown>;
	if (
		typeof o.column !== 'string' ||
		!TYPES.has(String(o.type)) ||
		!MODES.has(String(o.thresholdMode))
	) {
		return null;
	}
	const rule: ConditionalFormatRule = {
		column: o.column,
		type: o.type as ConditionalFormatRule['type'],
		thresholdMode: o.thresholdMode as ConditionalFormatRule['thresholdMode']
	};
	if (typeof o.lowThreshold === 'number') rule.lowThreshold = o.lowThreshold;
	if (typeof o.highThreshold === 'number') rule.highThreshold = o.highThreshold;
	if (typeof o.barColor === 'string' && o.barColor.trim()) rule.barColor = o.barColor.trim();
	if (o.colors && typeof o.colors === 'object') {
		const c = o.colors as Record<string, unknown>;
		const colors: ConditionalFormatRule['colors'] = {};
		if (typeof c.low === 'string' && c.low.trim()) colors.low = c.low.trim();
		if (typeof c.mid === 'string' && c.mid.trim()) colors.mid = c.mid.trim();
		if (typeof c.high === 'string' && c.high.trim()) colors.high = c.high.trim();
		if (colors.low || colors.mid || colors.high) rule.colors = colors;
	}
	return rule;
}
