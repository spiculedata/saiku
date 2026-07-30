<script lang="ts">
  /*
   * App Builder renderer for the embed surface (saiku#1441). A token pins ONE
   * .saikuapp; this fetches the whole document via GET /embed/app/{path} and
   * renders it READ-ONLY as a single unit — branded header, page navigation
   * (top tabs), and the active page's grid. Every page + tile rides the one
   * token grant, so the guest sees the app exactly as one scoped unit.
   *
   * This is the embed-native analogue of the main app's AppShell (which is
   * store-coupled to the authenticated dashboard stack and cannot run on the
   * token-scoped, credentials:"omit" embed surface). It is purely presentational
   * over the existing query/embed path: each page's tiles render through the
   * SAME shared <EmbedGrid> the dashboard embed uses, with fetchers wired to the
   * token-scoped app tile endpoints — so per-query RLS/PII enforcement is
   * unchanged. Author custom CSS is designer-only in Phase 1 and is NOT applied
   * here; embedders theme via the --saiku-embed-* CSS variables.
   */
  import { fetchApp, fetchAppTile, fetchAppTileMembers, EmbedFetchError, type EmbedFilterOverride } from "./api";
  import type { EmbedAppDoc } from "./types";
  import EmbedGrid from "./EmbedGrid.svelte";

  interface Props {
    server: string;
    token: string;
    path: string;
    onLoad?: (detail: { name: string; pages: number }) => void;
  }

  let { server, token, path, onLoad }: Props = $props();

  let app = $state<EmbedAppDoc | null>(null);
  let error = $state<string | null>(null);
  let loading = $state(false);
  let activePageId = $state<string | null>(null);

  let activePage = $derived(
    app?.pages.find((p) => p.id === activePageId) ?? app?.pages[0] ?? null,
  );

  $effect(() => {
    const s = server.trim();
    const p = path.trim();
    const t = token.trim();
    if (!p) {
      app = null;
      error = null;
      return;
    }
    let cancelled = false;
    loading = true;
    error = null;
    app = null;
    activePageId = null;
    fetchApp(s, p, t || undefined)
      .then((doc) => {
        if (cancelled) return;
        app = doc;
        activePageId = doc.pages[0]?.id ?? null;
        onLoad?.({ name: doc.name, pages: doc.pages.length });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        error = friendlyError(e);
      })
      .finally(() => {
        if (!cancelled) loading = false;
      });
    return () => {
      cancelled = true;
    };
  });

  function friendlyError(e: unknown): string {
    if (e instanceof EmbedFetchError) {
      if (e.status === 401) return "This embed is unavailable.";
      return e.body.error ?? `Embed failed (${e.status}).`;
    }
    return "Embed failed to load.";
  }

  function selectPage(id: string): void {
    activePageId = id;
  }

  // Bind the pageId into the tile/member fetchers so <EmbedGrid> stays
  // source-agnostic. The active page id is read at call time so a page switch
  // routes queries at the newly-active page.
  function tileFetch(pageId: string) {
    return (tileId: string, overrides: EmbedFilterOverride[]) =>
      fetchAppTile(server.trim(), path.trim(), pageId, tileId, token.trim() || undefined, overrides);
  }
  function memberFetch(pageId: string) {
    return (tileId: string, q?: string, limit?: number) =>
      fetchAppTileMembers(server.trim(), path.trim(), pageId, tileId, token.trim() || undefined, q, limit);
  }
</script>

{#if loading && !app}
  <div class="state">Loading app…</div>
{:else if error}
  <div class="state error" role="alert">{error}</div>
{:else if app}
  <div class="saiku-embed-app">
    <header class="app-header">
      {#if app.logo}
        <img class="app-logo" src={app.logo} alt="" />
      {/if}
      <span class="app-name">{app.name}</span>
    </header>

    {#if app.pages.length > 1}
      <nav class="app-nav" aria-label="Pages">
        {#each app.pages as p (p.id)}
          <button
            type="button"
            class="app-nav__tab"
            class:active={p.id === activePage?.id}
            aria-current={p.id === activePage?.id ? "page" : undefined}
            onclick={() => selectPage(p.id)}
          >
            {p.title}
          </button>
        {/each}
      </nav>
    {/if}

    <main class="app-page">
      {#if activePage}
        {#key activePage.id}
          <EmbedGrid
            layout={activePage.grid}
            fetchTile={tileFetch(activePage.id)}
            fetchMembers={memberFetch(activePage.id)}
          />
        {/key}
      {:else}
        <div class="state muted">This app has no pages.</div>
      {/if}
    </main>
  </div>
{/if}

<style>
  .saiku-embed-app {
    display: flex;
    flex-direction: column;
    min-height: 0;
    color: var(--saiku-embed-fg, #1f2937);
  }
  .app-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--saiku-embed-border, #e5e7eb);
    background: var(--saiku-embed-header-bg, transparent);
  }
  .app-logo {
    height: 24px;
    width: auto;
    max-width: 120px;
    object-fit: contain;
  }
  .app-name {
    font-family: system-ui, sans-serif;
    font-size: 15px;
    font-weight: 600;
  }
  .app-nav {
    display: flex;
    gap: 2px;
    padding: 6px 10px 0;
    border-bottom: 1px solid var(--saiku-embed-border, #e5e7eb);
    overflow-x: auto;
  }
  .app-nav__tab {
    appearance: none;
    border: none;
    background: transparent;
    padding: 8px 12px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
    color: var(--saiku-embed-muted, #6b7280);
    cursor: pointer;
    border-bottom: 2px solid transparent;
    white-space: nowrap;
  }
  .app-nav__tab:hover {
    color: var(--saiku-embed-fg, #1f2937);
  }
  .app-nav__tab.active {
    color: var(--saiku-embed-fg, #1f2937);
    border-bottom-color: var(--saiku-embed-accent, #2563eb);
    font-weight: 600;
  }
  .app-page {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }
  .state {
    padding: 16px;
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
  .state.muted {
    color: var(--saiku-embed-muted, #6b7280);
  }
  .state.error {
    color: var(--saiku-embed-error, #b91c1c);
  }
</style>
