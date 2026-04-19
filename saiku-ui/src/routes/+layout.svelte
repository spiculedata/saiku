<script lang="ts">
  import { onMount } from "svelte";
  import { session } from "$lib/stores/session.svelte";
  import { theme } from "$lib/stores/theme.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import ToastStack from "$lib/components/ToastStack.svelte";
  import UpgradeBanner from "$lib/components/UpgradeBanner.svelte";
  import "$lib/styles/tokens.css";
  import "$lib/styles/app.css";

  let { children } = $props();

  // Keep a reference so $effect runs in this layout's context.
  theme;

  onMount(() => {
    session.bootstrap();
    platform.ping();
  });

  $effect(() => {
    if (session.current && platform.version == null) {
      platform.loadVersion();
    }
  });
</script>

<div class="app">
  <UpgradeBanner />
  <header class="topbar">
    <div class="topbar__brand">saiku</div>
    <div class="topbar__actions">
      <button type="button" class="btn" onclick={() => theme.toggle()}>
        {theme.theme === "dark" ? "☾ dark" : theme.theme === "light" ? "☀ light" : "⌘ system"}
      </button>
      {#if session.current}
        {#if session.isAdmin}
          <a class="btn" href="/admin">Admin</a>
        {/if}
        <a class="btn" href="/">Workspace</a>
        <button
          type="button"
          class="btn"
          aria-pressed={platform.fullscreen}
          title={platform.fullscreen ? "Exit fullscreen" : "Enter fullscreen"}
          onclick={() => platform.toggleFullscreen()}
        >{platform.fullscreen ? "⤢" : "⤡"}</button>
        <span class="topbar__user">{session.current.username}</span>
        <button type="button" class="btn" onclick={() => session.logout()}>Sign out</button>
      {/if}
    </div>
  </header>

  <main class="app__main">
    {@render children()}
  </main>
  <ToastStack />
</div>

<style>
  .app {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
  }
  .topbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-5);
    background: var(--bg-muted);
    border-bottom: 1px solid var(--border);
  }
  .topbar__brand {
    font-weight: 700;
    letter-spacing: 0.02em;
  }
  .topbar__actions {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }
  .topbar__user {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .app__main {
    flex: 1;
    display: flex;
  }
</style>
