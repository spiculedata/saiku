/**
 * Canvas schema designer — ELK auto-layout helper.
 *
 * Wraps elkjs with the "Arrange" button's preferred config: hierarchical
 * layered layout, top-down (fact table sits at the top, dimensions fan
 * below), enough breathing room that wires don't cross awkwardly.
 *
 * The user can still drag nodes after — ELK gives them a starting
 * arrangement, not a constraint.
 */
import ELK from 'elkjs/lib/elk.bundled.js';
import type { SchemaCanvasState, SchemaCanvasTable } from './types.js';

const elk = new ELK();

interface LayoutOptions {
	/** Default node width if we don't know better. Each TableNode is roughly this wide. */
	nodeWidth?: number;
	/** Per-row height inside a TableNode — used to compute total node height. */
	rowHeight?: number;
	/** Fixed node header height. */
	headerHeight?: number;
	/**
	 * Cap the visible column count when estimating a table's rendered
	 * height. Pass `store.defaultColumnsShown`. Without this the
	 * estimator assumed every column was visible, which over-inflated
	 * the ring (and any other height-aware) layout by ~5×.
	 */
	visibleColumnsCap?: number;
	/** Available canvas viewport in CSS px, when known. Used by the
	 *  grid layout to size its column count to whatever the user can
	 *  actually see, so the result fits without horizontal panning. */
	canvasWidth?: number;
	canvasHeight?: number;
}

const DEFAULTS: Required<LayoutOptions> = {
	nodeWidth: 240,
	rowHeight: 22,
	headerHeight: 48,
	visibleColumnsCap: Number.POSITIVE_INFINITY,
	canvasWidth: 0,
	canvasHeight: 0
};

export function estimateNodeHeight(table: SchemaCanvasTable, opts: LayoutOptions = {}): number {
	const { headerHeight, rowHeight } = { ...DEFAULTS, ...opts };
	const cap = opts.visibleColumnsCap ?? Number.POSITIVE_INFINITY;
	// Visible-row count mirrors what TableNode actually renders:
	//   collapsed=true  → header only (0 rows)
	//   collapsed=false → every column
	//   undefined       → min(cap, columns.length)
	let visible: number;
	if (table.collapsed === true) {
		visible = 0;
	} else if (table.collapsed === false) {
		visible = table.columns.length;
	} else {
		visible = Math.min(cap, table.columns.length);
	}
	return headerHeight + Math.max(1, visible) * rowHeight + 12;
}

/**
 * Layout modes the user can pick from the canvas toolbar.
 *  - layered: ELK top-down hierarchical (good for snowflake / chained dims)
 *  - star: fact at centre, dimensions in a ring around it (classic star schema)
 *  - grid: even rows of N tables, alphabetical sort (clean reset)
 *  - byJoins: tables most-connected to the fact first, less-connected radiating out
 */
export type LayoutMode = 'layered' | 'star' | 'grid' | 'byJoins';

/**
 * Dispatcher — apply whichever layout the user picked.
 */
export async function applyLayout(
	state: SchemaCanvasState,
	mode: LayoutMode,
	opts: LayoutOptions = {}
): Promise<SchemaCanvasState> {
	switch (mode) {
		case 'star':
			return starLayout(state, opts);
		case 'grid':
			return gridLayout(state, opts);
		case 'byJoins':
			return byJoinsLayout(state, opts);
		case 'layered':
		default:
			return applyAutoLayout(state, opts);
	}
}

/**
 * Apply ELK layered layout to the canvas. Returns a fresh state with new
 * `position` values on every table; doesn't mutate the input. This is the
 * historic "Arrange" behaviour — preserved as the default.
 */
export async function applyAutoLayout(
	state: SchemaCanvasState,
	opts: LayoutOptions = {}
): Promise<SchemaCanvasState> {
	const merged = { ...DEFAULTS, ...opts };
	const elkGraph = {
		id: 'root',
		layoutOptions: {
			'elk.algorithm': 'layered',
			'elk.direction': 'DOWN',
			'elk.spacing.nodeNode': '60',
			'elk.spacing.edgeNode': '24',
			'elk.spacing.componentComponent': '80',
			'elk.layered.spacing.nodeNodeBetweenLayers': '80',
			'elk.layered.spacing.edgeNodeBetweenLayers': '20',
			'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES'
		},
		children: state.tables.map((t) => ({
			id: t.id,
			width: merged.nodeWidth,
			height: estimateNodeHeight(t, merged)
		})),
		edges: state.joins.map((j) => ({
			id: j.id,
			sources: [j.sourceTableId],
			targets: [j.targetTableId]
		}))
	};
	const laid = await elk.layout(elkGraph as Parameters<typeof elk.layout>[0]);
	const positions = new Map<string, { x: number; y: number }>();
	for (const child of laid.children ?? []) {
		if (child.id) {
			positions.set(child.id, { x: child.x ?? 0, y: child.y ?? 0 });
		}
	}
	return {
		...state,
		tables: state.tables.map((t) => ({
			...t,
			position: positions.get(t.id) ?? t.position
		})),
		updatedAt: new Date().toISOString()
	};
}

