/*
 * Active filters — the runtime composition of dashboard defaults, filter
 * widget values, and click-captured filters. Per the design doc:
 *
 *   active = defaults ∪ widgets ∪ clicks
 *
 * with precedence (most recent wins) for entries that target the same
 * dimension/hierarchy/level:
 *
 *   click > widget > default
 *
 * This commit lays down the shape + the storage shells. The applicability
 * check (does this filter target apply to a given tile's cube schema?)
 * and the per-tile effective-query merge live in $lib/dashboard/
 * effectiveQuery.ts and land alongside the click-filter capture wiring
 * in task #12.
 */

import { dashboardStore } from "$lib/stores/dashboard.svelte";
import type { DashboardFilter } from "$lib/api/dashboards";

/** Where an ActiveFilter came from. Source identity drives precedence. */
export type FilterSource = { kind: "default" } | { kind: "widget"; tileId: string } | { kind: "click"; tileId: string };

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
  /** Per-tile widget selections, keyed by the filter-widget tile's id. */
  widgetValues = $state<Map<string, ActiveFilter>>(new Map());

  /** Click-captured filters, in insertion order. Cleared on dashboard swap. */
  clicks = $state<ActiveFilter[]>([]);

  /** Derived: defaults from the active dashboard. Re-derived whenever
   *  dashboardStore.current changes. */
  defaults = $derived<ActiveFilter[]>(
    (dashboardStore.current?.filters ?? []).map((f, i) => ({
      id: `default-${i}-${targetKey(f)}`,
      source: { kind: "default" as const },
      filter: f,
    })),
  );

  /** Derived: the merged active set, with click > widget > default
   *  precedence applied per-target. Order in the output is stable —
   *  defaults first, then widgets, then clicks (most recent intent
   *  appears closer to the right in the chip bar). */
  all = $derived<ActiveFilter[]>((() => {
    const byKey = new Map<string, ActiveFilter>();
    for (const f of this.defaults) byKey.set(targetKey(f.filter), f);
    for (const f of this.widgetValues.values()) byKey.set(targetKey(f.filter), f);
    for (const f of this.clicks) byKey.set(targetKey(f.filter), f);
    return Array.from(byKey.values());
  })());

  /* ------------------------------ mutators ----------------------------- */

  /** Set (or clear) the widget filter for one filter-widget tile. Passing
   *  an empty members[] clears the entry — keeps the chip bar tidy. */
  setWidgetValue(tileId: string, filter: DashboardFilter): void {
    const next = new Map(this.widgetValues);
    if (filter.members.length === 0) {
      next.delete(tileId);
    } else {
      next.set(tileId, {
        id: `widget-${tileId}`,
        source: { kind: "widget", tileId },
        filter,
      });
    }
    this.widgetValues = next;
  }

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

  /** Remove a filter chip — works for widget or click sources; defaults
   *  are removed by editing dashboard.filters directly via dashboardStore. */
  clearChip(id: string): void {
    // Try clicks first
    const click = this.clicks.find((c) => c.id === id);
    if (click) {
      this.clicks = this.clicks.filter((c) => c.id !== id);
      return;
    }
    // Then widgets
    for (const [tileId, af] of this.widgetValues.entries()) {
      if (af.id === id) {
        const next = new Map(this.widgetValues);
        next.delete(tileId);
        this.widgetValues = next;
        return;
      }
    }
    // Defaults intentionally not clearable here — they live on the
    // dashboard document and must be removed via dashboardStore.
  }

  /** Wipe transient state on dashboard switch. Defaults re-derive
   *  automatically from the new dashboardStore.current. */
  resetTransient(): void {
    this.widgetValues = new Map();
    this.clicks = [];
  }
}

export const activeFilters = new ActiveFiltersStore();
