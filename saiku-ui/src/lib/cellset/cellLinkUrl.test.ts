import { describe, expect, it } from 'vitest';
import { buildCellLinkUrl, coordsAtIntersection, type CellLinkCoord } from './cellLinkUrl';
import { parseCellset } from '$lib/views/cellsetUtils';
import type { CellEntry } from '$lib/api/query';

const ops: CellLinkCoord[] = [
	{
		caption: 'Palermo',
		dimension: 'Barrio',
		hierarchy: '[Barrio]',
		level: '[Barrio].[Barrio]',
		uniqueName: '[Barrio].[Barrio].[Palermo]'
	},
	{
		caption: 'Venta',
		dimension: 'Tipo',
		hierarchy: '[Tipo]',
		uniqueName: '[Tipo].[Tipo].[Venta]'
	},
	{
		caption: 'Monto',
		dimension: 'Measures',
		hierarchy: '[Measures]',
		uniqueName: '[Measures].[Monto]'
	}
];

describe('buildCellLinkUrl', () => {
	it('substitutes dimension captions and encodes them', () => {
		expect(
			buildCellLinkUrl('https://erp.ejemplo/ops?barrio={Barrio}&tipo={Tipo}', ops)
		).toBe('https://erp.ejemplo/ops?barrio=Palermo&tipo=Venta');
	});

	it('substitutes uniqueName when asked', () => {
		expect(buildCellLinkUrl('https://erp.ejemplo/ops?u={Barrio.uniqueName}', ops)).toBe(
			'https://erp.ejemplo/ops?u=' + encodeURIComponent('[Barrio].[Barrio].[Palermo]')
		);
	});

	it('{Measures} uses the measure caption of that column', () => {
		expect(buildCellLinkUrl('https://erp.ejemplo/ops?m={Measures}', ops)).toBe(
			'https://erp.ejemplo/ops?m=Monto'
		);
	});

	it('unmatched placeholders become empty', () => {
		expect(buildCellLinkUrl('https://erp.ejemplo/ops?q={Missing}', ops)).toBe(
			'https://erp.ejemplo/ops?q='
		);
	});

	it('encodes spaces in captions', () => {
		expect(
			buildCellLinkUrl('https://x.test/?q={Barrio}', [
				{ caption: 'Puerto Madero', dimension: 'Barrio' }
			])
		).toBe('https://x.test/?q=Puerto%20Madero');
	});

	it('matches bracketed hierarchy names', () => {
		expect(
			buildCellLinkUrl('https://x.test/?b={Barrio}', [
				{ caption: 'Palermo', hierarchy: '[Barrio].[Barrio]' }
			])
		).toBe('https://x.test/?b=Palermo');
	});

	it('rejects javascript: and other non-http schemes', () => {
		expect(buildCellLinkUrl('javascript:alert(1)', ops)).toBeNull();
		expect(buildCellLinkUrl('data:text/html,hi', ops)).toBeNull();
		expect(buildCellLinkUrl('//evil.test/{Barrio}', ops)).toBeNull();
		expect(buildCellLinkUrl('', ops)).toBeNull();
		expect(buildCellLinkUrl(null, ops)).toBeNull();
	});

	it('accepts http as well as https', () => {
		expect(buildCellLinkUrl('http://intranet/ops?b={Barrio}', ops)).toBe(
			'http://intranet/ops?b=Palermo'
		);
	});

	it('matches uniqueName segments when dimension properties are missing', () => {
		expect(
			buildCellLinkUrl('https://x.test/?b={Barrio}&t={Tipo}', [
				{ caption: 'Palermo', uniqueName: '[Barrio].[Barrio].[Palermo]' },
				{ caption: 'Venta', uniqueName: '[Tipo].[Tipo].[Venta]' }
			])
		).toBe('https://x.test/?b=Palermo&t=Venta');
	});

	it('joins distinct captions when several coords match the same placeholder', () => {
		expect(
			buildCellLinkUrl('https://x.test/?b={Barrio}&t={Tipo}', [
				{ caption: 'Belgrano', dimension: 'Barrio' },
				{ caption: 'Casa', dimension: 'Tipo' },
				{ caption: 'Caballito', dimension: 'Barrio' },
				{ caption: 'Casa', dimension: 'Tipo' }
			])
		).toBe('https://x.test/?b=Belgrano,Caballito&t=Casa');
	});
});

