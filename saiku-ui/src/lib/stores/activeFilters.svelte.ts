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
import type { DashboardFilter, PanelFilter } from "$lib/api/dashboards";

/** Where an ActiveFilter came from. Source identity drives precedence. */
export type FilterSource =
  | { kind: "panel"; filterId: string }
  | { kind: "click"; tileId: string }
  /** Brush cross-filter (saiku#1085) — a rectangular brush on a chart tile
   *  emits a (usually multi-member) filter on its row hierarchy. Carries the
   *  source tile id so the SOURCE tile can exclude its own cross-filter and
   *  keep full context (unlike click filters, which apply everywhere). */
  | { kind: "cross"; tileId: string }
  /** App-scoped selection (saiku#1754) — the App Builder's header context
   *  pill. Unlike clicks it is NOT page state: it belongs to the app shell and
   *  must survive the per-page hydrate, or a page renders unfiltered numbers
   *  under a header still claiming the selection. */
  | { kind: "app"; sourceId: string };

export interface ActiveFilter {
  /** Stable per-instance id; used as the chip key. */
  id: string;
  source: FilterSource;
  /** Dim/hier/level target. Members empty = "any" (no-op, but registered). */
  filter: DashboardFilter;
}


/**
 * Project a panel filter onto the active-filter set (saiku#1803).
 *
 * Exported and tested on its own because the bug it fixes was invisible in a
 * $derived: the projection used to name four fields explicitly, and so silently
 * dropped the semantic-mapping fields added to DashboardFilter later — label,
 * bindings, captions. Every unit test built ActiveFilters directly and passed;
 * the effect only showed on a real page, where picking a state narrowed the
 * cube tiles and left the semantic-model tiles showing everything, because the
 * tile never saw the binding that told it how to address the concept.
 *
 * Spreading rather than listing means the next field added to DashboardFilter
 * arrives here for free instead of going missing.
 */
