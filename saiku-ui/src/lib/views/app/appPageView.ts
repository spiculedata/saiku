/*
 * Pure helpers for AppPageView — the bridge between the App Builder's opaque
 * per-page `grid` and the EXISTING dashboard renderer (DashboardGrid + tiles +
 * filter panel/bar), which is coupled to the `dashboardStore` singleton.
 *
 * Extracted out of AppPageView.svelte so the load-bearing conversion — page
 * grid ⇆ Dashboard — is unit-testable without mounting a Svelte component (the
 * store, the grid, and the tiles all require the runes runtime + a DOM).
 *
 * PARITY / BACK-COMPAT GUARANTEE: a classic dashboard is a 1-page app whose
 * page.grid IS the dashboard layout. {@link pageGridToDashboard} must hand that
 * layout to the renderer WITHOUT mutating or cloning it — the tiles array and
 * cols pass through by reference — so the app path renders byte-identical to
 * the standalone dashboard viewer. See the app-parity test.
 */

import type { AppPage } from "$lib/api/apps";
import type {
  Dashboard,
  DashboardFilter,
  DashboardFilterPanel,
  DashboardTile,
} from "$lib/api/dashboards";

/** Default column count when a page grid omits `cols` — mirrors
 *  {@code newDashboard()} / {@code emptyPage()} (both seed 12). */
const DEFAULT_COLS = 12;

/** The opaque shape stored in {@link AppPage.grid}. It is the EXACT object
 *  today's dashboards persist: the {@code layout} fields ({@code cols},
 *  {@code tiles}) plus the dashboard-level filter config ({@code filters},
 *  {@code filterPanel}). Every field is optional so a hand-authored or
 *  partially-populated grid still converts cleanly. */
export interface PageGrid {
  cols?: number;
  tiles?: DashboardTile[];
  filters?: DashboardFilter[];
  filterPanel?: DashboardFilterPanel;
}

/** Narrow an opaque {@code page.grid} to {@link PageGrid}. Non-object grids
 *  (null / undefined / a stray primitive from a corrupt doc) collapse to an
 *  empty grid so the renderer shows its own empty state rather than throwing. */
export function asPageGrid(grid: unknown): PageGrid {
  return grid && typeof grid === "object" ? (grid as PageGrid) : {};
}

/** Convert an {@link AppPage} into the {@link Dashboard} the existing renderer
 *  (dashboardStore → DashboardGrid + tiles) consumes.
 *
 *  The page id doubles as the dashboard id so the write-back path can tell
 *  which page the store currently holds. The tiles array and cols pass through
 *  BY REFERENCE — this function never clones or mutates the grid (the parity
 *  guarantee). `savedPath` stays empty when hydrated, so path-keyed server
 *  surfaces (Share / Embed / History / Comments) stay gated off for app pages. */
export function pageGridToDashboard(page: AppPage): Dashboard {
  const grid = asPageGrid(page.grid);
  return {
    id: page.id,
    name: page.title,
    version: 1,
    layout: {
      cols: grid.cols ?? DEFAULT_COLS,
      tiles: grid.tiles ?? [],
    },
    filters: grid.filters ?? [],
    filterPanel: grid.filterPanel,
  };
}

/** Project a live {@link Dashboard} (as edited in the store) back into the
 *  opaque {@link PageGrid} persisted on the page. Inverse of
 *  {@link pageGridToDashboard}; used by the editable write-back so in-grid
 *  edits survive a page switch. Preserves references (no deep clone). */
export function dashboardToPageGrid(dashboard: Dashboard): PageGrid {
  return {
    cols: dashboard.layout.cols,
    tiles: dashboard.layout.tiles,
    filters: dashboard.filters,
    filterPanel: dashboard.filterPanel,
  };
}
