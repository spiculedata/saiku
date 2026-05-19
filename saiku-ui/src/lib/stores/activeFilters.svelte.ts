/*
 * Active filters — the runtime composition of panel filters and
 * click-captured filters (saiku#996). The unified filter panel is the
 * source of truth for analyst-configured slicing; click filters layer
 * on top with most-recent-wins per (dim, hier, level):
 *
 *   active = panel ∪ clicks   with   click > panel
 *
 * Panel selections persist directly on the dashboard document
 * (filterPanel.filters[i].members), so there's no in-memory "widget"
 * map any more — mutations flow through dashboardStore.updatePanelFilter
 * and re-derive activeFilters via the panel's reactivity.
 */

import { dashboardStore } from "$lib/stores/dashboard.svelte";
import type { DashboardFilter } from "$lib/api/dashboards";

/** Where an ActiveFilter came from. Source identity drives precedence. */
export type FilterSource =
  | { kind: "panel"; filterId: string }
  | { kind: "click"; tileId: string };

export interface ActiveFilter {
  /** Stable per-instance id; used as the chip key. */
  id: string;
  source: FilterSource;
  /** Dim/hier/level target. Members empty = "any" (no-op, but registered). */
  filter: DashboardFilter;
}

/** Compose a target key from a filter for precedence comparison. Two
 *  ActiveFilters with the same target key collide and only the highest-
 *  precedence one wins. */
export function targetKey(f: DashboardFilter): string {
  return `${f.dimension}/${f.hierarchy}/${f.level}`;
}

class ActiveFiltersStore {
  /** Click-captured filters, in insertion order. Cleared on dashboard swap. */
  clicks = $state<ActiveFilter[]>([]);

  /** Derived: panel filters from the active dashboard. Re-derived
   *  whenever {@code dashboardStore.current.filterPanel} changes. */
  panel = $derived<ActiveFilter[]>(
    (dashboardStore.current?.filterPanel?.filters ?? []).map((f) => ({
      id: `panel-${f.id}`,
      source: { kind: "panel" as const, filterId: f.id },
      filter: {
        dimension: f.dimension,
        hierarchy: f.hierarchy,
        level: f.level,
        members: f.members ?? [],
      },
    })),
  );

  /** Derived: the merged active set. Panel first, clicks second — when
   *  two entries target the same (dim, hier, level), the click wins. */
  all = $derived<ActiveFilter[]>((() => {
    const byKey = new Map<string, ActiveFilter>();
    for (const f of this.panel) byKey.set(targetKey(f.filter), f);
    for (const f of this.clicks) byKey.set(targetKey(f.filter), f);
    return Array.from(byKey.values());
  })());

  /* ------------------------------ mutators ----------------------------- */

  /** Append (or replace) a click-captured filter. If a click for the same
   *  target already exists, the previous one is replaced — last click wins. */
  pushClick(filter: DashboardFilter, sourceTileId: string): void {
    const key = targetKey(filter);
    const without = this.clicks.filter((c) => targetKey(c.filter) !== key);
    this.clicks = [
      ...without,
      {
        id: `click-${sourceTileId}-${key}-${Date.now()}`,
        source: { kind: "click", tileId: sourceTileId },
        filter,
      },
    ];
  }

  /** Remove a filter chip — clicks are dropped from local state; panel
   *  entries are cleared by zeroing their members[] through the store
   *  so the change persists on save. */
  clearChip(id: string): void {
    const click = this.clicks.find((c) => c.id === id);
    if (click) {
      this.clicks = this.clicks.filter((c) => c.id !== id);
      return;
    }
    const panelMatch = this.panel.find((p) => p.id === id);
    if (panelMatch && panelMatch.source.kind === "panel") {
      dashboardStore.updatePanelFilter(panelMatch.source.filterId, { members: [] });
    }
  }

  /** Wipe transient click state on dashboard switch. Panel filters
   *  re-derive automatically from the new dashboardStore.current. */
  resetTransient(): void {
    this.clicks = [];
  }
}

export const activeFilters = new ActiveFiltersStore();
