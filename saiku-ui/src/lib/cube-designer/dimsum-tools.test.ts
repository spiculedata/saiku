/**
 * Unit tests for the DimSum tool executors — the payoff of audit finding
 * #1040. Each executor is driven against a real {@link SchemaCanvasStore} and
 * we assert both the returned tool-result text AND the resulting store state.
 * Node-env vitest; no Svelte render harness needed (the store is a plain
 * runes class that works headless).
 */
import { describe, it, expect, vi } from 'vitest';
import { SchemaCanvasStore } from './state.svelte.js';
import type { SourceTableCandidate } from './types.js';
import {
	buildCanvasSummary,
	executeDimSumTool,
	reconcileMeasureGroupLinks,
	findTableByName,
	DIMSUM_MUTATION_TOOLS,
	type DimSumToolDeps
} from './dimsum-tools.js';

// ── fixtures ─────────────────────────────────────────────────────
function candidate(
	name: string,
	columns: string[],
	schema: string | null = 'public'
): SourceTableCandidate {
	return {
		schema,
		name,
		columns: columns.map((c) => ({ name: c, sqlType: 'INTEGER' })),
		onCanvas: false
	};
}

function makeStore(): SchemaCanvasStore {
	return new SchemaCanvasStore('conn-dimsum');
}

function noopDeps(): DimSumToolDeps {
	return { arrangeCanvas: vi.fn(async () => {}) };
}

/** Add a table to the canvas and return its id. */
function addCanvasTable(store: SchemaCanvasStore, name: string, columns: string[]): string {
	return store.addTable(candidate(name, columns), { x: 0, y: 0 }).id;
}

function parse(content: string): Record<string, unknown> {
	return JSON.parse(content) as Record<string, unknown>;
}

describe('DIMSUM_MUTATION_TOOLS', () => {
	it('contains the ten mutating tools and excludes read-only ones', () => {
		expect(DIMSUM_MUTATION_TOOLS.size).toBe(10);
		expect(DIMSUM_MUTATION_TOOLS.has('add_join')).toBe(true);
		expect(DIMSUM_MUTATION_TOOLS.has('add_measure')).toBe(true);
		expect(DIMSUM_MUTATION_TOOLS.has('list_tables')).toBe(false);
		expect(DIMSUM_MUTATION_TOOLS.has('arrange_canvas')).toBe(false);
	});
});

describe('executeDimSumTool — read-only tools', () => {
	it('list_tables splits on-canvas from available catalog tables', async () => {
		const store = makeStore();
		addCanvasTable(store, 'sales', ['id', 'amount']);
		store.sourceTables = [candidate('sales', ['id', 'amount']), candidate('product', ['id'])];

		const { content, isError } = await executeDimSumTool(store, 'list_tables', {}, noopDeps());

		expect(isError).toBe(false);
		const out = parse(content) as {
			onCanvas: Array<{ qualifiedName: string }>;
			available: Array<{ qualifiedName: string }>;
		};
		expect(out.onCanvas.map((t) => t.qualifiedName)).toEqual(['public.sales']);
		// `sales` is on canvas so it's filtered out of available.
		expect(out.available.map((t) => t.qualifiedName)).toEqual(['public.product']);
	});

	it('describe_table returns columns + neighbouring joins for a canvas table', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['id', 'product_id']);
		const productId = addCanvasTable(store, 'product', ['product_id', 'name']);
		store.addJoin({
			sourceTableId: salesId,
			sourceColumnName: 'product_id',
			targetTableId: productId,
			targetColumnName: 'product_id',
			kind: 'inner'
		});

		const { content, isError } = await executeDimSumTool(
			store,
			'describe_table',
			{ qualifiedName: 'public.sales' },
			noopDeps()
		);

		expect(isError).toBe(false);
		const out = parse(content) as {
			onCanvas: boolean;
			columns: Array<{ name: string }>;
			joinsInvolving: Array<{
				neighbor: string;
				thisColumn: string;
				neighborColumn: string;
			}>;
		};
		expect(out.onCanvas).toBe(true);
		expect(out.columns.map((c) => c.name)).toEqual(['id', 'product_id']);
		expect(out.joinsInvolving).toHaveLength(1);
		expect(out.joinsInvolving[0].neighbor).toBe('public.product');
	});

	it('describe_table errors on an unknown table', async () => {
		const store = makeStore();
		const { content, isError } = await executeDimSumTool(
			store,
			'describe_table',
			{ qualifiedName: 'nope' },
			noopDeps()
		);
		expect(isError).toBe(true);
		expect(parse(content).error).toContain('No table named');
	});

	it('list_dimensions reports the fact table, dims and measures', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['id', 'amount']);
		store.setTableRole(salesId, 'fact');
		store.addMeasure({
			tableId: salesId,
			columnName: 'amount',
			aggregator: 'sum'
		});

		const { content } = await executeDimSumTool(store, 'list_dimensions', {}, noopDeps());
		const out = parse(content) as {
			factTable: string;
			measures: Array<{ column: string }>;
		};
		expect(out.factTable).toBe('public.sales');
		expect(out.measures[0].column).toBe('amount');
	});
});

