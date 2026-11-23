import type { SqlQueryResult } from '$lib/api/sqlWorkbench';
import { csvEscape } from '$lib/ossie/exportCsv';

/**
 * Turn a {@link SqlQueryResult} into a CSV string, reusing the escaping the chart/Ossie export
 * path already established (saiku#1107 issue notes: "reuses the chart export ticket's CSV
 * utility"). A `null` cell exports as an empty field, matching {@link csvEscape}'s own handling.
 */
export function sqlResultToCsv(result: SqlQueryResult): string {
	const lines: string[] = [result.columns.map((c) => csvEscape(c)).join(',')];
	for (const row of result.rows) {
		lines.push(row.map((cell) => csvEscape(cell == null ? null : String(cell))).join(','));
	}
	return lines.join('\r\n') + '\r\n';
}
