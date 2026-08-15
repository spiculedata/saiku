/*
 * Unit tests for the pure AI dashboard assembler. No store, no network.
 */
import { describe, expect, it } from 'vitest';
import { assembleDashboard } from './aiDashboardAssembler';
import type { DashboardSpec } from '$lib/api/aiDashboard';
import type { CubeRef, DashboardTile } from '$lib/api/dashboards';

const CUBE: CubeRef = {
	connectionName: 'foodmart',
	catalog: 'FoodMart',
	schema: 'FoodMart',
	cubeName: 'Sales'
};

/** Distinct AiQueryRequest-shaped bodies so we can assert verbatim wrapping. */
function q(tag: string): Record<string, unknown> {
	return { cube: { cubeName: 'Sales' }, measures: [{ name: tag }], _tag: tag };
}

function specWithTiles(tiles: DashboardSpec['tiles'], title = 'My Dashboard'): DashboardSpec {
	return { title, degraded: false, reason: '', tiles };
}

/** True if two tile rectangles overlap (shared area, edges excluded). */
function overlaps(a: DashboardTile, b: DashboardTile): boolean {
	return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

describe('assembleDashboard', () => {
	it('produces one tile per spec tile with non-overlapping geometry', () => {
		const spec = specWithTiles([
			{ title: 'A', type: 'chart', chartType: 'bar', query: q('a') },
			{ title: 'B', type: 'table', query: q('b') },
			{ title: 'C', type: 'kpi', query: q('c') },
			{ title: 'D', type: 'chart', chartType: 'line', query: q('d') }
		]);

		const dash = assembleDashboard(spec, CUBE);
		const tiles = dash.layout.tiles;

		expect(tiles).toHaveLength(4);
		// Pairwise: no two tiles overlap, and no two share an identical footprint.
		for (let i = 0; i < tiles.length; i++) {
			for (let j = i + 1; j < tiles.length; j++) {
				expect(overlaps(tiles[i], tiles[j])).toBe(false);
				const sameFootprint =
					tiles[i].x === tiles[j].x &&
					tiles[i].y === tiles[j].y &&
					tiles[i].w === tiles[j].w &&
					tiles[i].h === tiles[j].h;
				expect(sameFootprint).toBe(false);
			}
		}
		// Every tile has a fresh, unique id.
		const ids = new Set(tiles.map((t) => t.id));
		expect(ids.size).toBe(4);
	});

	it('wraps each tile query verbatim as an inline query', () => {
		const bodyA = q('a');
		const bodyB = q('b');
		const spec = specWithTiles([
			{ title: 'A', type: 'chart', chartType: 'pie', query: bodyA },
			{ title: 'B', type: 'table', query: bodyB }
		]);

		const dash = assembleDashboard(spec, CUBE);

		expect(dash.layout.tiles[0].query).toEqual({ kind: 'inline', body: bodyA });
		expect(dash.layout.tiles[1].query).toEqual({ kind: 'inline', body: bodyB });
		// Verbatim — the exact AiQueryRequest object is threaded through unchanged.
		expect((dash.layout.tiles[0].query as { body: unknown }).body).toBe(bodyA);
	});

	it('keeps chartType only on chart tiles and passes valid values through', () => {
		const spec = specWithTiles([
			{ title: 'chart', type: 'chart', chartType: 'area', query: q('a') },
			{ title: 'table', type: 'table', chartType: 'bar', query: q('b') },
			{ title: 'kpi', type: 'kpi', query: q('c') }
		]);

		const dash = assembleDashboard(spec, CUBE);
		const [chart, table, kpi] = dash.layout.tiles;

		expect(chart.type).toBe('chart');
		expect(chart.chartType).toBe('area');
		// chartType is dropped for non-chart tiles even if the spec carried one.
		expect(table.type).toBe('table');
		expect(table.chartType).toBeUndefined();
		expect(kpi.type).toBe('kpi');
		expect(kpi.chartType).toBeUndefined();
	});

	it('defaults a chart tile with a missing / unrecognised chartType to bar', () => {
		const spec = specWithTiles([
			{ title: 'no-chart-type', type: 'chart', query: q('a') },
			// An out-of-set value (shouldn't happen per the contract, but defended).
			{ title: 'bad', type: 'chart', chartType: 'wobble' as never, query: q('b') }
		]);

		const dash = assembleDashboard(spec, CUBE);

		expect(dash.layout.tiles[0].chartType).toBe('bar');
		expect(dash.layout.tiles[1].chartType).toBe('bar');
	});

	it('coerces an out-of-set tile type to table', () => {
		const spec = specWithTiles([{ title: 'weird', type: 'gauge' as never, query: q('a') }]);

		const dash = assembleDashboard(spec, CUBE);

		expect(dash.layout.tiles[0].type).toBe('table');
		expect(dash.layout.tiles[0].chartType).toBeUndefined();
	});

	it('sets the passed cube ref on every tile', () => {
		const spec = specWithTiles([
			{ title: 'A', type: 'chart', chartType: 'bar', query: q('a') },
			{ title: 'B', type: 'table', query: q('b') },
			{ title: 'C', type: 'kpi', query: q('c') }
		]);

		const dash = assembleDashboard(spec, CUBE);

		for (const tile of dash.layout.tiles) {
			expect(tile.cube).toEqual(CUBE);
		}
	});

	it('carries the tile title through verbatim (rendered as text downstream)', () => {
		// A title containing text that would be dangerous if HTML-interpolated —
		// the assembler must NOT transform it; Tile.svelte renders it as text.
		const nasty = 'javascript:alert(1) <img src=x onerror=alert(1)>';
		const spec = specWithTiles([{ title: nasty, type: 'table', query: q('a') }]);

		const dash = assembleDashboard(spec, CUBE);

		expect(dash.layout.tiles[0].title).toBe(nasty);
	});

	it('sets name = spec.title and layout.cols = 12', () => {
		const spec = specWithTiles([{ title: 'A', type: 'table', query: q('a') }], 'Quarterly Review');

		const dash = assembleDashboard(spec, CUBE);

		expect(dash.name).toBe('Quarterly Review');
		expect(dash.layout.cols).toBe(12);
	});

	it('throws when handed a degraded spec (guard — degraded is never assembled)', () => {
		const degraded: DashboardSpec = {
			degraded: true,
			reason: "couldn't build a dashboard for that request",
			tiles: []
		};
		expect(() => assembleDashboard(degraded, CUBE)).toThrow();
	});

	it('does not mutate the passed spec bodies', () => {
		const bodyA = q('a');
		const before = JSON.stringify(bodyA);
		const spec = specWithTiles([{ title: 'A', type: 'table', query: bodyA }]);

		assembleDashboard(spec, CUBE);

		expect(JSON.stringify(bodyA)).toBe(before);
	});
});
