import { describe, expect, it } from 'vitest';
import {
	recordsToGraph,
	validateGraphConfig,
	weightRange,
	nodeSize,
	NODE_SIZE_MIN,
	NODE_SIZE_MAX,
	NODE_SIZE_DEFAULT,
	graphLayoutBox,
	graphLabelExtent,
	type GraphConfig
} from './graphTile';

const EDGE_CONFIG: GraphConfig = {
	idCol: 'source',
	sourceCol: 'source',
	targetCol: 'target'
};

describe('recordsToGraph — nodes', () => {
	it('dedups nodes appearing as both source and target across rows', () => {
		// B is a target in row 1 and a source in row 2 → ONE node.
		const records = [
			{ source: 'A', target: 'B' },
			{ source: 'B', target: 'C' }
		];
		const { nodes } = recordsToGraph(records, EDGE_CONFIG);
		const ids = nodes.map((n) => n.id).sort();
		expect(ids).toEqual(['A', 'B', 'C']);
		expect(nodes).toHaveLength(3);
	});

	it("defaults a node's name to its id when no labelCol is configured", () => {
		const { nodes } = recordsToGraph([{ source: 'A', target: 'B' }], EDGE_CONFIG);
		const a = nodes.find((n) => n.id === 'A');
		expect(a?.name).toBe('A');
	});

	it('attaches a labelCol name to the node identified by idCol', () => {
		const records = [{ id: 'A', label: 'Acme Corp', source: 'A', target: 'B' }];
		const { nodes } = recordsToGraph(records, {
			idCol: 'id',
			labelCol: 'label',
			sourceCol: 'source',
			targetCol: 'target'
		});
		const a = nodes.find((n) => n.id === 'A');
		expect(a?.name).toBe('Acme Corp');
	});
});

describe('recordsToGraph — links', () => {
	it('maps each row to a source → target link', () => {
		const records = [
			{ source: 'A', target: 'B' },
			{ source: 'A', target: 'C' }
		];
		const { links } = recordsToGraph(records, EDGE_CONFIG);
		expect(links).toEqual([
			{ source: 'A', target: 'B' },
			{ source: 'A', target: 'C' }
		]);
	});

	it('skips rows missing a source or target endpoint', () => {
		const records = [
			{ source: 'A', target: 'B' },
			{ source: '', target: 'C' }, // missing source
			{ source: 'D', target: '' }, // missing target
			{ source: 'E' }, // no target key at all
			{ source: 'F', target: 'G' }
		];
		const { links, nodes } = recordsToGraph(records, EDGE_CONFIG);
		expect(links).toEqual([
			{ source: 'A', target: 'B' },
			{ source: 'F', target: 'G' }
		]);
		// Skipped rows contribute no nodes either.
		expect(nodes.map((n) => n.id).sort()).toEqual(['A', 'B', 'F', 'G']);
	});
});

describe('recordsToGraph — valueCol', () => {
	it('carries a numeric measure cell value onto links', () => {
		const records = [
			{ source: 'A', target: 'B', weight: { value: 42, formatted: '42' } },
			{ source: 'A', target: 'C', weight: { value: 7, formatted: '7' } }
		];
		const config: GraphConfig = { ...EDGE_CONFIG, valueCol: 'weight' };
		const { links } = recordsToGraph(records, config);
		expect(links[0].value).toBe(42);
		expect(links[1].value).toBe(7);
	});

	it('sums carried values into node weight on both endpoints', () => {
		const records = [
			{ source: 'A', target: 'B', weight: { value: 10, formatted: '10' } },
			{ source: 'A', target: 'C', weight: { value: 5, formatted: '5' } }
		];
		const config: GraphConfig = { ...EDGE_CONFIG, valueCol: 'weight' };
		const { nodes } = recordsToGraph(records, config);
		expect(nodes.find((n) => n.id === 'A')?.value).toBe(15);
		expect(nodes.find((n) => n.id === 'B')?.value).toBe(10);
		expect(nodes.find((n) => n.id === 'C')?.value).toBe(5);
	});

	it('leaves link.value unset when the value cell is non-numeric', () => {
		const records = [{ source: 'A', target: 'B', weight: { value: null, formatted: '-' } }];
		const config: GraphConfig = { ...EDGE_CONFIG, valueCol: 'weight' };
		const { links } = recordsToGraph(records, config);
		expect(links[0].value).toBeUndefined();
	});
});

