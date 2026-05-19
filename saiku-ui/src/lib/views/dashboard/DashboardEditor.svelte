<script lang="ts">
  /*
   * Top-level dashboard editor. Owns the route-level lifecycle (load on
   * mount, reset on path change) and orchestrates the toolbar / filter
   * bar / grid composition. Doubles as the Viewer when `readOnly={true}`.
   *
   * Reactive state lives in the singleton stores ($lib/stores/dashboard,
   * activeFilters, schemaCache) so tile components can subscribe directly
   * without prop-drilling.
   */

  import { onMount, untrack } from "svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { newTileId, type TileType } from "$lib/api/dashboards";
  import { buildTile } from "$lib/dashboard/tilePlacement";
  import { decodeFilterParams, encodeActiveFilters } from "$lib/dashboard/urlFilterState";
  import DashboardToolbar from "$lib/views/dashboard/DashboardToolbar.svelte";
  import DashboardFilterBar from "$lib/views/dashboard/DashboardFilterBar.svelte";
  import DashboardFilterPanel from "$lib/views/dashboard/DashboardFilterPanel.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";

  interface Props {
    dashboardPath: string;
    readOnly?: boolean;
  }

  let { dashboardPath, readOnly = false }: Props = $props();

  /** Hydrate transient click state from `?f=…` URL params (saiku#926).
   *  Called after a dashboard load completes so the URL deep link
   *  takes precedence over the panel's persisted defaults. */
  function hydrateFromUrl(): void {
    if (typeof window === "undefined") return;
    const incoming = decodeFilterParams(new URL(window.location.href).searchParams);
    for (const f of incoming) {
      activeFilters.pushClick(f, "url");
    }
  }

  onMount(() => {
    // Untracked so we don't re-fire on store mutations the load itself triggers.
    untrack(() => {
      activeFilters.resetTransient();
      void dashboardStore.load(dashboardPath).then(() => hydrateFromUrl());
    });
  });

  // Path can change without remounting (SvelteKit reuses the route component
  // when only the rest segment changes). Reload on every distinct path.
  $effect(() => {
    const path = dashboardPath;
    untrack(() => {
      if (dashboardStore.savedPath !== path) {
        activeFilters.resetTransient();
        void dashboardStore.load(path).then(() => hydrateFromUrl());
      }
    });
  });

  // Mirror the current active-filter set back into the URL via
  // replaceState — shareable deep links without touching the route or
  // triggering a SvelteKit navigation. Skip the very first run after
  // hydrate so we don't immediately re-encode the URL we just decoded
  // (the chip list is identical, but the param ordering may differ).
  let urlMirrorInit = $state(false);
  $effect(() => {
    const all = activeFilters.all;
    if (typeof window === "undefined") return;
    if (!urlMirrorInit) {
      urlMirrorInit = true;
      return;
    }
    const next = new URL(window.location.href);
    // Strip any existing `f` params, then build the fresh ones from
    // the current state. encodeActiveFilters returns a leading "?" or
    // an empty string; URL.search handles both.
    next.search = "";
    const encoded = encodeActiveFilters(all);
    next.search = encoded;
    if (next.toString() !== window.location.href) {
      window.history.replaceState(null, "", next.toString());
    }
  });

  async function handleSave(): Promise<void> {
    await dashboardStore.save();
  }

  function handleNameChange(name: string): void {
    dashboardStore.updateName(name);
  }

  function handleAddTile(type: TileType): void {
    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const tile = buildTile(layout, type, newTileId());
    dashboardStore.addTile(tile);
  }
</script>

<div class="dashboard-editor">
  {#if dashboardStore.loading}
    <div class="loading">Loading dashboard…</div>
  {:else if dashboardStore.current}
    <DashboardToolbar
      name={dashboardStore.current.name}
      onNameChange={handleNameChange}
      {readOnly}
      saving={dashboardStore.saving}
      dirty={dashboardStore.dirty}
      onSave={handleSave}
      onAddTile={readOnly ? undefined : handleAddTile}
    />
    {#if dashboardStore.loadError}
      <div class="notice">{dashboardStore.loadError}</div>
    {/if}
    {#if dashboardStore.saveError}
      <div class="error">Save failed: {dashboardStore.saveError}</div>
    {/if}
    <DashboardFilterPanel {readOnly} />
    <DashboardFilterBar {readOnly} />
    <DashboardGrid {readOnly} />
  {:else}
    <div class="error">{dashboardStore.loadError ?? "Unable to load dashboard."}</div>
  {/if}
</div>

<style>
  .dashboard-editor {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
    padding: 1rem;
    /* Fill .app__main (display:flex, default row direction) so the
       12-col grid spans the full viewport width — without flex:1 a
       flex-row child sizes to content. */
    flex: 1;
    min-width: 0;
    height: 100%;
    box-sizing: border-box;
  }
  .loading {
    color: var(--fg-muted);
    padding: 2rem 1rem;
  }
  .notice {
    padding: 0.5rem 0.75rem;
    background: var(--bg-muted);
    border-radius: 4px;
    color: var(--fg-muted);
    font-size: 0.875rem;
  }
  .error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
    border-radius: 4px;
    font-size: 0.875rem;
  }
</style>
