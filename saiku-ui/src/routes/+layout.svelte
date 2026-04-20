<script lang="ts">
  import { onMount } from "svelte";
  import { base } from "$app/paths";
  import { session } from "$lib/stores/session.svelte";
  import { theme } from "$lib/stores/theme.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import ToastStack from "$lib/components/ToastStack.svelte";
  import UpgradeBanner from "$lib/components/UpgradeBanner.svelte";
  import LocalePicker from "$lib/components/LocalePicker.svelte";
  import { Moon, Sun, Monitor, Maximize2, Minimize2, LogOut, Shield, Home } from "lucide-svelte";
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
        {#if theme.theme === "dark"}
          <Moon size={14} /><span>{i18n.t("topbar.theme.dark")}</span>
        {:else if theme.theme === "light"}
          <Sun size={14} /><span>{i18n.t("topbar.theme.light")}</span>
        {:else}
          <Monitor size={14} /><span>{i18n.t("topbar.theme.system")}</span>
        {/if}
      </button>
      {#if session.current}
        {#if session.isAdmin}
          <a class="btn" href="{base}/admin"><Shield size={14} /><span>{i18n.t("topbar.admin")}</span></a>
        {/if}
        <a class="btn" href="{base}/"><Home size={14} /><span>{i18n.t("topbar.workspace")}</span></a>
        <button
          type="button"
          class="btn"
          aria-pressed={platform.fullscreen}
          title={platform.fullscreen
            ? i18n.t("topbar.fullscreen.exit")
            : i18n.t("topbar.fullscreen.enter")}
          onclick={() => platform.toggleFullscreen()}
        >{#if platform.fullscreen}<Minimize2 size={14} />{:else}<Maximize2 size={14} />{/if}</button>
        <span class="topbar__user">{session.current.username}</span>
        <button type="button" class="btn" onclick={() => session.logout()}>
          <LogOut size={14} /><span>{i18n.t("topbar.signOut")}</span>
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
    height: 100vh;
    overflow: hidden;
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
  .topbar__actions :global(.btn) {
    height: 32px;
    padding: 0 var(--space-3);
    line-height: 1;
    box-sizing: border-box;
  }
  .topbar__actions :global(.btn > *) { line-height: 1; }
  .topbar__actions :global(.btn svg) { display: block; }
  .topbar__actions :global(.locale select) {
    height: 100%;
    padding-top: 0;
    padding-bottom: 0;
  }
  .topbar__user {
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .app__main {
    flex: 1;
    min-height: 0;
    display: flex;
    overflow: hidden;
  }
</style>
