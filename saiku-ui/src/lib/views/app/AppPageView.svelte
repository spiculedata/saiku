<script lang="ts">
  /*
   * Renders a single App Builder page through the EXISTING dashboard renderer
   * — no fork of the grid or tiles.
   *
   * Step-1 finding (see appPageView.ts header): DashboardGrid + the tile
   * components + the filter panel/bar are STORE-COUPLED, not prop-driven — they
   * read `dashboardStore.current` and the `activeFilters` singleton directly
   * rather than taking a layout prop. Forking that whole stack was explicitly
   * out of scope. Since an app shows exactly ONE page at a time, we reuse the
   * real renderer by hydrating the singleton with the ACTIVE page's grid on
   * every page switch, and give each page its OWN filter state by stashing /
   * restoring the transient (click/cross) layer per page id. Panel filters live
   * on the grid itself (page.grid.filterPanel), so they travel with the page.
   *
   * Per-page URL state: `?p=<pageId>` reflects the active page and
   * `f~<pageId>=…` carries each page's filters (urlFilterState.ts). Switching
   * pages preserves each page's filters and the URL round-trips.
   *
   * Editable write-back: when `editable`, in-grid edits (drag / resize / tile
   * edit) mutate `dashboardStore.current`; a guarded effect projects them back
   * into `appDoc` so they survive a page switch. The guard tracks ONLY the
   * store doc (page reads are untracked) so the write-back can't feed the
   * hydrate effect and trip Svelte's effect_update_depth_exceeded.
   */

  import { onMount, untrack } from "svelte";
  import type { AppPage } from "$lib/api/apps";
  import type { DashboardFilter } from "$lib/api/dashboards";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { appDoc } from "$lib/stores/appDoc.svelte";
  import { pageGridToDashboard, dashboardToPageGrid } from "$lib/views/app/appPageView";
  import { encodeAppFilterState, decodeAppFilterState } from "$lib/dashboard/urlFilterState";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";
  import DashboardFilterPanel from "$lib/views/dashboard/DashboardFilterPanel.svelte";
  import DashboardFilterBar from "$lib/views/dashboard/DashboardFilterBar.svelte";

  interface Props {
    page: AppPage;
    editable?: boolean;
  }

  let { page, editable = false }: Props = $props();

  const readOnly = $derived(!editable);

  // Per-page transient filter memory (click/cross layer), keyed by page id.
  // Plain object, not $state — it's imperative bookkeeping the effects read/
  // write directly; reactivity would only invite re-entrancy.
  const pageFilters: Record<string, DashboardFilter[]> = {};

  // Which page id the store currently holds. Gates the hydrate effect so a
  // write-back (which reassigns the `page` prop with the SAME id) doesn't
  // re-hydrate and wipe the just-made edit.
  let hydratedPageId: string | null = null;

  // Set true across a hydrate so the write-back effect ignores the store
  // change the hydrate itself causes (that change is us LOADING the page, not
  // the user EDITING it). Cleared on the next microtask.
  let suppressWriteBack = false;

  /** Snapshot the active page's live filter set (as plain DashboardFilters). */
  function snapshotActiveFilters(): DashboardFilter[] {
    return activeFilters.all.map((af) => ({
      dimension: af.filter.dimension,
      hierarchy: af.filter.hierarchy,
      level: af.filter.level,
      members: [...(af.filter.members ?? [])],
    }));
  }

  /** Hydrate the shared renderer with `p`'s grid and restore `p`'s filters.
   *  Stashes the outgoing page's filters first so switching back restores
   *  them. Mirrors the dashboard viewer's load path (hydrate → resetTransient
   *  → push deep-link filters as clicks). */
  function switchToPage(p: AppPage): void {
    if (hydratedPageId && hydratedPageId !== p.id) {
      pageFilters[hydratedPageId] = snapshotActiveFilters();
    }
    suppressWriteBack = true;
    dashboardStore.hydrate(pageGridToDashboard(p), "");
    activeFilters.resetTransient();
    for (const f of pageFilters[p.id] ?? []) {
      activeFilters.pushClick(f, "url");
    }
    hydratedPageId = p.id;
    queueMicrotask(() => {
      suppressWriteBack = false;
    });
  }

  // ------------------------------------------------------------------
  // Mount: seed per-page filters + active page from the URL (deep-link
  // restore) BEFORE the first hydrate runs.
  // ------------------------------------------------------------------
  onMount(() => {
    if (typeof window === "undefined") return;
    const { activePageId, filtersByPage } = decodeAppFilterState(
      new URL(window.location.href).searchParams,
    );
    for (const [id, filters] of Object.entries(filtersByPage)) {
      pageFilters[id] = filters;
    }
    // Honour ?p= only when it names a real page in the loaded app and differs
    // from the current selection — the store validates the rest.
    if (activePageId && activePageId !== appDoc.activePageId) {
      const known = appDoc.current?.pages.some((pg) => pg.id === activePageId);
      if (known) appDoc.setActivePage(activePageId);
    }
  });

  // ------------------------------------------------------------------
  // Hydrate on page switch. Tracks the `page` prop; re-hydrates only when the
  // page id actually changes (a write-back reassigns `page` with the same id
  // — the guard short-circuits so the edit stands).
  // ------------------------------------------------------------------
  $effect(() => {
    const p = page;
    untrack(() => {
      if (p.id === hydratedPageId) return;
      switchToPage(p);
    });
  });

  // ------------------------------------------------------------------
  // URL mirror: reflect the active page + every page's filters. Skips the
  // first run so we don't immediately re-encode the URL we just decoded on
  // mount (same state, possibly different param order). replaceState keeps it
  // a shareable deep link without a SvelteKit navigation.
  // ------------------------------------------------------------------
  let urlMirrorInit = $state(false);
  $effect(() => {
    // Track the live filter set + active page so the URL follows both.
    const all = activeFilters.all;
    const activeId = page.id;
    if (typeof window === "undefined") return;
    if (!urlMirrorInit) {
      urlMirrorInit = true;
      return;
    }
    const map: Record<string, DashboardFilter[]> = { ...pageFilters };
    map[activeId] = all.map((af) => ({
      dimension: af.filter.dimension,
      hierarchy: af.filter.hierarchy,
      level: af.filter.level,
      members: [...(af.filter.members ?? [])],
    }));
    const next = new URL(window.location.href);
    next.search = encodeAppFilterState(activeId, map);
    if (next.toString() !== window.location.href) {
      window.history.replaceState(null, "", next.toString());
    }
  });

  // ------------------------------------------------------------------
  // Editable write-back: project in-grid edits back into appDoc so they
  // survive a page switch. Tracks ONLY the store doc; `page` / `editable` /
  // `suppressWriteBack` are read under untrack so appDoc mutations here can't
  // re-fire this effect (no feedback loop with the hydrate effect).
  // ------------------------------------------------------------------
  $effect(() => {
    const doc = dashboardStore.current;
    untrack(() => {
      if (!editable || suppressWriteBack || !doc) return;
      if (doc.id !== page.id) return; // store holds a different page — ignore
      appDoc.updatePageGrid(page.id, dashboardToPageGrid(doc));
    });
  });
</script>

<div class="app-page">
  <!-- The panel self-hides in read-only mode when the page has no filters
       (its own `{#if panel || !readOnly}` guard), so it's always safe to
       mount — no wrapper condition needed here. -->
  <DashboardFilterPanel {readOnly} />
  <DashboardFilterBar {readOnly} />
  <DashboardGrid {readOnly} />
</div>

<style>
  .app-page {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    padding: 1rem;
    flex: 1;
    min-width: 0;
    height: 100%;
    box-sizing: border-box;
  }
</style>
