<script lang="ts">
  /*
   * #947 follow-up — read-only preview of an archived dashboard version at
   * /ui/preview?dashboard=…&version=…. Opened in a NEW TAB from the History
   * panel, so it renders in its own JS context (its own dashboardStore) and
   * never disturbs the editing tab's state. Tiles fetch live (the user has a
   * session); the version supplies the layout + tile config.
   */
  import { onMount } from "svelte";
  import { page } from "$app/state";
  import { parseHistoryPreviewParams } from "$lib/dashboard/historyPreview";
  import { getHistoryVersion } from "$lib/api/dashboards";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";

  let loading = $state(true);
  let error = $state<string | null>(null);
  let name = $state("");
  let ready = $state(false);

  onMount(async () => {
    const params = parseHistoryPreviewParams(page.url.searchParams);
    if (!params) {
      error = "This preview link is missing its dashboard or version.";
      loading = false;
      return;
    }
    try {
      const dash = await getHistoryVersion(params.dashboard, params.version);
      name = dash.name ?? "Dashboard";
      // Hydrate THIS tab's store with the archived version; savedPath "" marks
      // it as a non-savable preview (also hides the comment badge on tiles).
      dashboardStore.hydrate(dash, "");
      ready = true;
    } catch {
      error = "Could not load this version — it may have been pruned or you no longer have access.";
    } finally {
      loading = false;
    }
  });
</script>

<svelte:head><title>Preview — {name || "version"} — Saiku</title></svelte:head>

<div class="preview">
  {#if loading}
    <div class="preview__state">Loading version…</div>
  {:else if error}
    <div class="preview__state preview__state--error">{error}</div>
  {:else if ready}
    <header class="preview__header">
      <span class="preview__title">{name}</span>
      <span class="preview__badge">Read-only preview of an earlier version</span>
    </header>
    <div class="preview__grid">
      <DashboardGrid readOnly={true} />
    </div>
  {/if}
</div>

<style>
  .preview {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--bg);
  }
  .preview__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-5);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .preview__title {
    font-weight: var(--weight-bold);
    font-size: var(--fs-lg);
    color: var(--fg);
  }
  .preview__badge {
    font-size: var(--fs-sm);
    color: var(--fg-muted);
    padding: 2px var(--space-2);
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--bg-subtle);
  }
  .preview__grid {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: var(--space-4);
  }
  .preview__state {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--fg-muted);
    font-size: var(--fs-md);
  }
  .preview__state--error {
    color: var(--danger);
  }
</style>
