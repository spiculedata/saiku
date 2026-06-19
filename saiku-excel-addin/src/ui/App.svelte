<script lang="ts">
  import { onMount } from "svelte";
  import { loadRegistry, saveRegistry } from "../lib/queryStore";
  import { activeServer } from "../lib/serverRegistry";
  import { loadCredential } from "../lib/credentialStore";
  import type { ServerCtx } from "../lib/http";
  import type { SavedQuery } from "../lib/queryStore";
  import Servers from "./Servers.svelte";
  import Home from "./Home.svelte";
  import Wizard from "./Wizard.svelte";
  import Header from "./Header.svelte";

  type View = "servers" | "home" | "wizard";

  let view = $state<View>("home");
  let editing = $state<SavedQuery | null>(null);
  let loading = $state(true);
  let serverCtx = $state<ServerCtx | null>(null);
  let activeLabel = $state<string | null>(null);

  onMount(async () => {
    try {
      await refreshCtx();
    } catch {
      serverCtx = null;
    } finally {
      loading = false;
    }
  });

  async function refreshCtx() {
    const reg = await loadRegistry();
    const server = activeServer(reg);
    if (!server) {
      serverCtx = null;
      activeLabel = null;
      view = "servers";
      return;
    }
    const credential = await loadCredential(server.id);
    serverCtx = { baseUrl: server.baseUrl, credential };
    activeLabel = server.label;
    if (view === "servers") view = "home";
  }

  async function handleServersClose() {
    // Refresh ctx after returning from servers screen
    await refreshCtx();
  }
</script>

<main style="font-family: var(--font); padding: 0; width: 100%; box-sizing: border-box;">
  <Header />

  {#if !loading && activeLabel}
    <div class="server-bar">
      <span class="server-name" title={serverCtx?.baseUrl ?? ""}>{activeLabel}</span>
      <button class="btn-switch" onclick={() => { view = "servers"; }}>Switch</button>
    </div>
  {/if}

  {#if loading}
    <div class="loading">Loading…</div>
  {:else if view === "servers"}
    <Servers onClose={handleServersClose} />
  {:else if !serverCtx}
    <!-- No active server — show Servers screen -->
    <Servers onClose={handleServersClose} />
  {:else if view === "home"}
    <Home
      ctx={serverCtx}
      onNew={() => { editing = null; view = "wizard"; }}
      onEdit={(q) => { editing = q; view = "wizard"; }}
    />
  {:else}
    <Wizard
      ctx={serverCtx}
      initial={editing}
      onDone={() => { editing = null; view = "home"; }}
      onCancel={() => { editing = null; view = "home"; }}
    />
  {/if}
</main>

<style>
  .loading {
    padding: var(--space-5);
    text-align: center;
    color: var(--fg-muted);
  }

  .server-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 4px var(--space-4);
    background: var(--bg-muted);
    border-bottom: 1px solid var(--border);
    font-size: var(--fs-xs);
  }

  .server-name {
    color: var(--fg-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
  }

  .btn-switch {
    font-family: var(--font);
    font-size: var(--fs-xs);
    color: var(--accent);
    background: none;
    border: none;
    cursor: pointer;
    padding: 2px 4px;
    flex-shrink: 0;
    text-decoration: underline;
  }

  .btn-switch:hover { color: var(--accent-hover); }
</style>
