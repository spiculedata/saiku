<script lang="ts">
  import { onMount } from "svelte";
  import { session } from "$lib/stores/session.svelte";
  import { theme } from "$lib/stores/theme.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import ToastStack from "$lib/components/ToastStack.svelte";
  import UpgradeBanner from "$lib/components/UpgradeBanner.svelte";
  import LocalePicker from "$lib/components/LocalePicker.svelte";
  import Tour from "$lib/components/Tour.svelte";
  import SessionErrorModal from "$lib/modals/SessionErrorModal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { installAuthInterceptor, onAuthFailure } from "$lib/api/http";
  import "$lib/styles/tokens.css";
  import "$lib/styles/app.css";

  let { children } = $props();

  let sessionError = $state<{ open: boolean; message: string }>({ open: false, message: "" });

  // Keep a reference so $effect runs in this layout's context.
  theme;

  onMount(() => {
    installAuthInterceptor();
    const unsub = onAuthFailure((status) => {
      if (session.current) {
        sessionError = {
          open: true,
          message: `Your session ended (${status}).`,
        };
      }
    });
    session.bootstrap();
    platform.ping();
    return () => unsub();
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
    <div class="topbar__brand">{i18n.t("brand")}</div>
    <div class="topbar__actions">
      <LocalePicker />
      <button type="button" class="btn" onclick={() => theme.toggle()}>
        {theme.theme === "dark"
          ? `☾ ${i18n.t("topbar.theme.dark")}`
          : theme.theme === "light"
            ? `☀ ${i18n.t("topbar.theme.light")}`
            : `⌘ ${i18n.t("topbar.theme.system")}`}
      </button>
      {#if session.current}
        {#if session.isAdmin}
          <a class="btn" href="/admin">{i18n.t("topbar.admin")}</a>
        {/if}
        <a class="btn" href="/">{i18n.t("topbar.workspace")}</a>
        <button
          type="button"
          class="btn"
          aria-pressed={platform.fullscreen}
          title={platform.fullscreen
            ? i18n.t("topbar.fullscreen.exit")
            : i18n.t("topbar.fullscreen.enter")}
          onclick={() => platform.toggleFullscreen()}
        >{platform.fullscreen ? "⤢" : "⤡"}</button>
        <span class="topbar__user">{session.current.username}</span>
        <button type="button" class="btn" onclick={() => session.logout()}>
          {i18n.t("topbar.signOut")}
        </button>
      {/if}
    </div>
  </header>

  <main class="app__main">
    {@render children()}
  </main>
  <ToastStack />
  {#if session.current}<Tour />{/if}
  <SessionErrorModal
    message={sessionError.message}
    open={sessionError.open}
    onReload={() => {
      sessionError = { open: false, message: "" };
      location.reload();
    }}
  />
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
