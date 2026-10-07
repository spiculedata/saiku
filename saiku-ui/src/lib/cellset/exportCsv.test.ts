import { describe, expect, it } from 'vitest';
import type { CellEntry, QueryResult } from '$lib/api/query';
import { cellsetToCsv, csvDownloadName, isPlainNumber, neutralizeCsvFormula } from './exportCsv';

function cell(
	value: string,
	type: CellEntry['type'] = 'DATA_CELL',
	properties?: Record<string, string>
): CellEntry {
	return { value, type, properties };
}

/** A FoodMart-shaped result: two year columns over a
 *  [Store Country] / [Store Name] row hierarchy. */
function foodmartResult(): QueryResult {
	return {
		cellset: [
			// Column header row.
			[cell('', 'ROW_HEADER_HEADER'), cell('1997', 'COLUMN_HEADER'), cell('1998', 'COLUMN_HEADER')],
			// Body rows — Mexico repeats its parent (the grid renders it blank,
			// so the CSV must fill it forward).
			[cell('Mexico', 'ROW_HEADER'), cell('1,234.50', 'DATA_CELL'), cell('-2,000.25', 'DATA_CELL')],
			[cell('', 'ROW_HEADER'), cell('987.00', 'DATA_CELL'), cell('1,000.00', 'DATA_CELL')]
		]
	};
}

describe('cellsetToCsv', () => {
	it('emits a header line plus one line per body row, CRLF-terminated', () => {
		expect(cellsetToCsv(foodmartResult())).toBe(
			'"","1997","1998"\r\n' +
				'"Mexico","1,234.50","-2,000.25"\r\n' +
				'"Mexico","987.00","1,000.00"\r\n'
		);
	});

	it('fills forward blank row-header parents', () => {
		const rows = cellsetToCsv(foodmartResult()).trimEnd().split('\r\n');
		expect(rows[2]).toBe('"Mexico","987.00","1,000.00"');
	});

	it('joins multi-level column headers top-down with a slash', () => {
		const result: QueryResult = {
			cellset: [
				[cell('', 'ROW_HEADER_HEADER'), cell('1997', 'COLUMN_HEADER'), cell('', 'EMPTY')],
				[cell('', 'EMPTY'), cell('Q1', 'COLUMN_HEADER'), cell('Q1', 'COLUMN_HEADER')],
				[cell('USA', 'ROW_HEADER'), cell('10', 'DATA_CELL'), cell('20', 'DATA_CELL')]
			]
		};
		const rows = cellsetToCsv(result).trimEnd().split('\r\n');
		expect(rows[0]).toBe('"","1997/Q1","Q1"');
	});

	it('omits the header line for a measures-only result (no COLUMNS dimension)', () => {
		const result: QueryResult = {
			cellset: [[cell('USA', 'ROW_HEADER'), cell('10', 'DATA_CELL')]]
		};
		expect(cellsetToCsv(result)).toBe('"USA","10"\r\n');
	});

	it('doubles embedded quotes and encloses every field', () => {
		const result: QueryResult = {
			cellset: [
				[cell('', 'ROW_HEADER_HEADER'), cell('a"b', 'COLUMN_HEADER')],
				[cell('x,y', 'ROW_HEADER'), cell('plain', 'DATA_CELL')]
			]
		};
		const rows = cellsetToCsv(result).trimEnd().split('\r\n');
		expect(rows[0]).toBe('"","a""b"');
		expect(rows[1]).toBe('"x,y","plain"');
	});

	it('renders empty and literal-null cells as empty fields', () => {
		const result: QueryResult = {
			cellset: [[cell('null', 'ROW_HEADER'), cell('', 'DATA_CELL'), cell('7', 'DATA_CELL')]]
		};
		expect(cellsetToCsv(result)).toBe('"","","7"\r\n');
	});

	it('neutralises formula injection in headers and data', () => {
		const result: QueryResult = {
			cellset: [
				[cell('', 'ROW_HEADER_HEADER'), cell('=cmd|/C calc', 'COLUMN_HEADER')],
				[cell('@SUM(A1)', 'ROW_HEADER'), cell('=WEBSERVICE("http://x")', 'DATA_CELL')]
			]
		};
		const rows = cellsetToCsv(result).trimEnd().split('\r\n');
		expect(rows[0]).toBe('"","\'=cmd|/C calc"');
		expect(rows[1]).toBe('"\'@SUM(A1)","\'=WEBSERVICE(""http://x"")"');
	});

	it('returns an empty string for an empty or missing result', () => {
		expect(cellsetToCsv({ cellset: [] })).toBe('');
		expect(cellsetToCsv(null)).toBe('');
		expect(cellsetToCsv(undefined)).toBe('');
	});

	it('honours a custom delimiter and enclosure', () => {
		expect(cellsetToCsv(foodmartResult(), { delimiter: ';', enclosing: "'" })).toBe(
			"'';'1997';'1998'\r\n'Mexico';'1,234.50';'-2,000.25'\r\n'Mexico';'987.00';'1,000.00'\r\n"
		);
	});
});

describe('neutralizeCsvFormula', () => {
	it('defangs = + - @ and leading TAB/CR', () => {
		expect(neutralizeCsvFormula('=1+1')).toBe("'=1+1");
		expect(neutralizeCsvFormula('+x')).toBe("'+x");
		// `+1` is a plain number server-side too, so it stays a number.
		expect(neutralizeCsvFormula('-x')).toBe("'-x");
		expect(neutralizeCsvFormula('@a')).toBe("'@a");
		expect(neutralizeCsvFormula('\t=x')).toBe("'\t=x");
		expect(neutralizeCsvFormula('\r=x')).toBe("'\r=x");
	});

	it('leaves plain numbers alone so measures stay numeric', () => {
		expect(neutralizeCsvFormula('-1,234.50')).toBe('-1,234.50');
		expect(neutralizeCsvFormula('1234')).toBe('1234');
		expect(neutralizeCsvFormula('$4,250.51')).toBe('$4,250.51');
		expect(neutralizeCsvFormula('42%')).toBe('42%');
		expect(neutralizeCsvFormula('2500 €')).toBe('2500 €');
	});

	it('passes through empty and non-risky values', () => {
		expect(neutralizeCsvFormula('')).toBe('');
		expect(neutralizeCsvFormula('USA')).toBe('USA');
	});
});

describe('isPlainNumber', () => {
	it('accepts signed / currency / grouped numbers and rejects text', () => {
		expect(isPlainNumber('1')).toBe(true);
		expect(isPlainNumber('1,234.5')).toBe(true);
		expect(isPlainNumber('-$99')).toBe(true);
		expect(isPlainNumber('-')).toBe(false);
		expect(isPlainNumber('1,23')).toBe(false);
		expect(isPlainNumber('12a')).toBe(false);
	});
});

describe('csvDownloadName', () => {
	it('names the file after the query', () => {
		expect(csvDownloadName('Sales by Year')).toBe('Sales by Year.csv');
	});

	it('strips path separators and reserved filename characters', () => {
		expect(csvDownloadName('a/b:c*d?e"f<g>h|i')).toBe('a-b-c-d-e-f-g-h-i.csv');
	});

	it('falls back to the server default name', () => {
		expect(csvDownloadName('')).toBe('saiku-export.csv');
		expect(csvDownloadName(null)).toBe('saiku-export.csv');
		expect(csvDownloadName('   ')).toBe('saiku-export.csv');
	});
});