describe('coordsAtIntersection', () => {
	it('collects row and column headers for the clicked cell', () => {
		const barrio = (value: string): CellEntry => ({
			value,
			type: 'ROW_HEADER',
			properties: {
				dimension: 'Barrio',
				hierarchy: '[Barrio]',
				uniquename: `[Barrio].[Barrio].[${value}]`
			}
		});
		const measure = (value: string): CellEntry => ({
			value,
			type: 'COLUMN_HEADER',
			properties: { dimension: 'Measures', hierarchy: '[Measures]', uniquename: `[Measures].[${value}]` }
		});
		const parsed = parseCellset({
			cellset: [
				[
					{ value: '', type: 'ROW_HEADER_HEADER' },
					measure('Monto')
				],
				[barrio('Palermo'), { value: '1', type: 'DATA_CELL' }]
			]
		});
		const coords = coordsAtIntersection(parsed, 0, 0);
		expect(coords.map((c) => c.caption)).toEqual(['Palermo', 'Monto']);
		expect(coords[0].dimension).toBe('Barrio');
		expect(coords[1].dimension).toBe('Measures');
	});

	it('fills column-header identity from the query model when Arrow sent captions only', () => {
		const parsed = parseCellset({
			cellset: [
				[
					{ value: '', type: 'ROW_HEADER_HEADER' },
					{ value: 'Venta', type: 'COLUMN_HEADER' }
				],
				[
					{
						value: 'Palermo',
						type: 'ROW_HEADER',
						properties: { dimension: 'Barrio', uniquename: '[Barrio].[Barrio].[Palermo]' }
					},
					{ value: '1', type: 'DATA_CELL' }
				]
			]
		});
		const coords = coordsAtIntersection(parsed, 0, 0, {
			axes: {
				FILTER: emptyAxis('FILTER'),
				PAGES: emptyAxis('PAGES'),
				ROWS: {
					...emptyAxis('ROWS'),
					hierarchies: [{ name: '[Barrio]', dimension: 'Barrio', levels: {}, cmembers: {} }]
				},
				COLUMNS: {
					...emptyAxis('COLUMNS'),
					hierarchies: [{ name: '[Tipo]', dimension: 'Tipo', levels: {}, cmembers: {} }]
				}
			},
			visualTotals: false,
			visualTotalsPattern: null,
			lowestLevelsOnly: false,
			details: { axis: 'COLUMNS', location: 'BOTTOM', measures: [] },
			calculatedMeasures: [],
			calculatedMembers: []
		});
		expect(buildCellLinkUrl('https://example.com/ops?barrio={Barrio}&tipo={Tipo}', coords)).toBe(
			'https://example.com/ops?barrio=Palermo&tipo=Venta'
		);
	});

	it('walks up blank parent row headers', () => {
		const parsed = twoRowOpsCellset();
		const coords = coordsAtIntersection(parsed, 1, 0);
		expect(buildCellLinkUrl('https://example.com/ops?barrio={Barrio}&tipo={Tipo}', coords)).toBe(
			'https://example.com/ops?barrio=Palermo&tipo=Depto'
		);
	});

	it('takes only the clicked intersection (Tipo × Operacion × Barrio)', () => {
		const parsed = deptoAlquilerPalermoCellset();
		// Depto/Alquiler is body row 2; Palermo is data column 2.
		const coords = coordsAtIntersection(parsed, 2, 2);
		expect(
			buildCellLinkUrl(
				'https://example.com/ops?barrio={Barrio}&tipo={Tipo}&operacion={Operacion}',
				coords
			)
		).toBe('https://example.com/ops?barrio=Palermo&tipo=Depto&operacion=Alquiler');
	});
});