/**
 * Star layout — fact at centre, dimensions arranged in a ring around it.
 * The radius grows with table count so big schemas don't collapse on
 * themselves. Dimensions are sorted alphabetically so the same schema
 * lays out the same way every time you hit Star.
 */
export function starLayout(state: SchemaCanvasState, opts: LayoutOptions = {}): SchemaCanvasState {
	const merged = { ...DEFAULTS, ...opts };
	const fact = state.tables.find((t) => t.role === 'fact');
	const others = state.tables
		.filter((t) => t.role !== 'fact')
		.slice()
		.sort((a, b) => a.name.localeCompare(b.name));
	// Adjacent-card chord requirement. Cards aren't rotated to face the
	// centre — they stay axis-aligned — so the limiting dimension is
	// the LARGER of width or height (not the diagonal). Using the
	// diagonal was over-spacing the ring. Floor at 320 so 1–2 tables
	// don't collapse onto each other.
	// Generous pad so adjacent cards never sit edge-to-edge — they used to
	// touch (gap == 0) which made overlapping-content (long names, hover
	// shadows) visually merge two tables. 48px of breathing room each way.
	const PAD = 48;
	const MAX_HEIGHT_FOR_RADIUS = 400;
	const rawMaxHeight = Math.max(...state.tables.map((t) => estimateNodeHeight(t, merged)));
	const heightForRadius = Math.min(MAX_HEIGHT_FOR_RADIUS, rawMaxHeight);
	const chordRequirement = Math.max(merged.nodeWidth + PAD, heightForRadius + PAD);
	const N = others.length;
	const minRadius = N >= 2 ? chordRequirement / (2 * Math.sin(Math.PI / N)) : 220;
	const radius = Math.max(220, minRadius);
	const centre = { x: radius + 80, y: radius + 80 };
	const positions = new Map<string, { x: number; y: number }>();
	if (fact) {
		const factH = estimateNodeHeight(fact, merged);
		positions.set(fact.id, {
			x: centre.x - merged.nodeWidth / 2,
			y: centre.y - factH / 2
		});
	}
	const n = Math.max(1, others.length);
	others.forEach((t, i) => {
		// -π/2 puts the first dim at the top of the ring.
		const angle = -Math.PI / 2 + (i / n) * Math.PI * 2;
		const h = estimateNodeHeight(t, merged);
		positions.set(t.id, {
			x: centre.x + Math.cos(angle) * radius - merged.nodeWidth / 2,
			y: centre.y + Math.sin(angle) * radius - h / 2
		});
	});
	return applyPositions(state, positions);
}

/**
 * Grid layout — fact in the top-left slot, everything else arranged in
 * even rows below it. Useful when the canvas has drifted and you just
 * want a clean reset.
 */
