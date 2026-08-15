import { describe, expect, it } from 'vitest';
import type { DashboardLayout, DashboardTile } from '$lib/api/dashboards';
import { TEMPLATES, getTemplate, instantiateTemplate, type DashboardTemplate } from './templates';

/** True if any two tiles in the list overlap on the grid. Mirrors the
 *  rectsOverlap rule the layout engine uses. */
function hasOverlap(tiles: ReadonlyArray<{ x: number; y: number; w: number; h: number }>): boolean {
	for (let i = 0; i < tiles.length; i++) {
		for (let j = i + 1; j < tiles.length; j++) {
			const a = tiles[i];
			const b = tiles[j];
			if (a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y) {
				return true;
			}
		}
	}
	return false;
}

const COLS = 12;

describe('TEMPLATES', () => {
	it('ships a non-empty catalogue with unique ids', () => {
		expect(TEMPLATES.length).toBeGreaterThanOrEqual(3);
		const ids = TEMPLATES.map((t) => t.id);
		expect(new Set(ids).size).toBe(ids.length);
	});

	it('every template has a name, description and at least one tile', () => {
		for (const t of TEMPLATES) {
			expect(t.name.trim().length).toBeGreaterThan(0);
			expect(t.description.trim().length).toBeGreaterThan(0);
			expect(t.tiles.length).toBeGreaterThan(0);
		}
	});

	it('every template tile is structurally valid and fits the 12-col grid', () => {
		for (const t of TEMPLATES) {
			for (const tile of t.tiles) {
				expect(tile.w).toBeGreaterThanOrEqual(1);
				expect(tile.h).toBeGreaterThanOrEqual(1);
				expect(tile.x).toBeGreaterThanOrEqual(0);
				expect(tile.y).toBeGreaterThanOrEqual(0);
				expect(tile.x + tile.w).toBeLessThanOrEqual(COLS);
			}
		}
	});

	it('no template has overlapping tiles', () => {
		for (const t of TEMPLATES) {
			expect(hasOverlap(t.tiles), `template ${t.id} has overlapping tiles`).toBe(false);
		}
	});
});

describe('getTemplate', () => {
	it('finds a known template by id', () => {
		const t = getTemplate(TEMPLATES[0].id);
		expect(t).toBeDefined();
		expect(t?.id).toBe(TEMPLATES[0].id);
	});

	it('returns undefined for an unknown id', () => {
		expect(getTemplate('nope-not-a-template')).toBeUndefined();
	});
});

describe('instantiateTemplate', () => {
	const sample: DashboardTemplate = {
		id: 't',
		name: 'Sample',
		description: 'd',
		tiles: [
			{ x: 0, y: 0, w: 6, h: 4, type: 'chart', title: 'A', chartType: 'bar' },
			{ x: 6, y: 0, w: 6, h: 4, type: 'table', title: 'B' }
		]
	};

	function counterMint(): () => string {
		let n = 0;
		return () => `tile-${n++}`;
	}

	it("seeds a fresh dashboard with the template's tiles", () => {
		const d = instantiateTemplate(sample, counterMint());
		expect(d.layout.tiles).toHaveLength(2);
		expect(d.layout.cols).toBe(12);
		expect(d.layout.tiles[0].type).toBe('chart');
		expect(d.layout.tiles[0].chartType).toBe('bar');
		expect(d.layout.tiles[1].type).toBe('table');
	});

	it('mints a fresh id per tile', () => {
		const d = instantiateTemplate(sample, counterMint());
		const ids = d.layout.tiles.map((t) => t.id);
		expect(ids).toEqual(['tile-0', 'tile-1']);
		expect(new Set(ids).size).toBe(ids.length);
	});

	it('defaults the dashboard name to the template name, override when given', () => {
		expect(instantiateTemplate(sample, counterMint()).name).toBe('Sample');
		expect(instantiateTemplate(sample, counterMint(), 'Custom').name).toBe('Custom');
	});

	it('does not mutate the source template', () => {
		const before = JSON.stringify(sample);
		const d = instantiateTemplate(sample, counterMint());
		(d.layout.tiles[0] as DashboardTile).title = 'mutated';
		expect(JSON.stringify(sample)).toBe(before);
	});

	it('produces a fresh top-level id and an empty filter list', () => {
		const d = instantiateTemplate(sample, counterMint());
		expect(d.id).toBeTruthy();
		expect(d.filters).toEqual([]);
		expect(d.version).toBe(1);
	});

	it('real templates instantiate to non-overlapping laid-out tiles', () => {
		for (const t of TEMPLATES) {
			const d = instantiateTemplate(t, counterMint());
			const layout: DashboardLayout = d.layout;
			expect(layout.tiles).toHaveLength(t.tiles.length);
			expect(hasOverlap(layout.tiles)).toBe(false);
		}
	});
});
