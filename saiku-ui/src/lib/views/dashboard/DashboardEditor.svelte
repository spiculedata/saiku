<script lang="ts">
  /*
   * Top-level dashboard editor. Loads the dashboard at `dashboardPath` and
   * orchestrates the toolbar / filter bar / grid composition. Doubles as
   * the Viewer when `readOnly={true}` — the toolbar hides edit affordances,
   * the grid hides the drag-resize handles.
   *
   * Loading state lives here, not in the route, so missing dashboards can
   * surface inside the editor frame ("create new at this path?") rather
   * than breaking the route.
   */

  import { onMount } from "svelte";
  import {
    loadDashboard,
    newDashboard,
    saveDashboard,
    type Dashboard,
  } from "$lib/api/dashboards";
  import DashboardToolbar from "$lib/views/dashboard/DashboardToolbar.svelte";
  import DashboardFilterBar from "$lib/views/dashboard/DashboardFilterBar.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";

  interface Props {
    dashboardPath: string;
    readOnly?: boolean;
  }

  let { dashboardPath, readOnly = false }: Props = $props();

  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let dashboard = $state<Dashboard | null>(null);
  let saving = $state(false);
  let saveError = $state<string | null>(null);

  onMount(async () => {
    if (!dashboardPath) {
      // No path supplied — start a fresh empty dashboard. The save flow
      // will prompt for a target path.
      dashboard = newDashboard();
      loading = false;
      return;
    }
    try {
      dashboard = await loadDashboard(dashboardPath);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      // Distinguish 404 (offer create-new) from other failures (show error).
      if (msg.includes("-> 404")) {
        dashboard = newDashboard();
        loadError = `No dashboard at ${dashboardPath} — creating a new one. Save to persist.`;
      } else {
        loadError = msg;
      }
    } finally {
      loading = false;
    }
  });

  async function handleSave(): Promise<void> {
    if (!dashboard || !dashboardPath || readOnly) return;
    saving = true;
    saveError = null;
    try {
      await saveDashboard(dashboardPath, dashboard);
    } catch (e: unknown) {
      saveError = e instanceof Error ? e.message : String(e);
    } finally {
      saving = false;
    }
  }
</script>

<div class="dashboard-editor">
  {#if loading}
    <div class="loading">Loading dashboard…</div>
  {:else if dashboard}
    <DashboardToolbar
      bind:name={dashboard.name}
      {readOnly}
      {saving}
      onSave={handleSave}
    />
    {#if loadError}
      <div class="notice">{loadError}</div>
    {/if}
    {#if saveError}
      <div class="error">Save failed: {saveError}</div>
    {/if}
    <DashboardFilterBar dashboard={dashboard} {readOnly} />
    <DashboardGrid bind:dashboard {readOnly} />
  {:else}
    <div class="error">{loadError ?? "Unable to load dashboard."}</div>
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
