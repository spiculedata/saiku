/**
 * SQL type → coarse Mondrian column kind (saiku-cloud#1039 split).
 *
 * Mondrian only really cares about Numeric vs everything else for level +
 * measure typing. We do a string-contains check so the rule stays portable
 * across PG / MySQL / DuckDB / Snowflake without tracking every dialect's
 * spelling. Extracted from `WorkbenchView.svelte` so it is unit-testable.
 */
import type { SchemaCanvasColumnKind } from './types.js';

export function classifyColumn(sqlType: string | undefined): SchemaCanvasColumnKind | undefined {
	if (!sqlType) return undefined;
	const t = sqlType.toLowerCase();
	if (/int|serial|bigint|smallint|tinyint/.test(t)) return 'Integer';
	if (/numeric|decimal|float|double|real|money/.test(t)) return 'Numeric';
	if (/bool/.test(t)) return 'Boolean';
	if (/date|time|timestamp/.test(t)) return 'Date';
	return 'String';
}

export function isNumericKind(kind: SchemaCanvasColumnKind | undefined): boolean {
	return kind === 'Numeric' || kind === 'Integer';
}