describe('recordsToGraph — malformed / empty input', () => {
	it('returns an empty graph for an empty array', () => {
		expect(recordsToGraph([], EDGE_CONFIG)).toEqual({ nodes: [], links: [] });
	});

	it('returns an empty graph for a non-array input', () => {
		expect(recordsToGraph(null, EDGE_CONFIG)).toEqual({ nodes: [], links: [] });
		expect(recordsToGraph(undefined, EDGE_CONFIG)).toEqual({
			nodes: [],
			links: []
		});
		expect(recordsToGraph({ source: 'A' } as unknown, EDGE_CONFIG)).toEqual({
			nodes: [],
			links: []
		});
	});

	it('tolerates malformed rows mixed with valid ones', () => {
		const records = [null, 'nope', 42, { source: 'A', target: 'B' }];
		const { nodes, links } = recordsToGraph(records, EDGE_CONFIG);
		expect(links).toEqual([{ source: 'A', target: 'B' }]);
		expect(nodes.map((n) => n.id).sort()).toEqual(['A', 'B']);
	});
});

describe('validateGraphConfig — accept', () => {
	it('accepts the minimal required column mapping and defaults layout to force', () => {
		const r = validateGraphConfig({
			idCol: 'src',
			sourceCol: 'src',
			targetCol: 'tgt'
		});
		expect(r.ok).toBe(true);
		if (r.ok) {
			expect(r.value).toEqual({
				idCol: 'src',
				sourceCol: 'src',
				targetCol: 'tgt',
				layout: 'force'
			});
		}
	});

	it('carries optional labelCol / valueCol and a circular layout, trimming whitespace', () => {
		const r = validateGraphConfig({
			idCol: ' src ',
			sourceCol: 'src',
			targetCol: 'tgt',
			labelCol: ' name ',
			valueCol: 'amount',
			layout: 'circular'
		});
		expect(r.ok).toBe(true);
		if (r.ok) {
			expect(r.value).toEqual({
				idCol: 'src',
				sourceCol: 'src',
				targetCol: 'tgt',
				labelCol: 'name',
				valueCol: 'amount',
				layout: 'circular'
			});
		}
	});
});

describe('validateGraphConfig — reject', () => {
	it('rejects a non-object', () => {
		expect(validateGraphConfig(null).ok).toBe(false);
		expect(validateGraphConfig('x').ok).toBe(false);
		expect(validateGraphConfig([]).ok).toBe(false);
	});

	it('rejects a missing or empty required column', () => {
		expect(validateGraphConfig({ sourceCol: 's', targetCol: 't' }).ok).toBe(false);
		expect(validateGraphConfig({ idCol: '', sourceCol: 's', targetCol: 't' }).ok).toBe(false);
		expect(validateGraphConfig({ idCol: 'i', sourceCol: '  ', targetCol: 't' }).ok).toBe(false);
		expect(validateGraphConfig({ idCol: 'i', sourceCol: 's' }).ok).toBe(false);
	});

	it('rejects a non-string optional column', () => {
		const r = validateGraphConfig({
			idCol: 'i',
			sourceCol: 's',
			targetCol: 't',
			valueCol: 42
		});
		expect(r.ok).toBe(false);
	});
});

/*
 * Node symbol sizing (saiku#1755). The original scale was absolute —
 * `min(60, 20 + sqrt(value))` — which saturates at value > 1600, so every node
 * in a graph weighted by any real measure (revenue, volume) rendered at the
 * cap and the weighting conveyed nothing. Sizing is now relative to the
 * weights actually present.
 */
