<script lang="ts">
  /*
   * #941 share viewer — public, account-free, read-only dashboard page at
   * /ui/share#<token>. The token lives in the URL fragment (never sent to the
   * server); we read it from window.location.hash and echo it as the
   * X-Saiku-Share-Token header to the guest endpoints. Each data tile's query
   * is prefetched and injected via shareViewContext, so the normal tile
   * components render read-only without a Saiku session or /ai/query access.
   */
  import { onMount } from "svelte";
  import { parseShareToken } from "$lib/dashboard/shareUrl";
  import { loadSharedDashboard } from "$lib/api/dashboards";
  import { runSharedTileQuery, type AiQueryResponse } from "$lib/api/aiQuery";
  import { setShareViewResponses } from "$lib/dashboard/shareViewContext";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import DashboardGrid from "$lib/views/dashboard/DashboardGrid.svelte";

  // Same Map reference is mutated in onMount, then read by tiles once `ready`.
  const responses = new Map<string, AiQueryResponse>();
  setShareViewResponses(responses);

  let loading = $state(true);
  let error = $state<string | null>(null);
  let name = $state("");
  let ready = $state(false);

  onMount(async () => {
    const token = parseShareToken(window.location.hash);
    if (!token) {
      error = "This share link is missing its access token.";
      loading = false;
      return;
    }
    try {
      const dash = await loadSharedDashboard(token);
      name = dash.name ?? "Shared dashboard";
      for (const tile of dash.layout?.tiles ?? []) {
        if (tile.type === "chart" || tile.type === "table" || tile.type === "kpi") {
          try {
            responses.set(tile.id, await runSharedTileQuery(token, tile.id));
          } catch {
            // leave it unset — the tile shows its own error frame
          }
        }
      }
      dashboardStore.hydrate(dash, "");
      ready = true;
    } catch {
      error = "This share link is invalid or has expired.";
    } finally {
      loading = false;
    }
  });
</script>

<svelte:head><title>{name || "Shared dashboard"} — Saiku</title></svelte:head>

<div class="share-view">
  {#if loading}
    <div class="share-view__state">Loading shared dashboard…</div>
  {:else if error}
    <div class="share-view__state share-view__state--error">{error}</div>
  {:else if ready}
    <header class="share-view__header">
      <span class="share-view__title">{name}</span>
      <span class="share-view__badge">Read-only shared view</span>
    </header>
    <div class="share-view__grid">
      <DashboardGrid readOnly={true} />
    </div>
  {/if}
</div>

<style>
  .share-view {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: var(--bg);
  }
  .share-view__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-5);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .share-view__title {
    font-weight: var(--weight-bold);
    font-size: var(--fs-lg);
    color: var(--fg);
  }
  .share-view__badge {
    font-size: var(--fs-sm);
    color: var(--fg-muted);
    padding: 2px var(--space-2);
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--bg-subtle);
  }
  .share-view__grid {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: var(--space-4);
  }
  .share-view__state {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--fg-muted);
    font-size: var(--fs-md);
  }
  .share-view__state--error {
    color: var(--danger);
  }
</style>
