/*
 * Filter-affinity hover state (#924). A tiny broadcast store so the
 * filter panel (where the hover happens) can tell the dashboard grid
 * which tiles a hovered filter affects — the panel and the grid live in
 * separate component subtrees, so a shared store is the clean conduit.
 *
 * The panel sets it on mouse-enter / focus-in of a filter row and clears
 * it on leave; the row reads `affectedCount`/`totalCount` for its badge,
 * and each Tile reads `active` + `isAffected(id)` to highlight or dim.
 * Purely presentational and transient — never persisted.
 */

import type { FilterAffinity } from '$lib/dashboard/filterAffinity';

class FilterAffinityHoverStore {
	/** Panel-filter id currently hovered, or null when nothing is hovered. */
	hoveredFilterId = $state<string | null>(null);
	/** Tile ids the hovered filter narrows. */
	affected = $state<Set<string>>(new Set());
	affectedCount = $state(0);
	totalCount = $state(0);

	/** True while a filter is being hovered — gates the grid highlight/dim. */
	get active(): boolean {
		return this.hoveredFilterId !== null;
	}

	set(filterId: string, affinity: FilterAffinity): void {
		this.hoveredFilterId = filterId;
		this.affected = affinity.affected;
		this.affectedCount = affinity.affectedCount;
		this.totalCount = affinity.totalCount;
	}

	clear(): void {
		this.hoveredFilterId = null;
		this.affected = new Set();
		this.affectedCount = 0;
		this.totalCount = 0;
	}

	isAffected(tileId: string): boolean {
		return this.affected.has(tileId);
	}
}

export const filterAffinityHover = new FilterAffinityHoverStore();
