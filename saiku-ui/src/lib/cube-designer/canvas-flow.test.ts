/**
 * Unit tests for the SvelteFlow node/edge builders.
 */
import { describe, it, expect } from 'vitest';
import { SchemaCanvasStore } from './state.svelte.js';
import type { SourceTableCandidate } from './types.js';
import { buildFlowNodes, buildFlowEdges, type FlowFocus } from './canvas-flow.js';

function candidate(name: string, columns: string[]): SourceTableCandidate {
	return {
		schema: 'public',
		name,
		columns: columns.map((c) => ({ name: c, sqlType: 'INTEGER' })),
		onCanvas: false
	};
}

function addTable(store: SchemaCanvasStore, name: string, columns: string[], x = 0): string {
	return store.addTable(candidate(name, columns), { x, y: 0 }).id;
}

const NO_FOCUS: FlowFocus = {
	focusActive: false,
	focusedTableIds: new Set(),
	focusedColumnKeys: new Set(),
	connectedColumnKeys: new Set(),
	focusedJoinIds: new Set()
};

describe('buildFlowNodes', () => {
	it('maps each canvas table to a table node carrying its position + data', () => {
		const store = new SchemaCanvasStore('c1');
		const id = addTable(store, 'sales', ['id', 'amount'], 120);

		const nodes = buildFlowNodes(store, NO_FOCUS);

		expect(nodes).toHaveLength(1);
		expect(nodes[0].id).toBe(id);
		expect(nodes[0].type).toBe('table');
		expect(nodes[0].position).toEqual({ x: 120, y: 0 });
		expect((nodes[0].data as { table: { name: string } }).table.name).toBe('sales');
	});

	it('lifts the selected node to the front and fades unfocused peers', () => {
		const store = new SchemaCanvasStore('c2');
		const a = addTable(store, 'a', ['id']);
		const b = addTable(store, 'b', ['id']);
		store.selectedTableId = a;
		const focus: FlowFocus = { ...NO_FOCUS, focusActive: true, focusedTableIds: new Set([a]) };

		const nodes = buildFlowNodes(store, focus);
		const nodeA = nodes.find((n) => n.id === a)!;
		const nodeB = nodes.find((n) => n.id === b)!;

		expect(nodeA.zIndex).toBe(100);
		expect(nodeA.selected).toBe(true);
		expect((nodeB.data as { isFaded: boolean }).isFaded).toBe(true);
		expect((nodeA.data as { isFaded: boolean }).isFaded).toBe(false);
	});

	it('column-click callback pushes a pick in pick mode, else arms a join', () => {
		const store = new SchemaCanvasStore('c3');
		const id = addTable(store, 'sales', ['product_id']);
		store.pickModeActive = true;

		const node = buildFlowNodes(store, NO_FOCUS)[0];
		(node.data as { onColumnClick: (t: string, c: string) => void }).onColumnClick(
			id,
			'product_id'
		);

		expect(store.eShiftPicks).toEqual([{ tableId: id, columnName: 'product_id' }]);
	});
});

describe('SchemaCanvasStore.addTable dedupe (#1084)', () => {
	it('does not push a second node for the same schema+name identity', () => {
		// Arrange
		const store = new SchemaCanvasStore('dedupe-1');
		const first = store.addTable(candidate('customer', ['customer_id', 'fullname']), {
			x: 0,
			y: 0
		});

		// Act — re-add the same physical table (e.g. a re-drag / re-tool call).
		const second = store.addTable(candidate('customer', ['customer_id', 'fullname']), {
			x: 400,
			y: 200
		});

		// Assert — one node only, and the existing node is returned untouched.
		expect(store.doc.tables).toHaveLength(1);
		expect(second.id).toBe(first.id);
		expect(second.position).toEqual({ x: 0, y: 0 });
	});

	it('treats same name in different schemas as distinct tables', () => {
		// Arrange
		const store = new SchemaCanvasStore('dedupe-2');
		const publicOrders: SourceTableCandidate = {
			schema: 'public',
			name: 'orders',
			columns: [{ name: 'id', sqlType: 'INTEGER' }],
			onCanvas: false
		};
		const stagingOrders: SourceTableCandidate = {
			...publicOrders,
			schema: 'staging'
		};

		// Act
		store.addTable(publicOrders, { x: 0, y: 0 });
		store.addTable(stagingOrders, { x: 0, y: 0 });

		// Assert — schema is part of the identity, so both land.
		expect(store.doc.tables).toHaveLength(2);
	});
});

describe('buildFlowEdges', () => {
	it('returns [] when join lines are hidden', () => {
		const store = new SchemaCanvasStore('e1');
		const a = addTable(store, 'a', ['id']);
		const b = addTable(store, 'b', ['id']);
		store.addJoin({
			sourceTableId: a,
			sourceColumnName: 'id',
			targetTableId: b,
			targetColumnName: 'id',
			kind: 'inner'
		});
		store.joinsHidden = true;
		expect(buildFlowEdges(store, NO_FOCUS)).toEqual([]);
	});

	it('aggregates multiple joins between the same pair into one edge', () => {
		const store = new SchemaCanvasStore('e2');
		const fact = addTable(store, 'fact', ['order_date', 'ship_date'], 0);
		const date = addTable(store, 'date', ['d'], 400);
		store.addJoin({
			sourceTableId: fact,
			sourceColumnName: 'order_date',
			targetTableId: date,
			targetColumnName: 'd',
			kind: 'inner'
		});
		store.addJoin({
			sourceTableId: fact,
			sourceColumnName: 'ship_date',
			targetTableId: date,
			targetColumnName: 'd',
			kind: 'inner'
		});

		const edges = buildFlowEdges(store, NO_FOCUS);
		expect(edges).toHaveLength(1);
		expect((edges[0].data as { joins: unknown[] }).joins).toHaveLength(2);
		// fact is left of date → exit right, enter left.
		expect(edges[0].sourceHandle).toContain(':out-right');
		expect(edges[0].targetHandle).toContain(':in-left');
	});

	it('hides cube-link joins unless showCubeLinks is on, then styles them dashed', () => {
		const store = new SchemaCanvasStore('e3');
		const a = addTable(store, 'a', ['id']);
		const b = addTable(store, 'b', ['id']);
		store.addJoin({
			sourceTableId: a,
			sourceColumnName: 'id',
			targetTableId: b,
			targetColumnName: 'id',
			kind: 'inner',
			origin: 'cube-link'
		});

		expect(buildFlowEdges(store, NO_FOCUS)).toHaveLength(0);
		store.showCubeLinks = true;
		const edges = buildFlowEdges(store, NO_FOCUS);
		expect(edges).toHaveLength(1);
		expect(edges[0].style).toContain('stroke-dasharray');
		expect((edges[0].data as { readOnly: boolean }).readOnly).toBe(true);
	});
});