describe('executeDimSumTool — canvas mutations', () => {
	it('add_table_to_canvas pulls a catalog table onto the canvas', async () => {
		const store = makeStore();
		store.sourceTables = [candidate('product', ['product_id'])];

		const { content, isError } = await executeDimSumTool(
			store,
			'add_table_to_canvas',
			{ qualifiedName: 'public.product' },
			noopDeps()
		);

		expect(isError).toBe(false);
		expect(parse(content).added).toBe(true);
		expect(store.doc.tables).toHaveLength(1);
		expect(store.doc.tables[0].name).toBe('product');
	});

	it('add_table_to_canvas is idempotent when already present', async () => {
		const store = makeStore();
		addCanvasTable(store, 'product', ['product_id']);
		store.sourceTables = [candidate('product', ['product_id'])];

		const { content } = await executeDimSumTool(
			store,
			'add_table_to_canvas',
			{ qualifiedName: 'public.product' },
			noopDeps()
		);
		expect(parse(content).added).toBe(false);
		expect(parse(content).reason).toBe('already_on_canvas');
		expect(store.doc.tables).toHaveLength(1);
	});

	it('add_join wires two canvas columns and remove_join drops it', async () => {
		const store = makeStore();
		addCanvasTable(store, 'sales', ['product_id']);
		addCanvasTable(store, 'product', ['product_id']);

		const add = await executeDimSumTool(
			store,
			'add_join',
			{
				from: { table: 'sales', column: 'product_id' },
				to: { table: 'product', column: 'product_id' }
			},
			noopDeps()
		);
		expect(add.isError).toBe(false);
		expect(store.doc.joins).toHaveLength(1);

		const rm = await executeDimSumTool(
			store,
			'remove_join',
			{
				from: { table: 'sales', column: 'product_id' },
				to: { table: 'product', column: 'product_id' }
			},
			noopDeps()
		);
		expect(rm.isError).toBe(false);
		expect(store.doc.joins).toHaveLength(0);
	});

	it('add_join errors when a column is missing', async () => {
		const store = makeStore();
		addCanvasTable(store, 'sales', ['product_id']);
		addCanvasTable(store, 'product', ['product_id']);
		const { isError, content } = await executeDimSumTool(
			store,
			'add_join',
			{
				from: { table: 'sales', column: 'ghost' },
				to: { table: 'product', column: 'product_id' }
			},
			noopDeps()
		);
		expect(isError).toBe(true);
		expect(parse(content).reason).toContain('ghost');
		expect(store.doc.joins).toHaveLength(0);
	});

	it('remove_table_from_canvas removes the table and reports cascaded joins', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['product_id']);
		const productId = addCanvasTable(store, 'product', ['product_id']);
		store.addJoin({
			sourceTableId: salesId,
			sourceColumnName: 'product_id',
			targetTableId: productId,
			targetColumnName: 'product_id',
			kind: 'inner'
		});

		const { content } = await executeDimSumTool(
			store,
			'remove_table_from_canvas',
			{ qualifiedName: 'public.product' },
			noopDeps()
		);
		expect(parse(content).removed).toBe(true);
		expect(parse(content).joinsRemoved).toBe(1);
		expect(store.doc.tables).toHaveLength(1);
		expect(store.doc.joins).toHaveLength(0);
	});

	it('arrange_canvas delegates to the injected arrangeCanvas dep', async () => {
		const store = makeStore();
		addCanvasTable(store, 'sales', ['id']);
		const deps = noopDeps();

		const { content, isError } = await executeDimSumTool(
			store,
			'arrange_canvas',
			{ mode: 'star' },
			deps
		);
		expect(isError).toBe(false);
		expect(parse(content).arranged).toBe(true);
		expect(deps.arrangeCanvas).toHaveBeenCalledWith('star');
	});

	it('arrange_canvas short-circuits on an empty canvas without calling the dep', async () => {
		const store = makeStore();
		const deps = noopDeps();
		const { content } = await executeDimSumTool(store, 'arrange_canvas', {}, deps);
		expect(parse(content).arranged).toBe(false);
		expect(deps.arrangeCanvas).not.toHaveBeenCalled();
	});
});