function rh(
	dimension: string,
	value: string
): CellEntry {
	return {
		value,
		type: 'ROW_HEADER',
		properties: value
			? {
					dimension,
					hierarchy: `[${dimension}]`,
					uniquename: `[${dimension}].[${dimension}].[${value}]`
				}
			: { dimension, hierarchy: `[${dimension}]` }
	};
}

function ch(dimension: string, value: string): CellEntry {
	return {
		value,
		type: 'COLUMN_HEADER',
		properties: {
			dimension,
			hierarchy: dimension === 'Measures' ? '[Measures]' : `[${dimension}]`,
			uniquename:
				dimension === 'Measures'
					? `[Measures].[${value}]`
					: `[${dimension}].[${dimension}].[${value}]`
		}
	};
}

function emptyHdr(): CellEntry {
	return { value: '', type: 'ROW_HEADER_HEADER' };
}

function data(v: string): CellEntry {
	return { value: v, type: 'DATA_CELL' };
}

/** Nested rows Tipo+Operacion, columns Barrio+Cantidad — the user's Operaciones grid. */
function deptoAlquilerPalermoCellset() {
	const barrios = ['Belgrano', 'Caballito', 'Palermo', 'Recoleta'];
	const headerBarrios: CellEntry[] = [emptyHdr(), emptyHdr(), ...barrios.map((b) => ch('Barrio', b))];
	const headerMeasures: CellEntry[] = [
		emptyHdr(),
		emptyHdr(),
		...barrios.map(() => ch('Measures', 'Cantidad'))
	];
	return parseCellset({
		cellset: [
			headerBarrios,
			headerMeasures,
			[rh('Tipo', 'Casa'), rh('Operacion', 'Alquiler'), data(''), data(''), data('1'), data('')],
			[rh('Tipo', ''), rh('Operacion', 'Venta'), data('1'), data('1'), data(''), data('1')],
			[rh('Tipo', 'Depto'), rh('Operacion', 'Alquiler'), data(''), data('1'), data('1'), data('1')],
			[rh('Tipo', ''), rh('Operacion', 'Venta'), data('1'), data(''), data('2'), data('1')]
		]
	});
}

function twoRowOpsCellset() {
	const barrio = (value: string): CellEntry => ({
		value,
		type: 'ROW_HEADER',
		properties: value
			? {
					dimension: 'Barrio',
					hierarchy: '[Barrio]',
					uniquename: `[Barrio].[Barrio].[${value}]`
				}
			: { dimension: 'Barrio', hierarchy: '[Barrio]' }
	});
	const tipo = (value: string): CellEntry => ({
		value,
		type: 'ROW_HEADER',
		properties: {
			dimension: 'Tipo',
			hierarchy: '[Tipo]',
			uniquename: `[Tipo].[Tipo].[${value}]`
		}
	});
	const measure = (value: string): CellEntry => ({
		value,
		type: 'COLUMN_HEADER',
		properties: { dimension: 'Measures', hierarchy: '[Measures]', uniquename: `[Measures].[${value}]` }
	});
	return parseCellset({
		cellset: [
			[
				{ value: '', type: 'ROW_HEADER_HEADER' },
				{ value: '', type: 'ROW_HEADER_HEADER' },
				measure('Monto')
			],
			[barrio('Palermo'), tipo('Casa'), { value: '1', type: 'DATA_CELL' }],
			[barrio(''), tipo('Depto'), { value: '2', type: 'DATA_CELL' }],
			[barrio('Belgrano'), tipo('Casa'), { value: '3', type: 'DATA_CELL' }]
		]
	});
}

function emptyAxis(location: 'FILTER' | 'COLUMNS' | 'ROWS' | 'PAGES') {
	return {
		location,
		mdx: null,
		filters: [],
		sortOrder: null,
		sortEvaluationLiteral: null,
		hierarchizeMode: null,
		hierarchies: [] as { name: string; dimension: string; levels: Record<string, never>; cmembers: Record<string, string> }[],
		nonEmpty: false
	};
}
