import { describe, expect, it } from 'vitest';
import { roundCellDisplay } from './decimalFormat';

describe('roundCellDisplay', () => {
	it('returns the input untouched in auto mode', () => {
		expect(roundCellDisplay('$2,561.57', null)).toBe('$2,561.57');
		expect(roundCellDisplay('$2,561.57', undefined)).toBe('$2,561.57');
	});

	it('drops the cents at 0 decimals but keeps the currency prefix', () => {
		expect(roundCellDisplay('$2,561.57', 0)).toBe('$2,562');
		expect(roundCellDisplay('2,561.57', 0)).toBe('2,562');
	});

	it('rounds to the requested precision', () => {
		expect(roundCellDisplay('1.004', 2)).toBe('1.00');
		expect(roundCellDisplay('1.006', 2)).toBe('1.01');
		expect(roundCellDisplay('2.5', 0)).toBe('3');
		expect(roundCellDisplay('3.5', 0)).toBe('4');
		expect(roundCellDisplay('-2.5', 0)).toBe('-3');
	});

	it('truncates display precision when fewer decimals are asked for', () => {
		expect(roundCellDisplay('$4,250.519', 1)).toBe('$4,250.5');
		expect(roundCellDisplay('12.34', 0)).toBe('12');
	});

	it('preserves the server grouping style', () => {
		expect(roundCellDisplay('1,234,567.891', 0)).toBe('1,234,568');
		expect(roundCellDisplay('1.234.567,891', 0)).toBe('1.234.568');
		expect(roundCellDisplay('1.234.567,891', 2)).toBe('1.234.567,89');
	});

	it('does not add grouping where the server had none', () => {
		expect(roundCellDisplay('1234.567', 0)).toBe('1235');
		expect(roundCellDisplay('1234.5', 2)).toBe('1234.50');
	});

	it('keeps affixes: percent, currency suffix, spacing', () => {
		expect(roundCellDisplay('12.345%', 0)).toBe('12%');
		expect(roundCellDisplay('2.561,57 €', 0)).toBe('2.562 €');
		expect(roundCellDisplay('€ 1,234.56', 0)).toBe('€ 1,235');
	});

	it('preserves the accounting parenthesis form for negatives', () => {
		expect(roundCellDisplay('($2,561.57)', 0)).toBe('($2,562)');
		expect(roundCellDisplay('-$1.50', 0)).toBe('-$2');
	});

	it('keeps the sign when a small negative rounds to zero', () => {
		expect(roundCellDisplay('-$0.004', 2)).toBe('-$0.00');
		expect(roundCellDisplay('($0.004)', 2)).toBe('($0.00)');
	});

	it('leaves non-numeric text alone', () => {
		expect(roundCellDisplay('Q1 revenue', 0)).toBe('Q1 revenue');
		expect(roundCellDisplay('', 0)).toBe('');
		expect(roundCellDisplay('All Products', 2)).toBe('All Products');
		expect(roundCellDisplay(null, 2)).toBe('');
	});

	it('clamps the requested precision into the 0–20 range', () => {
		expect(roundCellDisplay('1.239', -5)).toBe('1');
		expect(roundCellDisplay('1', 99)).toBe('1.00000000000000000000');
	});
});