describe('executeDimSumTool — perform_action', () => {
	it('zoom actions stamp requestedCanvasAction', async () => {
		const store = makeStore();
		const { content } = await executeDimSumTool(
			store,
			'perform_action',
			{ action: 'zoom_in' },
			noopDeps()
		);
		expect(parse(content).performed).toBe('zoom_in');
		expect(store.requestedCanvasAction?.kind).toBe('zoom_in');
	});

	it('undo_last_change no-ops when there is nothing to undo', async () => {
		const store = makeStore();
		const { content } = await executeDimSumTool(
			store,
			'perform_action',
			{ action: 'undo_last_change' },
			noopDeps()
		);
		expect(parse(content).performed).toBe(false);
	});

	it('center_view_on_table sets a jump target for a known table', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['id']);
		const { content } = await executeDimSumTool(
			store,
			'perform_action',
			{ action: 'center_view_on_table', qualifiedName: 'public.sales' },
			noopDeps()
		);
		expect(parse(content).performed).toBe('center_view_on_table');
		expect(store.requestedJumpTarget?.tableId).toBe(salesId);
	});

	it('unknown action id errors', async () => {
		const store = makeStore();
		const { isError, content } = await executeDimSumTool(
			store,
			'perform_action',
			{ action: 'nope' },
			noopDeps()
		);
		expect(isError).toBe(true);
		expect(parse(content).error).toContain('Unknown action id');
	});
});

