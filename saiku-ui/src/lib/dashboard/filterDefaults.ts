/*
 * Saved-default tracking for dashboard panel filters (issue #927).
 *
 * Pure helpers: capture the per-widget members[] of a dashboard at
 * load time, detect when the in-memory panel diverges from those
 * defaults, and restore-to-defaults without losing widget identity.
 *
 * Lives separately from $lib/api/dashboards (DTO + REST surface) and
 * from $lib/stores/dashboard (singleton store) so the diff/restore
 * logic stays unit-testable without DOM or fetch mocks.
 */

import type { PanelFilter } from '$lib/api/dashboards';

/** Map of {@code PanelFilter.id} → the members[] those widgets had on
 *  the saved-to-disk dashboard. Captured at {@link snapshotPanelDefaults}
 *  / dashboardStore.hydrate time so callers can compare or restore
 *  without a network round-trip.
 *
 *  Widgets added in-session (after hydrate) are intentionally absent —
 *  see {@link restoreMembersToDefaults} for the new-widget semantics. */
export type SavedDefaultMembers = Record<string, string[]>;

/** Build a {@link SavedDefaultMembers} index from the panel filters of
 *  a freshly-loaded dashboard. Deep-clones each members[] so subsequent
 *  in-memory mutations on the live panel can't leak back into the
 *  snapshot. */
export function snapshotPanelDefaults(filters: PanelFilter[] | undefined): SavedDefaultMembers {
	const out: SavedDefaultMembers = {};
	if (!filters) return out;
	for (const f of filters) {
		out[f.id] = [...(f.members ?? [])];
	}
	return out;
}

/** True iff any panel widget's members[] differs from its saved
 *  default. New widgets (id not present in {@code defaults}) are
 *  considered non-default when they carry any members; empty-member
 *  new widgets count as default (nothing to reset). */
export function panelDiffersFromDefaults(
	filters: PanelFilter[] | undefined,
	defaults: SavedDefaultMembers
): boolean {
	if (!filters || filters.length === 0) return false;
	for (const f of filters) {
		const cur = f.members ?? [];
		const saved = defaults[f.id] ?? [];
		if (!arraysEqual(cur, saved)) return true;
	}
	return false;
}

/** Return a new filters array where each widget's members[] is restored
 *  to its saved default — or to empty[] for widgets added in-session
 *  (the "default" for a freshly-created widget is no slicing). Widget
 *  definitions themselves (id, widget type, cube, dim/hier/level) are
 *  preserved.
 *
 *  Returns the SAME array reference when nothing would change so callers
 *  can use referential identity to short-circuit a markDirty() / no-op
 *  store update. */
export function restoreMembersToDefaults(
	filters: PanelFilter[],
	defaults: SavedDefaultMembers
): PanelFilter[] {
	let changed = false;
	const next = filters.map((f) => {
		const saved = defaults[f.id] ?? [];
		const cur = f.members ?? [];
		if (arraysEqual(cur, saved)) return f;
		changed = true;
		return { ...f, members: [...saved] };
	});
	return changed ? next : filters;
}

function arraysEqual(a: string[], b: string[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) return false;
	}
	return true;
}
