import { describe, expect, test } from 'vitest';
import { sqlResultToCsv } from './csv';
import type { SqlQueryResult } from '$lib/api/sqlWorkbench';

function mkResult(columns: string[], rows: unknown[][]): SqlQueryResult {
	return { columns, rows, rowCount: rows.length, truncated: false, durationMs: 1 };
}

describe('sqlResultToCsv', () => {
	test('columns + rows render as CSV', () => {
		const r = mkResult(['id', 'name'], [[1, 'alice']]);
		expect(sqlResultToCsv(r)).toBe('id,name\r\n1,alice\r\n');
	});

	test('escapes commas / quotes / newlines per RFC 4180', () => {
		const r = mkResult(
			['name', 'note'],
			[
				["O'Brien", 'has, comma'],
				['quoted "inline"', 'line1\nline2']
			]
		);
		expect(sqlResultToCsv(r)).toBe(
			'name,note\r\n' + 'O\'Brien,"has, comma"\r\n' + '"quoted ""inline""","line1\nline2"\r\n'
		);
	});

	test('empty rows produces just the header row', () => {
		const r = mkResult(['id', 'name'], []);
		expect(sqlResultToCsv(r)).toBe('id,name\r\n');
	});

	test("null cells render as empty strings, not the literal 'null'", () => {
		const r = mkResult(['id', 'name'], [[1, null]]);
		expect(sqlResultToCsv(r)).toBe('id,name\r\n1,\r\n');
	});
});