describe('executeDimSumTool — logical layer (dims / measures)', () => {
	it('set_fact_table promotes a canvas table to the fact role', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['id', 'amount']);
		const { isError } = await executeDimSumTool(
			store,
			'set_fact_table',
			{ qualifiedName: 'public.sales' },
			noopDeps()
		);
		expect(isError).toBe(false);
		expect(findTableByName(store, 'public.sales')?.role).toBe('fact');
		expect(store.doc.tables.find((t) => t.id === salesId)?.role).toBe('fact');
	});

	it('create_dimension seeds the PK as an attribute and errors on duplicates', async () => {
		const store = makeStore();
		addCanvasTable(store, 'product', ['product_id', 'name']);

		const first = await executeDimSumTool(
			store,
			'create_dimension',
			{ table: 'product', name: 'Product', primaryKeyColumn: 'product_id' },
			noopDeps()
		);
		expect(first.isError).toBe(false);
		const dim = store.dimensions.find((d) => d.name === 'Product');
		expect(dim).toBeDefined();
		expect(dim?.primaryKey).toBe('product_id');
		expect(dim?.attributes?.some((a) => a.columnName === 'product_id')).toBe(true);

		const dup = await executeDimSumTool(
			store,
			'create_dimension',
			{ table: 'product', name: 'Product' },
			noopDeps()
		);
		expect(dup.isError).toBe(true);
		expect(parse(dup.content).reason).toContain('already exists');
	});

	it('create_dimension errors when the primary-key column is absent', async () => {
		const store = makeStore();
		addCanvasTable(store, 'product', ['product_id']);
		const { isError, content } = await executeDimSumTool(
			store,
			'create_dimension',
			{ table: 'product', name: 'Product', primaryKeyColumn: 'ghost' },
			noopDeps()
		);
		expect(isError).toBe(true);
		expect(parse(content).reason).toContain('ghost');
	});

	it('add_hierarchy then add_level builds a level backed by an attribute', async () => {
		const store = makeStore();
		addCanvasTable(store, 'product', ['product_id', 'category']);
		await executeDimSumTool(
			store,
			'create_dimension',
			{ table: 'product', name: 'Product', primaryKeyColumn: 'product_id' },
			noopDeps()
		);
		await executeDimSumTool(
			store,
			'add_hierarchy',
			{ dimension: 'Product', name: 'Products' },
			noopDeps()
		);
		const level = await executeDimSumTool(
			store,
			'add_level',
			{ dimension: 'Product', hierarchy: 'Products', column: 'category' },
			noopDeps()
		);
		expect(level.isError).toBe(false);
		const dim = store.dimensions.find((d) => d.name === 'Product')!;
		const hier = dim.hierarchies.find((h) => h.name === 'Products')!;
		expect(hier.levels.some((l) => l.columnName === 'category')).toBe(true);
		expect(dim.attributes?.some((a) => a.columnName === 'category')).toBe(true);
	});

	it('add_level preserves the coarse→fine order DimSum adds levels in', async () => {
		// DimSum is instructed (tool schema + system prompt) to add levels
		// coarsest-first. add_level appends, so the stored order must match the
		// call order — and with the pane no longer sorting alphabetically
		// (saiku-cloud#1174), that authored order is what the user sees.
		const store = makeStore();
		addCanvasTable(store, 'dim_date', [
			'date_key',
			'year',
			'quarter',
			'month_of_year',
			'day_of_month'
		]);
		await executeDimSumTool(
			store,
			'create_dimension',
			{
				table: 'dim_date',
				name: 'Time',
				primaryKeyColumn: 'date_key',
				type: 'Time'
			},
			noopDeps()
		);
		await executeDimSumTool(
			store,
			'add_hierarchy',
			{ dimension: 'Time', name: 'Calendar' },
			noopDeps()
		);
		// Add coarsest-first — the order the AI is told to use.
		for (const column of ['year', 'quarter', 'month_of_year', 'day_of_month']) {
			await executeDimSumTool(
				store,
				'add_level',
				{ dimension: 'Time', hierarchy: 'Calendar', column },
				noopDeps()
			);
		}
		const hier = store.dimensions
			.find((d) => d.name === 'Time')!
			.hierarchies.find((h) => h.name === 'Calendar')!;
		expect(hier.levels.map((l) => l.columnName)).toEqual([
			'year',
			'quarter',
			'month_of_year',
			'day_of_month'
		]);
	});

	it('add_measure requires a fact table', async () => {
		const store = makeStore();
		addCanvasTable(store, 'sales', ['amount']);
		const { isError, content } = await executeDimSumTool(
			store,
			'add_measure',
			{ column: 'amount', aggregator: 'sum' },
			noopDeps()
		);
		expect(isError).toBe(true);
		expect(parse(content).reason).toContain('No fact table');
	});

	it('add_measure folds the column into a measure group on the cube', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['amount', 'product_id']);
		store.setTableRole(salesId, 'fact');

		const { content, isError } = await executeDimSumTool(
			store,
			'add_measure',
			{ column: 'amount', aggregator: 'sum', name: 'Revenue' },
			noopDeps()
		);
		expect(isError).toBe(false);
		expect(parse(content).measureGroup).toBe('Measures');
		expect(store.cubes).toHaveLength(1);
		expect(store.cubes[0].measureGroups[0].measureColumns).toContain('amount');
	});

	it('add_measure carries the percentile fraction for a percentile aggregator', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['latency_ms']);
		store.setTableRole(salesId, 'fact');

		const { isError } = await executeDimSumTool(
			store,
			'add_measure',
			{
				column: 'latency_ms',
				aggregator: 'percentile',
				percentile: 90,
				name: 'P90 Latency'
			},
			noopDeps()
		);
		expect(isError).toBe(false);
		const m = store.measures.find((mm) => mm.name === 'P90 Latency');
		expect(m?.aggregator).toBe('percentile');
		expect(m?.percentile).toBe(90);
	});

	it('add_measure with a count aggregator and no column skips the group fold', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['amount']);
		store.setTableRole(salesId, 'fact');
		const { content, isError } = await executeDimSumTool(
			store,
			'add_measure',
			{ aggregator: 'count' },
			noopDeps()
		);
		expect(isError).toBe(false);
		expect(parse(content).measureGroup).toBeNull();
	});

	it('add_measure_group needs a fact and creates a group on the cube', async () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['amount']);
		store.setTableRole(salesId, 'fact');
		const { content, isError } = await executeDimSumTool(
			store,
			'add_measure_group',
			{ name: 'Sales facts' },
			noopDeps()
		);
		expect(isError).toBe(false);
		expect(parse(content).measureGroup).toBe('Sales facts');
		expect(store.cubes[0].measureGroups.some((g) => g.name === 'Sales facts')).toBe(true);
	});
});

describe('executeDimSumTool — unknown tool + throw safety', () => {
	it('returns an error for an unknown tool name', async () => {
		const store = makeStore();
		const { isError, content } = await executeDimSumTool(store, 'frobnicate', {}, noopDeps());
		expect(isError).toBe(true);
		expect(parse(content).error).toContain('Unknown tool');
	});
});

