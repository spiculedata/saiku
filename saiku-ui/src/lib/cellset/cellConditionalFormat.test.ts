import { describe, expect, it } from 'vitest';
import {
	CELL_CF_PROPERTY,
	formatDataCell,
	readCellConditionalFormat,
	withCellConditionalFormat
} from './cellConditionalFormat';
import type { CellEntry } from '$lib/api/query';

const rule = {
	column: 'Cantidad',
	type: 'background' as const,
	thresholdMode: 'absolute' as const,
	lowThreshold: 2,
	highThreshold: 5
};

describe('cell conditional format property', () => {
	it('round-trips rules on query properties', () => {
		const props = withCellConditionalFormat({ 'saiku.report.title': 'Ops' }, [rule]);
		expect(readCellConditionalFormat(props)).toEqual([rule]);
		expect(props['saiku.report.title']).toBe('Ops');
	});

	it('drops blank columns and clears the property when nothing remains', () => {
		const props = withCellConditionalFormat({ [CELL_CF_PROPERTY]: [rule] }, [
			{ ...rule, column: '  ' }
		]);
		expect(props[CELL_CF_PROPERTY]).toBeUndefined();
		expect(readCellConditionalFormat(props)).toEqual([]);
	});

	it('keeps per-band colours', () => {
		const colored = { ...rule, colors: { low: '#111111', high: '#22aa22' } };
		expect(readCellConditionalFormat(withCellConditionalFormat({}, [colored]))[0].colors).toEqual({
			low: '#111111',
			high: '#22aa22'
		});
	});

	it('accepts a JSON string payload', () => {
		expect(readCellConditionalFormat({ [CELL_CF_PROPERTY]: JSON.stringify([rule]) })).toEqual([
			rule
		]);
	});
});

describe('formatDataCell', () => {
	const cell = (value: string, raw?: string): CellEntry => ({
		value,
		type: 'DATA_CELL',
		properties: raw != null ? { raw } : undefined
	});

	it('bands by the raw number and keeps a Mondrian text colour', () => {
		const painted = formatDataCell([rule], 'Cantidad', cell('|1|style=red', '1'), [1, 3, 9]);
		expect(painted.display).toBe('1');
		expect(painted.style).toContain('color: red');
		expect(painted.style).toContain('background-color');
	});

	it('leaves an unruled column to the schema colour only', () => {
		const painted = formatDataCell([rule], 'Monto', cell('|9|style=green', '9'), [9]);
		expect(painted.style).toBe('color: green');
		expect(painted.display).toBe('9');
	});
});
