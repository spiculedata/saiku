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
  import DashboardToolbar from "$lib/views/dashboard/DashboardToolbar.svelte";
  import DashboardFilterBar from "$lib/views/dashboard/DashboardFilterBar.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";

  interface Props {
    dashboardPath: string;
    readOnly?: boolean;
  }

  let { dashboardPath, readOnly = false }: Props = $props();

  onMount(() => {
    // Untracked so we don't re-fire on store mutations the load itself triggers.
    untrack(() => {
      activeFilters.resetTransient();
      void dashboardStore.load(dashboardPath);
    });
  });

  // Path can change without remounting (SvelteKit reuses the route component
  // when only the rest segment changes). Reload on every distinct path.
  $effect(() => {
    const path = dashboardPath;
    untrack(() => {
      if (dashboardStore.savedPath !== path) {
        activeFilters.resetTransient();
        void dashboardStore.load(path);
      }
    });
  });

  async function handleSave(): Promise<void> {
    await dashboardStore.save();
  }

  function handleNameChange(name: string): void {
    dashboardStore.updateName(name);
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
    />
    {#if dashboardStore.loadError}
      <div class="notice">{dashboardStore.loadError}</div>
    {/if}
    {#if dashboardStore.saveError}
      <div class="error">Save failed: {dashboardStore.saveError}</div>
    {/if}
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
    height: 100%;
    box-sizing: border-box;
  }
  .loading {
    color: var(--fg-muted);
    padding: 2rem 1rem;
  }
  .notice {
    padding: 0.5rem 0.75rem;
    background: var(--bg-muted, #f3f4f6);
    border-radius: 4px;
    color: var(--fg-muted);
    font-size: 0.875rem;
  }
  .error {
    padding: 0.5rem 0.75rem;
    background: #fee2e2;
    color: #991b1b;
    border-radius: 4px;
    font-size: 0.875rem;
  }
</style>
