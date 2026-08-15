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
  import { tileSelection } from "$lib/stores/tileSelection.svelte";
  import { presentation } from "$lib/stores/presentation.svelte";
  import { newTileId, type TileType } from "$lib/api/dashboards";
  import { isEnterPresentationKey } from "$lib/dashboard/presentationHotkeys";
  import { isUndoKey, isRedoKey } from "$lib/dashboard/historyHotkeys";
  import { panelDiffersFromDefaults } from "$lib/dashboard/filterDefaults";
  import { Minimize2 } from "@lucide/svelte";
  import { buildTile } from "$lib/dashboard/tilePlacement";
  import { decodeFilterParams, encodeActiveFilters } from "$lib/dashboard/urlFilterState";
  import DashboardToolbar from "$lib/views/dashboard/DashboardToolbar.svelte";
  import DashboardFilterBar from "$lib/views/dashboard/DashboardFilterBar.svelte";
  import DashboardFilterPanel from "$lib/views/dashboard/DashboardFilterPanel.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";
  import DashboardBulkActionsBar from "$lib/views/dashboard/DashboardBulkActionsBar.svelte";
  import EmptyDashboardGuidance from "$lib/views/dashboard/EmptyDashboardGuidance.svelte";

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
      tileSelection.clear(); // #915: never carry a selection into a fresh load
      void dashboardStore.load(dashboardPath).then(() => hydrateFromUrl());
    });
    // saiku#928: press F (outside a text field) to enter presentation mode.
    // Esc-to-exit + idle-cursor hiding are owned by the presentation store.
    const onKey = (e: KeyboardEvent) => {
      if (isEnterPresentationKey(e) && !presentation.active) {
        e.preventDefault();
        void presentation.enter();
        return;
      }
      // Issue #914: Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z (or Ctrl+Y) redo.
      // Disabled in viewer mode; the predicates already ignore keystrokes
      // originating inside a text field / contenteditable.
      if (readOnly) return;
      if (isUndoKey(e)) {
        e.preventDefault();
        handleUndo();
      } else if (isRedoKey(e)) {
        e.preventDefault();
        handleRedo();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // Path can change without remounting (SvelteKit reuses the route component
  // when only the rest segment changes). Reload on every distinct path.
  $effect(() => {
    const path = dashboardPath;
    untrack(() => {
      if (dashboardStore.savedPath !== path) {
        activeFilters.resetTransient();
        tileSelection.clear(); // #915: selection belongs to the prior dashboard
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

  function handleTagsChange(tags: string[]): void {
    dashboardStore.updateTags(tags);
  }

  function handleAddTile(type: TileType): void {
    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const tile = buildTile(layout, type, newTileId());
    dashboardStore.addTile(tile);
  }

  // Issue #927: Reset filters button enable-state. True when there are
  // any click-captured filters OR any panel widget whose members[]
  // differs from its saved default. The URL deep-link state mirrors
  // activeFilters.all via the existing $effect above, so resetting
  // clicks here propagates to the URL automatically.
  let canResetFilters = $derived(
    activeFilters.clicks.length > 0 ||
      panelDiffersFromDefaults(
        dashboardStore.current?.filterPanel?.filters,
        dashboardStore.savedDefaultMembers,
      ),
  );

  function handleResetFilters(): void {
    activeFilters.resetTransient();
    dashboardStore.resetPanelFiltersToSaved();
  }

  // Issue #914: undo / redo of structural edits. Both are no-ops in
  // viewer mode (readOnly) — guarded here and at the store/keyboard
  // layer so neither the button nor the shortcut can mutate a viewed
  // dashboard.
  function handleUndo(): void {
    if (readOnly) return;
    dashboardStore.undo();
  }

  function handleRedo(): void {
    if (readOnly) return;
    dashboardStore.redo();
  }

  // Issue #929: handle on the dashboard grid root so the toolbar's Export
  // action can rasterise the rendered tiles (honouring active filters,
  // since they're already applied to the rendered output) to PNG / PDF.
  // Bound from <DashboardGrid> below; null while the grid is unmounted
  // (e.g. the empty-state guidance is showing).
  let gridElement = $state<HTMLDivElement | null>(null);

  // Issue #916: surface the empty-state guidance when an editable
  // dashboard has zero tiles. Viewer mode (readOnly) keeps the blank
  // canvas — a published-but-empty dashboard isn't an authoring moment.
  let showEmptyGuidance = $derived(
    !readOnly && (dashboardStore.current?.layout?.tiles?.length ?? 0) === 0,
  );
</script>

<div class="dashboard-editor" class:presentation={presentation.active}>
  {#if dashboardStore.loading}
    <div class="text-fg-muted py-8 px-4">Loading dashboard…</div>
  {:else if dashboardStore.current}
    {#if !presentation.active}
      <DashboardToolbar
        name={dashboardStore.current.name}
        onNameChange={handleNameChange}
        tags={dashboardStore.current.tags ?? []}
        onTagsChange={handleTagsChange}
        {readOnly}
        saving={dashboardStore.saving}
        dirty={dashboardStore.dirty}
        onSave={handleSave}
        onAddTile={readOnly ? undefined : handleAddTile}
        {canResetFilters}
        onResetFilters={readOnly ? undefined : handleResetFilters}
        onPresent={() => presentation.enter()}
        canUndo={!readOnly && dashboardStore.canUndo}
        canRedo={!readOnly && dashboardStore.canRedo}
        onUndo={readOnly ? undefined : handleUndo}
        onRedo={readOnly ? undefined : handleRedo}
        {gridElement}
      />
      {#if dashboardStore.loadError}
        <div class="py-2 px-3 bg-bg-muted rounded-sm text-fg-muted text-sm">{dashboardStore.loadError}</div>
      {/if}
      {#if dashboardStore.saveError}
        <div class="error">Save failed: {dashboardStore.saveError}</div>
      {/if}
      <DashboardFilterPanel {readOnly} />
      <DashboardFilterBar {readOnly} />
    {/if}
    {#if showEmptyGuidance && !presentation.active}
      <EmptyDashboardGuidance onAddTile={handleAddTile} />
    {:else}
      <DashboardGrid {readOnly} bind:gridElement />
    {/if}
    <!-- #915: bulk-ops bar — self-hides unless 2+ tiles are selected in
         an editable, non-presentation dashboard. -->
    {#if !presentation.active}
      <DashboardBulkActionsBar {readOnly} />
    {/if}
    {#if presentation.active}
      <button
        type="button"
        class="present-exit"
        onclick={() => presentation.exit()}
        title="Exit presentation (Esc)"
        aria-label="Exit presentation"
      >
        <Minimize2 size={16} aria-hidden="true" />
        <span>Exit</span>
      </button>
    {/if}
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
  .error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, hsl(var(--danger)) 14%, transparent);
    color: hsl(var(--danger));
    border-radius: 4px;
    font-size: 0.875rem;
  }
  /* Presentation mode (saiku#928): full-bleed tiles, no editor padding, and
     flatten per-tile chrome (borders, shadows, edit affordances) for a clean
     TV-wall surface. Tile classes are global selectors because they live in
     scoped child components. */
  .dashboard-editor.presentation {
    padding: 0;
    gap: 0;
  }
  .dashboard-editor.presentation :global(.tile) {
    border-color: transparent;
    box-shadow: none;
  }
  .dashboard-editor.presentation :global(.tile-actions) {
    display: none;
  }
  /* Floating exit affordance — fades out with the cursor (see layout's
     idle-cursor rule) so it doesn't sit on a TV wall, but is reachable on
     any pointer move. */
  .present-exit {
    position: fixed;
    top: 0.75rem;
    right: 0.75rem;
    z-index: 50;
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.375rem 0.625rem;
    border: 1px solid hsl(var(--border-strong));
    border-radius: 4px;
    background: color-mix(in srgb, hsl(var(--bg)) 80%, transparent);
    color: hsl(var(--fg));
    font-size: 0.8125rem;
    cursor: pointer;
    opacity: 0.35;
    transition: opacity 0.15s ease;
  }
  .present-exit:hover,
  .present-exit:focus-visible {
    opacity: 1;
  }
</style>