export function gridLayout(state: SchemaCanvasState, opts: LayoutOptions = {}): SchemaCanvasState {
	const merged = { ...DEFAULTS, ...opts };
	// Generous gaps so a join label landing at the midpoint between two
	// cards has room to read without sitting on either card. Not the
	// real obstacle-avoidance fix (queued), but it makes labels visually
	// "fit" between rows / columns instead of crowding them.
	const gapX = 160;
	const gapY = 160;
	// Cols default — when the caller passes a real canvasWidth, size the
	// grid to fit that width (minus a small padding budget for the
	// SvelteFlow chrome).  When not, fall back to a step-function on
	// table count so a stale caller still gets something reasonable.
	let cols: number;
	if (merged.canvasWidth > 0) {
		const usable = Math.max(merged.canvasWidth - 120, merged.nodeWidth);
		cols = Math.max(1, Math.floor((usable + gapX) / (merged.nodeWidth + gapX)));
		// Don't waste columns on a small set of tables — cap at table count.
		cols = Math.min(cols, Math.max(1, state.tables.length));
	} else {
		cols = state.tables.length <= 4 ? 2 : state.tables.length <= 9 ? 3 : 4;
	}
	const fact = state.tables.find((t) => t.role === 'fact');
	const rest = state.tables
		.filter((t) => t.id !== fact?.id)
		.slice()
		.sort((a, b) => a.name.localeCompare(b.name));
	const ordered = fact ? [fact, ...rest] : rest;
	const positions = new Map<string, { x: number; y: number }>();
	// Compute the tallest node in each row so the next row clears it.
	let rowTopY = 60;
	for (let row = 0; row * cols < ordered.length; row++) {
		const rowTables = ordered.slice(row * cols, row * cols + cols);
		const rowMaxH = rowTables.reduce((m, t) => Math.max(m, estimateNodeHeight(t, merged)), 0);
		rowTables.forEach((t, i) => {
			positions.set(t.id, {
				x: 60 + i * (merged.nodeWidth + gapX),
				y: rowTopY
			});
		});
		rowTopY += rowMaxH + gapY;
	}
	return applyPositions(state, positions);
}

/**
 * By-joins layout — concentric rings around the fact based on how many
 * hops away each table sits in the join graph. Fact at centre, direct
 * dims in the first ring, indirect (e.g. bridge → outer dim) in the next.
 * Falls back to star semantics when there are no joins yet.
 */
export function byJoinsLayout(
	state: SchemaCanvasState,
	opts: LayoutOptions = {}
): SchemaCanvasState {
	const fact = state.tables.find((t) => t.role === 'fact');
	if (!fact) return gridLayout(state, opts);

	// BFS from the fact across the join graph to assign a ring number.
	const adj = new Map<string, Set<string>>();
	for (const t of state.tables) adj.set(t.id, new Set());
	for (const j of state.joins) {
		adj.get(j.sourceTableId)?.add(j.targetTableId);
		adj.get(j.targetTableId)?.add(j.sourceTableId);
	}
	const ring = new Map<string, number>();
	ring.set(fact.id, 0);
	const queue = [fact.id];
	while (queue.length) {
		const cur = queue.shift()!;
		const d = ring.get(cur)!;
		for (const next of adj.get(cur) ?? []) {
			if (ring.has(next)) continue;
			ring.set(next, d + 1);
			queue.push(next);
		}
	}
	// Disconnected tables sit one ring further out than the deepest connected.
	const maxRing = [...ring.values()].reduce((m, r) => Math.max(m, r), 0);
	for (const t of state.tables) {
		if (!ring.has(t.id)) ring.set(t.id, maxRing + 1);
	}

	// Place each ring around the centre.
	const merged = { ...DEFAULTS, ...opts };
	const centre = { x: 600, y: 400 };
	const byRing = new Map<number, string[]>();
	for (const [id, r] of ring) {
		if (!byRing.has(r)) byRing.set(r, []);
		byRing.get(r)!.push(id);
	}
	const positions = new Map<string, { x: number; y: number }>();
	for (const [r, ids] of byRing) {
		ids.sort((a, b) => {
			const ta = state.tables.find((t) => t.id === a)!;
			const tb = state.tables.find((t) => t.id === b)!;
			return ta.name.localeCompare(tb.name);
		});
		if (r === 0) {
			const t = state.tables.find((tt) => tt.id === ids[0])!;
			const h = estimateNodeHeight(t, merged);
			positions.set(ids[0], {
				x: centre.x - merged.nodeWidth / 2,
				y: centre.y - h / 2
			});
			continue;
		}
		const radius = 220 + r * 200;
		const n = Math.max(1, ids.length);
		ids.forEach((id, i) => {
			const angle = -Math.PI / 2 + (i / n) * Math.PI * 2;
			const t = state.tables.find((tt) => tt.id === id)!;
			const h = estimateNodeHeight(t, merged);
			positions.set(id, {
				x: centre.x + Math.cos(angle) * radius - merged.nodeWidth / 2,
				y: centre.y + Math.sin(angle) * radius - h / 2
			});
		});
	}
	return applyPositions(state, positions);
}

function applyPositions(
	state: SchemaCanvasState,
	positions: Map<string, { x: number; y: number }>
): SchemaCanvasState {
	return {
		...state,
		tables: state.tables.map((t) => ({
			...t,
			position: positions.get(t.id) ?? t.position
		})),
		updatedAt: new Date().toISOString()
	};
}