export function panelFilterToActive(f: PanelFilter): ActiveFilter {
  // Strip the fields that belong to the PANEL ROW rather than to the target.
  const { id, widget: _widget, cube: _cube, cascading: _cascading, topN: _topN, ...target } = f;
  return {
    id: `panel-${id}`,
    source: { kind: "panel", filterId: id },
    filter: { ...target, members: target.members ?? [] },
  };
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

  /** Brush cross-filters (saiku#1085), at most one per source tile. Transient
   *  like clicks — cleared on dashboard swap. */
  crosses = $state<ActiveFilter[]>([]);

  /** App-scoped selections (saiku#1754) — currently just the App Builder's
   *  header context pill. Deliberately NOT cleared by {@link resetTransient}:
   *  an app-level scope outlives the page hydrate that swaps the grid. */
  appLevel = $state<ActiveFilter[]>([]);

  /** Derived: panel filters from the active dashboard. Re-derived
   *  whenever {@code dashboardStore.current.filterPanel} changes. */
  panel = $derived<ActiveFilter[]>(
    (dashboardStore.current?.filterPanel?.filters ?? []).map(panelFilterToActive),
  );

  /** Derived: the merged active set. Panel first, then the app-level scope,
   *  then clicks, brush cross-filters last — when entries target the same
   *  (dim, hier, level), the most-intentional
   *  (cross > click > app > panel) wins. An in-page click therefore still
   *  narrows past the app's context pill. Per-tile source exclusion (a tile
   *  ignoring its OWN cross-filter) happens downstream in effectiveQueryFor,
   *  which knows the consuming tile id. */
  all = $derived<ActiveFilter[]>(
    (() => {
      const byKey = new Map<string, ActiveFilter>();
      for (const f of this.panel) byKey.set(targetKey(f.filter), f);
      for (const f of this.appLevel) byKey.set(targetKey(f.filter), f);
      for (const f of this.clicks) byKey.set(targetKey(f.filter), f);
      for (const f of this.crosses) byKey.set(targetKey(f.filter), f);
      return Array.from(byKey.values());
    })(),
  );

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

  /** Push (or replace) a brush cross-filter for a source tile (saiku#1085).
   *  At most one cross-filter per source tile — a fresh brush on the same
   *  tile replaces its previous selection. An empty members[] is treated as
   *  "clear" so a brush that selects nothing removes the tile's cross-filter. */
  pushCross(filter: DashboardFilter, sourceTileId: string): void {
    const without = this.crosses.filter(
      (c) => !(c.source.kind === "cross" && c.source.tileId === sourceTileId),
    );
    if (!filter.members || filter.members.length === 0) {
      this.crosses = without; // empty selection = clear
      return;
    }
    this.crosses = [
      ...without,
      {
        id: `cross-${sourceTileId}-${targetKey(filter)}-${Date.now()}`,
        source: { kind: "cross", tileId: sourceTileId },
        filter,
      },
    ];
  }

  /** Drop the click filter(s) pushed under a given source id. Symmetric with
   *  {@link clearCrossesFrom}; used by the App Builder's header context
   *  selector when the viewer picks its "All" entry, so the selection is
   *  REMOVED rather than registered as an empty (and therefore invalid)
   *  constraint. No-op when the source has none. */
  clearClicksFrom(sourceTileId: string): void {
    this.clicks = this.clicks.filter(
      (c) => !(c.source.kind === "click" && c.source.tileId === sourceTileId),
    );
  }

  /** Push (or replace) the app-level selection for a source (saiku#1754).
   *  One entry per (source, target): re-picking in the App Builder's context
   *  pill replaces the previous scope rather than stacking. */
  pushApp(filter: DashboardFilter, sourceId: string): void {
    const key = targetKey(filter);
    const without = this.appLevel.filter(
      (a) =>
        !(
          a.source.kind === "app" &&
          a.source.sourceId === sourceId &&
          targetKey(a.filter) === key
        ),
    );
    this.appLevel = [
      ...without,
      {
        id: `app-${sourceId}-${key}-${Date.now()}`,
        source: { kind: "app", sourceId },
        filter,
      },
    ];
  }

  /** Drop the app-level filter(s) registered under a source id — the context
   *  pill's "All" entry. An empty members[] is no constraint at all, so the
   *  entry is removed rather than registered empty (same reasoning as
   *  {@link clearClicksFrom}). No-op when the source has none. */
  clearApp(sourceId: string): void {
    this.appLevel = this.appLevel.filter(
      (a) => !(a.source.kind === "app" && a.source.sourceId === sourceId),
    );
  }

  /** Drop the cross-filter emitted by a given source tile (brush cleared /
   *  Esc / click-outside on that tile). No-op if the tile has none. */
  clearCrossesFrom(sourceTileId: string): void {
    this.crosses = this.crosses.filter(
      (c) => !(c.source.kind === "cross" && c.source.tileId === sourceTileId),
    );
  }

  /** Remove a filter chip — clicks and cross-filters are dropped from local
   *  state; panel entries are cleared by zeroing their members[] through the
   *  store so the change persists on save. */
  clearChip(id: string): void {
    const click = this.clicks.find((c) => c.id === id);
    if (click) {
      this.clicks = this.clicks.filter((c) => c.id !== id);
      return;
    }
    const cross = this.crosses.find((c) => c.id === id);
    if (cross) {
      this.crosses = this.crosses.filter((c) => c.id !== id);
      return;
    }
    const app = this.appLevel.find((a) => a.id === id);
    if (app) {
      this.appLevel = this.appLevel.filter((a) => a.id !== id);
      return;
    }
    const panelMatch = this.panel.find((p) => p.id === id);
    if (panelMatch && panelMatch.source.kind === "panel") {
      dashboardStore.updatePanelFilter(panelMatch.source.filterId, {
        members: [],
      });
    }
  }

  /** Wipe transient click + cross state on dashboard switch. Panel filters
   *  re-derive automatically from the new dashboardStore.current, and the
   *  app-level scope (saiku#1754) deliberately survives — it belongs to the
   *  app shell, not to the page being swapped out. */
  resetTransient(): void {
    this.clicks = [];
    this.crosses = [];
  }
}

export const activeFilters = new ActiveFiltersStore();
