/*
 * Round-trip test for the Arrow drillthrough adapter.
 *
 * Same strategy as arrow.test.ts: build a small Arrow IPC stream in-test
 * using apache-arrow, with the schema layout ArrowDrillthroughWriter emits
 * (numeric columns as Float64, string columns as Utf8, `saiku.drillthrough`
 * metadata blob), and assert parseArrowDrillthrough reconstructs the
 * QueryResult shape the DrillthroughResultModal expects.
 */

import { describe, expect, it } from 'vitest';
import { Schema, Table, tableToIPC, vectorFromArray } from 'apache-arrow';

import { parseArrowDrillthrough } from './arrow';

function buildFixture(): ArrayBuffer {
	const cells = {
		name: vectorFromArray(['Widget', 'Gadget', 'Sprocket']),
		qty: vectorFromArray(new Float64Array([3, 7, 12])),
		price: vectorFromArray(new Float64Array([9.99, 19.5, 5.0]))
	} as unknown as Record<string, any>;

	const table = new Table(cells);
	const metadata = new Map<string, string>();
	metadata.set(
		'saiku.drillthrough',
		JSON.stringify({
			captions: ['Product name', 'Quantity', 'Unit price'],
			rowCount: 3,
			runtimeMs: 17
		})
	);
	const schemaWithMeta = new Schema(table.schema.fields, metadata);
	const tableWithMeta = new Table(schemaWithMeta, table.batches);

	const ipc = tableToIPC(tableWithMeta, 'stream');
	const ab = new ArrayBuffer(ipc.byteLength);
	new Uint8Array(ab).set(ipc);
	return ab;
}

describe('parseArrowDrillthrough', () => {
	it('round-trips a drillthrough Arrow stream into the legacy QueryResult shape', async () => {
		const result = await parseArrowDrillthrough(buildFixture());

		// Shape pass-through
		expect(result.height).toBe(3);
		expect(result.width).toBe(3);
		expect(result.runtime).toBe(17);

		// 1 header row + 3 data rows = 4 total
		expect(result.cellset.length).toBe(4);

		// Header row uses captions, not raw field names.
		const header = result.cellset[0];
		expect(header.map((c) => c.value)).toEqual(['Product name', 'Quantity', 'Unit price']);
		expect(header.every((c) => c.type === 'COLUMN_HEADER')).toBe(true);

		// First data row: string col is plain, numerics are tagged numeric=true.
		const row1 = result.cellset[1];
		expect(row1[0]).toMatchObject({ type: 'DATA_CELL', value: 'Widget' });
		expect(row1[0].properties?.numeric).toBeUndefined();
		expect(row1[1]).toMatchObject({ type: 'DATA_CELL', value: '3' });
		expect(row1[1].properties?.numeric).toBe('true');
		expect(row1[2]).toMatchObject({ type: 'DATA_CELL', value: '9.99' });
		expect(row1[2].properties?.numeric).toBe('true');

		// Spot-check the rest of the body.
		expect(result.cellset[2][0].value).toBe('Gadget');
		expect(result.cellset[3][0].value).toBe('Sprocket');
		expect(result.cellset[2][2].value).toBe('19.5');
	});
});