describe('reconcileMeasureGroupLinks', () => {
	it('links a dimension via its explicit foreignKey', () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['prod_fk', 'amount']);
		const productId = addCanvasTable(store, 'product', ['product_id']);
		store.setTableRole(salesId, 'fact');
		const dim = store.addDimension({ name: 'Product', tableId: productId });
		store.updateDimension(dim.id, {
			primaryKeyTableId: productId,
			foreignKey: 'prod_fk'
		});
		const cube = store.addCube({ name: 'Sales' });
		const mg = store.addMeasureGroup(cube.id, {
			name: 'Facts',
			factTableId: salesId
		})!;

		reconcileMeasureGroupLinks(store);

		const link = store.cubes[0].measureGroups
			.find((g) => g.id === mg.id)!
			.dimensionLinks?.find((l) => l.dimensionId === dim.id);
		expect(link?.foreignKeyColumn).toBe('prod_fk');
		expect(link?.linkKind).toBe('foreign-key');
	});

	it('links a dimension via an existing canvas join when no explicit FK is set', () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['product_id', 'amount']);
		const productId = addCanvasTable(store, 'product', ['product_id']);
		store.setTableRole(salesId, 'fact');
		store.addJoin({
			sourceTableId: salesId,
			sourceColumnName: 'product_id',
			targetTableId: productId,
			targetColumnName: 'product_id',
			kind: 'inner'
		});
		const dim = store.addDimension({ name: 'Product', tableId: productId });
		store.updateDimension(dim.id, { primaryKeyTableId: productId });
		const cube = store.addCube({ name: 'Sales' });
		store.addMeasureGroup(cube.id, { name: 'Facts', factTableId: salesId });

		reconcileMeasureGroupLinks(store);

		const link = store.cubes[0].measureGroups[0].dimensionLinks?.find(
			(l) => l.dimensionId === dim.id
		);
		expect(link?.foreignKeyColumn).toBe('product_id');
	});

	it('falls back to the star-schema convention (fact column matches the dim PK)', () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['product_id', 'amount']);
		const productId = addCanvasTable(store, 'product', ['product_id']);
		store.setTableRole(salesId, 'fact');
		const dim = store.addDimension({ name: 'Product', tableId: productId });
		// No foreignKey, no join — only the PK name matching a fact column.
		store.updateDimension(dim.id, {
			primaryKeyTableId: productId,
			primaryKey: 'product_id'
		});
		const cube = store.addCube({ name: 'Sales' });
		store.addMeasureGroup(cube.id, { name: 'Facts', factTableId: salesId });

		reconcileMeasureGroupLinks(store);

		const link = store.cubes[0].measureGroups[0].dimensionLinks?.find(
			(l) => l.dimensionId === dim.id
		);
		expect(link?.foreignKeyColumn).toBe('product_id');
		expect(link?.linkKind).toBe('foreign-key');
	});

	it('marks a degenerate dimension on the fact table as a fact link', () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['payment_type', 'amount']);
		store.setTableRole(salesId, 'fact');
		const dim = store.addDimension({ name: 'Payment', tableId: salesId });
		store.updateDimension(dim.id, {
			primaryKeyTableId: salesId,
			foreignKey: 'payment_type'
		});
		const cube = store.addCube({ name: 'Sales' });
		store.addMeasureGroup(cube.id, { name: 'Facts', factTableId: salesId });

		reconcileMeasureGroupLinks(store);

		const link = store.cubes[0].measureGroups[0].dimensionLinks?.find(
			(l) => l.dimensionId === dim.id
		);
		expect(link?.linkKind).toBe('fact');
	});
});

describe('buildCanvasSummary', () => {
	it('summarises tables and splits user-made from semantic joins', () => {
		const store = makeStore();
		const salesId = addCanvasTable(store, 'sales', ['product_id', 'amount']);
		const productId = addCanvasTable(store, 'product', ['product_id']);
		store.addJoin({
			sourceTableId: salesId,
			sourceColumnName: 'product_id',
			targetTableId: productId,
			targetColumnName: 'product_id',
			kind: 'inner',
			origin: 'physical'
		});
		store.addJoin({
			sourceTableId: salesId,
			sourceColumnName: 'amount',
			targetTableId: productId,
			targetColumnName: 'product_id',
			kind: 'inner',
			origin: 'cube-link'
		});

		const summary = buildCanvasSummary(store);
		expect(summary).toContain('ON-CANVAS TABLES (2):');
		expect(summary).toContain('public.sales: [product_id:INTEGER, amount:INTEGER]');
		expect(summary).toContain('USER-MADE JOINS (1):');
		expect(summary).toContain('SEMANTIC (cube-link, read-only) JOINS (1):');
	});

	it('reports empties cleanly on a blank canvas', () => {
		const summary = buildCanvasSummary(makeStore());
		expect(summary).toContain('ON-CANVAS TABLES (0):');
		expect(summary).toContain('  (none)');
	});
});