describe('node sizing (saiku#1755)', () => {
	it('returns null range when no node carries a weight', () => {
		expect(weightRange([{ id: 'a', name: 'A' }])).toBeNull();
	});

	it('spans the full size band across a currency-scale spread', () => {
		const nodes = [
			{ id: 's', name: 'State', value: 5_304_323 },
			{ id: 'r', name: 'Region', value: 21_009_113 }
		];
		const range = weightRange(nodes);
		expect(nodeSize(5_304_323, range)).toBe(NODE_SIZE_MIN);
		expect(nodeSize(21_009_113, range)).toBe(NODE_SIZE_MAX);
	});

	it('is monotonic in value', () => {
		const range = { min: 0, max: 1_000_000 };
		const sizes = [0, 1_000, 100_000, 500_000, 1_000_000].map((v) => nodeSize(v, range));
		expect(sizes[0]).toBe(NODE_SIZE_MIN); // a zero weight is the lightest node, not "unweighted"
		for (let i = 1; i < sizes.length; i++) expect(sizes[i]).toBeGreaterThanOrEqual(sizes[i - 1]);
	});

	it('keeps every node inside the band', () => {
		const range = { min: 10, max: 20 };
		for (const v of [-5, 0, 10, 15, 20, 1e12]) {
			const s = nodeSize(v, range);
			expect(s).toBeGreaterThanOrEqual(NODE_SIZE_MIN);
			expect(s).toBeLessThanOrEqual(NODE_SIZE_MAX);
		}
	});

	it('gives equal-weight nodes one consistent size', () => {
		const range = weightRange([
			{ id: 'a', name: 'A', value: 500 },
			{ id: 'b', name: 'B', value: 500 }
		]);
		expect(nodeSize(500, range)).toBe(nodeSize(500, range));
	});

	it('falls back to the default only when there is no usable weight', () => {
		const range = { min: 1, max: 100 };
		expect(nodeSize(undefined, range)).toBe(NODE_SIZE_DEFAULT);
		expect(nodeSize(Number.NaN, range)).toBe(NODE_SIZE_DEFAULT);
		expect(nodeSize(50, null)).toBe(NODE_SIZE_DEFAULT);
	});

	it('sizes negative weights (a loss is a weight, not a missing value)', () => {
		const range = weightRange([
			{ id: 'a', name: 'A', value: -500 },
			{ id: 'b', name: 'B', value: 1_500 }
		]);
		expect(range).toEqual({ min: -500, max: 1_500 });
		expect(nodeSize(-500, range)).toBe(NODE_SIZE_MIN);
		expect(nodeSize(1_500, range)).toBe(NODE_SIZE_MAX);
	});
});

/*
 * saiku#1793 — node labels are drawn outside the node (position: "right", and
 * rotated outward from the ring under `circular`). With no layout box the ring
 * filled the container and every outer label was clipped at the tile edge.
 */
describe('graphLayoutBox', () => {
	it('insets on all four sides for both layouts', () => {
		for (const layout of ['force', 'circular'] as const) {
			const box = graphLayoutBox(layout);
			for (const side of ['left', 'right', 'top', 'bottom'] as const) {
				const pct = Number.parseFloat(box[side]);
				expect(pct, `${layout}.${side} should reserve label room`).toBeGreaterThan(0);
				// Sanity ceiling: past this the plot area is mostly gutter.
				expect(pct, `${layout}.${side} inset is implausibly large`).toBeLessThan(30);
			}
		}
	});

	it('gives a circular layout more room than a force layout', () => {
		// Circular labels radiate in every direction; force keeps nodes central.
		const circular = graphLayoutBox('circular');
		const force = graphLayoutBox('force');
		for (const side of ['left', 'right', 'top', 'bottom'] as const) {
			expect(Number.parseFloat(circular[side])).toBeGreaterThan(Number.parseFloat(force[side]));
		}
	});

	it('defaults to the force box when no layout is set', () => {
		expect(graphLayoutBox(undefined)).toEqual(graphLayoutBox('force'));
	});
});

describe('graphLabelExtent (saiku#1793)', () => {
	it('caps the label so an over-long name ellipsizes inside the tile', () => {
		const extent = graphLabelExtent();
		expect(extent.overflow).toBe('truncate');
		expect(extent.width).toBeGreaterThan(0);
		// Wide enough to carry a typical member name, narrow enough that a rotated
		// label still lands inside the gutter graphLayoutBox reserves.
		expect(extent.width).toBeGreaterThanOrEqual(64);
		expect(extent.width).toBeLessThanOrEqual(160);
	});
});
