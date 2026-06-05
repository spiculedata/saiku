<script lang="ts">
  import { onMount } from "svelte";
  import { base } from "$app/paths";
  import { page } from "$app/state";
  import { session } from "$lib/stores/session.svelte";
  import { theme } from "$lib/stores/theme.svelte";
  import { platform } from "$lib/stores/platform.svelte";
  import { embed } from "$lib/stores/embed.svelte";
  import { presentation } from "$lib/stores/presentation.svelte";
  import ToastStack from "$lib/components/ToastStack.svelte";
  import UpgradeBanner from "$lib/components/UpgradeBanner.svelte";
  import { LogOut, Shield, Home, LayoutDashboard, UserRound } from "lucide-svelte";
  import Tour from "$lib/components/Tour.svelte";
  import SessionExpiredBanner from "$lib/components/SessionExpiredBanner.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { installAuthInterceptor, onAuthFailure } from "$lib/api/http";
  import "$lib/styles/tokens.css";
  import "$lib/styles/app.css";

  let { children } = $props();

  // #941 share viewer: the public /share route renders a dashboard for an
  // account-free guest — no app chrome (topbar / upgrade banner), no session.
  const isShare = $derived(page.url.pathname.startsWith(`${base}/share`));

  // Non-modal session-expired banner state (issue #944). The previous
  // SessionErrorModal was a blocking modal in the middle of the screen,
  // which is jarring on long-running dashboard / TV-wall views. We now
  // pin a sticky banner to the top instead and let users sign in when
  // ready. The auth-failure detection / pending-op replay contract in
  // $lib/api/http.ts is unchanged.
  let sessionError = $state<{ open: boolean; statusLabel: string }>({
    open: false,
    statusLabel: "",
  });

  // Operator branding: try SVG, then PNG logo from <saiku.home>/branding/,
  // then the bundled default at ${base}/logo.png. Text brand is the last resort.
  let brandLogo = $state<string | null>("/ui/branding/logo.svg");
  function onBrandLogoError() {
    if (brandLogo === "/ui/branding/logo.svg") {
      brandLogo = "/ui/branding/logo.png";
    } else if (brandLogo === "/ui/branding/logo.png") {
      brandLogo = `${base}/logo.svg`;
    } else {
      brandLogo = null;
    }
  }

  // Keep a reference so $effect runs in this layout's context.
  theme;

  onMount(() => {
    embed.bootstrap();
    installAuthInterceptor();
    const unsub = onAuthFailure((status) => {
      if (session.current) {
        sessionError = { open: true, statusLabel: String(status) };
      }
    });
    const onResumed = () => {
      sessionError = { open: false, statusLabel: "" };
    };
    window.addEventListener("saiku-session-resumed", onResumed);
    session.bootstrap();
    platform.ping();
    return () => {
      unsub();
      window.removeEventListener("saiku-session-resumed", onResumed);
    };
  });

  $effect(() => {
    if (session.current && platform.version == null) {
      platform.loadVersion();
    }
  });
</script>

<div class="app" class:app--cursor-hidden={presentation.active && presentation.cursorHidden}>
  {#if !embed.active && !presentation.active && !isShare}
    <UpgradeBanner />
  {/if}
  {#if !embed.active && !presentation.active && !isShare}
  <header class="topbar">
    <a class="topbar__brand" href="{base}/" aria-label={i18n.t("brand")}>
      {#if brandLogo}
        <img
          class="topbar__brand-logo"
          src={brandLogo}
          alt={i18n.t("brand")}
          onerror={onBrandLogoError}
        />
      {:else}
        <span class="topbar__brand-wordmark">{i18n.t("brand")}</span>
      {/if}
    </a>
    <div class="topbar__actions">
      {#if session.current}
        <span class="topbar__user" title="Signed in as {session.current.username}">
          <UserRound size={14} />
          {session.current.username}
        </span>
        {#if !page.url.pathname.startsWith(`${base}/dashboards`) && !page.url.pathname.startsWith(`${base}/admin`)}
          <a class="btn" href="{base}/dashboards"><LayoutDashboard size={14} /><span>Dashboards</span></a>
        {/if}
        {#if page.url.pathname.startsWith(`${base}/dashboards`)}
          <a class="btn" href="{base}/"><Home size={14} /><span>{i18n.t("topbar.workspace")}</span></a>
        {/if}
        {#if session.isAdmin && !page.url.pathname.startsWith(`${base}/admin`)}
          <a class="btn" href="{base}/admin"><Shield size={14} /><span>{i18n.t("topbar.admin")}</span></a>
        {/if}
        {#if page.url.pathname.startsWith(`${base}/admin`)}
          <a class="btn" href="{base}/"><Home size={14} /><span>{i18n.t("topbar.workspace")}</span></a>
        {/if}
        <button type="button" class="btn" onclick={() => session.logout()}>
          <LogOut size={14} /><span>{i18n.t("topbar.signOut")}</span>
        </button>
      {/if}
    </div>
  </header>
  {/if}

  <main class="app__main">
    {@render children()}
  </main>
  <ToastStack />
  {#if session.current}<Tour />{/if}
  <SessionExpiredBanner
    open={sessionError.open}
    statusLabel={sessionError.statusLabel}
    onDismiss={() => {
      sessionError = { open: false, statusLabel: "" };
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
  /* Presentation mode (saiku#928): hide the cursor once the pointer has been
     idle, for a clean TV-wall surface. Applies everywhere while engaged. */
  .app--cursor-hidden,
  .app--cursor-hidden :global(*) {
    cursor: none !important;
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
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--fg);
    text-decoration: none;
  }
  .topbar__brand:hover { text-decoration: none; }
  .topbar__brand-logo {
    height: 28px;
    max-height: 28px;
    width: auto;
    display: block;
    object-fit: contain;
  }
  /* Wordmark — typeset Saiku next to the symbol. Falls back to text-only
     brand when no logo file ships with the deployment. Hidden on narrow
     viewports so the topbar doesn't crowd on mobile. */
  .topbar__brand-wordmark {
    font-weight: var(--weight-bold);
    font-size: var(--fs-lg);
    letter-spacing: -0.01em;
    line-height: 1;
  }
  @media (max-width: 640px) {
    .topbar__brand-wordmark { display: none; }
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
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    padding: 4px var(--space-2);
    background: var(--bg-subtle);
    border: 1px solid var(--border);
    border-radius: 999px;
    color: var(--fg-muted);
    font-size: var(--fs-sm);
  }
  .topbar__user :global(svg) { color: var(--fg-subtle); }
  .app__main {
    flex: 1;
    min-height: 0;
    display: flex;
    overflow: hidden;
  }
</style>
